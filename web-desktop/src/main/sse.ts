/**
 * Session SSE client — fetch + ReadableStream in the main process (D-87/D-91).
 *
 * Contract §3.1:
 *  - first frame after connect/reconnect is a `session.status` snapshot;
 *  - `message.created` carries `id:` = seq;
 *  - `: ping` comments keep the line alive (parser ignores comments);
 *  - reconnect uses `Last-Event-ID` (preferred over `?since=`);
 *  - `retry:` sets the reconnect delay (default from config).
 *
 * One active subscription: switching sessions aborts the previous stream.
 */
import { log } from './logger.js';
import type { ServerConfig } from '../shared/ipc-contract.js';

export type SseFrame = {
  id?: string;
  event: string;
  data: string;
  retryMs?: number;
};

export type SseEventHandler = (sessionId: string, frame: SseFrame) => void;

/** Pure incremental SSE parser — unit-testable without network. */
export function createSseParser(onFrame: (frame: SseFrame) => void): {
  push: (chunk: string) => void;
  flush: () => void;
} {
  let buffer = '';
  let dataLines: string[] = [];
  let eventName = '';
  let lastId: string | undefined;
  let retryMs: number | undefined;

  const emit = (): void => {
    if (dataLines.length === 0 && eventName === '') {
      return;
    }
    onFrame({
      id: lastId,
      event: eventName || 'message',
      data: dataLines.join('\n'),
      retryMs,
    });
    dataLines = [];
    eventName = '';
  };

  const handleLine = (raw: string): void => {
    if (raw === '') {
      emit();
      return;
    }
    if (raw.startsWith(':')) {
      // comment (`: ping`) — ignored
      return;
    }
    const colon = raw.indexOf(':');
    const field = colon === -1 ? raw : raw.slice(0, colon);
    let value = colon === -1 ? '' : raw.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    switch (field) {
      case 'data':
        dataLines.push(value);
        break;
      case 'event':
        eventName = value;
        break;
      case 'id':
        lastId = value;
        break;
      case 'retry': {
        const n = Number.parseInt(value, 10);
        if (Number.isFinite(n)) retryMs = n;
        break;
      }
      default:
        break;
    }
  };

  return {
    push(chunk: string): void {
      buffer += chunk;
      let idx: number;
      while ((idx = buffer.indexOf('\n')) !== -1) {
        let line = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1);
        handleLine(line);
      }
    },
    flush(): void {
      if (buffer.length > 0) {
        let line = buffer;
        if (line.endsWith('\r')) line = line.slice(0, -1);
        buffer = '';
        handleLine(line);
      }
      emit();
    },
  };
}

export class SessionSseClient {
  private abort: AbortController | null = null;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private activeSessionId: string | null = null;
  /** Last SSE `id:` (seq). `null` → use `initialSince` on the next open. */
  private lastEventId: string | null = null;
  /** seq tail already loaded via REST history — first connect starts after it. */
  private initialSince = 0;
  private retryMs: number;
  private running = false;

  constructor(
    private readonly cfg: ServerConfig,
    private readonly onFrame: SseEventHandler,
    private readonly getAccessToken: () => Promise<string | null>,
    /** Injected for tests. */
    private readonly fetchImpl: typeof fetch = fetch,
  ) {
    this.retryMs = cfg.sseRetryDefaultMs;
  }

  /**
   * Opens a stream for `sessionId`, replacing any previous subscription.
   * Resolves once the first request has been issued (or immediately, if no
   * prior subscription) — frames arrive via the callback. Reconnect happens
   * in the background.
   *
   * `sinceSeq` — last seq already in the feed (history tail); the first
   * connect uses `?since=` so the interval (since, …] has no gap/dupes.
   */
  subscribe(sessionId: string, sinceSeq = 0): Promise<void> {
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.abort?.abort();
    this.abort = null;
    this.activeSessionId = sessionId;
    this.lastEventId = null;
    this.initialSince = sinceSeq;
    this.retryMs = this.cfg.sseRetryDefaultMs;
    this.running = true;
    void this.openStream();
    return Promise.resolve();
  }

  /** Cancels the active stream (session switch / app shutdown). */
  async unsubscribe(): Promise<void> {
    this.running = false;
    this.activeSessionId = null;
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

  private async openStream(): Promise<void> {
    while (this.running) {
      const sessionId = this.activeSessionId;
      if (!sessionId) return;

      const controller = new AbortController();
      this.abort = controller;

      try {
        const token = await this.getAccessToken();
        if (!token) {
          log.warn('sse: no access token — reconnect later');
          if (!(await this.waitReconnect())) return;
          continue;
        }
        const base = this.cfg.serverBaseUrl.replace(/\/+$/, '');
        const headers: Record<string, string> = {
          Authorization: `Bearer ${token}`,
          Accept: 'text/event-stream',
        };
        let url = `${base}/api/v1/sessions/${encodeURIComponent(sessionId)}/events`;
        if (this.lastEventId !== null) {
          headers['Last-Event-ID'] = this.lastEventId;
        } else {
          url += `?since=${this.initialSince}`;
        }

        const res = await this.fetchImpl(url, { headers, signal: controller.signal });
        if (!res.ok || !res.body) {
          log.warn('sse connect failed', { sessionId, status: res.status });
          if (!(await this.waitReconnect())) return;
          continue;
        }

        log.info('sse connected', {
          sessionId,
          resumeFrom: this.lastEventId ?? this.initialSince,
        });
        await this.readLoop(sessionId, res.body, controller.signal);
        if (controller.signal.aborted || !this.running) return;
        log.warn('sse stream closed by peer');
      } catch (err) {
        if (controller.signal.aborted || !this.running) return;
        log.warn('sse stream error', {
          sessionId,
          err: err instanceof Error ? err.message : String(err),
        });
      } finally {
        if (this.abort === controller) this.abort = null;
      }

      if (!(await this.waitReconnect())) return;
    }
  }

  private async readLoop(sessionId: string, body: ReadableStream<Uint8Array>, signal: AbortSignal): Promise<void> {
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
      if (this.running && this.activeSessionId === sessionId && !signal.aborted) {
        this.onFrame(sessionId, frame);
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
    if (this.reconnectTimer) {
      return new Promise((resolve) => {
        const prev = this.reconnectTimer;
        this.reconnectTimer = setTimeout(() => {
          this.reconnectTimer = null;
          resolve(this.running);
        }, this.retryMs);
        this.reconnectTimer.unref?.();
        void prev;
      });
    }
    return new Promise((resolve) => {
      this.reconnectTimer = setTimeout(() => {
        this.reconnectTimer = null;
        resolve(this.running);
      }, this.retryMs);
      this.reconnectTimer.unref?.();
    });
  }
}
