<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';

const router = useRouter();
const loading = ref(false);
const error = ref<string | null>(null);

async function login(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    await window.harness.auth.loginStart();
    await router.push('/chat');
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err);
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <section class="login-view">
    <h2>Sign in</h2>
    <p>SSO via Keycloak. Bundle B will implement the full PKCE flow.</p>
    <button
      :disabled="loading"
      @click="login"
    >
      {{ loading ? 'Opening Keycloak…' : 'Sign in' }}
    </button>
    <p
      v-if="error"
      class="error"
    >
      {{ error }}
    </p>
  </section>
</template>

<style scoped>
.login-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 360px;
  margin: 80px auto 0;
}
button {
  padding: 8px 16px;
  border: 0;
  border-radius: 4px;
  background: #3a76f0;
  color: white;
  font-size: 14px;
  cursor: pointer;
}
button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.error {
  color: #f5554d;
  font-size: 13px;
}
</style>
