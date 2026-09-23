import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router';
import LoginView from './views/LoginView.vue';
import ChatView from './views/ChatView.vue';
import TreeView from './views/TreeView.vue';
import ArtifactsView from './views/ArtifactsView.vue';
import SettingsView from './views/SettingsView.vue';

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/login' },
  { path: '/login', name: 'login', component: LoginView },
  { path: '/chat', name: 'chat', component: ChatView },
  { path: '/tree', name: 'tree', component: TreeView },
  { path: '/artifacts', name: 'artifacts', component: ArtifactsView },
  { path: '/settings', name: 'settings', component: SettingsView },
];

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
});
