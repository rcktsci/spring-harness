/**
 * Shared SSE stream loop for the desktop client.
 *
 * Both `SessionSseClient` and `TaskSseClient` previously duplicated the
 * fetch + ReadableStream + reconnect + Last-Event-ID handling (api-contracts
 * §3.1 and §3.2 share the wire format). Extracted here so they can plug in
 * only the bits that differ: URL builder, ping handling, and the event-tag
 * filter for the client-specific event names.
 *
 * Wire details:
 *  - first frame after (re)connect is a snapshot — caller decides the
 *    payload; we forward it via `emit(id, frame)`.
 *  - `: ping` comments are ignored (parser never emits them).
 *  - `retry:` is sticky until the next `retry:` field.
 *  - reconnect uses `Last-Event-ID` (preferred over `?since=`).
 */
import { log } from './logger.js';
import type { ServerConfig } from '../shared/ipc-contract.js';
import { createSseParser, type SseFrame } from './sse-parser.js';

export type { SseFrame };

/**
 * Hooks to wire a client around the shared stream.
 *  - `urlFor(id)` builds the connect URL; `initialSince` is supplied so the
 *    client can pass a known seq tail (history already loaded via REST).
 *  - `headers` is augmented with `Authorization` and `Last-Event-ID` by us.
 *  - `emit(id, frame)` forwards parsed frames to the renderer.
 *  - `acceptEvent(name)` filters event types — `null` keeps the default
 *    `ping` filter (only `message.*` + custom event names survive).
 */
export interface SseStreamHooks {
  urlFor(id: string, lastEventId: string | null, initialSince: number): string;
  extraHeaders?(): Record<string, string>;
  emit(id: string, frame: SseFrame): void;
  /** Optional label for log messages ("session", "task"). */
  label: string;
}

export interface SseStreamDeps {
  getAccessToken: () => Promise<string | null>;
  fetchImpl?: typeof fetch;
  /** Default retry used until the stream emits its own `retry:` value. */
  defaultRetryMs: number;
}

export class SseStream {
  private abort: AbortController | null = null;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private activeId: string | null = null;
  private lastEventId: string | null = null;
  private initialSince = 0;
  private retryMs: number;
  private running = false;

  constructor(
    private readonly cfg: ServerConfig,
    private readonly hooks: SseStreamHooks,
    deps: SseStreamDeps,
  ) {
    this.retryMs = deps.defaultRetryMs;
    this.getAccessToken = deps.getAccessToken;
    this.fetchImpl = deps.fetchImpl ?? fetch;
  }

  subscribe(id: string, sinceSeq = 0): Promise<void> {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.abort?.abort();
    this.abort = null;
    this.activeId = id;
    this.lastEventId = null;
    this.initialSince = sinceSeq;
    this.retryMs = this.cfg.sseRetryDefaultMs;
    this.running = true;
    void this.openStream();
    return Promise.resolve();
  }

  async unsubscribe(): Promise<void> {
    this.running = false;
    this.activeId = null;
    this.lastEventId = null;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.abort?.abort();
    this.abort = null;
  }

  shutdown(): void {
    void this.unsubscribe();
  }

  private getAccessToken: () => Promise<string | null>;
  private fetchImpl: typeof fetch;

  private async openStream(): Promise<void> {
    while (this.running) {
      const id = this.activeId;
      if (!id) return;

      const controller = new AbortController();
      this.abort = controller;

      try {
        const token = await this.getAccessToken();
        if (!token) {
          log.warn(`${this.hooks.label} sse: no access token — reconnect later`);
          if (!(await this.waitReconnect())) return;
          continue;
        }
        const url = this.hooks.urlFor(id, this.lastEventId, this.initialSince);
        const headers: Record<string, string> = {
          Authorization: `Bearer ${token}`,
          Accept: 'text/event-stream',
        };
        if (this.lastEventId !== null) {
          headers['Last-Event-ID'] = this.lastEventId;
        }
        const extra = this.hooks.extraHeaders?.();
        if (extra) Object.assign(headers, extra);

        const res = await this.fetchImpl(url, { headers, signal: controller.signal });
        if (!res.ok || !res.body) {
          log.warn(`${this.hooks.label} sse connect failed`, {
            id,
            status: res.status,
          });
          if (!(await this.waitReconnect())) return;
          continue;
        }

        log.info(`${this.hooks.label} sse connected`, {
          id,
          resumeFrom: this.lastEventId ?? this.initialSince,
        });
        await this.readLoop(id, res.body, controller.signal);
        if (controller.signal.aborted || !this.running) return;
        log.warn(`${this.hooks.label} sse stream closed by peer`);
      } catch (err) {
        if (controller.signal.aborted || !this.running) return;
        log.warn(`${this.hooks.label} sse stream error`, {
          id,
          err: err instanceof Error ? err.message : String(err),
        });
      } finally {
        if (this.abort === controller) this.abort = null;
      }

      if (!(await this.waitReconnect())) return;
    }
  }

  private async readLoop(id: string, body: ReadableStream<Uint8Array>, signal: AbortSignal): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    const parser = createSseParser((frame) => {
      if (frame.retryMs !== undefined && frame.retryMs > 0) {
        this.retryMs = frame.retryMs;
      }
      if (frame.id !== undefined) {
        this.lastEventId = frame.id;
      }
      if (frame.event === 'ping') return;
      if (this.running && this.activeId === id && !signal.aborted) {
        this.hooks.emit(id, frame);
      }
    });

    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parser.push(decoder.decode(value, { stream: true }));
    }
    parser.flush();
  }

  /** @returns false when the client is no longer running. */
  private waitReconnect(): Promise<boolean> {
    if (!this.running) return Promise.resolve(false);
    return new Promise((resolve) => {
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = null;
        resolve(this.running);
      }, this.retryMs);
      this.reconnectTimer.unref?.();
    });
  }
}
