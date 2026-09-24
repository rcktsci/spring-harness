<script setup lang="ts">
import type { SessionRuntimeStatus, SessionTreeNode } from '@shared/api-types';
import { computed } from 'vue';
import { childrenOf as filterChildren, rootsOf as filterRoots } from '../lib/session-tree';

const props = defineProps<{
  items: SessionTreeNode[];
  activeId: string | null;
  error: string | null;
  loading: boolean;
}>();

const emit = defineEmits<{
  select: [id: string];
  refresh: [];
}>();

const roots = computed(() => filterRoots(props.items));

function childrenOf(node: SessionTreeNode): SessionTreeNode[] {
  return filterChildren(props.items, node.id);
}

function statusClass(node: SessionTreeNode): string {
  const s: SessionRuntimeStatus = node.runtimeStatus;
  switch (s) {
    case 'TURN_RUNNING':
      return 'st-running';
    case 'PARKED_ASYNC':
      return 'st-parked';
    default:
      return 'st-idle';
  }
}

function statusText(node: SessionTreeNode): string {
  switch (node.runtimeStatus) {
    case 'TURN_RUNNING':
      return 'думает';
    case 'PARKED_ASYNC':
      return 'ждёт инструмент';
    case 'PARKED_CLIENT':
      // D-84 reserved — never assigned in M5.
      return node.runtimeStatus;
    default:
      return 'idle';
  }
}

function taskLabel(node: SessionTreeNode): string {
  if (node.kind === 'STATE' && node.taskId && node.stateCode) {
    return `${node.taskId.slice(0, 8)} · ${node.stateCode}`;
  }
  return '';
}
</script>

<template>
  <section class="tree-view">
    <header class="tree-head">
      <h3>Дерево</h3>
      <button
        type="button"
        class="refresh"
        @click="emit('refresh')"
      >
        ↻
      </button>
    </header>
    <p
      v-if="error"
      class="error"
    >
      {{ error }}
    </p>
    <p
      v-if="loading && items.length === 0"
      class="hint"
    >
      Загрузка…
    </p>
    <p
      v-else-if="roots.length === 0"
      class="hint"
    >
      Выберите активную сессию, чтобы увидеть дерево.
    </p>
    <ol
      v-else
      class="tree"
      data-testid="tree"
    >
      <li
        v-for="root in roots"
        :key="root.id"
        class="root"
      >
        <button
          type="button"
          :class="['node', 'kind-' + root.kind, statusClass(root), { active: root.id === activeId }]"
          :data-session-id="root.id"
          @click="emit('select', root.id)"
        >
          <span class="kind">{{ root.kind }}</span>
          <span class="agent">{{ root.agent.key }}:r{{ root.agent.rev }}</span>
          <span :class="['badge', statusClass(root)]">{{ statusText(root) }}</span>
          <span
            v-if="taskLabel(root)"
            class="state-code"
          >{{ taskLabel(root) }}</span>
        </button>
        <ol
          v-if="childrenOf(root).length > 0"
          class="children"
        >
          <li
            v-for="child in childrenOf(root)"
            :key="child.id"
          >
            <button
              type="button"
              :class="['node', 'kind-' + child.kind, statusClass(child), { active: child.id === activeId }]"
              :data-session-id="child.id"
              @click="emit('select', child.id)"
            >
              <span class="kind">{{ child.kind }}</span>
              <span class="agent">{{ child.agent.key }}:r{{ child.agent.rev }}</span>
              <span :class="['badge', statusClass(child)]">{{ statusText(child) }}</span>
              <span
                v-if="taskLabel(child)"
                class="state-code"
              >{{ taskLabel(child) }}</span>
            </button>
          </li>
        </ol>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.tree-view {
  display: flex;
  flex-direction: column;
  gap: 6px;
  height: 100%;
  overflow: hidden;
}
.tree-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px;
  border-bottom: 1px solid #333;
}
.tree-head h3 {
  font-size: 12px;
  margin: 0;
  text-transform: uppercase;
  color: #888;
}
.refresh {
  background: #333;
  color: #fff;
  border: 0;
  border-radius: 4px;
  padding: 2px 8px;
  cursor: pointer;
}
.tree {
  list-style: none;
  margin: 0;
  padding: 4px;
  overflow-y: auto;
  flex: 1;
}
.children {
  list-style: none;
  margin: 4px 0 0 18px;
  padding: 0 0 0 8px;
  border-left: 1px dashed #444;
}
.node {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  width: 100%;
  padding: 6px 8px;
  border-radius: 4px;
  background: #1e1e1e;
  border: 1px solid #333;
  color: #ccc;
  text-align: left;
  cursor: pointer;
  margin-bottom: 4px;
  font-size: 12px;
}
.node.kind-STATE {
  background: #1a2a1a;
}
.node.active {
  outline: 2px solid #3a76f0;
}
.node .kind {
  font-size: 10px;
  color: #888;
}
.badge {
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 10px;
}
.st-idle {
  background: #333;
  color: #aaa;
}
.st-running {
  background: #1f6e3a;
  color: #cfc;
}
.st-parked {
  background: #6e5a1f;
  color: #ffc;
}
.state-code {
  font-family: ui-monospace, monospace;
  color: #8cf;
}
.error {
  color: #f66;
  font-size: 12px;
  padding: 0 8px;
}
.hint {
  color: #888;
  font-size: 12px;
  padding: 4px 8px;
}
</style>
