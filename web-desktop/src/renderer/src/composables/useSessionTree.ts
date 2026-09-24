/**
 * useSessionTree — fetches `GET /sessions/{id}/tree` for the active session,
 * polls at `cfg.treeRefreshIntervalMs`, and re-fetches on `session.status`
 * transitions (driven by the SSE bridge from `useChat`). One subscription
 * at a time; switching rootSessionId aborts the timer + closes the SSE
 * dependency handle.
 */
import { onUnmounted, ref, type Ref } from 'vue';
import type { SessionTreeNode, SessionTreePage } from '@shared/api-types';
import { flattenForRender, pollIntervalMs as clampPollMs } from '../lib/session-tree';

export interface UseSessionTree {
  items: Ref<SessionTreeNode[]>;
  rootId: Ref<string | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  flat: Ref<SessionTreeNode[]>;
  /** Set the active root (the FREE session whose sub-tree to render). */
  setRoot: (id: string | null) => Promise<void>;
  /** Force-refresh (timer tick + manual button). */
  refresh: () => Promise<void>;
}

export function useSessionTree(): UseSessionTree {
  const items = ref<SessionTreeNode[]>([]);
  const rootId = ref<string | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const flat = ref<SessionTreeNode[]>([]);
  let pollTimer: ReturnType<typeof setInterval> | null = null;
  let intervalMs = 10_000;
  let requestId = 0;

  async function fetchTree(nowRoot: string): Promise<void> {
    const token = ++requestId;
    loading.value = true;
    error.value = null;
    try {
      const page: SessionTreePage = await window.harness.session.tree(nowRoot);
      if (token !== requestId) return;
      items.value = page.items;
      flat.value = flattenForRender(page.items);
    } catch (err) {
      if (token !== requestId) return;
      error.value = err instanceof Error ? err.message : String(err);
    } finally {
      if (token === requestId) loading.value = false;
    }
  }

  function startPoll(): void {
    stopPoll();
    if (!rootId.value) return;
    pollTimer = setInterval(() => {
      if (rootId.value) void fetchTree(rootId.value);
    }, intervalMs);
  }

  function stopPoll(): void {
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
  }

  async function setRoot(id: string | null): Promise<void> {
    rootId.value = id;
    items.value = [];
    flat.value = [];
    if (!id) {
      stopPoll();
      return;
    }
    try {
      const cfg = await window.harness.config.get();
      intervalMs = clampPollMs(cfg.treeRefreshIntervalMs);
    } catch {
      /* keep default */
    }
    await fetchTree(id);
    startPoll();
  }

  async function refresh(): Promise<void> {
    if (rootId.value) await fetchTree(rootId.value);
  }

  onUnmounted(() => {
    stopPoll();
    requestId += 1;
  });

  return {
    items,
    rootId,
    loading,
    error,
    flat,
    setRoot,
    refresh,
  };
}

/**
 * Convenience: bake a parent-chain badge for a STATE subnode (id-only —
 * SessionTreeNode has no title field on the wire contract).
 */
export function describeChain(path: string[], nodes: SessionTreeNode[]): string {
  const label = (id: string): string => {
    const n = nodes.find((x) => x.id === id);
    return n ? id.slice(0, 8) : id.slice(0, 8);
  };
  return path.map(label).join(' › ');
}
