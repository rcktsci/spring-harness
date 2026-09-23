/**
 * Relay WebSocket frame types — manual mirror of api-contracts §5.
 *
 * The OpenAPI spec does not describe the WS transport (§5 is documented
 * separately in docs/design/api-contracts.md), so these types are written
 * by hand against the frozen contract. `parseRelayFrame` validates an
 * unknown inbound payload and narrows it to the client-facing union.
 *
 * Close codes (§5.5): 4401 unauthenticated, 4403 protocol /
 * protocol-mismatch, 4409 registration refusal / takeover.
 */

export const RELAY_CLOSE_CODE = {
  UNAUTHENTICATED: 4401,
  PROTOCOL: 4403,
  SESSION_REGISTRATION: 4409,
} as const;

export const RELAY_PROTOCOL_VERSION = 1;

export type RelayCloseCode = (typeof RELAY_CLOSE_CODE)[keyof typeof RELAY_CLOSE_CODE];

export const RELAY_REGISTRATION_ERROR = [
  'session-not-found',
  'wrong-session-kind',
  'workspace-occupied',
  'duplicate-tool-name',
  'superseded',
] as const;

export type RelayRegistrationError = (typeof RELAY_REGISTRATION_ERROR)[number];

/* ------------------------------------------------------------------ */
/* Client → server                                                     */
/* ------------------------------------------------------------------ */

export interface HelloFrame {
  type: 'hello';
  protocol: typeof RELAY_PROTOCOL_VERSION;
}

export interface ClientTool {
  name: string;
  description?: string;
  inputSchema: Record<string, unknown>;
  source: 'client' | `client.mcp:${string}`;
}

export interface RegisterFrame {
  type: 'register';
  sessionId: string;
  basePath: string;
  client: {
    version: string;
    tools: ClientTool[];
  };
}

export interface ToolProgressFrame {
  type: 'tool.progress';
  callId: string;
  chunk: string;
}

export interface ToolResultFrame {
  type: 'tool.result';
  callId: string;
  output: string;
  exitCode: number;
}

export interface PongFrame {
  type: 'pong';
}

export type ClientFrame =
  | HelloFrame
  | RegisterFrame
  | ToolProgressFrame
  | ToolResultFrame
  | PongFrame;

/* ------------------------------------------------------------------ */
/* Server → client                                                     */
/* ------------------------------------------------------------------ */

export interface WelcomeFrame {
  type: 'welcome';
  protocol: typeof RELAY_PROTOCOL_VERSION;
}

export interface RegisteredFrame {
  type: 'registered';
  sessionId: string;
}

export interface RelayErrorFrame {
  type: 'error';
  /**
   * §5.2 fixes the registration-refusal set; the union is kept open for
   * codes added by a later protocol revision, so unknown values are
   * still carried through rather than dropped.
   */
  code: RelayRegistrationError | (string & {});
  message: string;
}

export interface ToolCallFrame {
  type: 'tool.call';
  callId: string;
  sessionId: string;
  tool: string;
  args: Record<string, unknown>;
}

export interface ToolCancelFrame {
  type: 'tool.cancel';
  callId: string;
}

export interface PingFrame {
  type: 'ping';
}

export type ServerFrame =
  | WelcomeFrame
  | RegisteredFrame
  | RelayErrorFrame
  | ToolCallFrame
  | ToolCancelFrame
  | PingFrame;

/* ------------------------------------------------------------------ */
/* Parsing                                                             */
/* ------------------------------------------------------------------ */

const SERVER_FRAME_TYPES = new Set<ServerFrame['type']>([
  'welcome',
  'registered',
  'error',
  'tool.call',
  'tool.cancel',
  'ping',
]);

const isObject = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const hasString = (o: Record<string, unknown>, k: string): boolean =>
  k in o && typeof o[k] === 'string';

/**
 * Narrows an unknown inbound payload to a typed server frame.
 * Returns `undefined` for anything the contract does not define so
 * callers can ignore malformed input instead of crashing the socket.
 */
export function parseRelayFrame(payload: unknown): ServerFrame | undefined {
  if (!isObject(payload)) {
    return undefined;
  }
  const type = payload['type'];
  if (typeof type !== 'string' || !SERVER_FRAME_TYPES.has(type as ServerFrame['type'])) {
    return undefined;
  }

  switch (type) {
    case 'welcome':
      // §5.1: an unsupported protocol version is a 4403 protocol-mismatch.
      // The client must reject rather than proceed on a mismatched wire.
      if (payload['protocol'] !== RELAY_PROTOCOL_VERSION) return undefined;
      return {
        type: 'welcome',
        protocol: payload['protocol'] as typeof RELAY_PROTOCOL_VERSION,
      };

    case 'registered':
      if (!hasString(payload, 'sessionId')) return undefined;
      return { type: 'registered', sessionId: payload['sessionId'] as string };

    case 'error':
      if (!hasString(payload, 'code') || !hasString(payload, 'message')) return undefined;
      return {
        type: 'error',
        code: payload['code'] as string,
        message: payload['message'] as string,
      };

    case 'tool.call': {
      if (
        !hasString(payload, 'callId') ||
        !hasString(payload, 'sessionId') ||
        !hasString(payload, 'tool')
      ) {
        return undefined;
      }
      const args = payload['args'];
      return {
        type: 'tool.call',
        callId: payload['callId'] as string,
        sessionId: payload['sessionId'] as string,
        tool: payload['tool'] as string,
        args: isObject(args) ? args : {},
      };
    }

    case 'tool.cancel':
      if (!hasString(payload, 'callId')) return undefined;
      return { type: 'tool.cancel', callId: payload['callId'] as string };

    case 'ping':
      return { type: 'ping' };

    default:
      return undefined;
  }
}

/**
 * Classifies a WS close code per §5.5. Unknown codes are reported as
 * `unknown` so the reconnect layer can still react.
 */
export function classifyCloseCode(code: number): {
  kind: 'unauthenticated' | 'protocol' | 'registration' | 'unknown';
  recoverable: boolean;
} {
  switch (code) {
    case RELAY_CLOSE_CODE.UNAUTHENTICATED:
      // 401-equivalent: silent refresh then reconnect
      return { kind: 'unauthenticated', recoverable: true };
    case RELAY_CLOSE_CODE.PROTOCOL:
      // 4403 is fatal for this build — no reconnect
      return { kind: 'protocol', recoverable: false };
    case RELAY_CLOSE_CODE.SESSION_REGISTRATION:
      // 4409: workspace-occupied/superseded/etc. — surfaced to the user
      return { kind: 'registration', recoverable: false };
    default:
      return { kind: 'unknown', recoverable: true };
  }
}
