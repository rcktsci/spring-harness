/**
 * Pure helpers for session-tree manipulation — kept side-effect-free so
 * the renderer can wire them up reactively without DOM dependencies.
 */
import type { SessionTreeNode } from '@shared/api-types';

export function rootsOf(items: SessionTreeNode[]): SessionTreeNode[] {
  return items.filter((n) => n.parentSessionId === null);
}

export function childrenOf(items: SessionTreeNode[], parentId: string): SessionTreeNode[] {
  return items.filter((n) => n.parentSessionId === parentId);
}

/** Flat DFS in display order; root first, depth-first children. */
export function flattenForRender(items: SessionTreeNode[]): SessionTreeNode[] {
  const out: SessionTreeNode[] = [];
  const walk = (n: SessionTreeNode): void => {
    out.push(n);
    for (const c of childrenOf(items, n.id)) walk(c);
  };
  for (const r of rootsOf(items)) walk(r);
  return out;
}

/** Build a path-from-root lookup (id → ordered list of node ids). */
export function buildPathIndex(items: SessionTreeNode[]): Map<string, string[]> {
  const out = new Map<string, string[]>();
  const walk = (node: SessionTreeNode, ancestors: string[]): void => {
    const path = [...ancestors, node.id];
    out.set(node.id, path);
    for (const c of childrenOf(items, node.id)) walk(c, path);
  };
  for (const root of rootsOf(items)) walk(root, []);
  return out;
}

/** Confirm a node is a STATE sub-session with a taskId. */
export function isStateSubnode(n: SessionTreeNode): boolean {
  return n.kind === 'STATE' && Boolean(n.taskId);
}

/** Lower bound for poll cadence to avoid runaway timers. */
export function pollIntervalMs(refreshIntervalMs: number): number {
  return Math.max(refreshIntervalMs, 1_000);
}
