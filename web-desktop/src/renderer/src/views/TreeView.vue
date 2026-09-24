<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import SessionTree from '../components/SessionTree.vue';
import TaskPanel from '../components/TaskPanel.vue';
import { useSessionTree, describeChain } from '../composables/useSessionTree';
import { useTask } from '../composables/useTask';
import type { SessionTreeNode } from '@shared/api-types';

const route = useRoute();
const router = useRouter();
const tree = useSessionTree();

const rootId = computed(() => {
  const q = route.query['root'];
  return typeof q === 'string' ? q : null;
});
const selectedNode = ref<SessionTreeNode | null>(null);
const taskPanel = useTask();
const selectedTaskId = computed(() => selectedNode.value?.taskId ?? null);

watch(
  rootId,
  async (id) => {
    selectedNode.value = null;
    await tree.setRoot(id);
  },
  { immediate: true },
);

watch(
  () => tree.items.value,
  (items) => {
    if (!selectedNode.value && items.length > 0) {
      const root = items.find((n) => n.parentSessionId === null);
      if (root) selectedNode.value = root;
    }
  },
);

watch(selectedTaskId, async (id) => {
  if (id) await taskPanel.setTask(id);
  else await taskPanel.setTask(null);
});

function selectNodeById(id: string): void {
  const node = tree.items.value.find((n) => n.id === id);
  selectedNode.value = node ?? null;
}

async function openInChat(node: SessionTreeNode): Promise<void> {
  await router.push({ path: '/chat', query: { sessionId: node.id } });
}

async function addComment(body: string): Promise<void> {
  if (!taskPanel.task.value) return;
  try {
    await window.harness.task.comments.add(taskPanel.task.value.id, { body });
  } catch (err) {
    taskPanel.error.value = err instanceof Error ? err.message : String(err);
  }
}

const chainLabel = computed(() => {
  const n = selectedNode.value;
  if (!n) return '';
  const ancestors: string[] = [];
  let cursor: string | null = n.parentSessionId;
  while (cursor) {
    ancestors.unshift(cursor);
    const parent = tree.items.value.find((x) => x.id === cursor);
    cursor = parent?.parentSessionId ?? null;
  }
  return describeChain([...ancestors, n.id], tree.items.value);
});
</script>

<template>
  <section class="tree-view">
    <header>
      <h2>Дерево</h2>
      <p
        v-if="rootId"
        class="root-label"
      >
        Корень: {{ rootId.slice(0, 8) }}
      </p>
    </header>
    <div class="layout">
      <SessionTree
        :items="tree.items.value"
        :active-id="selectedNode ? selectedNode.id : null"
        :loading="tree.loading.value"
        :error="tree.error.value"
        @select="selectNodeById"
        @refresh="tree.refresh"
      />
      <div class="detail">
        <header
          v-if="selectedNode"
          class="detail-head"
        >
          <h3>{{ chainLabel || selectedNode.id.slice(0, 8) }}</h3>
          <button
            type="button"
            class="open-chat"
            @click="openInChat(selectedNode)"
          >
            Открыть чат
          </button>
        </header>
        <p
          v-if="rootId && taskPanel.task.value === null && !taskPanel.loading.value"
          class="hint"
        >
          Выберите узел STATE-сессии — справа появится панель задачи.
        </p>
        <TaskPanel
          v-if="selectedTaskId"
          :title="taskPanel.task.value?.title ?? ''"
          :status="taskPanel.statusProjection.value"
          :current-state="taskPanel.task.value?.currentState ?? ''"
          :history="taskPanel.history.value"
          :comments="taskPanel.comments.value"
          :error="taskPanel.error.value"
          :loading="taskPanel.loading.value"
          :can-comment="true"
          @add-comment="addComment"
          @refresh="taskPanel.refresh"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.tree-view {
  display: flex;
  flex-direction: column;
  height: 100%;
}
header {
  padding: 4px 8px;
  border-bottom: 1px solid #333;
}
header h2 {
  margin: 0;
  font-size: 14px;
}
.root-label {
  margin: 4px 0 0;
  color: #888;
  font-size: 11px;
}
.layout {
  display: grid;
  grid-template-columns: minmax(220px, 1fr) minmax(0, 2fr);
  gap: 8px;
  flex: 1;
  overflow: hidden;
  padding: 8px;
}
.detail {
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow: hidden;
}
.detail-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.detail-head h3 {
  margin: 0;
  font-size: 13px;
}
.open-chat {
  background: #3a76f0;
  color: #fff;
  border: 0;
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
}
.hint {
  color: #888;
  font-size: 12px;
}
</style>
