// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import SessionTree from '../../../src/renderer/src/components/SessionTree.vue';
import type { SessionTreeNode } from '../../../src/shared/api-types';

function n(partial: Partial<SessionTreeNode> & { id: string }): SessionTreeNode {
  return {
    id: partial.id,
    parentSessionId: partial.parentSessionId ?? null,
    kind: partial.kind ?? 'FREE',
    agent: partial.agent ?? { key: 'a', rev: 1 },
    runtimeStatus: partial.runtimeStatus ?? 'IDLE',
    lastSeq: partial.lastSeq ?? 0,
    lastActivityAt: partial.lastActivityAt ?? '2026-09-23T00:00:00Z',
    taskId: partial.taskId,
    stateCode: partial.stateCode,
  } as SessionTreeNode;
}

describe('SessionTree', () => {
  it('renders root + nested STATE children with badges', () => {
    const items: SessionTreeNode[] = [
      n({ id: 'r', kind: 'FREE', runtimeStatus: 'TURN_RUNNING' }),
      n({
        id: 's1',
        parentSessionId: 'r',
        kind: 'STATE',
        taskId: 't1',
        stateCode: 'init',
        runtimeStatus: 'PARKED_ASYNC',
      }),
    ];
    const wrapper = mount(SessionTree, {
      props: { items, activeId: 's1', loading: false, error: null },
    });
    expect(wrapper.find('[data-testid="tree"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('FREE');
    expect(wrapper.text()).toContain('STATE');
    expect(wrapper.text()).toContain('думает');
    expect(wrapper.text()).toContain('ждёт инструмент');
    expect(wrapper.text()).toContain('t1 · init');
    // active state should mark s1
    const activeBtn = wrapper.find('button.active');
    expect(activeBtn.attributes('data-session-id')).toBe('s1');
  });

  it('emits select with the chosen id', async () => {
    const items: SessionTreeNode[] = [
      n({ id: 'r' }),
      n({ id: 's1', parentSessionId: 'r', kind: 'STATE', taskId: 't1', stateCode: 'init' }),
    ];
    const wrapper = mount(SessionTree, {
      props: { items, activeId: null, loading: false, error: null },
    });
    await wrapper.find('[data-session-id="s1"]').trigger('click');
    expect(wrapper.emitted('select')?.[0]).toEqual(['s1']);
  });

  it('shows empty hint when no roots', () => {
    const wrapper = mount(SessionTree, {
      props: { items: [], activeId: null, loading: false, error: null },
    });
    expect(wrapper.text()).toContain('Выберите активную сессию');
  });

  it('shows an error message when fetch failed', () => {
    const wrapper = mount(SessionTree, {
      props: { items: [], activeId: null, loading: false, error: 'boom' },
    });
    expect(wrapper.find('.error').text()).toBe('boom');
  });
});
