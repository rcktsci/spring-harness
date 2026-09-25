// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { flushPromises, mount } from '@vue/test-utils';
import { DEFAULT_CONFIG, type AgentCatalog, type SessionPage } from '../../src/shared/ipc-contract';
import { useSessions, type UseSessions } from '../../src/renderer/src/composables/useSessions';

function makeHarness() {
  return {
    config: { get: vi.fn(async () => ({ ...DEFAULT_CONFIG })) },
    agents: {
      list: vi.fn(async (): Promise<AgentCatalog> => ({
        items: [{ key: 'tester', name: 'Tester', latestRev: 1 }],
      })),
    },
    session: {
      list: vi.fn(async (): Promise<SessionPage> => ({ items: [] })),
    },
  };
}

type Harness = ReturnType<typeof makeHarness>;
let harness: Harness;

beforeEach(() => {
  harness = makeHarness();
  (window as unknown as { harness: unknown }).harness = harness;
});

function mountHost(): UseSessions {
  let exposed: UseSessions | undefined;
  const Host = defineComponent({
    setup() {
      exposed = useSessions();
      return () => h('div');
    },
  });
  mount(Host);
  return exposed!;
}

describe('useSessions catalog', () => {
  it('surfaces a catalog failure instead of a silent empty catalog', async () => {
    harness.agents.list.mockRejectedValue(new Error('backend restarting'));
    const sessions = mountHost();
    await flushPromises();
    expect(sessions.agentsError.value).toBe('backend restarting');
    expect(sessions.agents.value).toEqual([]);
  });

  it('keeps a genuinely empty catalog distinct from a failure', async () => {
    harness.agents.list.mockResolvedValue({ items: [] });
    const sessions = mountHost();
    await flushPromises();
    expect(sessions.agentsError.value).toBeNull();
    expect(sessions.agents.value).toEqual([]);
  });

  it('reloads the catalog on refresh after a failure', async () => {
    harness.agents.list.mockRejectedValueOnce(new Error('backend restarting'));
    const sessions = mountHost();
    await flushPromises();
    expect(sessions.agentsError.value).toBe('backend restarting');

    await sessions.refresh();
    expect(sessions.agentsError.value).toBeNull();
    expect(sessions.agents.value.map((a) => a.key)).toEqual(['tester']);
  });
});
