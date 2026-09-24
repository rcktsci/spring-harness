import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { ServerConfig } from '@shared/ipc-contract';

export const useConfigStore = defineStore('config', () => {
  const config = ref<ServerConfig | null>(null);

  async function load(): Promise<void> {
    config.value = await window.harness.config.get();
  }

  async function save(): Promise<void> {
    if (!config.value) return;
    config.value = await window.harness.config.set({ ...config.value });
  }

  return { config, load, save };
});
