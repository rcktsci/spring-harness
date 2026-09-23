<script setup lang="ts">
import { ref } from 'vue';

const draft = ref('');
const messages = ref<Array<{ id: string; kind: string; body: string }>>([]);

async function send(): Promise<void> {
  const body = draft.value.trim();
  if (!body) return;
  messages.value.push({ id: crypto.randomUUID(), kind: 'USER', body });
  draft.value = '';
}
</script>

<template>
  <section class="chat-view">
    <h2>Chat</h2>
    <p class="hint">
      Bundle D will wire SSE, send/compact/stop, and message rendering.
    </p>
    <div class="messages">
      <div
        v-for="m in messages"
        :key="m.id"
        :class="['message', `kind-${m.kind.toLowerCase()}`]"
      >
        <span class="kind">{{ m.kind }}</span>
        <span class="body">{{ m.body }}</span>
      </div>
    </div>
    <form
      class="composer"
      @submit.prevent="send"
    >
      <input
        v-model="draft"
        placeholder="Type a message…"
      />
      <button type="submit">
        Send
      </button>
    </form>
  </section>
</template>

<style scoped>
.chat-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}
.hint {
  color: #888;
  font-size: 12px;
}
.messages {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.message {
  padding: 6px 10px;
  border-radius: 4px;
  background: #2a2a2a;
}
.message .kind {
  font-size: 11px;
  color: #888;
  margin-right: 8px;
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
  color: white;
  border: 0;
  border-radius: 4px;
}
</style>
