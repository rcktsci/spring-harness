/**
 * Relay WS client — main process only (D-91).
 *
 * Owns the socket to `/api/v1/relay`: connect with Bearer, hello/welcome
 * handshake, session registration with the standard tool set, pong +
 * ping-watchdog, reconnect with exponential backoff, and the close-code
 * policy of §5.5:
 *   4401 → silent refresh, then retry
 *   4403 → fatal, never retry
 *   4409 → stop retrying, notify ("session open elsewhere")
 *
 * Tool execution and confirmation gating live here too (D-93).
 */

import { app } from 'electron';
import { EventEmitter } from 'node:events';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { WebSocket } from 'ws';
import { log } from './logger.js';
import {
  RELAY_PROTOCOL_VERSION,
  classifyCloseCode,
  parseRelayFrame,
  type ClientFrame,
  type ClientTool,
  type ServerFrame,
  type ToolCallFrame,
  type ToolCancelFrame,
} from './ws-frames.js';
import { executeTool, STANDARD_TOOLS } from './local-tools.js';
import type { ServerConfig, RelayStatus } from '../shared/ipc-contract.js';

const CLIENT_VERSION = app.getVersion() ?? '0.0.0';

export interface RelayEvents {
  status: (status: RelayStatus) => void;
  /** A tool call needs user confirmation (D-93) before it may run. */
  toolCall: (call: ToolCallFrame, basePath: string) => void;
  /** A registration needs first-time consent (spec: Безопасность). */
  registrationConsent: (sessionId: string, basePath: string, tools: string[]) => void;
  /** A tool result was produced (also forwarded to the renderer). */
  toolResult: (callId: string, output: string, exitCode: number) => void;
}

interface Registration {
  sessionId: string;
  basePath: string;
  /** True after the user accepted the first-time consent for this session. */
  consented: boolean;
}

interface RegisterOutcome {
  ok: boolean;
  code?: string;
  message?: string;
}

const REGISTRATION_ERROR_TEXT: Record<string, string> = {
  'session-not-found': 'Session no longer exists on the server.',
  'wrong-session-kind': 'Relay is only available for root sessions.',
  'workspace-occupied': 'Session is occupied by another user.',
  'duplicate-tool-name': 'The server rejected the tool declaration.',
  superseded: 'Session opened elsewhere; this connection is retired.',
};

export class RelayClient extends EventEmitter {
  private socket: WebSocket | null = null;
  private registration: Registration | null = null;
  private basePath: string | null = null;
  private reconnectTimer: NodeJS.Timeout | null = null;
  private pingTimer: NodeJS.Timeout | null = null;
  private reconnectAttempt = 0;
  private stopped = false;
  /** True only for fatal closes (4403 protocol) — distinct from user disconnect. */
  private fatal = false;
  private welcomed = false;
  private registering: Promise<RegisterOutcome> | null = null;
  /**
   * Last register-refusal from an error frame, kept so a following
   * close 4409 does not overwrite it with a generic "superseded"
   * (§5.2: registration refusals arrive as error + close 4409).
   */
  private lastRegisterError: { code: string; message: string } | null = null;
  private readonly cancelHandles = new Map<string, () => void>();
  /** callId → resolver for the renderer's confirm/deny answer. */
  private readonly confirmWaiters = new Map<string, (approved: boolean) => void>();
  /** sessionId → resolver for the first-time registration consent. */
  private readonly consentResolvers = new Map<string, (approved: boolean) => void>();
  /** sessionId → decision that arrived before register() asked for it. */
  private readonly pendingConsent = new Map<string, boolean>();
  /** callIds cancelled on the wire but still being torn down. */
  private readonly cancelledCalls = new Set<string>();

  constructor(
    private readonly cfg: ServerConfig,
    private readonly onToken: () => Promise<string | null>,
  ) {
    super();
  }

  /* ---------------------------------------------------------------- */
  /* lifecycle                                                        */
  /* ---------------------------------------------------------------- */

  /** Connects and performs the hello/welcome handshake. */
  async connect(): Promise<void> {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      return;
    }
    this.dropSocket();
    this.stopped = false;
    this.fatal = false;
    const token = await this.onToken();
    if (!token) {
      this.emit('status', {
        connected: false,
        registered: false,
        phase: 'disconnected',
        reason: 'Not signed in.',
      });
      return;
    }

    const url = this.cfg.serverBaseUrl.replace(/^http/, 'ws') + '/api/v1/relay';
    this.emit('status', { connected: false, registered: false, phase: 'connecting' });
    log.info(`relay connecting to ${url}`);

    const socket = new WebSocket(url, {
      headers: { Authorization: `Bearer ${token}` },
    });
    this.socket = socket;

    await new Promise<void>((resolve, reject) => {
      const onceOpen = (): void => {
        socket.removeListener('error', onceError);
        resolve();
      };
      const onceError = (err: Error): void => {
        socket.removeListener('open', onceOpen);
        reject(err);
      };
      socket.once('open', onceOpen);
      socket.once('error', onceError);
    }).catch((err: unknown) => {
      log.warn(`relay connect failed: ${String(err)}`);
      this.scheduleReconnect();
    });

    if (this.socket?.readyState !== WebSocket.OPEN) {
      return;
    }

    this.wireSocket();
    this.welcomed = false;
    this.send({ type: 'hello', protocol: RELAY_PROTOCOL_VERSION });
    this.emit('status', { connected: true, registered: false, phase: 'handshake' });
  }

  /**
   * Waits until the welcome frame arrives. Owns the single handshake
   * timer: on expiry the socket is closed and reconnect is scheduled.
   */
  private async awaitHandshake(): Promise<boolean> {
    if (this.welcomed) return true;

    return new Promise<boolean>((resolve) => {
      const timer = setTimeout(() => {
        this.removeListener('welcome', onWelcome);
        log.warn('handshake timeout — no welcome frame');
        this.closeSocketAndReconnect();
        resolve(false);
      }, this.cfg.relayHandshakeTimeoutMs);

      const onWelcome = (): void => {
        clearTimeout(timer);
        resolve(true);
      };
      this.once('welcome', onWelcome);
    });
  }

  /** Tears the current socket down without touching reconnect state. */
  private dropSocket(): void {
    if (this.socket) {
      this.socket.removeAllListeners();
      if (this.socket.readyState === WebSocket.OPEN) {
        this.socket.close(1000, 'replaced');
      }
      this.socket = null;
    }
    this.welcomed = false;
  }

  /** Disconnects for good (app quit / explicit user action). */
  disconnect(): void {
    this.stopped = true;
    this.fatal = false;
    this.welcomed = false;
    this.reconnectAttempt = 0;
    this.clearTimers();
    this.cancelHandles.clear();
    this.dropSocket();
    this.registration = null;
    this.lastRegisterError = null;
    this.emit('status', { connected: false, registered: false, phase: 'disconnected' });
  }

  /** Graceful shutdown used on app quit: no orphan registrations. */
  shutdown(): void {
    this.disconnect();
  }

  /* ---------------------------------------------------------------- */
  /* registration (3.2)                                               */
  /* ---------------------------------------------------------------- */

  /**
   * Registers on a session. Creates `basePath` first (spec requires the
   * directory to exist before the frame is sent), then sends `register`
   * with the standard tool declaration.
   */
  async register(
    sessionId: string,
    kind: 'FREE' | 'STATE',
    userBasePath?: string,
  ): Promise<RegisterOutcome> {
    if (kind !== 'FREE') {
      this.emit('status', {
        connected: false,
        registered: false,
        phase: 'disconnected',
        reason: 'Relay is only available for root sessions.',
        code: 'wrong-session-kind',
      });
      return { ok: false, code: 'wrong-session-kind' };
    }

    const basePath =
      userBasePath ?? join(homedir(), 'harness-workspaces', sessionId);
    const { mkdir } = await import('node:fs/promises');
    await mkdir(basePath, { recursive: true });
    this.basePath = basePath;

    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      await this.connect();
      if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
        return { ok: false, message: 'relay connection unavailable' };
      }
    }

    if (!(await this.awaitHandshake())) {
      return { ok: false, message: 'relay handshake timed out' };
    }

    // Consent gate for a first registration on this session (D-93).
    // `registration` itself is only set after the server sends `registered`
    // (see sendRegister) so a refusal never leaves registered=true.
    const isNew = !this.registration || this.registration.sessionId !== sessionId;
    if (isNew) {
      const allowed = await this.requestRegistrationConsent(sessionId, basePath);
      if (!allowed) {
        return { ok: false, message: 'User declined local execution.' };
      }
    }

    return this.sendRegister(sessionId, basePath);
  }

  private sendRegister(sessionId: string, basePath: string): Promise<RegisterOutcome> {
    if (this.registering) {
      return this.registering;
    }

    const tools: ClientTool[] = STANDARD_TOOLS.map((name) => ({
      name,
      description: `Web Desktop local tool: ${name}`,
      inputSchema: { type: 'object', additionalProperties: true },
      source: 'client',
    }));

    this.lastRegisterError = null;
    this.registering = new Promise<RegisterOutcome>((resolve) => {
      const timer = setTimeout(() => {
        // Guarantee cleanup so a timed-out register never leaks the listener.
        this.removeListener('frame', onFrame);
        this.registering = null;
        resolve({ ok: false, message: 'registration timeout' });
      }, this.cfg.relayRegisterTimeoutMs);

      const onFrame = (frame: ServerFrame): void => {
        if (frame.type === 'registered' && frame.sessionId === sessionId) {
          clearTimeout(timer);
          this.removeListener('frame', onFrame);
          this.registering = null;
          this.registration = { sessionId, basePath, consented: true };
          this.basePath = basePath;
          this.emit('status', {
            connected: true,
            registered: true,
            phase: 'connected',
            sessionId,
            basePath,
            toolCount: tools.length,
          });
          resolve({ ok: true });
        } else if (frame.type === 'error') {
          clearTimeout(timer);
          this.removeListener('frame', onFrame);
          this.registering = null;
          const message = REGISTRATION_ERROR_TEXT[frame.code] ?? frame.message;
          this.lastRegisterError = { code: frame.code, message };
          // Refusal: do not leave a success-shaped registration behind.
          if (this.registration?.sessionId === sessionId) {
            this.registration = null;
          }
          this.emit('status', {
            connected: true,
            registered: false,
            phase: 'connected',
            sessionId,
            basePath,
            code: frame.code,
            reason: message,
          });
          resolve({ ok: false, code: frame.code, message });
        }
      };
      this.on('frame', onFrame);

      this.send({
        type: 'register',
        sessionId,
        basePath,
        client: { version: CLIENT_VERSION, tools },
      });
    });

    return this.registering;
  }

  /* ---------------------------------------------------------------- */
  /* socket wiring                                                    */
  /* ---------------------------------------------------------------- */

  private wireSocket(): void {
    const s = this.socket;
    if (!s) return;

    s.on('message', (raw) => {
      let parsed: unknown;
      try {
        parsed = JSON.parse(String(raw));
      } catch {
        log.warn('relay: non-JSON frame ignored');
        return;
      }
      const frame = parseRelayFrame(parsed);
      if (!frame) {
        log.warn('relay: unparseable frame ignored');
        return;
      }
      this.emit('frame', frame);
      this.handleFrame(frame);
    });

    s.on('ping', () => {
      this.send({ type: 'pong' });
    });

    s.on('close', (code) => {
      this.clearTimers();
      this.socket = null;
      const cls = classifyCloseCode(code ?? 1006);
      log.info(`relay closed ${code} (${cls.kind})`);

      if (cls.kind === 'protocol') {
        // 4403: fatal — never retry
        this.stopped = true;
        this.fatal = true;
        this.emit('status', {
          connected: false,
          registered: false,
          phase: 'fatal',
          code: 'protocol-mismatch',
          reason: 'Server requires a different protocol version.',
        });
        return;
      }
      if (cls.kind === 'registration') {
        // 4409: stop reconnecting. §5.2 delivers the refusal as an error
        // frame *then* close 4409 — keep that code/reason when present;
        // plain 4409 without a prior error means superseded/takeover.
        this.stopped = true;
        const prior = this.lastRegisterError;
        this.lastRegisterError = null;
        this.emit('status', {
          connected: false,
          registered: false,
          phase: 'disconnected',
          code: prior?.code ?? 'superseded',
          reason:
            prior
              ? (REGISTRATION_ERROR_TEXT[prior.code] ?? prior.message)
              : 'Session opened in another place.',
        });
        return;
      }
      if (cls.kind === 'unauthenticated') {
        // 4401: silent refresh then retry once
        void this.handle401();
        return;
      }
      // network drop / unknown → reconnect with backoff
      this.scheduleReconnect();
    });

    s.on('error', (err) => {
      log.warn(`relay socket error: ${err.message}`);
    });

  }

  private async handle401(): Promise<void> {
    const refreshed = await this.onToken();
    if (!refreshed) {
      this.emit('status', {
        connected: false,
        registered: false,
        phase: 'disconnected',
        reason: 'Session expired — sign in again.',
      });
      return;
    }
    this.reconnectAttempt = 0;
    await this.connect();
    if (this.registration) {
      await this.sendRegister(this.registration.sessionId, this.registration.basePath);
    }
  }

  private closeSocketAndReconnect(): void {
    if (this.socket) {
      this.socket.removeAllListeners();
      if (this.socket.readyState === WebSocket.OPEN) {
        this.socket.close(1000, 'watchdog');
      }
      this.socket = null;
    }
    this.clearTimers();
    this.scheduleReconnect();
  }

  private scheduleReconnect(): void {
    if (this.stopped) return;
    this.clearTimers();
    const base = this.cfg.relayReconnectInitialMs;
    // exponential: base * factor^attempt, capped at relayReconnectMaxMs
    const growth = Math.pow(this.cfg.relayReconnectBackoffFactor, this.reconnectAttempt);
    const delay = Math.min(base * growth, this.cfg.relayReconnectMaxMs);
    this.reconnectAttempt += 1;
    log.info(`relay reconnect in ${delay}ms (attempt ${this.reconnectAttempt})`);
    this.reconnectTimer = setTimeout(() => {
      this.welcomed = false;
      void this.connect().then(() => {
        if (this.registration && this.socket?.readyState === WebSocket.OPEN) {
          void this.sendRegister(this.registration.sessionId, this.registration.basePath);
        }
      });
    }, delay);
  }

  /* ---------------------------------------------------------------- */
  /* inbound frames                                                   */
  /* ---------------------------------------------------------------- */

  private handleFrame(frame: ServerFrame): void {
    switch (frame.type) {
      case 'welcome':
        this.welcomed = true;
        // A completed handshake means the endpoint is healthy again —
        // restart the backoff ladder from the initial delay.
        this.reconnectAttempt = 0;
        this.emit('welcome');
        // Arm the ping watchdog only after the handshake completes;
        // during handshake the handshake timer is the sole guard.
        this.resetPingWatchdog();
        log.info('relay handshake complete');
        this.emit('status', {
          connected: true,
          registered: false,
          phase: this.registration ? 'registering' : 'connected',
          sessionId: this.registration?.sessionId,
          basePath: this.registration?.basePath ?? undefined,
        });
        break;

      case 'ping':
        this.send({ type: 'pong' });
        this.resetPingWatchdog();
        break;

      case 'tool.call':
        void this.handleToolCall(frame);
        break;

      case 'tool.cancel':
        this.handleToolCancel(frame);
        break;

      case 'registered':
      case 'error':
        // handled by the register promise listener
        break;
    }
  }

  private resetPingWatchdog(): void {
    if (this.pingTimer) clearTimeout(this.pingTimer);
    this.pingTimer = setTimeout(() => {
      log.warn('ping watchdog fired — forcing reconnect');
      this.closeSocketAndReconnect();
    }, this.cfg.relayHeartbeatIntervalMs * this.cfg.relayPingWatchdogMultiplier);
  }

  /* ---------------------------------------------------------------- */
  /* tool execution (3.3) + confirmation (3.4)                        */
  /* ---------------------------------------------------------------- */

  private async handleToolCall(call: ToolCallFrame): Promise<void> {
    const basePath = this.basePath ?? this.registration?.basePath;
    if (!basePath) {
      this.send({
        type: 'tool.result',
        callId: call.callId,
        output: 'no registration — cannot execute',
        exitCode: -1,
      });
      return;
    }

    try {
      if (this.cfg.confirmCommands === 'always') {
        this.emit('toolCall', call, basePath);
        const approved = await this.awaitConfirmation(call);
        if (!approved) {
          this.send({
            type: 'tool.result',
            callId: call.callId,
            output: 'command rejected by user',
            exitCode: -1,
          });
          return;
        }
      }

      const result = await executeTool(call, {
        basePath,
        timeoutMs: this.cfg.relayToolCallTimeoutMs,
        outputLimitBytes: this.cfg.toolOutputLimitBytes,
        progressChunkBytes: this.cfg.toolProgressChunkBytes,
        killGraceMs: this.cfg.toolKillGraceMs,
        globMaxResults: this.cfg.toolGlobMaxResults,
        grepMaxMatches: this.cfg.toolGrepMaxMatches,
        cancelHandles: this.cancelHandles,
        emitProgress: (f: ClientFrame) => this.send(f),
        isCancelled: () => this.cancelledCalls.has(call.callId),
      });

      if (result.suppressed) {
        log.info(`tool.result suppressed for cancelled call ${call.callId}`);
        return;
      }
      this.emit('toolResult', call.callId, result.output, result.exitCode);
      this.send(result);
    } catch (err) {
      // §5.3: every tool.call must produce a tool.result — never hang until
      // the server's tool-timeout because of an unexpected throw.
      const message = err instanceof Error ? err.message : String(err);
      log.warn(`tool ${call.tool} failed: ${message}`);
      if (this.cancelledCalls.has(call.callId)) {
        return;
      }
      this.emit('toolResult', call.callId, message, -1);
      this.send({
        type: 'tool.result',
        callId: call.callId,
        output: `${call.tool}: ${message}`,
        exitCode: -1,
      });
    } finally {
      this.cancelledCalls.delete(call.callId);
      this.confirmWaiters.delete(call.callId);
    }
  }

  private handleToolCancel(frame: ToolCancelFrame): void {
    const handle = this.cancelHandles.get(frame.callId);
    if (!handle) {
      // §5.3: cancel for an unknown/finished callId is ignored silently
      return;
    }
    this.cancelledCalls.add(frame.callId);
    handle();
  }

  /** Renderer-initiated cancel (same path as a server `tool.cancel`). */
  cancelToolCall(callId: string): void {
    this.handleToolCancel({ type: 'tool.cancel', callId });
  }

  /** Resolves when the renderer answers the confirm dialog (D-93). */
  private awaitConfirmation(call: ToolCallFrame): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      this.confirmWaiters.set(call.callId, resolve);
    });
  }

  /** Called from the IPC handler when the user answers the dialog. */
  resolveConfirmation(callId: string, approved: boolean): void {
    const waiter = this.confirmWaiters.get(callId);
    if (waiter) {
      this.confirmWaiters.delete(callId);
      waiter(approved);
    }
  }

  private async requestRegistrationConsent(
    sessionId: string,
    basePath: string,
  ): Promise<boolean> {
    // An early decision (caller resolved before register() reached here)
    // is already waiting — consume it instead of hanging on the resolver.
    const early = this.pendingConsent.get(sessionId);
    if (early !== undefined) {
      this.pendingConsent.delete(sessionId);
      return early;
    }
    return new Promise<boolean>((resolve) => {
      this.consentResolvers.set(sessionId, resolve);
      this.emit('registrationConsent', sessionId, basePath, [...STANDARD_TOOLS]);
    });
  }

  /** Called from the IPC handler when the user answers registration consent. */
  resolveRegistrationConsent(sessionId: string, approved: boolean): void {
    const resolve = this.consentResolvers.get(sessionId);
    if (resolve) {
      this.consentResolvers.delete(sessionId);
      resolve(approved);
    } else {
      // No waiter yet — remember the decision for requestRegistrationConsent.
      this.pendingConsent.set(sessionId, approved);
    }
  }

  /* ---------------------------------------------------------------- */
  /* helpers                                                          */
  /* ---------------------------------------------------------------- */

  private send(frame: ClientFrame): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(frame));
    }
  }

  private clearTimers(): void {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer);
    if (this.pingTimer) clearTimeout(this.pingTimer);
    this.reconnectTimer = null;
    this.pingTimer = null;
  }

  get currentStatus(): RelayStatus {
    const connected = this.socket?.readyState === WebSocket.OPEN;
    return {
      connected,
      registered: this.registration !== null,
      phase: this.fatal
        ? 'fatal'
        : connected
          ? 'connected'
          : 'disconnected',
      sessionId: this.registration?.sessionId,
      basePath: this.basePath ?? undefined,
      toolCount: connected ? STANDARD_TOOLS.length : undefined,
    };
  }

  /** Test hook: current exponential-backoff attempt counter. */
  get backoffAttempt(): number {
    return this.reconnectAttempt;
  }
}
