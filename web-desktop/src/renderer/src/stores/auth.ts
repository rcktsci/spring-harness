import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { LoginState } from '@shared/ipc-contract';

export const useAuthStore = defineStore('auth', () => {
  const state = ref<LoginState>({ loggedIn: false });

  async function refresh(): Promise<void> {
    state.value = await window.harness.auth.loginState();
  }

  async function login(): Promise<void> {
    await window.harness.auth.loginStart();
    await refresh();
  }

  async function logout(): Promise<void> {
    await window.harness.auth.logout();
    await refresh();
  }

  return { state, refresh, login, logout };
});
