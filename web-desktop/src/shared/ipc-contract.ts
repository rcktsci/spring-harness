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

  SSE_SUBSCRIBE: 'sse:subscribe',
  SSE_UNSUBSCRIBE: 'sse:unsubscribe',

  ARTIFACT_DOWNLOAD: 'artifact:download',
  ARTIFACT_OPEN: 'artifact:open',

  TOOL_CONFIRM: 'tool:confirm',
  TOOL_CANCEL: 'tool:cancel',

  APP_QUIT: 'app:quit',
} as const;

export const DEFAULT_CONFIG: ServerConfig = {
  serverBaseUrl: 'http://localhost:8080',
  keycloakIssuer: 'http://localhost:8080/realms/harness',
  keycloakClientId: 'spring-harness-web-desktop',
  keycloakRedirectUri: 'http://127.0.0.1:43189/callback',
  showTray: true,
  theme: 'system',
  logLevel: 'info',
  logMaxSizeBytes: 5_242_880,
  windowWidth: 1200,
  windowHeight: 800,
  windowMinWidth: 800,
  windowMinHeight: 600,
  windowStateDebounceMs: 500,
  confirmCommands: 'always',
};

export type IpcChannel = (typeof IPC)[keyof typeof IPC];

export type LoginState = {
  loggedIn: boolean;
  subject?: string;
  expiresAt?: number;
};

export type ServerConfig = {
  serverBaseUrl: string;
  keycloakIssuer: string;
  keycloakClientId: string;
  keycloakRedirectUri: string;
  showTray: boolean;
  theme: 'light' | 'dark' | 'system';
  logLevel: 'error' | 'warn' | 'info' | 'verbose' | 'debug' | 'silly';
  logMaxSizeBytes: number;
  windowWidth: number;
  windowHeight: number;
  windowMinWidth: number;
  windowMinHeight: number;
  windowStateDebounceMs: number;
  confirmCommands: 'always' | 'never';
};

export type ServerConfigPatch = Partial<ServerConfig>;

export type RelayStatus = {
  connected: boolean;
  sessionId?: string;
  toolCount?: number;
  reason?: string;
};

export type ConfirmCommandsResult = {
  callId: string;
  approved: boolean;
};
