// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest';
import { h } from 'vue';
import { createRouter, createMemoryHistory, RouterView, type Router } from 'vue-router';
import { createPinia, setActivePinia, type Pinia } from 'pinia';
import { flushPromises, mount } from '@vue/test-utils';
import { installAuthGuard } from '../../src/renderer/src/auth-gate';
import { useAuthStore } from '../../src/renderer/src/stores/auth';
import { DEFAULT_CONFIG } from '../../src/shared/ipc-contract';
import { sessionInvalidatingChange } from '../../src/shared/config-invalidation';
// @ts-expect-error — SFC resolved by vite plugin in vitest; tsc project is node-only
import SettingsView from '../../src/renderer/src/views/SettingsView.vue';

const harnessState = {
  loggedIn: true,
  loginStateCalls: 0,
  configSetCalls: 0,
};
const sessionLostCbs: Array<() => void> = [];

function installIpcBridge(): void {
  sessionLostCbs.length = 0;
  harnessState.configSetCalls = 0;
  (window as unknown as { harness: unknown }).harness = {
    auth: {
      loginState: async () => {
        harnessState.loginStateCalls += 1;
        return { loggedIn: harnessState.loggedIn };
      },
      loginStart: async () => undefined,
      logout: async () => undefined,
      refresh: async () => undefined,
      onSessionLost: (cb: () => void) => {
        sessionLostCbs.push(cb);
        return () => undefined;
      },
    },
    config: {
      get: async () => ({ ...DEFAULT_CONFIG }),
      set: async (patch: Partial<typeof DEFAULT_CONFIG>) => {
        harnessState.configSetCalls += 1;
        const next = { ...DEFAULT_CONFIG, ...patch };
        if (sessionInvalidatingChange(DEFAULT_CONFIG, next)) {
          harnessState.loggedIn = false;
          for (const cb of [...sessionLostCbs]) cb();
        }
        return next;
      },
    },
  };
}

function emitSessionLost(): void {
  for (const cb of [...sessionLostCbs]) cb();
}

function buildApp(): { router: Router; pinia: Pinia; wrapper: ReturnType<typeof mount> } {
  const pinia = createPinia();
  setActivePinia(pinia);
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'login', component: { render: () => h('div', 'login') } },
      { path: '/chat', name: 'chat', component: { render: () => h('div', 'chat') } },
      { path: '/settings', name: 'settings', component: SettingsView },
    ],
  });
  installAuthGuard(router, pinia);
  const Shell = { render: () => h(RouterView) };
  const wrapper = mount(Shell, { global: { plugins: [router, pinia] } });
  return { router, pinia, wrapper };
}

beforeEach(() => {
  harnessState.loggedIn = true;
  harnessState.loginStateCalls = 0;
  installIpcBridge();
});

describe('session loss drives the UI to /login', () => {
  it('routes to /login when a session loss event arrives from main', async () => {
    const { router, pinia } = buildApp();
    await router.push('/chat');
    await router.isReady();
    await flushPromises();

    harnessState.loggedIn = false;
    emitSessionLost();
    await flushPromises();

    expect(router.currentRoute.value.path).toBe('/login');
    expect(useAuthStore(pinia).state.loggedIn).toBe(false);
  });

  it('is idempotent on repeated loss events', async () => {
    const { router } = buildApp();
    await router.push('/chat');
    await router.isReady();
    await flushPromises();

    harnessState.loggedIn = false;
    emitSessionLost();
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/login');

    emitSessionLost();
    await flushPromises();
    expect(router.currentRoute.value.path).toBe('/login');
  });

  it('keeps the user in place when the session is still alive', async () => {
    const { router } = buildApp();
    await router.push('/chat');
    await router.isReady();
    await flushPromises();

    emitSessionLost();
    await flushPromises();

    expect(router.currentRoute.value.path).toBe('/chat');
  });
});

describe('keycloak endpoint change acts like a session loss', () => {
  it('routes to /login when the issuer is changed in settings', async () => {
    const { router, pinia, wrapper } = buildApp();
    await router.push('/settings');
    await router.isReady();
    await flushPromises();

    const inputs = wrapper.findAll('input');
    await inputs[1]!.setValue('http://keycloak-other:8080/realms/harness');
    await wrapper.find('[data-testid="save"]').trigger('click');
    await flushPromises();

    expect(router.currentRoute.value.path).toBe('/login');
    expect(useAuthStore(pinia).state.loggedIn).toBe(false);
  });

  it('does not sign out on non-keycloak settings changes', async () => {
    const { router, wrapper } = buildApp();
    await router.push('/settings');
    await router.isReady();
    await flushPromises();

    await wrapper.find('input[type="checkbox"]').setValue(false);
    await wrapper.find('[data-testid="save"]').trigger('click');
    await flushPromises();

    expect(harnessState.configSetCalls).toBe(1);
    expect(wrapper.find('[data-testid="save"]').exists()).toBe(true);
    expect(router.currentRoute.value.path).toBe('/settings');
    expect(useAuthStore().state.loggedIn).toBe(true);
  });
});
