import type { Pinia } from 'pinia';
import type { Router } from 'vue-router';
import { useAuthStore } from './stores/auth';

/**
 * Where a navigation must land given the login state.
 *
 * Not signed in → everything funnels to /login (today's behaviour: the app
 * parks there even when a valid token exists, which makes a restart look like
 * a lost session). Signed in → /login bounces to /chat.
 */
export function entryRoute(toName: string | null | undefined, loggedIn: boolean): string | undefined {
  if (loggedIn) {
    return !toName || toName === 'login' ? 'chat' : undefined;
  }
  return toName === 'login' ? undefined : 'login';
}

export function installAuthGuard(router: Router, pinia: Pinia): void {
  const auth = useAuthStore(pinia);
  let bootstrapped: Promise<void> | null = null;
  router.beforeEach(async (to) => {
    bootstrapped ??= auth.refresh().then(() => undefined);
    await bootstrapped;
    const name = typeof to.name === 'string' ? to.name : undefined;
    return entryRoute(name, auth.state.loggedIn);
  });

  window.harness.auth.onSessionLost(() => {
    void handleSessionLoss(router, auth);
  });
}

async function handleSessionLoss(router: Router, auth: ReturnType<typeof useAuthStore>): Promise<void> {
  await auth.refresh();
  if (auth.state.loggedIn) {
    return;
  }
  if (router.currentRoute.value.path !== '/login') {
    await router.push('/login');
  }
}
