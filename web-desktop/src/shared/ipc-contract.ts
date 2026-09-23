/**
 * IPC contract shared between main, preload, and renderer.
 *
 * Per design D-91: main owns all secrets and network clients
 * (JWT, REST, WS, SSE). Renderer never touches these directly;
 * it talks to main only through the typed bridge defined here
 * and exposed via preload's contextBridge.
 *
 * Each command has a request payload type and a response payload type.
 * Channels are namespaced as "domain:action".
 */

export const IPC = {
  AUTH_LOGIN_STATE: 'auth:login-state',
  AUTH_LOGIN_START: 'auth:login-start',
  AUTH_LOGIN_LOGOUT: 'auth:login-logout',
  AUTH_REFRESH: 'auth:refresh',

  CONFIG_GET: 'config:get',
  CONFIG_SET: 'config:set',
  CONFIG_OPEN_LOGS: 'config:open-logs',

  SESSION_LIST: 'session:list',
  SESSION_GET: 'session:get',
  SESSION_MESSAGES: 'session:messages',
  SESSION_SEND: 'session:send',
  SESSION_COMPACT: 'session:compact',
  SESSION_STOP: 'session:stop',
  SESSION_TREE: 'session:tree',

  RELAY_CONNECT: 'relay:connect',
  RELAY_REGISTER: 'relay:register',
  RELAY_DISCONNECT: 'relay:disconnect',
  RELAY_STATUS: 'relay:status',
  RELAY_SET_SESSION: 'relay:set-session',
  RELAY_CONFIRM_REGISTRATION: 'relay:confirm-registration',

  SSE_SUBSCRIBE: 'sse:subscribe',
  SSE_UNSUBSCRIBE: 'sse:unsubscribe',

  ARTIFACT_DOWNLOAD: 'artifact:download',
  ARTIFACT_OPEN: 'artifact:open',

  TOOL_CONFIRM: 'tool:confirm',
  TOOL_CANCEL: 'tool:cancel',
  TOOL_RESPOND_CONFIRM: 'tool:respond-confirm',

  APP_QUIT: 'app:quit',
} as const;

export const DEFAULT_CONFIG: ServerConfig = {
  serverBaseUrl: 'http://localhost:8080',
  keycloakIssuer: 'http://localhost:8080/realms/harness',
  keycloakClientId: 'spring-harness-web-desktop',
  showTray: true,
  theme: 'system',
  logLevel: 'info',
  logMaxSizeBytes: 5_242_880,
  windowWidth: 1200,
  windowHeight: 800,
  windowMinWidth: 800,
  windowMinHeight: 600,
  windowStateDebounceMs: 500,
  loginWindowWidth: 900,
  loginWindowHeight: 750,
  tokenClockSkewSeconds: 30,
  relayHandshakeTimeoutMs: 10_000,
  relayHeartbeatIntervalMs: 15_000,
  /** Server ping watchdog fires at 2× the expected interval (§5.4). */
  relayPingWatchdogMultiplier: 2,
  relayReconnectInitialMs: 1_000,
  relayReconnectMaxMs: 30_000,
  relayReconnectBackoffFactor: 2,
  /**
   * Client-side tool ceiling. Must be **strictly less** than the server's
   * tool-call-timeout (§5.4 = 300_000) so the client fires first and we
   * never race the server's tool-timeout.
   */
  relayToolCallTimeoutMs: 295_000,
  relayRegisterTimeoutMs: 10_000,
  /** Max bytes/chars a single tool result emits before the truncated marker. */
  toolOutputLimitBytes: 1_000_000,
  toolProgressChunkBytes: 64_000,
  /** SIGTERM → SIGKILL grace period for cancelled/timed-out bash. */
  toolKillGraceMs: 2_000,
  /** Default cap for glob matches when the tool args omit maxResults. */
  toolGlobMaxResults: 1_000,
  /** Default cap for grep matches when the tool args omit maxMatches. */
  toolGrepMaxMatches: 1_000,
  confirmCommands: 'always',
};

export type IpcChannel = (typeof IPC)[keyof typeof IPC];

export type LoginState = {
  loggedIn: boolean;
  subject?: string;
  expiresAt?: number;
  /** Human-readable reason when auth is unavailable (e.g. no OS keychain). */
  error?: string;
};

export type ServerConfig = {
  serverBaseUrl: string;
  keycloakIssuer: string;
  keycloakClientId: string;
  /**
   * The OAuth2 redirect_uri is deliberately NOT configurable here: the
   * loopback listener binds an ephemeral port at login time, so the URI
   * is always `http://127.0.0.1:<ephemeral>/callback`. Keycloak must have
   * a wildcard loopback redirect registered for the client.
   */
  showTray: boolean;
  theme: 'light' | 'dark' | 'system';
  logLevel: 'error' | 'warn' | 'info' | 'verbose' | 'debug' | 'silly';
  logMaxSizeBytes: number;
  windowWidth: number;
  windowHeight: number;
  windowMinWidth: number;
  windowMinHeight: number;
  windowStateDebounceMs: number;
  loginWindowWidth: number;
  loginWindowHeight: number;
  /** Seconds of clock skew tolerated before an access token is treated as expired. */
  tokenClockSkewSeconds: number;
  relayHandshakeTimeoutMs: number;
  relayHeartbeatIntervalMs: number;
  relayPingWatchdogMultiplier: number;
  relayReconnectInitialMs: number;
  relayReconnectMaxMs: number;
  relayReconnectBackoffFactor: number;
  /** Strictly < server tool-call-timeout (§5.4). Effective = min(args, this). */
  relayToolCallTimeoutMs: number;
  relayRegisterTimeoutMs: number;
  toolOutputLimitBytes: number;
  toolProgressChunkBytes: number;
  toolKillGraceMs: number;
  toolGlobMaxResults: number;
  toolGrepMaxMatches: number;
  confirmCommands: 'always' | 'never';
  /**
   * Last registered FREE session — auto connect+register on next startup
   * (desktop-relay-client: «при старте с сохранённой активной сессией»).
   */
  relayActiveSessionId?: string;
};

export type ServerConfigPatch = Partial<ServerConfig>;

export type RelayStatus = {
  connected: boolean;
  registered: boolean;
  phase: 'disconnected' | 'connecting' | 'handshake' | 'registering' | 'connected' | 'fatal';
  sessionId?: string;
  basePath?: string;
  toolCount?: number;
  /** Human-readable state reason (error code, notification text). */
  reason?: string;
  /** Machine-readable code for UI branching (§5.2 / §5.5). */
  code?: string;
};

export type ToolCallView = {
  callId: string;
  sessionId: string;
  tool: string;
  args: Record<string, unknown>;
  basePath: string;
};

export type RegistrationConsentRequest = {
  sessionId: string;
  basePath: string;
  tools: string[];
};

export type ConfirmCommandsResult = {
  callId: string;
  approved: boolean;
};
