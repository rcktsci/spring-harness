<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import type { ServerConfig } from '@shared/ipc-contract';
import { useAuthStore } from '../stores/auth';

const router = useRouter();
const cfg = ref<ServerConfig | null>(null);
const saved = ref(false);
const error = ref<string | null>(null);
const authBusy = ref(false);
const auth = useAuthStore();

onMounted(async () => {
  cfg.value = await window.harness.config.get();
  await auth.refresh();
});

/** Явная переавторизация: без неё истёкший токен оставляет пользователя запертым в UI. */
async function onSignIn(): Promise<void> {
  authBusy.value = true;
  error.value = null;
  try {
    await auth.login();
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    authBusy.value = false;
  }
}

async function onSignOut(): Promise<void> {
  authBusy.value = true;
  error.value = null;
  try {
    await auth.logout();
    await router.push('/login');
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    authBusy.value = false;
  }
}

async function save(): Promise<void> {
  if (!cfg.value) return;
  error.value = null;
  saved.value = false;
  try {
    cfg.value = await window.harness.config.set({ ...cfg.value });
    saved.value = true;
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  }
}

async function openLogs(): Promise<void> {
  await window.harness.config.openLogs();
}
</script>

<template>
  <section class="settings-view">
    <h2>Settings</h2>
    <p class="hint">
      Values are stored in <code>userData/config.json</code>; secrets never leave <code>main</code>.
    </p>
    <div
      v-if="cfg"
      class="form"
    >
      <div class="session">
        <span>Session</span>
        <button
          v-if="!auth.state.loggedIn"
          type="button"
          data-testid="sign-in"
          :disabled="authBusy"
          @click="onSignIn"
        >
          Sign in
        </button>
        <button
          v-else
          type="button"
          class="secondary"
          data-testid="sign-out"
          :disabled="authBusy"
          @click="onSignOut"
        >
          Sign out
        </button>
      </div>
      <label>
        <span>Server base URL</span>
        <input v-model="cfg.serverBaseUrl" />
      </label>
      <label>
        <span>Keycloak issuer</span>
        <input v-model="cfg.keycloakIssuer" />
      </label>
      <label>
        <span>Keycloak client id</span>
        <input v-model="cfg.keycloakClientId" />
      </label>
      <label>
        <span>Confirm commands</span>
        <select v-model="cfg.confirmCommands">
          <option value="always">always (default)</option>
          <option value="never">never</option>
        </select>
      </label>
      <label>
        <span>Show tray icon</span>
        <input
          v-model="cfg.showTray"
          type="checkbox"
        />
      </label>
      <label>
        <span>Log level</span>
        <select v-model="cfg.logLevel">
          <option value="error">error</option>
          <option value="warn">warn</option>
          <option value="info">info (default)</option>
          <option value="verbose">verbose</option>
          <option value="debug">debug</option>
          <option value="silly">silly</option>
        </select>
      </label>
      <div class="actions">
        <button
          type="button"
          data-testid="save"
          @click="save"
        >
          Save
        </button>
        <button
          class="secondary"
          @click="openLogs"
        >
          Open logs folder
        </button>
      </div>
      <p
        v-if="saved"
        class="ok"
      >
        Saved.
      </p>
      <p
        v-if="error"
        class="error"
      >
        {{ error }}
      </p>
    </div>
  </section>
</template>

<style scoped>
.settings-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 640px;
}
.hint {
  color: #888;
  font-size: 12px;
}
.hint code {
  background: #1a1a1a;
  padding: 1px 4px;
  border-radius: 3px;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 13px;
}
input[type='text'],
input:not([type]),
select {
  padding: 6px 8px;
  background: #1a1a1a;
  color: #fff;
  border: 1px solid #333;
  border-radius: 4px;
  font-size: 13px;
}
.actions {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}
button {
  padding: 6px 12px;
  background: #3a76f0;
  color: white;
  border: 0;
  border-radius: 4px;
  cursor: pointer;
}
button.secondary {
  background: #2a2a2a;
  color: #ddd;
}
.ok {
  color: #6ad36a;
  font-size: 12px;
}
.error {
  color: #f5554d;
  font-size: 12px;
}
</style>
