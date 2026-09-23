/// <reference types="vite/client" />

import type { HarnessApi } from '../preload/index';

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<Record<string, unknown>, Record<string, unknown>, unknown>;
  export default component;
}

interface ImportMetaEnv {
  readonly VITE_DEV_SERVER_URL?: string;
}

interface Window {
  readonly harness: HarnessApi;
}
