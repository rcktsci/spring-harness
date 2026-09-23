<script setup lang="ts">
import { ref } from 'vue';

const path = ref('');
const lastResult = ref<string | null>(null);
const error = ref<string | null>(null);

function isRelative(p: string): boolean {
  if (!p) return false;
  if (p.startsWith('/') || /^[a-zA-Z]:[\\/]/.test(p)) return false;
  if (p.includes('..')) return false;
  return true;
}

async function download(): Promise<void> {
  error.value = null;
  if (!isRelative(path.value)) {
    error.value = 'Path must be relative and not contain ".." segments';
    return;
  }
  try {
    const dest = await window.harness.artifact.download(path.value);
    lastResult.value = dest;
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  }
}
</script>

<template>
  <section class="artifacts-view">
    <h2>Artifacts</h2>
    <p class="hint">
      Bundle E will implement download/save-as/open-in-OS via the server workspace endpoint.
    </p>
    <form
      class="row"
      @submit.prevent="download"
    >
      <input
        v-model="path"
        placeholder="relative/path/under/workspace"
      />
      <button type="submit">
        Download
      </button>
    </form>
    <p
      v-if="lastResult"
      class="result"
    >
      Saved to: {{ lastResult }}
    </p>
    <p
      v-if="error"
      class="error"
    >
      {{ error }}
    </p>
  </section>
</template>

<style scoped>
.artifacts-view {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.hint {
  color: #888;
  font-size: 12px;
}
.row {
  display: flex;
  gap: 8px;
}
.row input {
  flex: 1;
  padding: 8px;
  background: #1a1a1a;
  border: 1px solid #333;
  color: #fff;
  border-radius: 4px;
}
.row button {
  padding: 8px 16px;
  background: #3a76f0;
  color: white;
  border: 0;
  border-radius: 4px;
}
.result {
  color: #6ad36a;
  font-size: 12px;
}
.error {
  color: #f5554d;
  font-size: 12px;
}
</style>
