// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { h } from 'vue';
import { createRouter, createMemoryHistory, RouterView, type Router } from 'vue-router';
import { createPinia, type Pinia } from 'pinia';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
// @ts-expect-error вЂ” SFC resolved by vite plugin in vitest; tsc project is node-only
import LoginView from '../../src/renderer/src/views/LoginView.vue';
// @ts-expect-error вЂ” SFC resolved by vite plugin in vitest; tsc project is node-only
import SettingsView from '../../src/renderer/src/views/SettingsView.vue';
import { installAuthGuard } from '../../src/renderer/src/auth-gate';
import { DEFAULT_CONFIG } from '../../src/shared/ipc-contract';

const harnessState = {
  loggedIn: false,
  loginStart: vi.fn(async () => undefined),
  logout: vi.fn(async () => undefined),
};

function installIpcBridge(): void {
  (window as unknown as { harness: unknown }).harness = {
    auth: {
      loginState: async () => ({ loggedIn: harnessState.loggedIn }),
      loginStart: async () => {
        await harnessState.loginStart();
        harnessState.loggedIn = true;
      },
      logout: async () => {
        await harnessState.logout();
        harnessState.loggedIn = false;
      },
      refresh: async () => undefined,
      onSessionLost: vi.fn(),
    },
    config: {
      get: async () => ({ ...DEFAULT_CONFIG }),
      set: async (patch: unknown) => ({ ...DEFAULT_CONFIG, ...(patch as object) }),
      openLogs: async () => undefined,
    },
  };
}

function buildApp(): { router: Router; pinia: Pinia; wrapper: VueWrapper } {
  const pinia = createPinia();
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: LoginView },
      { path: '/settings', name: 'settings', component: SettingsView },
      { path: '/chat', name: 'chat', component: { render: () => h('div', 'chat') } },
    ],
  });
  installAuthGuard(router, pinia);
  const wrapper = mount({ render: () => h(RouterView) }, { global: { plugins: [router, pinia] } });
  return { router, pinia, wrapper };
}

beforeEach(() => {
  harnessState.loggedIn = false;
  harnessState.loginStart.mockClear();
  harnessState.logout.mockClear();
  installIpcBridge();
});

describe('login navigation through the real guard/store/router stack', () => {
  it('keeps an unauthenticated user on /login even when /chat is requested', async () => {
    const { router } = buildApp();
    await router.push('/chat');
    await router.isReady();
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/login');
  });

  it('navigates to /chat after signing in from the login screen', async () => {
    const { router, wrapper } = buildApp();
    await router.push('/login');
    await router.isReady();
    await flushPromises();

    await wrapper.find('[data-testid="login-submit"]').trigger('click');
    await flushPromises();

    expect(harnessState.loginStart).toHaveBeenCalledTimes(1);
    expect(router.currentRoute.value.path).toBe('/chat');
  });

  it('returns to /login after signing out from settings', async () => {
    harnessState.loggedIn = true;
    const { router, wrapper } = buildApp();
    await router.push('/settings');
    await router.isReady();
    await flushPromises();

    await wrapper.find('[data-testid="sign-out"]').trigger('click');
    await flushPromises();

    expect(harnessState.logout).toHaveBeenCalledTimes(1);
    expect(router.currentRoute.value.path).toBe('/login');
  });
});
