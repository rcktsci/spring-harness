// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
// @ts-expect-error — SFC resolved by vite plugin in vitest; tsc project is node-only
import RelayDialogs from '../../../src/renderer/src/components/RelayDialogs.vue';

type Handler = (...args: unknown[]) => void;

/**
 * One harness instance per file: useRelay wires its subscriptions on first
 * use (module-scoped state). Subscription callbacks are collected into
 * plain arrays at registration time — Vitest may clear mock call history
 * between tests, the arrays survive that.
 */
const consentCbs: Handler[] = [];
const toolCallCbs: Handler[] = [];

const harness = {
  relay: {
    connect: vi.fn(async () => null),
    register: vi.fn(async () => ({ ok: true })),
    setSession: vi.fn(async () => ({ ok: true })),
    disconnect: vi.fn(async () => undefined),
    status: vi.fn(async () => null),
    confirmRegistration: vi.fn(async () => ({ ok: true })),
    pendingConsent: vi.fn(async () => null),
    onStatus: vi.fn((_cb: Handler) => () => undefined),
    onRegistrationConsent: vi.fn((cb: Handler) => {
      consentCbs.push(cb);
      return () => undefined;
    }),
  },
  tool: {
    onCall: vi.fn((cb: Handler) => {
      toolCallCbs.push(cb);
      return () => undefined;
    }),
    onResult: vi.fn((_cb: Handler) => () => undefined),
    respondConfirm: vi.fn(async () => undefined),
    cancel: vi.fn(async () => undefined),
  },
};

(window as unknown as { harness: typeof harness }).harness = harness;

function emitConsent(payload: unknown): void {
  for (const cb of consentCbs) cb(payload);
}

function emitToolCall(call: unknown, basePath: string): void {
  for (const cb of toolCallCbs) cb(call, basePath);
}

beforeEach(() => {
  harness.relay.pendingConsent.mockReset().mockResolvedValue(null);
  harness.relay.status.mockReset().mockResolvedValue(null);
  harness.relay.confirmRegistration.mockClear().mockResolvedValue({ ok: true });
  harness.tool.respondConfirm.mockClear().mockResolvedValue(undefined);
});

describe('RelayDialogs', () => {
  it('renders nothing when no prompt is pending', async () => {
    const wrapper = mount(RelayDialogs);
    await flushPromises();
    expect(wrapper.find('[data-testid="consent-dialog"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="tool-confirm-dialog"]').exists()).toBe(false);
  });

  it('re-fetches a pending consent that arrived before mount', async () => {
    harness.relay.pendingConsent.mockResolvedValue({
      sessionId: 'sess-a',
      basePath: '/home/u/harness-workspaces/sess-a',
      tools: ['bash', 'grep'],
    });
    const wrapper = mount(RelayDialogs);
    await flushPromises();
    const dialog = wrapper.find('[data-testid="consent-dialog"]');
    expect(dialog.exists()).toBe(true);
    expect(dialog.text()).toContain('sess-a');
    expect(dialog.text()).toContain('bash, grep');
    await wrapper.find('[data-testid="consent-approve"]').trigger('click');
    await flushPromises();
    expect(harness.relay.confirmRegistration).toHaveBeenCalledWith('sess-a', true);
    expect(wrapper.find('[data-testid="consent-dialog"]').exists()).toBe(false);
  });

  it('shows a live consent request and forwards a decline', async () => {
    const wrapper = mount(RelayDialogs);
    await flushPromises();
    emitConsent({ sessionId: 'sess-b', basePath: '/tmp/ws', tools: ['bash'] });
    await flushPromises();
    expect(wrapper.find('[data-testid="consent-dialog"]').exists()).toBe(true);
    await wrapper.find('[data-testid="consent-deny"]').trigger('click');
    await flushPromises();
    expect(harness.relay.confirmRegistration).toHaveBeenCalledWith('sess-b', false);
    expect(wrapper.find('[data-testid="consent-dialog"]').exists()).toBe(false);
  });

  it('shows the tool-confirm dialog and forwards the answer', async () => {
    const wrapper = mount(RelayDialogs);
    await flushPromises();
    emitToolCall(
      { callId: 'call-9', sessionId: 'sess-b', tool: 'bash', args: { command: 'rm -rf /tmp/x' }, basePath: '/tmp/ws' },
      '/tmp/ws',
    );
    await flushPromises();
    const dialog = wrapper.find('[data-testid="tool-confirm-dialog"]');
    expect(dialog.exists()).toBe(true);
    expect(dialog.text()).toContain('rm -rf /tmp/x');
    await wrapper.find('[data-testid="tool-confirm-deny"]').trigger('click');
    await flushPromises();
    expect(harness.tool.respondConfirm).toHaveBeenCalledWith('call-9', false);
    expect(wrapper.find('[data-testid="tool-confirm-dialog"]').exists()).toBe(false);
  });
});
