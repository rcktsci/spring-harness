<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import SessionList from '../components/SessionList.vue';
import ChatFeed from '../components/ChatFeed.vue';
import { useSessions } from '../composables/useSessions';
import { useChat } from '../composables/useChat';
import { useRelay } from '../composables/useRelay';
import { relayLabel as relayLabelOf } from '../lib/relay-label';
import { isAgentWorking } from '../lib/feed';

const route = useRoute();
const router = useRouter();
const {
  sessions,
  agents,
  search,
  loading: listLoading,
  error: listError,
  hasMore,
  refresh,
  loadMore,
  create,
} = useSessions();

const chat = useChat();
const relay = useRelay();

const draft = ref('');
const drafts = ref<Record<string, string>>({});
const stopConfirm = ref(false);

const activeId = computed(() => chat.activeId.value);
const activeSession = computed(() => chat.activeSession.value);

const working = computed(() => isAgentWorking(chat.runtimeStatus.value));
const isFree = computed(() => activeSession.value?.kind !== 'STATE');
const showCompact = computed(() => isFree.value);

const relayLabel = computed(() =>
  relayLabelOf(relay.status.value, activeSession.value?.kind),
);

const relayConnected = computed(() => Boolean(relay.status.value?.registered));

watch(activeId, (id, prev) => {
  if (prev) drafts.value[prev] = draft.value;
  draft.value = (id && drafts.value[id]) || '';
});

/**
 * Opening a FREE root session triggers the relay auto-connect+register
 * (desktop-relay-client 3.2); ensureConnected dedupes repeats. STATE
 * sessions never register.
 */
watch(activeSession, (session) => {
  const id = session?.id;
  if (!id || session?.kind === 'STATE') return;
  void relay.ensureConnected(id, session.kind);
});

/**
 * Open a session pinned via `?sessionId=…` (e.g. arrived from the SessionTree
 * drill-down). One-shot: clears the query so browser back/forward does not
 * silently re-open a stale id.
 */
function routeSessionId(): string | null {
  const q = route.query['sessionId'];
  return typeof q === 'string' && q ? q : null;
}

async function openRouteSession(): Promise<void> {
  const id = routeSessionId();
  if (!id) return;
  if (id === chat.activeId.value) return;
  try {
    await chat.openSession(id);
  } finally {
    // Drop the query key whether open succeeded or not — keep the URL stable
    // for refresh but stop router-driven re-entry.
    const q = { ...route.query };
    delete q['sessionId'];
    router.replace({ path: route.path, query: q }).catch(() => undefined);
  }
}

onMounted(() => {
  void openRouteSession();
});

watch(
  () => route.query['sessionId'],
  (_next, _prev) => {
    void openRouteSession();
  },
);

async function onSelect(id: string): Promise<void> {
  await chat.openSession(id);
}

async function onCreate(agentKey: string, title: string): Promise<void> {
  const session = await create(agentKey, title || undefined);
  await chat.openSession(session.id);
}

async function onSend(): Promise<void> {
  const text = draft.value.trim();
  if (!text || !activeId.value) return;
  const id = activeId.value;
  try {
    await chat.send(text);
    drafts.value[id] = '';
    draft.value = '';
  } catch {
    // error surfaced via chat.error
  }
}

async function onCompact(): Promise<void> {
  try {
    await chat.compact();
  } catch {
    /* surfaced */
  }
}

async function onStop(): Promise<void> {
  if (working.value && !stopConfirm.value) {
    stopConfirm.value = true;
    return;
  }
  stopConfirm.value = false;
  try {
    await chat.stop();
  } catch {
    /* surfaced */
  }
}

function cancelStopConfirm(): void {
  stopConfirm.value = false;
}

async function toggleRelay(): Promise<void> {
  const id = activeId.value;
  if (!id || activeSession.value?.kind === 'STATE') return;
  if (relayConnected.value) {
    await relay.disconnect();
  } else {
    await relay.ensureConnected(id, activeSession.value?.kind ?? 'FREE');
  }
}

function onRefreshSessions(): void {
  void refresh();
}

async function onOpenArtifact(path: string): Promise<void> {
  const sessionId = activeId.value;
  if (!sessionId) return;
  await router.push({
    path: '/artifacts',
    query: { sessionId, path },
  });
}
</script>

<template>
  <section class="chat-view">
    <SessionList
      :sessions="sessions"
      :agents="agents"
      :active-id="activeId"
      :search="search"
      :loading="listLoading"
      :error="listError"
      :has-more="hasMore"
      @update:search="search = $event"
      @select="onSelect"
      @refresh="onRefreshSessions"
      @load-more="loadMore"
      @create="onCreate"
    />

    <div class="chat-main">
      <div
        v-if="!activeId"
        class="empty-state"
      >
        Выберите сессию слева или создайте новую.
      </div>

      <template v-else>
        <header class="status-bar">
          <div class="session-info">
            <span class="title">{{ activeSession?.title || activeId.slice(0, 8) }}</span>
            <span
              v-if="chat.runtimeStatus.value"
              :class="['rt-badge', `rt-${chat.runtimeStatus.value}`]"
            >{{ chat.runtimeStatus.value }}</span>
          </div>

          <span
            v-if="working"
            class="working"
            data-testid="working"
          >агент работает…</span>

          <div class="commands">
            <button
              v-if="showCompact"
              type="button"
              class="cmd"
              title="Compact"
              @click="onCompact"
            >
              Compact
            </button>
            <button
              v-if="!isFree"
              type="button"
              class="cmd cmd-stop"
              disabled
              title="Stop недоступен для STATE"
            >
              Stop
            </button>
            <template v-else>
              <span
                v-if="stopConfirm"
                class="stop-confirm"
              >
                остановить Turn?
                <button
                  type="button"
                  class="cmd cmd-stop"
                  @click="onStop"
                >
                  Да
                </button>
                <button
                  type="button"
                  class="cmd"
                  @click="cancelStopConfirm"
                >
                  Нет
                </button>
              </span>
              <button
                v-else
                type="button"
                class="cmd cmd-stop"
                data-testid="stop"
                @click="onStop"
              >
                Stop
              </button>
            </template>
          </div>

          <div class="relay">
            <span
              class="relay-status"
              data-testid="relay-status"
              :class="{ on: relayConnected }"
            >{{ relayLabel }}</span>
            <button
              type="button"
              class="cmd"
              data-testid="relay-toggle"
              :disabled="activeSession?.kind === 'STATE'"
              @click="toggleRelay"
            >
              {{ relayConnected ? 'Отключить' : 'Подключить' }}
            </button>
          </div>
        </header>

        <p
          v-if="chat.error.value"
          class="chat-error"
        >
          {{ chat.error.value }}
        </p>

        <ChatFeed
          :entries="chat.entries.value"
          @open-artifact="onOpenArtifact"
        />

        <form
          class="composer"
          @submit.prevent="onSend"
        >
          <input
            v-model="draft"
            data-testid="composer-input"
            placeholder="Сообщение…"
            autocomplete="off"
          />
          <button
            type="submit"
            data-testid="composer-send"
          >
            Отправить
          </button>
        </form>
      </template>
    </div>
  </section>
</template>

<style scoped>
.chat-view {
  display: flex;
  height: 100%;
  min-height: 0;
}
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  gap: 8px;
}
.empty-state {
  color: #888;
  font-size: 13px;
  padding: 24px;
}
.status-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 8px;
  border-bottom: 1px solid #333;
  background: #222;
  flex-wrap: wrap;
}
.session-info {
  display: flex;
  gap: 8px;
  align-items: center;
}
.title {
  font-size: 13px;
  font-weight: 600;
}
.rt-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 8px;
  background: #333;
  color: #aaa;
}
.rt-TURN_RUNNING {
  background: #1f6e3a;
  color: #cfc;
}
.rt-PARKED_ASYNC {
  background: #6e5a1f;
  color: #ffc;
}
.working {
  color: #8cf;
  font-size: 12px;
  animation: pulse 1.2s ease-in-out infinite;
}
@keyframes pulse {
  50% {
    opacity: 0.5;
  }
}
.commands {
  display: flex;
  gap: 6px;
  align-items: center;
}
.cmd {
  padding: 4px 10px;
  background: #444;
  color: #ddd;
  border: 0;
  border-radius: 4px;
  font-size: 12px;
  cursor: pointer;
}
.cmd:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.cmd-stop {
  background: #7a2a2a;
}
.stop-confirm {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  color: #fc6;
}
.relay {
  margin-left: auto;
  display: flex;
  gap: 8px;
  align-items: center;
}
.relay-status {
  font-size: 11px;
  color: #999;
}
.relay-status.on {
  color: #8c8;
}
.chat-error {
  margin: 0;
  padding: 4px 8px;
  color: #f66;
  font-size: 12px;
  background: #3a1a1a;
}
.composer {
  display: flex;
  gap: 8px;
}
.composer input {
  flex: 1;
  padding: 8px;
  background: #1a1a1a;
  border: 1px solid #333;
  color: #fff;
  border-radius: 4px;
}
.composer button {
  padding: 8px 16px;
  background: #3a76f0;
  color: #fff;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
}
</style>
