<script setup lang="ts">
import { useRoute } from 'vue-router';
import { computed } from 'vue';
import RelayDialogs from './components/RelayDialogs.vue';

const route = useRoute();
const viewName = computed(() => String(route.name ?? 'unknown'));
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <h1>Spring Harness Web Desktop</h1>
      <nav>
        <RouterLink to="/chat">
          Chat
        </RouterLink>
        <RouterLink to="/tree">
          Tree
        </RouterLink>
        <RouterLink to="/artifacts">
          Artifacts
        </RouterLink>
        <RouterLink to="/settings">
          Settings
        </RouterLink>
      </nav>
    </header>
    <main class="app-main">
      <RouterView v-slot="{ Component }">
        <component
          :is="Component"
          :key="viewName"
        />
      </RouterView>
    </main>
    <RelayDialogs />
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg, #1e1e1e);
  color: var(--fg, #e6e6e6);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}
.app-header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 8px 16px;
  border-bottom: 1px solid #333;
}
.app-header h1 {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}
.app-header nav {
  display: flex;
  gap: 12px;
}
.app-header nav a {
  color: #aaa;
  text-decoration: none;
  font-size: 13px;
}
.app-header nav a.router-link-active {
  color: #fff;
}
.app-main {
  flex: 1;
  overflow: auto;
  padding: 16px;
}
</style>
