<script setup lang="ts">
import type { CommentDto, TransitionDto } from '@shared/api-types';
import { ref } from 'vue';

defineProps<{
  title: string;
  status: string | null;
  currentState: string;
  history: TransitionDto[];
  comments: CommentDto[];
  error: string | null;
  loading: boolean;
  canComment: boolean;
}>();

const emit = defineEmits<{
  'add-comment': [body: string];
  refresh: [];
}>();

const draft = ref('');
const posting = ref(false);

async function submitComment(): Promise<void> {
  const text = draft.value.trim();
  if (!text) return;
  posting.value = true;
  try {
    emit('add-comment', text);
    draft.value = '';
  } finally {
    posting.value = false;
  }
}
</script>

<template>
  <section class="task-panel">
    <header class="tp-head">
      <div>
        <h3>{{ title || 'Задача' }}</h3>
        <p class="meta">
          <span
            v-if="status"
            :class="['badge', 'st-' + status]"
          >{{ status }}</span>
          <span class="state">{{ currentState }}</span>
        </p>
      </div>
      <button
        type="button"
        class="refresh"
        title="Обновить"
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
      v-if="loading && history.length === 0"
      class="hint"
    >
      Загрузка…
    </p>
    <div class="cols">
      <div class="col">
        <h4>История</h4>
        <ol
          v-if="history.length > 0"
          class="history"
          data-testid="history"
        >
          <li
            v-for="(t, idx) in history"
            :key="t.id ?? `${t.createdAt}-${idx}`"
          >
            <time :datetime="t.createdAt">{{ new Date(t.createdAt).toLocaleString() }}</time>
            <span class="from">{{ t.fromState }}</span>
            <span class="arrow">→</span>
            <span class="to">{{ t.toState }}</span>
            <span class="kind">{{ t.kind }}</span>
          </li>
        </ol>
        <p
          v-else
          class="empty"
        >
          Переходов пока нет.
        </p>
      </div>
      <div class="col">
        <h4>Комментарии</h4>
        <ol
          v-if="comments.length > 0"
          class="comments"
          data-testid="comments"
        >
          <li
            v-for="c in comments"
            :key="c.id"
          >
            <p class="body">
              {{ c.body }}
            </p>
            <p class="meta">
              <span v-if="c.author">{{ c.author }}</span>
              <span v-else>агент</span>
              <time :datetime="c.createdAt">{{ new Date(c.createdAt).toLocaleString() }}</time>
            </p>
          </li>
        </ol>
        <p
          v-else
          class="empty"
        >
          Комментариев пока нет.
        </p>
        <form
          v-if="canComment"
          class="compose"
          @submit.prevent="submitComment"
        >
          <textarea
            v-model="draft"
            data-testid="comment-input"
            placeholder="Комментарий…"
            rows="2"
          />
          <button
            type="submit"
            :disabled="posting || !draft.trim()"
            data-testid="comment-add"
          >
            Отправить
          </button>
        </form>
      </div>
    </div>
  </section>
</template>

<style scoped>
.task-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
  overflow: hidden;
}
.tp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px;
  border-bottom: 1px solid #333;
}
.tp-head h3 {
  font-size: 14px;
  margin: 0;
}
.tp-head .meta {
  display: flex;
  gap: 6px;
  align-items: center;
  margin: 4px 0 0;
  font-size: 11px;
  color: #888;
}
.badge {
  padding: 1px 6px;
  border-radius: 8px;
  font-size: 10px;
  background: #333;
  color: #aaa;
}
.badge.st-RUNNING {
  background: #1f6e3a;
  color: #cfc;
}
.badge.st-WAITING {
  background: #6e5a1f;
  color: #ffc;
}
.badge.st-SUCCEEDED {
  background: #1f6e3a;
  color: #cfc;
}
.badge.st-FAILED,
.badge.st-CANCELLED {
  background: #7a2a2a;
  color: #fcc;
}
.state {
  font-family: ui-monospace, monospace;
  color: #9cf;
}
.cols {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  overflow: hidden;
  flex: 1;
}
.col {
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow: hidden;
}
.col h4 {
  font-size: 12px;
  margin: 0;
  text-transform: uppercase;
  color: #888;
}
.history,
.comments {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  flex: 1;
}
.history li {
  padding: 4px 6px;
  border-bottom: 1px solid #282828;
  font-size: 12px;
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}
.history .from {
  font-family: ui-monospace, monospace;
  color: #aaa;
}
.history .to {
  font-family: ui-monospace, monospace;
  color: #8cf;
}
.history .arrow {
  color: #555;
}
.history .kind {
  font-size: 10px;
  color: #888;
}
.comments li {
  padding: 4px 6px;
  border-bottom: 1px solid #282828;
  font-size: 12px;
}
.comments .body {
  margin: 0;
  color: #ddd;
  white-space: pre-wrap;
  word-break: break-word;
}
.comments .meta {
  margin: 4px 0 0;
  font-size: 10px;
  color: #888;
  display: flex;
  gap: 8px;
}
.compose {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}
.compose textarea {
  background: #1a1a1a;
  border: 1px solid #333;
  color: #fff;
  border-radius: 4px;
  padding: 6px;
  resize: vertical;
  font-size: 12px;
}
.compose button {
  align-self: flex-end;
  padding: 4px 10px;
  background: #3a76f0;
  color: #fff;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}
.compose button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.error {
  color: #f66;
  font-size: 12px;
  padding: 0 8px;
}
.empty,
.hint {
  color: #888;
  font-size: 12px;
  padding: 4px 8px;
}
.refresh {
  background: #333;
  color: #fff;
  border: 0;
  border-radius: 4px;
  padding: 2px 8px;
  cursor: pointer;
}
</style>
