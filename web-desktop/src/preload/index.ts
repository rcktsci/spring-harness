import { contextBridge, ipcRenderer } from 'electron';
import type {
  AgentCatalog,
  ArtifactDownloadBody,
  CommentDto,
  CommentPage,
  MessageListQuery,
  MessagePage,
  SendMessageAccepted,
  SessionCreateBody,
  SessionDto,
  SessionListQuery,
  SessionPage,
  SessionTreePage,
  TaskCommentAddBody,
  TaskCommentsListQuery,
  TaskDto,
  TaskHistoryQuery,
  TaskListQuery,
  TaskPage,
  TransitionPage,
  WorkspaceDownloadResult,
} from '../shared/ipc-contract.js';
import {
  IPC,
  type LoginState,
  type RelayStatus,
  type ServerConfig,
  type ServerConfigPatch,
  type TaskSubscribeBody,
  type ToolCallView,
} from '../shared/ipc-contract.js';

type Unsub = () => void;

const api = {
  auth: {
    loginState: (): Promise<LoginState> => ipcRenderer.invoke(IPC.AUTH_LOGIN_STATE),
    loginStart: (): Promise<void> => ipcRenderer.invoke(IPC.AUTH_LOGIN_START),
    logout: (): Promise<void> => ipcRenderer.invoke(IPC.AUTH_LOGIN_LOGOUT),
    refresh: (): Promise<void> => ipcRenderer.invoke(IPC.AUTH_REFRESH),
    onSessionLost: (cb: () => void): Unsub => {
      const handler = (): void => cb();
      ipcRenderer.on(IPC.AUTH_SESSION_LOST, handler);
      return () => ipcRenderer.removeListener(IPC.AUTH_SESSION_LOST, handler);
    },
  },
  config: {
    get: (): Promise<ServerConfig> => ipcRenderer.invoke(IPC.CONFIG_GET),
    set: (patch: ServerConfigPatch): Promise<ServerConfig> =>
      ipcRenderer.invoke(IPC.CONFIG_SET, patch),
    openLogs: (): Promise<void> => ipcRenderer.invoke(IPC.CONFIG_OPEN_LOGS),
  },
  session: {
    list: (query: SessionListQuery = {}): Promise<SessionPage> =>
      ipcRenderer.invoke(IPC.SESSION_LIST, query),
    get: (id: string): Promise<SessionDto> => ipcRenderer.invoke(IPC.SESSION_GET, id),
    create: (body: SessionCreateBody): Promise<SessionDto> =>
      ipcRenderer.invoke(IPC.SESSION_CREATE, body),
    messages: (id: string, query: MessageListQuery = {}): Promise<MessagePage> =>
      ipcRenderer.invoke(IPC.SESSION_MESSAGES, { id, ...query }),
    send: (id: string, text: string): Promise<SendMessageAccepted> =>
      ipcRenderer.invoke(IPC.SESSION_SEND, { id, text }),
    compact: (id: string): Promise<void> => ipcRenderer.invoke(IPC.SESSION_COMPACT, id),
    stop: (id: string): Promise<void> => ipcRenderer.invoke(IPC.SESSION_STOP, id),
    tree: (id: string): Promise<SessionTreePage> => ipcRenderer.invoke(IPC.SESSION_TREE, id),
  },
  agents: {
    list: (): Promise<AgentCatalog> => ipcRenderer.invoke(IPC.AGENTS_LIST),
  },
  relay: {
    connect: (): Promise<RelayStatus | null> => ipcRenderer.invoke(IPC.RELAY_CONNECT),
    register: (sessionId: string, kind: string, basePath?: string): Promise<unknown> =>
      ipcRenderer.invoke(IPC.RELAY_REGISTER, { sessionId, kind, basePath }),
    setSession: (sessionId: string, kind: string): Promise<unknown> =>
      ipcRenderer.invoke(IPC.RELAY_SET_SESSION, { sessionId, kind }),
    disconnect: (): Promise<void> => ipcRenderer.invoke(IPC.RELAY_DISCONNECT),
    status: (): Promise<RelayStatus | null> => ipcRenderer.invoke(IPC.RELAY_STATUS),
    confirmRegistration: (sessionId: string, approved: boolean): Promise<unknown> =>
      ipcRenderer.invoke(IPC.RELAY_CONFIRM_REGISTRATION, { sessionId, approved }),
    pendingConsent: (): Promise<{ sessionId: string; basePath: string; tools: string[] } | null> =>
      ipcRenderer.invoke(IPC.RELAY_PENDING_CONSENT),
    onStatus: (cb: (status: RelayStatus) => void): Unsub => {
      const handler = (_e: Electron.IpcRendererEvent, payload: RelayStatus): void => cb(payload);
      ipcRenderer.on('relay:status', handler);
      return () => ipcRenderer.removeListener('relay:status', handler);
    },
    onRegistrationConsent: (
      cb: (req: { sessionId: string; basePath: string; tools: string[] }) => void,
    ): Unsub => {
      const handler = (
        _e: Electron.IpcRendererEvent,
        payload: { sessionId: string; basePath: string; tools: string[] },
      ): void => cb(payload);
      ipcRenderer.on('relay:registration-consent', handler);
      return () => ipcRenderer.removeListener('relay:registration-consent', handler);
    },
  },
  sse: {
    /**
     * Subscribes to the session event stream in main. Events arrive on
     * `sse:event` as `{ sessionId, id?, event, data }`. Unsubscribing the
     * previous session happens implicitly on the next `subscribe` and on
     * this returned teardown.
     */
    subscribe: (
      sessionId: string,
      sinceSeq: number,
      cb: (frame: { sessionId: string; id?: string; event: string; data: string }) => void,
    ): Unsub => {
      const handler = (
        _e: Electron.IpcRendererEvent,
        payload: { sessionId: string; id?: string; event: string; data: string },
      ): void => cb(payload);
      ipcRenderer.on('sse:event', handler);
      void ipcRenderer.invoke(IPC.SSE_SUBSCRIBE, { sessionId, sinceSeq });
      return () => {
        ipcRenderer.removeListener('sse:event', handler);
        void ipcRenderer.invoke(IPC.SSE_UNSUBSCRIBE, sessionId);
      };
    },
  },
  artifact: {
    /** OS save-as dialog → stream file → chosen local path. */
    download: (body: ArtifactDownloadBody): Promise<WorkspaceDownloadResult> =>
      ipcRenderer.invoke(IPC.ARTIFACT_DOWNLOAD, body),
    /** Cache copy under userData/cache/artifacts + shell.openPath. */
    open: (body: ArtifactDownloadBody): Promise<WorkspaceDownloadResult> =>
      ipcRenderer.invoke(IPC.ARTIFACT_OPEN, body),
  },
  task: {
    list: (query: TaskListQuery = {}): Promise<TaskPage> =>
      ipcRenderer.invoke(IPC.TASK_LIST, query),
    get: (id: string): Promise<TaskDto> => ipcRenderer.invoke(IPC.TASK_GET, id),
    history: (id: string, query: TaskHistoryQuery = {}): Promise<TransitionPage> =>
      ipcRenderer.invoke(IPC.TASK_HISTORY, { id, ...query }),
    comments: {
      list: (id: string, query: TaskCommentsListQuery = {}): Promise<CommentPage> =>
        ipcRenderer.invoke(IPC.TASK_COMMENTS_LIST, { id, ...query }),
      add: (id: string, body: TaskCommentAddBody): Promise<CommentDto> =>
        ipcRenderer.invoke(IPC.TASK_COMMENT_ADD, { id, ...body }),
    },
    subscribe: (
      payload: TaskSubscribeBody,
      cb: (frame: {
        taskId: string;
        id?: string;
        event: string;
        data: string;
      }) => void,
    ): Unsub => {
      const handler = (
        _e: Electron.IpcRendererEvent,
        msg: { taskId: string; id?: string; event: string; data: string },
      ): void => cb(msg);
      ipcRenderer.on('task:event', handler);
      void ipcRenderer.invoke(IPC.TASK_SUBSCRIBE, payload);
      return () => {
        ipcRenderer.removeListener('task:event', handler);
        void ipcRenderer.invoke(IPC.TASK_UNSUBSCRIBE, payload.taskId);
      };
    },
  },
  tool: {
    respondConfirm: (callId: string, approved: boolean): Promise<void> =>
      ipcRenderer.invoke(IPC.TOOL_RESPOND_CONFIRM, { callId, approved }),
    cancel: (callId: string): Promise<void> =>
      ipcRenderer.invoke(IPC.TOOL_CANCEL, callId),
    onCall: (cb: (call: ToolCallView, basePath: string) => void): Unsub => {
      const handler = (
        _e: Electron.IpcRendererEvent,
        call: ToolCallView,
        basePath: string,
      ): void => cb(call, basePath);
      ipcRenderer.on('tool:call', handler);
      return () => ipcRenderer.removeListener('tool:call', handler);
    },
    onResult: (cb: (callId: string, output: string, exitCode: number) => void): Unsub => {
      const handler = (
        _e: Electron.IpcRendererEvent,
        callId: string,
        output: string,
        exitCode: number,
      ): void => cb(callId, output, exitCode);
      ipcRenderer.on('tool:result', handler);
      return () => ipcRenderer.removeListener('tool:result', handler);
    },
  },
  app: {
    quit: (): Promise<void> => ipcRenderer.invoke(IPC.APP_QUIT),
  },
} as const;

contextBridge.exposeInMainWorld('harness', api);

export type HarnessApi = typeof api;
