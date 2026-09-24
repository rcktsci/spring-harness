<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { isRelativeArtifactPath } from '../lib/path-utils';

const route = useRoute();
const path = ref(typeof route.query['path'] === 'string' ? route.query['path'] : '');
const sessionId = ref(typeof route.query['sessionId'] === 'string' ? route.query['sessionId'] : '');
const lastResult = ref<{ kind: 'open' | 'download'; localPath: string; basename: string; bytes?: number } | null>(null);
const error = ref<string | null>(null);
const busy = ref(false);

const validation = computed<{ ok: boolean; reason?: string }>(() => {
  if (!sessionId.value) return { ok: false, reason: 'выберите активную сессию (например, клик по пути в чате)' };
  if (!path.value) return { ok: false, reason: 'введите путь' };
  if (!isRelativeArtifactPath(path.value)) {
    return { ok: false, reason: 'путь должен быть относительным внутри workspace (без абсолютных, без ..)' };
  }
  return { ok: true };
});

async function openInOs(): Promise<void> {
  error.value = null;
  if (!validation.value.ok) {
    error.value = validation.value.reason ?? null;
    return;
  }
  busy.value = true;
  try {
    const result = await window.harness.artifact.open({ sessionId: sessionId.value, path: path.value });
    lastResult.value = { kind: 'open', localPath: result.localPath, basename: result.basename, bytes: result.bytes };
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    busy.value = false;
  }
}

async function saveAs(): Promise<void> {
  error.value = null;
  if (!validation.value.ok) {
    error.value = validation.value.reason ?? null;
    return;
  }
  busy.value = true;
  try {
    const result = await window.harness.artifact.download({ sessionId: sessionId.value, path: path.value });
    lastResult.value = { kind: 'download', localPath: result.localPath, basename: result.basename, bytes: result.bytes };
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <section class="artifacts-view">
    <h2>Артефакты</h2>
    <p class="hint">
      Скачивание файлов серверного workspace сессии (api-contracts §8,
      canonical-path-гвард D-72). Кликабельный путь из чата заполнит форму.
    </p>
    <form
      class="row"
      @submit.prevent="saveAs"
    >
      <label>
        <span>Session</span>
        <input
          v-model="sessionId"
          placeholder="session uuid"
          data-testid="artifact-session"
        />
      </label>
      <label class="grow">
        <span>Path</span>
        <input
          v-model="path"
          placeholder="relative/path/under/workspace"
          data-testid="artifact-path"
        />
      </label>
      <button
        type="button"
        class="cmd"
        :disabled="busy"
        data-testid="artifact-open"
        @click="openInOs"
      >
        Открыть
      </button>
      <button
        type="submit"
        class="cmd"
        :disabled="busy"
        data-testid="artifact-save"
      >
        Save as
      </button>
    </form>
    <p
      v-if="error"
      class="error"
      data-testid="artifact-error"
    >
      {{ error }}
    </p>
    <p
      v-if="lastResult"
      class="result"
      data-testid="artifact-result"
    >
      {{ lastResult.kind === 'open' ? 'Открыт' : 'Сохранён' }}:
      <code>{{ lastResult.localPath }}</code>
      <span
        v-if="lastResult.bytes !== undefined"
        class="meta"
      > ({{ lastResult.bytes }} bytes)</span>
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
  align-items: flex-end;
  flex-wrap: wrap;
}
.row label {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11px;
  color: #888;
}
.row label.grow {
  flex: 1;
  min-width: 220px;
}
.row input {
  padding: 6px 8px;
  background: #1a1a1a;
  border: 1px solid #333;
  color: #fff;
  border-radius: 4px;
  min-width: 180px;
  font-size: 12px;
}
.cmd {
  padding: 6px 14px;
  background: #3a76f0;
  color: #fff;
  border: 0;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}
.cmd:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.error {
  color: #f5554d;
  font-size: 12px;
}
.result {
  color: #6ad36a;
  font-size: 12px;
}
.result code {
  font-family: ui-monospace, monospace;
}
.meta {
  color: #888;
}
</style>
