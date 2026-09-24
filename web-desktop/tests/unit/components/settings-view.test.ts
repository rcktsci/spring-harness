// @vitest-environment jsdom
import { describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
// @ts-expect-error — SFC resolved by vite plugin in vitest; tsc project is node-only
import SettingsView from '../../../src/renderer/src/views/SettingsView.vue';
import { DEFAULT_CONFIG } from '../../../src/shared/ipc-contract';

type Bridge = { config: { get: () => Promise<unknown>; set: (patch: unknown) => Promise<unknown> } };

function installBridge() {
  const set = vi.fn(async (patch: unknown) => {
    const cloned = structuredClone(patch);
    return { ...DEFAULT_CONFIG, ...(cloned as Partial<typeof DEFAULT_CONFIG>) };
  });
  (window as unknown as { harness: Bridge }).harness = {
    config: {
      get: async () => ({ ...DEFAULT_CONFIG }),
      set,
    },
  };
  return set;
}

describe('SettingsView', () => {
  it('sends a structured-cloneable payload over IPC', async () => {
    const set = installBridge();
    const wrapper = mount(SettingsView);
    await flushPromises();
    await wrapper.findAll('button')[0]!.trigger('click');
    await flushPromises();
    expect(set).toHaveBeenCalledTimes(1);
    expect(wrapper.find('.error').exists()).toBe(false);
    expect(wrapper.find('.ok').exists()).toBe(true);
  });

  it('keeps edited values in the payload', async () => {
    const set = installBridge();
    const wrapper = mount(SettingsView);
    await flushPromises();
    const input = wrapper.find('input');
    await input.setValue('http://vm.example:8080');
    await wrapper.findAll('button')[0]!.trigger('click');
    await flushPromises();
    expect(set.mock.calls[0]![0]).toMatchObject({ serverBaseUrl: 'http://vm.example:8080' });
  });
});
