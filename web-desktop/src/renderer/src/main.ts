import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { router } from './router';
import { installAuthGuard } from './auth-gate';

const app = createApp(App);
const pinia = createPinia();
app.use(pinia);
installAuthGuard(router, pinia);
app.use(router);
app.mount('#app');
