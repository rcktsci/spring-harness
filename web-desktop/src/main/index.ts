import { app, BrowserWindow, ipcMain, nativeTheme, shell } from 'electron';
import { join } from 'node:path';
import { log, initLogger } from './logger.js';
import { loadConfig, saveConfig } from './config.js';
import { createMainWindow } from './window.js';
import { broadcastToRenderer, sendToRenderer } from './renderer-bridge.js';
import { planEndpointChange } from '../shared/config-invalidation.js';
import { buildAppMenu } from './menu.js';
import { createTray } from './tray.js';
import { startLogin, logout, readLoginState, refreshIfNeeded } from './auth.js';
import { RelayClient } from './relay-client.js';
import { SessionSseClient } from './sse.js';
import { TaskSseClient } from './task-sse.js';
import {
  addTaskComment,
  compactSession,
  createSession,
  getSession,
  getSessionTree,
  getTask,
  listAgents,
  listMessages,
  listSessions,
  listTaskComments,
  listTaskHistory,
  listTasks,
  sendMessage,
  stopSession,
} from './rest-client.js';
import { openArtifact, pruneCache, saveArtifactAs, WorkspaceFileError } from './artifact.js';
import {
  IPC,
  type ArtifactDownloadBody,
  type MessageListQuery,
  type ServerConfig,
  type SessionCreateBody,
  type SessionListQuery,
  type SessionSendBody,
  type TaskCommentAddBody,
  type TaskCommentsListQuery,
  type TaskHistoryQuery,
  type TaskListQuery,
  type TaskSubscribeBody,
} from '../shared/ipc-contract.js';

const DEV_URL = process.env['VITE_DEV_SERVER_URL'];
const isDev = Boolean(DEV_URL);
let mainWindow: BrowserWindow | null = null;
let config = await loadConfig();
let relay: RelayClient | null = null;
let sse: SessionSseClient | null = null;
let taskSse: TaskSseClient | null = null;
initLogger(config.logLevel, config.logMaxSizeBytes);
log.info('app starting', { isDev, version: app.getVersion() });

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  log.warn('another instance is already running; quitting');
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) {
        mainWindow.restore();
      }
      mainWindow.focus();
    }
  });
  void bootstrap();
}

async function bootstrap(): Promise<void> {
  await app.whenReady();

  config = await loadConfig();
  applyTheme(config.theme);
  mainWindow = await createMainWindow(config);

  const menu = buildAppMenu({
    isDev,
    onOpenLogs: () => {
      void shell.openPath(join(app.getPath('userData'), 'logs'));
    },
  });
  app.applicationMenu = menu;

  createTray({
    showTray: config.showTray,
    onShow: () => {
      if (!mainWindow) {
        return;
      }
      if (mainWindow.isMinimized()) {
        mainWindow.restore();
      }
      mainWindow.show();
      mainWindow.focus();
    },
    onQuit: () => app.quit(),
  });

  registerIpcStubs();
  relay = createRelay();
  sse = new SessionSseClient(
    config,
    (sessionId, frame) => {
      sendToRenderer(() => mainWindow, 'sse:event', { sessionId, ...frame });
    },
    async () => refreshIfNeeded(config, config.tokenClockSkewSeconds),
  );
  taskSse = new TaskSseClient(config, (taskId, frame) => {
    sendToRenderer(() => mainWindow, 'task:event', { taskId, ...frame });
  });

  // Background prune of the artifact open-cache (userData/cache/artifacts/*)
  void pruneCache(config.artifactCacheMaxAgeMs);

  if (isDev && DEV_URL) {
    await mainWindow.loadURL(DEV_URL);
    mainWindow.webContents.openDevTools({ mode: 'detach' });
  } else {
    await mainWindow.loadFile(join(__dirname, '../renderer/index.html'));
  }

  // Spec: startup with a saved active session → auto connect + register.
  if (config.relayActiveSessionId) {
    void relay.register(config.relayActiveSessionId, 'FREE').then((outcome) => {
      if (!outcome.ok) {
        log.warn('startup auto-register failed', {
          sessionId: config.relayActiveSessionId,
          code: outcome.code,
          message: outcome.message,
        });
        if (outcome.code === 'session-not-found' || outcome.code === 'wrong-session-kind') {
          // Stale id — drop it so the next start does not retry a dead session.
          void saveConfig({ ...config, relayActiveSessionId: undefined }).then(() => {
            config = { ...config, relayActiveSessionId: undefined };
          });
        }
      }
    });
  }

  app.on('activate', async () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      config = await loadConfig();
      mainWindow = await createMainWindow(config);
    }
  });
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('before-quit', () => {
  relay?.shutdown();
  sse?.shutdown();
  taskSse?.shutdown();
  // Best-effort: prune expired open-in-OS cache files on graceful exit
  // (spec: «кэш чистится при выходе»). Fire-and-forget — never block quit.
  void pruneCache(config.artifactCacheMaxAgeMs);
  log.info('app quitting');
});

function applyTheme(theme: 'light' | 'dark' | 'system'): void {
  nativeTheme.themeSource = theme === 'system' ? 'system' : theme;
  log.info(`theme set to ${theme}`);
}

/**
 * Builds a RelayClient wired to the renderer. Call again after a config
 * change that invalidates the endpoint (baseUrl) so the client picks up
 * the new URL — `cfg` is constructor-captured (readonly).
 */
function createRelay(): RelayClient {
  const client = new RelayClient(config, async () =>
    refreshIfNeeded(config, config.tokenClockSkewSeconds),
  );
  client.on('status', (status) => {
    sendToRenderer(() => mainWindow, 'relay:status', status);
  });
  client.on('toolCall', (call, basePath) => {
    sendToRenderer(() => mainWindow, 'tool:call', call, basePath);
  });
  client.on('registrationConsent', (sessionId, basePath, tools) => {
    sendToRenderer(() => mainWindow, 'relay:registration-consent', { sessionId, basePath, tools });
  });
  client.on('toolResult', (callId, output, exitCode) => {
    sendToRenderer(() => mainWindow, 'tool:result', callId, output, exitCode);
  });
  return client;
}

/**
 * Called when the user changes server settings in the Settings view.
 * Existing network clients must drop their server affinity; the next
 * request re-authenticates against the new issuer (spec "смена сервера").
 */
async function onServerConfigChanged(next: ServerConfig): Promise<void> {
  const plan = planEndpointChange(config, next);
  const sessionInvalidated = plan.invalidateSession;
  config = next;
  applyTheme(next.theme);
  // Tokens are scoped to the old issuer; a stale JWT would only produce 401s.
  if (sessionInvalidated) {
    const { clearTokens } = await import('./token-store.js');
    const { invalidateSession } = await import('./auth.js');
    clearTokens();
    invalidateSession();
    broadcastToRenderer(IPC.AUTH_SESSION_LOST);
  }
  if (plan.sseResubscribe) {
    log.info('server/keycloak endpoint changed — reconnecting relay');
    // Drop any live SSE so the next subscribe re-authenticates cleanly.
    void sse?.unsubscribe();
    void taskSse?.unsubscribe();
  }
  if (plan.clientsRebuild) {
    // Both clients capture cfg in the constructor — rebuild for the new URL.
    sse = new SessionSseClient(
      config,
      (sessionId, frame) => {
        sendToRenderer(() => mainWindow, 'sse:event', { sessionId, ...frame });
      },
      async () => refreshIfNeeded(config, config.tokenClockSkewSeconds),
    );
    taskSse = new TaskSseClient(config, (taskId, frame) => {
      sendToRenderer(() => mainWindow, 'task:event', { taskId, ...frame });
    });
    relay?.shutdown();
    relay = createRelay();
    if (config.relayActiveSessionId) {
      void relay.register(config.relayActiveSessionId, 'FREE').catch(() => {
        /* status events already surface failures */
      });
    }
  }
  if (plan.notifyConfigChanged) {
    sendToRenderer(() => mainWindow, 'config:changed', { baseUrl: next.serverBaseUrl });
  }
}

function registerIpcStubs(): void {
  const implemented = new Set<string>([
    IPC.CONFIG_GET,
    IPC.CONFIG_SET,
    IPC.CONFIG_OPEN_LOGS,
    IPC.AUTH_LOGIN_STATE,
    IPC.AUTH_LOGIN_START,
    IPC.AUTH_LOGIN_LOGOUT,
    IPC.AUTH_REFRESH,
    IPC.RELAY_CONNECT,
    IPC.RELAY_REGISTER,
    IPC.RELAY_DISCONNECT,
    IPC.RELAY_STATUS,
    IPC.RELAY_SET_SESSION,
    IPC.RELAY_CONFIRM_REGISTRATION,
    IPC.RELAY_PENDING_CONSENT,
    IPC.TOOL_RESPOND_CONFIRM,
    IPC.TOOL_CANCEL,
    IPC.SESSION_LIST,
    IPC.SESSION_GET,
    IPC.SESSION_CREATE,
    IPC.SESSION_MESSAGES,
    IPC.SESSION_SEND,
    IPC.SESSION_COMPACT,
    IPC.SESSION_STOP,
    IPC.SESSION_TREE,
    IPC.AGENTS_LIST,
    IPC.SSE_SUBSCRIBE,
    IPC.SSE_UNSUBSCRIBE,
    IPC.TASK_GET,
    IPC.TASK_LIST,
    IPC.TASK_HISTORY,
    IPC.TASK_COMMENTS_LIST,
    IPC.TASK_COMMENT_ADD,
    IPC.TASK_SUBSCRIBE,
    IPC.TASK_UNSUBSCRIBE,
    IPC.ARTIFACT_DOWNLOAD,
    IPC.ARTIFACT_OPEN,
    IPC.APP_QUIT,
  ]);

  for (const channel of Object.values(IPC)) {
    if (implemented.has(channel)) {
      continue;
    }
    ipcMain.handle(channel, async () => {
      throw new Error(`not implemented in batch B: ${channel}`);
    });
  }

  ipcMain.handle(IPC.CONFIG_GET, async () => config);

  ipcMain.handle(IPC.CONFIG_SET, async (_evt, patch: Record<string, unknown>) => {
    const next = { ...config, ...(patch as Partial<ServerConfig>) };
    await saveConfig(next);
    await onServerConfigChanged(next);
    return config;
  });

  ipcMain.handle(IPC.CONFIG_OPEN_LOGS, async () => {
    await shell.openPath(join(app.getPath('userData'), 'logs'));
  });

  ipcMain.handle(IPC.AUTH_LOGIN_STATE, async () => {
    try {
      return readLoginState(config.tokenClockSkewSeconds);
    } catch (err) {
      // safeStorage unavailable — surface a readable reason, never a crash
      return {
        loggedIn: false,
        error: err instanceof Error ? err.message : 'auth state unavailable',
      };
    }
  });

  ipcMain.handle(IPC.AUTH_LOGIN_START, async () => {
    const result = await startLogin(config);
    if (!result.ok) {
      throw new Error(result.message ?? `login ${result.reason}`);
    }
    return readLoginState(config.tokenClockSkewSeconds);
  });

  ipcMain.handle(IPC.AUTH_LOGIN_LOGOUT, async () => {
    await logout(config);
  });

  ipcMain.handle(IPC.AUTH_REFRESH, async () => {
    const token = await refreshIfNeeded(config, config.tokenClockSkewSeconds);
    if (!token) {
      throw new Error('session expired — sign in again');
    }
    return readLoginState(config.tokenClockSkewSeconds);
  });

  ipcMain.handle(IPC.RELAY_CONNECT, async () => {
    await relay?.connect();
    return relay?.currentStatus ?? null;
  });

  ipcMain.handle(IPC.RELAY_REGISTER, async (_evt, payload: { sessionId: string; kind: string; basePath?: string }) => {
    if (!relay) return { ok: false, message: 'relay not initialised' };
    const outcome = await relay.register(
      payload.sessionId,
      payload.kind === 'STATE' ? 'STATE' : 'FREE',
      payload.basePath,
    );
    if (outcome.ok && payload.kind !== 'STATE') {
      // Remember for startup auto-register; a session switch overwrites.
      if (config.relayActiveSessionId !== payload.sessionId) {
        config = { ...config, relayActiveSessionId: payload.sessionId };
        await saveConfig(config);
      }
    } else if (!outcome.ok && (outcome.code === 'session-not-found' || outcome.code === 'wrong-session-kind')) {
      if (config.relayActiveSessionId) {
        config = { ...config, relayActiveSessionId: undefined };
        await saveConfig(config);
      }
    }
    return outcome;
  });

  ipcMain.handle(IPC.RELAY_SET_SESSION, async (_evt, payload: { sessionId: string; kind: string }) => {
    // Lifecycle (spec): switching re-registers on the same socket; a
    // STATE session simply does not register.
    if (payload.kind !== 'FREE') {
      return { ok: false, code: 'wrong-session-kind' };
    }
    return relay?.register(payload.sessionId, 'FREE') ?? { ok: false };
  });

  ipcMain.handle(IPC.RELAY_DISCONNECT, async () => {
    relay?.disconnect();
    if (config.relayActiveSessionId) {
      // Explicit user disconnect — do not auto-reconnect on next start.
      config = { ...config, relayActiveSessionId: undefined };
      await saveConfig(config);
    }
  });

  ipcMain.handle(IPC.RELAY_STATUS, async () => relay?.currentStatus ?? null);

  ipcMain.handle(IPC.RELAY_CONFIRM_REGISTRATION, async (_evt, payload: { sessionId: string; approved: boolean }) => {
    relay?.resolveRegistrationConsent(payload.sessionId, payload.approved);
    return { ok: payload.approved };
  });

  ipcMain.handle(IPC.RELAY_PENDING_CONSENT, async () => relay?.getPendingConsent() ?? null);

  ipcMain.handle(IPC.TOOL_RESPOND_CONFIRM, async (_evt, payload: { callId: string; approved: boolean }) => {
    relay?.resolveConfirmation(payload.callId, payload.approved);
  });

  ipcMain.handle(IPC.TOOL_CANCEL, async (_evt, callId: string) => {
    relay?.cancelToolCall(callId);
  });

  ipcMain.handle(IPC.SESSION_LIST, async (_evt, query: SessionListQuery = {}) => {
    return listSessions(config, { mine: true, ...query, limit: query.limit ?? config.sessionListLimit });
  });

  ipcMain.handle(IPC.SESSION_GET, async (_evt, id: string) => getSession(config, id));

  ipcMain.handle(IPC.SESSION_CREATE, async (_evt, body: SessionCreateBody) =>
    createSession(config, body),
  );

  ipcMain.handle(IPC.AGENTS_LIST, async () => listAgents(config));

  ipcMain.handle(IPC.SESSION_MESSAGES, async (_evt, payload: { id: string } & MessageListQuery) => {
    const { id, since, limit } = payload;
    return listMessages(config, id, {
      since: since ?? 0,
      limit: limit ?? config.chatPageLimit,
    });
  });

  ipcMain.handle(IPC.SESSION_SEND, async (_evt, payload: SessionSendBody) =>
    sendMessage(config, payload.id, payload.text),
  );

  ipcMain.handle(IPC.SESSION_COMPACT, async (_evt, id: string) => compactSession(config, id));

  ipcMain.handle(IPC.SESSION_STOP, async (_evt, id: string) => stopSession(config, id));

  ipcMain.handle(IPC.SESSION_TREE, async (_evt, id: string) => getSessionTree(config, id));

  ipcMain.handle(
    IPC.SSE_SUBSCRIBE,
    async (_evt, payload: { sessionId: string; sinceSeq?: number }) => {
      await sse?.subscribe(payload.sessionId, payload.sinceSeq ?? 0);
      return true;
    },
  );

  ipcMain.handle(IPC.SSE_UNSUBSCRIBE, async (_evt, _sessionId?: string) => {
    await sse?.unsubscribe();
    return true;
  });

  ipcMain.handle(IPC.TASK_LIST, async (_evt, query: TaskListQuery = {}) =>
    listTasks(config, {
      mine: true,
      ...query,
      limit: query.limit ?? config.taskHistoryLimit,
    }),
  );

  ipcMain.handle(IPC.TASK_GET, async (_evt, id: string) => getTask(config, id));

  ipcMain.handle(IPC.TASK_HISTORY, async (_evt, payload: { id: string } & TaskHistoryQuery) =>
    listTaskHistory(config, payload.id, {
      since: payload.since,
      limit: payload.limit ?? config.taskHistoryLimit,
    }),
  );

  ipcMain.handle(IPC.TASK_COMMENTS_LIST, async (_evt, payload: { id: string } & TaskCommentsListQuery) =>
    listTaskComments(config, payload.id, {
      cursor: payload.cursor,
      limit: payload.limit ?? config.taskHistoryLimit,
    }),
  );

  ipcMain.handle(IPC.TASK_COMMENT_ADD, async (_evt, payload: { id: string } & TaskCommentAddBody) =>
    addTaskComment(config, payload.id, { body: payload.body }),
  );

  ipcMain.handle(IPC.TASK_SUBSCRIBE, async (_evt, payload: TaskSubscribeBody) => {
    await taskSse?.subscribe(payload.taskId, payload.sinceSeq ?? 0);
    return true;
  });

  ipcMain.handle(IPC.TASK_UNSUBSCRIBE, async (_evt, _taskId?: string) => {
    await taskSse?.unsubscribe();
    return true;
  });

  ipcMain.handle(IPC.ARTIFACT_OPEN, async (_evt, body: ArtifactDownloadBody) => {
    try {
      return await openArtifact(config, body);
    } catch (err) {
      throw mapArtifactError(err);
    }
  });

  ipcMain.handle(IPC.ARTIFACT_DOWNLOAD, async (_evt, body: ArtifactDownloadBody) => {
    try {
      return await saveArtifactAs(config, body, mainWindow);
    } catch (err) {
      throw mapArtifactError(err);
    }
  });

  ipcMain.handle(IPC.APP_QUIT, async () => {
    relay?.shutdown();
    sse?.shutdown();
    taskSse?.shutdown();
    app.quit();
  });
}

function mapArtifactError(err: unknown): Error {
  if (err instanceof WorkspaceFileError) {
    const code = parseProblemCode(err.detail);
    const map: Record<number, string> = {
      404: 'файл не найден',
      413: 'файл слишком большой',
      422: code === 'extension-not-allowed' ? 'тип файла нельзя скачать' : 'некорректный путь',
    };
    const friendly = map[err.status] ?? `ошибка сервера: ${err.status}`;
    return new Error(`${friendly} (${err.status})`);
  }
  return err instanceof Error ? err : new Error(String(err));
}

function parseProblemCode(detail: string): string | undefined {
  try {
    const parsed = JSON.parse(detail) as { code?: string };
    return parsed.code;
  } catch {
    return undefined;
  }
}
