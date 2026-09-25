// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
// @ts-expect-error — SFC resolved by vite plugin in vitest; tsc project is node-only
import SessionList from '../../../src/renderer/src/components/SessionList.vue';

function mountList(agentsError: string | null) {
  return mount(SessionList, {
    props: {
      sessions: [],
      agents: [],
      agentsError,
      activeId: null,
      search: '',
      loading: false,
      error: null,
      hasMore: false,
    },
  });
}

describe('SessionList catalog error', () => {
  it('shows the catalog error in the existing error style', () => {
    const wrapper = mountList('backend restarting');
    const err = wrapper.find('[data-testid="agents-error"]');
    expect(err.exists()).toBe(true);
    expect(err.text()).toBe('backend restarting');
    expect(err.classes()).toContain('error');
  });

  it('renders no error placeholder when the catalog is fine', () => {
    const wrapper = mountList(null);
    expect(wrapper.find('[data-testid="agents-error"]').exists()).toBe(false);
  });
});
