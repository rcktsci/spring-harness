// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import TaskPanel from '../../../src/renderer/src/components/TaskPanel.vue';
import type { CommentDto, TransitionDto } from '../../../src/shared/api-types';

const history: TransitionDto[] = [
  {
    id: 'tr1',
    fromState: 'init',
    toState: 'build',
    kind: 'agent',
    reason: {},
    createdAt: '2026-09-23T00:00:00Z',
  },
];
const comments: CommentDto[] = [
  { id: 'c1', taskId: 't1', body: 'привет', author: 'me', createdAt: '2026-09-23T00:00:00Z' },
];

describe('TaskPanel', () => {
  it('renders status, state, history list, and comments list', () => {
    const wrapper = mount(TaskPanel, {
      props: {
        title: 'My task',
        status: 'RUNNING',
        currentState: 'build',
        history,
        comments,
        error: null,
        loading: false,
        canComment: true,
      },
    });
    expect(wrapper.text()).toContain('My task');
    expect(wrapper.text()).toContain('RUNNING');
    expect(wrapper.find('[data-testid="history"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="comments"]').exists()).toBe(true);
    expect(wrapper.text()).toContain('init');
    expect(wrapper.text()).toContain('build');
    expect(wrapper.text()).toContain('привет');
  });

  it('emits add-comment with the typed body', async () => {
    const wrapper = mount(TaskPanel, {
      props: {
        title: 't',
        status: null,
        currentState: 'init',
        history: [],
        comments: [],
        error: null,
        loading: false,
        canComment: true,
      },
    });
    await wrapper.find('[data-testid="comment-input"]').setValue('новый коммент');
    await wrapper.find('form.compose').trigger('submit.prevent');
    expect(wrapper.emitted('add-comment')?.[0]).toEqual(['новый коммент']);
  });

  it('shows empty-state copy when no history/comments', () => {
    const wrapper = mount(TaskPanel, {
      props: {
        title: 't',
        status: null,
        currentState: 'init',
        history: [],
        comments: [],
        error: null,
        loading: false,
        canComment: false,
      },
    });
    expect(wrapper.text()).toContain('Переходов пока нет');
    expect(wrapper.text()).toContain('Комментариев пока нет');
  });
});
