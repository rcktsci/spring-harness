// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
// @ts-expect-error — SFC resolved by vite plugin in vitest; tsc project is node-only
import ChatFeed from '../../../src/renderer/src/components/ChatFeed.vue';
import { buildFeed } from '../../../src/renderer/src/lib/feed';
import type { MessageDto } from '../../../src/shared/api-types';

function msg(partial: Partial<MessageDto> & Pick<MessageDto, 'id' | 'seq' | 'kind'>): MessageDto {
  return {
    payload: {},
    createdAt: '2026-09-23T00:00:00Z',
    ...partial,
  } as MessageDto;
}

const allKinds: MessageDto[] = [
  msg({ id: '1', seq: 1, kind: 'USER', author: 'alice', payload: { text: '**bold** user' } }),
  msg({ id: '2', seq: 2, kind: 'ASSISTANT', payload: { text: 'assistant reply' } }),
  msg({ id: '3', seq: 3, kind: 'SYSTEM', payload: { text: 'system note' } }),
  msg({
    id: '4',
    seq: 4,
    kind: 'TOOL_CALL',
    callId: 'call-1',
    payload: { callId: 'call-1', tool: 'bash', arguments: { command: 'ls' } },
  }),
  msg({
    id: '5',
    seq: 5,
    kind: 'TOOL_RESULT',
    callId: 'call-1',
    payload: { callId: 'call-1', tool: 'bash', status: 'OK', output: 'a.txt' },
  }),
  msg({
    id: '6',
    seq: 6,
    kind: 'COMPACT',
    payload: { covers: [{ from: 1, to: 3 }], summary: 'boundary summary' },
  }),
];

describe('ChatFeed', () => {
  it('renders all MessageKind entries', () => {
    const wrapper = mount(ChatFeed, { props: { entries: buildFeed(allKinds) } });
    expect(wrapper.find('[data-kind="USER"]').exists()).toBe(true);
    expect(wrapper.find('[data-kind="ASSISTANT"]').exists()).toBe(true);
    expect(wrapper.find('[data-kind="SYSTEM"]').exists()).toBe(true);
    expect(wrapper.find('[data-kind="COMPACT"]').exists()).toBe(true);
    expect(wrapper.find('[data-kind="TOOL"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('alice');
    expect(wrapper.text()).toContain('assistant reply');
    expect(wrapper.text()).toContain('system note');
    expect(wrapper.text()).toContain('boundary summary');
    expect(wrapper.text()).toContain('bash');
  });

  it('renders markdown for ASSISTANT and sanitizes HTML', () => {
    const entries = buildFeed([
      msg({ id: 'a', seq: 1, kind: 'ASSISTANT', payload: { text: '**bold**' } }),
      msg({
        id: 'b',
        seq: 2,
        kind: 'ASSISTANT',
        payload: { text: '<img src=x onerror=alert(1)>' },
      }),
    ]);
    const wrapper = mount(ChatFeed, { props: { entries } });
    expect(wrapper.find('strong').exists()).toBe(true);
    const html = wrapper.find('[data-kind="ASSISTANT"]').html();
    expect(html).not.toContain('onerror');
  });

  it('shows pending placeholder for TOOL_CALL without result', () => {
    const entries = buildFeed([
      msg({
        id: 'c',
        seq: 1,
        kind: 'TOOL_CALL',
        callId: 'call-9',
        payload: { callId: 'call-9', tool: 'bash', arguments: {} },
      }),
    ]);
    const wrapper = mount(ChatFeed, { props: { entries } });
    const tool = wrapper.find('[data-kind="TOOL"]');
    expect(tool.exists()).toBe(true);
    expect(tool.attributes('data-pending')).toBe('1');
    expect(wrapper.text()).toContain('ожидает результат');
  });

  it('shows late marker when result arrived late', () => {
    const entries = buildFeed([
      msg({
        id: 'c',
        seq: 1,
        kind: 'TOOL_CALL',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash', arguments: {} },
      }),
      msg({
        id: 'r',
        seq: 2,
        kind: 'TOOL_RESULT',
        callId: 'call-1',
        late: true,
        payload: { callId: 'call-1', tool: 'bash', status: 'OK', output: 'late out', late: true },
      }),
    ]);
    const wrapper = mount(ChatFeed, { props: { entries } });
    const tool = wrapper.find('[data-kind="TOOL"]');
    expect(tool.attributes('data-late')).toBe('1');
    expect(wrapper.text()).toContain('late');
    expect(tool.attributes('data-pending')).toBe('0');
  });

  it('toggles collapsible tool block', async () => {
    const entries = buildFeed([
      msg({
        id: 'c',
        seq: 1,
        kind: 'TOOL_CALL',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash', arguments: { command: 'ls' } },
      }),
      msg({
        id: 'r',
        seq: 2,
        kind: 'TOOL_RESULT',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash', status: 'OK', output: 'a.txt' },
      }),
    ]);
    const wrapper = mount(ChatFeed, { props: { entries } });
    // collapsed by default — args not visible
    expect(wrapper.text()).not.toContain('command');
    await wrapper.find('.tool-toggle').trigger('click');
    expect(wrapper.text()).toContain('command');
    expect(wrapper.text()).toContain('a.txt');
    await wrapper.find('.tool-toggle').trigger('click');
    expect(wrapper.text()).not.toContain('a.txt');
  });
});
