// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
// @ts-expect-error — SFC resolved by vite plugin in vitest; tsc project is node-only
import SettingsView from '../../../src/renderer/src/views/SettingsView.vue';
import { DEFAULT_CONFIG } from '../../../src/shared/ipc-contract';

function mountSettings() {
  const pinia = createPinia();
  setActivePinia(pinia);
  return mount(SettingsView, { global: { plugins: [pinia] } });
}

type Bridge = {
  config: { get: () => Promise<unknown>; set: (patch: unknown) => Promise<unknown> };
  auth: { loginState: () => Promise<{ loggedIn: boolean }>; loginStart: () => Promise<unknown>; logout: () => Promise<void> };
};

function installBridge(options: { loggedIn?: boolean } = {}) {
  const set = vi.fn(async (patch: unknown) => {
    const cloned = structuredClone(patch);
    return { ...DEFAULT_CONFIG, ...(cloned as Partial<typeof DEFAULT_CONFIG>) };
  });
  const loginStart = vi.fn(async () => ({ loggedIn: true }));
  const logout = vi.fn(async () => undefined);
  (window as unknown as { harness: Bridge }).harness = {
    config: {
      get: async () => ({ ...DEFAULT_CONFIG }),
      set,
    },
    auth: {
      loginState: async () => ({ loggedIn: options.loggedIn ?? true }),
      loginStart,
      logout,
    },
  };
  return { set, loginStart, logout };
}

describe('SettingsView', () => {
  it('sends a structured-cloneable payload over IPC', async () => {
    const { set } = installBridge();
    const wrapper = mountSettings();
    await flushPromises();
    await wrapper.find('[data-testid="save"]').trigger('click');
    await flushPromises();
    expect(set).toHaveBeenCalledTimes(1);
    expect(wrapper.find('.error').exists()).toBe(false);
    expect(wrapper.find('.ok').exists()).toBe(true);
  });

  it('offers Sign in when the session is dead', async () => {
    const { loginStart } = installBridge({ loggedIn: false });
    const wrapper = mountSettings();
    await flushPromises();
    await wrapper.find('[data-testid="sign-in"]').trigger('click');
    await flushPromises();
    expect(loginStart).toHaveBeenCalledTimes(1);
  });

  it('offers Sign out when the session is alive', async () => {
    const { logout } = installBridge({ loggedIn: true });
    const wrapper = mountSettings();
    await flushPromises();
    expect(wrapper.find('[data-testid="sign-out"]').exists()).toBe(true);
    await wrapper.find('[data-testid="sign-out"]').trigger('click');
    await flushPromises();
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it('keeps edited values in the payload', async () => {
    const { set } = installBridge();
    const wrapper = mountSettings();
    await flushPromises();
    const input = wrapper.findAll('input:not([type="checkbox"])')[0]!;
    await input.setValue('http://vm.example:8080');
    await wrapper.find('[data-testid="save"]').trigger('click');
    await flushPromises();
    expect(set.mock.calls[0]![0]).toMatchObject({ serverBaseUrl: 'http://vm.example:8080' });
  });
});
