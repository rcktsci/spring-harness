import { contextBridge, ipcRenderer } from 'electron';
import {
  IPC,
  type LoginState,
  type RelayStatus,
  type ServerConfig,
  type ServerConfigPatch,
  type ToolCallView,
} from '../shared/ipc-contract.js';

type Unsub = () => void;

const api = {
  auth: {
    loginState: (): Promise<LoginState> => ipcRenderer.invoke(IPC.AUTH_LOGIN_STATE),
    loginStart: (): Promise<void> => ipcRenderer.invoke(IPC.AUTH_LOGIN_START),
    logout: (): Promise<void> => ipcRenderer.invoke(IPC.AUTH_LOGIN_LOGOUT),
    refresh: (): Promise<void> => ipcRenderer.invoke(IPC.AUTH_REFRESH),
  },
  config: {
    get: (): Promise<ServerConfig> => ipcRenderer.invoke(IPC.CONFIG_GET),
    set: (patch: ServerConfigPatch): Promise<ServerConfig> =>
      ipcRenderer.invoke(IPC.CONFIG_SET, patch),
    openLogs: (): Promise<void> => ipcRenderer.invoke(IPC.CONFIG_OPEN_LOGS),
  },
  session: {
    list: (): Promise<unknown[]> => ipcRenderer.invoke(IPC.SESSION_LIST),
    get: (id: string): Promise<unknown> => ipcRenderer.invoke(IPC.SESSION_GET, id),
    messages: (id: string): Promise<unknown[]> => ipcRenderer.invoke(IPC.SESSION_MESSAGES, id),
    send: (id: string, body: string): Promise<unknown> =>
      ipcRenderer.invoke(IPC.SESSION_SEND, { id, body }),
    compact: (id: string): Promise<unknown> => ipcRenderer.invoke(IPC.SESSION_COMPACT, id),
    stop: (id: string): Promise<unknown> => ipcRenderer.invoke(IPC.SESSION_STOP, id),
    tree: (id: string): Promise<unknown> => ipcRenderer.invoke(IPC.SESSION_TREE, id),
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
    subscribe: (sessionId: string, cb: (event: unknown) => void): Unsub => {
      const handler = (_e: Electron.IpcRendererEvent, payload: unknown): void => cb(payload);
      void ipcRenderer.invoke(IPC.SSE_SUBSCRIBE, sessionId);
      ipcRenderer.on('sse:event', handler);
      return () => {
        ipcRenderer.removeListener('sse:event', handler);
        void ipcRenderer.invoke(IPC.SSE_UNSUBSCRIBE, sessionId);
      };
    },
  },
  artifact: {
    download: (path: string): Promise<string> => ipcRenderer.invoke(IPC.ARTIFACT_DOWNLOAD, path),
    open: (path: string): Promise<void> => ipcRenderer.invoke(IPC.ARTIFACT_OPEN, path),
  },
  tool: {
    respondConfirm: (callId: string, approved: boolean): Promise<void> =>
      ipcRenderer.invoke(IPC.TOOL_RESPOND_CONFIRM, { callId, approved }),
    cancel: (callId: string): Promise<void> => ipcRenderer.invoke(IPC.TOOL_CANCEL, callId),
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
