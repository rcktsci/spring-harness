<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRelay } from '../composables/useRelay';

const relay = useRelay();
const delivering = ref<'consent' | 'tool' | null>(null);

async function answerConsent(approved: boolean): Promise<void> {
  if (delivering.value !== null) return;
  delivering.value = 'consent';
  try {
    await relay.respondConsent(approved);
  } finally {
    if (delivering.value === 'consent') delivering.value = null;
  }
}

async function answerToolConfirm(approved: boolean): Promise<void> {
  if (delivering.value !== null) return;
  delivering.value = 'tool';
  try {
    await relay.respondToolConfirm(approved);
  } finally {
    if (delivering.value === 'tool') delivering.value = null;
  }
}

function argsJson(args: Record<string, unknown>): string {
  try {
    return JSON.stringify(args, null, 2);
  } catch {
    return String(args);
  }
}

// A consent request can arrive before this component mounts (startup
// auto-register of the saved session) — re-fetch it once so the user
// never faces a silently hanging registration.
onMounted(() => {
  void window.harness.relay.pendingConsent().then((req) => {
    if (req && !relay.consent.value) relay.consent.value = req;
  });
});
</script>

<template>
  <div
    v-if="relay.consent.value"
    class="overlay"
    data-testid="consent-dialog"
  >
    <div class="dialog">
      <h3>Разрешить локальное исполнение?</h3>
      <p>
        Оркестратор просит разрешение выполнять команды на этом компьютере
        для сессии <code>{{ relay.consent.value.sessionId }}</code>.
      </p>
      <p class="path">
        Каталог: <code>{{ relay.consent.value.basePath }}</code>
      </p>
      <p class="tools">
        Инструменты: {{ relay.consent.value.tools.join(', ') }}
      </p>
      <div class="actions">
        <button
          type="button"
          class="approve"
          :disabled="delivering !== null"
          data-testid="consent-approve"
          @click="answerConsent(true)"
        >
          Разрешить
        </button>
        <button
          type="button"
          class="deny"
          :disabled="delivering !== null"
          data-testid="consent-deny"
          @click="answerConsent(false)"
        >
          Отклонить
        </button>
      </div>
    </div>
  </div>

  <div
    v-if="relay.toolConfirm.value"
    class="overlay"
    data-testid="tool-confirm-dialog"
  >
    <div class="dialog">
      <h3>Подтвердите команду</h3>
      <p>
        Инструмент <code>{{ relay.toolConfirm.value.call.tool }}</code>
        в каталоге <code>{{ relay.toolConfirm.value.basePath }}</code>:
      </p>
      <pre class="args">{{ argsJson(relay.toolConfirm.value.call.args) }}</pre>
      <div class="actions">
        <button
          type="button"
          class="approve"
          :disabled="delivering !== null"
          data-testid="tool-confirm-approve"
          @click="answerToolConfirm(true)"
        >
          Разрешить
        </button>
        <button
          type="button"
          class="deny"
          :disabled="delivering !== null"
          data-testid="tool-confirm-deny"
          @click="answerToolConfirm(false)"
        >
          Отклонить
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.dialog {
  background: #2a2a2a;
  border: 1px solid #444;
  border-radius: 8px;
  padding: 20px;
  max-width: 560px;
  width: 90%;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.5);
}
.dialog h3 {
  margin: 0 0 12px;
  font-size: 15px;
}
.dialog p {
  margin: 0 0 8px;
  font-size: 13px;
}
.path,
.tools {
  color: #bbb;
  word-break: break-all;
}
.args {
  background: #111;
  padding: 8px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}
.actions {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  margin-top: 16px;
}
.actions button {
  padding: 8px 16px;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}
.approve {
  background: #2c7a3f;
  color: #fff;
}
.deny {
  background: #7a2a2a;
  color: #fff;
}
</style>
