import { describe, expect, it } from 'vitest';
import {
  buildPathIndex,
  childrenOf,
  flattenForRender,
  isStateSubnode,
  rootsOf,
} from '../../src/renderer/src/lib/session-tree';
import type { SessionTreeNode } from '../../src/shared/api-types';

function node(partial: Partial<SessionTreeNode> & { id: string }): SessionTreeNode {
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

describe('session-tree helpers', () => {
  const items: SessionTreeNode[] = [
    node({ id: 'r', parentSessionId: null, kind: 'FREE', runtimeStatus: 'TURN_RUNNING' }),
    node({ id: 's1', parentSessionId: 'r', kind: 'STATE', taskId: 't1', stateCode: 'init' }),
    node({ id: 's2', parentSessionId: 'r', kind: 'STATE', taskId: 't2', stateCode: 'wait' }),
    node({ id: 'g1', parentSessionId: 's1', kind: 'STATE', taskId: 't3', stateCode: 'x' }),
  ];

  it('rootsOf returns nodes without a parent', () => {
    expect(rootsOf(items).map((n) => n.id)).toEqual(['r']);
  });

  it('childrenOf filters by parent id', () => {
    expect(childrenOf(items, 'r').map((n) => n.id).sort()).toEqual(['s1', 's2']);
    expect(childrenOf(items, 's1').map((n) => n.id)).toEqual(['g1']);
    expect(childrenOf(items, 'nope')).toEqual([]);
  });

  it('flattenForRender walks root → deep children in order', () => {
    expect(flattenForRender(items).map((n) => n.id)).toEqual(['r', 's1', 'g1', 's2']);
  });

  it('buildPathIndex maps each id to its ancestor chain + self', () => {
    const idx = buildPathIndex(items);
    expect(idx.get('r')).toEqual(['r']);
    expect(idx.get('s1')).toEqual(['r', 's1']);
    expect(idx.get('g1')).toEqual(['r', 's1', 'g1']);
    expect(idx.get('s2')).toEqual(['r', 's2']);
  });

  it('isStateSubnode requires STATE kind + taskId', () => {
    expect(isStateSubnode(node({ id: 'a', kind: 'STATE', taskId: 't' }))).toBe(true);
    expect(isStateSubnode(node({ id: 'b', kind: 'FREE', taskId: 't' }))).toBe(false);
    expect(isStateSubnode(node({ id: 'c', kind: 'STATE' }))).toBe(false);
  });
});
