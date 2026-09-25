<script setup lang="ts">
import { computed, ref } from 'vue';
import type { SessionDto } from '@shared/api-types';

defineProps<{
  sessions: SessionDto[];
  agents: Array<{ key: string; name: string }>;
  agentsError: string | null;
  activeId: string | null;
  search: string;
  loading: boolean;
  error: string | null;
  hasMore: boolean;
}>();

const emit = defineEmits<{
  'update:search': [value: string];
  select: [id: string];
  refresh: [];
  'load-more': [];
  create: [agentKey: string, title: string];
}>();

const showCreate = ref(false);
const newTitle = ref('');
const newAgentKey = ref('');
const createError = ref<string | null>(null);

const statusLabel = computed(() => {
  return (s: SessionDto): string => {
    switch (s.runtimeStatus) {
      case 'TURN_RUNNING':
        return 'думает';
      case 'PARKED_ASYNC':
        return 'ждёт инструмент';
      case 'PARKED_CLIENT':
        // D-84: reserved — never assigned in M5; surface raw if it ever appears.
        return s.runtimeStatus;
      default:
        return 'idle';
    }
  };
});

function statusClass(s: SessionDto): string {
  switch (s.runtimeStatus) {
    case 'TURN_RUNNING':
      return 'st-running';
    case 'PARKED_ASYNC':
      return 'st-parked';
    default:
      return 'st-idle';
  }
}

function onSearch(e: Event): void {
  emit('update:search', (e.target as HTMLInputElement).value);
}

function submitCreate(): void {
  createError.value = null;
  if (!newAgentKey.value) {
    createError.value = 'Выберите агента';
    return;
  }
  emit('create', newAgentKey.value, newTitle.value);
  showCreate.value = false;
  newTitle.value = '';
}
</script>

<template>
  <aside class="session-list">
    <header class="list-header">
      <input
        class="search"
        type="search"
        placeholder="Поиск…"
        :value="search"
        @input="onSearch"
      />
      <button
        type="button"
        class="new-btn"
        title="Новая сессия"
        @click="showCreate = !showCreate"
      >
        +
      </button>
    </header>

    <form
      v-if="showCreate"
      class="create-form"
      @submit.prevent="submitCreate"
    >
      <input
        v-model="newTitle"
        placeholder="Заголовок (опц.)"
      />
      <select
        v-model="newAgentKey"
        required
      >
        <option
          value=""
          disabled
        >
          Агент…
        </option>
        <option
          v-for="a in agents"
          :key="a.key"
          :value="a.key"
        >
          {{ a.name }}
        </option>
      </select>
      <p
        v-if="createError"
        class="error"
      >
        {{ createError }}
      </p>
      <button type="submit">
        Создать
      </button>
    </form>

    <p
      v-if="error"
      class="error"
    >
      {{ error }}
    </p>

    <p
      v-if="agentsError"
      class="error"
      data-testid="agents-error"
    >
      {{ agentsError }}
    </p>

    <ul class="sessions">
      <li
        v-for="s in sessions"
        :key="s.id"
        :class="['session-item', { active: s.id === activeId }]"
        @click="emit('select', s.id)"
      >
        <span class="title">{{ s.title || s.id.slice(0, 8) }}</span>
        <span class="meta">
          <span class="agent">{{ s.agent.key }}:r{{ s.agent.rev }}</span>
          <span :class="['badge', statusClass(s)]">{{ statusLabel(s) }}</span>
          <time :datetime="s.lastActivityAt">{{ new Date(s.lastActivityAt).toLocaleTimeString() }}</time>
        </span>
      </li>
      <li
        v-if="sessions.length === 0 && !loading"
        class="empty"
      >
        Нет сессий
      </li>
    </ul>

    <button
      v-if="hasMore"
      type="button"
      class="more"
      :disabled="loading"
      @click="emit('load-more')"
    >
      {{ loading ? '…' : 'Ещё' }}
    </button>
    <button
      type="button"
      class="refresh"
      @click="emit('refresh')"
    >
      ↻
    </button>
  </aside>
</template>

<style scoped>
.session-list {
  display: flex;
  flex-direction: column;
  width: 260px;
  min-width: 220px;
  border-right: 1px solid #333;
  background: #252525;
  height: 100%;
  overflow: hidden;
}
.list-header {
  display: flex;
  gap: 6px;
  padding: 8px;
  border-bottom: 1px solid #333;
}
.search {
  flex: 1;
  padding: 6px 8px;
  background: #1a1a1a;
  border: 1px solid #333;
  color: #fff;
  border-radius: 4px;
}
.new-btn,
.refresh,
.more {
  padding: 6px 10px;
  background: #3a76f0;
  color: #fff;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
}
.refresh {
  margin: 8px;
  background: #444;
}
.create-form {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px;
  border-bottom: 1px solid #333;
}
.create-form input,
.create-form select {
  padding: 6px;
  background: #1a1a1a;
  border: 1px solid #333;
  color: #fff;
  border-radius: 4px;
}
.sessions {
  list-style: none;
  margin: 0;
  padding: 4px;
  flex: 1;
  overflow-y: auto;
}
.session-item {
  padding: 8px;
  border-radius: 4px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.session-item:hover {
  background: #2e2e2e;
}
.session-item.active {
  background: #1f3a6e;
}
.title {
  font-size: 13px;
  color: #eee;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meta {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 11px;
  color: #999;
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
.error {
  color: #f66;
  font-size: 12px;
  padding: 0 8px;
  margin: 4px 0;
}
.empty {
  color: #777;
  font-size: 12px;
  padding: 12px;
  text-align: center;
}
.more {
  margin: 0 8px 8px;
  background: #444;
}
</style>
