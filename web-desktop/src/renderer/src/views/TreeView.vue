<script setup lang="ts">
import { ref } from 'vue';

interface TreeNode {
  id: string;
  taskId: string;
  stateCode: string;
  label: string;
  children: TreeNode[];
}

const tree = ref<TreeNode | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    tree.value = {
      id: 'root',
      taskId: '-',
      stateCode: 'INIT',
      label: 'No active session',
      children: [],
    };
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <section class="tree-view">
    <h2>Tree</h2>
    <p class="hint">
      Bundle E will render the active session's sub-session/task tree.
    </p>
    <button @click="load">
      Load tree
    </button>
    <pre
      v-if="tree"
      class="tree"
    >{{ JSON.stringify(tree, null, 2) }}</pre>
    <p
      v-if="error"
      class="error"
    >
      {{ error }}
    </p>
  </section>
</template>

<style scoped>
.tree-view {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.hint {
  color: #888;
  font-size: 12px;
}
.tree {
  background: #111;
  color: #ddd;
  padding: 12px;
  border-radius: 4px;
  font-size: 12px;
  overflow: auto;
}
.error {
  color: #f5554d;
}
button {
  align-self: flex-start;
  padding: 6px 12px;
  background: #2a2a2a;
  color: #ddd;
  border: 1px solid #444;
  border-radius: 4px;
  cursor: pointer;
}
</style>
