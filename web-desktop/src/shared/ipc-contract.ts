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

import type {
  AgentCatalog,
  CommentDto,
  CommentPage,
  MessagePage,
  SendMessageAccepted,
  SessionDto,
  SessionPage,
  SessionTreePage,
  TaskDto,
  TaskPage,
  TransitionDto,
  TransitionPage,
  WorkspaceDownloadResult,
} from './api-types.js';

export type {
  AgentCatalog,
  MessagePage,
  SendMessageAccepted,
  SessionDto,
  SessionPage,
  SessionTreePage,
  TaskDto,
  TaskPage,
  TransitionDto,
  TransitionPage,
  CommentDto,
  CommentPage,
  WorkspaceDownloadResult,
};

export const IPC = {
  AUTH_LOGIN_STATE: 'auth:login-state',
  AUTH_LOGIN_START: 'auth:login-start',
  AUTH_LOGIN_LOGOUT: 'auth:login-logout',
  AUTH_REFRESH: 'auth:refresh',
  AUTH_SESSION_LOST: 'auth:session-lost',

  CONFIG_GET: 'config:get',
  CONFIG_SET: 'config:set',
  CONFIG_OPEN_LOGS: 'config:open-logs',

  SESSION_LIST: 'session:list',
  SESSION_GET: 'session:get',
  SESSION_CREATE: 'session:create',
  SESSION_MESSAGES: 'session:messages',
  SESSION_SEND: 'session:send',
  SESSION_COMPACT: 'session:compact',
  SESSION_STOP: 'session:stop',
  SESSION_TREE: 'session:tree',
  AGENTS_LIST: 'agents:list',

  RELAY_CONNECT: 'relay:connect',
  RELAY_REGISTER: 'relay:register',
  RELAY_DISCONNECT: 'relay:disconnect',
  RELAY_STATUS: 'relay:status',
  RELAY_SET_SESSION: 'relay:set-session',
  RELAY_CONFIRM_REGISTRATION: 'relay:confirm-registration',
  RELAY_PENDING_CONSENT: 'relay:pending-consent',

  SSE_SUBSCRIBE: 'sse:subscribe',
  SSE_UNSUBSCRIBE: 'sse:unsubscribe',

  TASK_GET: 'task:get',
  TASK_LIST: 'task:list',
  TASK_HISTORY: 'task:history',
  TASK_COMMENTS_LIST: 'task:comments:list',
  TASK_COMMENT_ADD: 'task:comments:add',
  TASK_SUBSCRIBE: 'task:subscribe',
  TASK_UNSUBSCRIBE: 'task:unsubscribe',

  ARTIFACT_DOWNLOAD: 'artifact:download',
  ARTIFACT_OPEN: 'artifact:open',
  ARTIFACT_PICK_PATH: 'artifact:pick-path',

  TOOL_CONFIRM: 'tool:confirm',
  TOOL_CANCEL: 'tool:cancel',
  TOOL_RESPOND_CONFIRM: 'tool:respond-confirm',

  APP_QUIT: 'app:quit',
} as const;

export const DEFAULT_CONFIG: ServerConfig = {
  serverBaseUrl: 'http://localhost:8080',
  keycloakIssuer: 'http://localhost:8080/realms/harness',
  keycloakClientId: 'spring-harness-web-desktop',
  keycloakScopes: 'openid profile email',
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
  keycloakRequestTimeoutMs: 15_000,
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
  /** Page size for GET /sessions (cursor envelope). */
  sessionListLimit: 50,
  /** Debounce for the session-list search field → `q=`. */
  sessionSearchDebounceMs: 300,
  /** Page size for GET /sessions/{id}/messages when walking history to the tail. */
  chatPageLimit: 100,
  /** Safety cap on history pages per open (since=0 walk). */
  chatHistoryMaxPages: 500,
  /** Fallback SSE reconnect delay when the stream omits `retry:` (§3.1 = 5000). */
  sseRetryDefaultMs: 5_000,
  /** Tree poll interval (also re-runs on session.status SSE). */
  treeRefreshIntervalMs: 10_000,
  /** Page size for GET /tasks/{id}/history (opaque cursor walk). */
  taskHistoryLimit: 50,
  /** Safety cap on history pages (task). */
  taskHistoryMaxPages: 200,
  /** Reconnect default for /tasks/{id}/events. */
  taskSseRetryDefaultMs: 5_000,
  /** Optional whitelist of extensions the user can save/open; informational only — server enforces. */
  artifactExtensionHint: '.txt,.md,.json,.csv,.log,.yaml,.yml,.pdf,.png,.jpg,.jpeg,.svg',
  /** Cache TTL for temp-artifact copies written for `open-in-OS`. */
  artifactCacheMaxAgeMs: 7 * 24 * 60 * 60 * 1_000,
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
   * Space-separated scopes requested at the authorization endpoint. Keycloak
   * rejects the whole request with `invalid_scope` when any requested scope
   * is not assigned to the client — `groups` is therefore NOT requested by
   * default: the `groups` claim comes from a protocol mapper, not a scope.
   */
  keycloakScopes: string;
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
  /** Timeout for every Keycloak token-endpoint request (refresh + code exchange). */
  keycloakRequestTimeoutMs: number;
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
  sessionListLimit: number;
  sessionSearchDebounceMs: number;
  chatPageLimit: number;
  chatHistoryMaxPages: number;
  sseRetryDefaultMs: number;
  treeRefreshIntervalMs: number;
  taskHistoryLimit: number;
  taskHistoryMaxPages: number;
  taskSseRetryDefaultMs: number;
  artifactExtensionHint: string;
  artifactCacheMaxAgeMs: number;
  /**
   * Last registered FREE session — auto connect+register on next startup
   * (desktop-relay-client: «при старте с сохранённой активной сессией»).
   */
  relayActiveSessionId?: string;
};

export type SessionListQuery = {
  mine?: boolean;
  q?: string;
  cursor?: string;
  limit?: number;
};

export type SessionCreateBody = {
  title?: string;
  agentKey: string;
  agentRev?: number;
};

export type MessageListQuery = {
  since?: number;
  limit?: number;
};

export type SessionSendBody = {
  id: string;
  text: string;
};

export type TaskListQuery = {
  mine?: boolean;
  q?: string;
  status?: string;
  tags?: string[];
  cursor?: string;
  limit?: number;
};

export type TaskHistoryQuery = {
  /** Opaque cursor from previous page (not numeric — api-contracts §4.2). */
  since?: string;
  limit?: number;
};

export type TaskCommentsListQuery = {
  cursor?: string;
  limit?: number;
};

export type TaskCommentAddBody = {
  body: string;
};

export type TaskSubscribeBody = {
  taskId: string;
  /** Initial task_event_seq (from snapshot) — `0` means snapshot+full history. */
  sinceSeq?: number;
};

export type ArtifactDownloadBody = {
  sessionId: string;
  path: string;
  /**
   * Reserved for future use — UI today picks between `artifact.download`
   * (OS save-as dialog) and `artifact.open` (cache + shell.openPath) via
   * dedicated IPC methods. Keep the field out of the wire contract until
   * the renderer actually has both modes behind one entrypoint.
   */
  // saveAs?: boolean;
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
