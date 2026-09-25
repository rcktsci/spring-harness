import { onMounted, onUnmounted, ref, watch, type Ref } from 'vue';
import type { AgentCatalogItem, SessionDto } from '@shared/api-types';

export interface UseSessions {
  sessions: Ref<SessionDto[]>;
  agents: Ref<AgentCatalogItem[]>;
  agentsError: Ref<string | null>;
  search: Ref<string>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  hasMore: Ref<boolean>;
  refresh: () => Promise<void>;
  loadMore: () => Promise<void>;
  create: (agentKey: string, title?: string) => Promise<SessionDto>;
}

/**
 * Session list: GET /sessions?mine=true with cursor paging and a debounced
 * `q=` search (debounce from config — no hardcoded numbers).
 */
export function useSessions(): UseSessions {
  const sessions = ref<SessionDto[]>([]);
  const agents = ref<AgentCatalogItem[]>([]);
  const agentsError = ref<string | null>(null);
  const search = ref('');
  const loading = ref(false);
  const error = ref<string | null>(null);
  const hasMore = ref(false);
  let cursor: string | undefined;
  let debounceTimer: ReturnType<typeof setTimeout> | null = null;
  let debounceMs = 300;
  let listLimit = 50;
  let disposed = false;
  let requestId = 0;
  let stopSearchWatch: (() => void) | null = null;

  async function loadAgents(): Promise<void> {
    if (disposed) return;
    agentsError.value = null;
    try {
      const catalog = await window.harness.agents.list();
      if (disposed) return;
      agents.value = catalog.items;
    } catch (err) {
      if (disposed) return;
      agentsError.value = err instanceof Error ? err.message : String(err);
    }
  }

  async function fetchPage(reset: boolean): Promise<void> {
    const token = ++requestId;
    loading.value = true;
    error.value = null;
    try {
      const cfg = await window.harness.config.get();
      debounceMs = cfg.sessionSearchDebounceMs;
      listLimit = cfg.sessionListLimit;
      if (reset) cursor = undefined;
      const page = await window.harness.session.list({
        mine: true,
        q: search.value.trim() || undefined,
        cursor: reset ? undefined : cursor,
        limit: listLimit,
      });
      if (disposed || token !== requestId) return;
      sessions.value = reset ? page.items : [...sessions.value, ...page.items];
      cursor = page.nextCursor;
      hasMore.value = Boolean(page.nextCursor);
    } catch (err) {
      if (disposed || token !== requestId) return;
      error.value = err instanceof Error ? err.message : String(err);
    } finally {
      if (!disposed && token === requestId) loading.value = false;
    }
  }

  function scheduleSearch(): void {
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = setTimeout(() => {
      debounceTimer = null;
      if (!disposed) void fetchPage(true);
    }, debounceMs);
  }

  async function refresh(): Promise<void> {
    await loadAgents();
    await fetchPage(true);
  }

  async function loadMore(): Promise<void> {
    if (!hasMore.value || loading.value) return;
    await fetchPage(false);
  }

  async function create(agentKey: string, title?: string): Promise<SessionDto> {
    const body = title && title.trim() ? { agentKey, title: title.trim() } : { agentKey };
    const session = await window.harness.session.create(body);
    await fetchPage(true);
    return session;
  }

  const stopWatch = watch(search, () => {
    scheduleSearch();
  });
  stopSearchWatch = stopWatch;

  onMounted(async () => {
    try {
      const cfg = await window.harness.config.get();
      debounceMs = cfg.sessionSearchDebounceMs;
      listLimit = cfg.sessionListLimit;
    } catch {
      /* defaults already set */
    }
    await refresh();
  });

  onUnmounted(() => {
    disposed = true;
    stopSearchWatch?.();
    stopSearchWatch = null;
    if (debounceTimer) clearTimeout(debounceTimer);
    debounceTimer = null;
  });

  return {
    sessions,
    agents,
    agentsError,
    search,
    loading,
    error,
    hasMore,
    refresh,
    loadMore,
    create,
  };
}
