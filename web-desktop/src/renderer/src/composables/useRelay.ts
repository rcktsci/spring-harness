import { ref, type Ref } from 'vue';
import type {
  RegistrationConsentRequest,
  RelayStatus,
  ToolCallView,
} from '@shared/ipc-contract';

export interface PendingToolConfirm {
  call: ToolCallView;
  basePath: string;
}

export interface UseRelay {
  status: Ref<RelayStatus | null>;
  /** First-registration consent prompt (null when none is pending). */
  consent: Ref<RegistrationConsentRequest | null>;
  /** Tool-call confirmation (D-93) when confirmCommands is 'always'. */
  toolConfirm: Ref<PendingToolConfirm | null>;
  connect: () => Promise<RelayStatus | null>;
  register: (sessionId: string, kind?: string, basePath?: string) => Promise<unknown>;
  setSession: (sessionId: string, kind?: string) => Promise<unknown>;
  disconnect: () => Promise<void>;
  /**
   * Auto-connect for an opened session (spec 3.2): no-op for STATE sessions,
   * for a session already registered, and for a duplicate trigger while a
   * previous registration for the same session is still in flight.
   */
  ensureConnected: (sessionId: string, kind?: string) => Promise<void>;
  respondConsent: (approved: boolean) => Promise<void>;
  respondToolConfirm: (approved: boolean) => Promise<void>;
  cancelTool: (callId: string) => Promise<void>;
}

/**
 * Renderer-side relay state. Module-scoped singleton: relay events come from
 * the one main process and must be visible from every view (global dialogs in
 * App.vue, status bar in ChatView), so per-component subscriptions would race
 * and lose events.
 */
const status = ref<RelayStatus | null>(null);
const consent = ref<RegistrationConsentRequest | null>(null);
const toolConfirm = ref<PendingToolConfirm | null>(null);
let wired = false;
const autoInFlight = new Set<string>();

function wire(): void {
  if (wired) return;
  wired = true;
  window.harness.relay.onStatus((s) => { status.value = s; });
  window.harness.relay.onRegistrationConsent((req) => { consent.value = req; });
  window.harness.tool.onCall((call, basePath) => {
    toolConfirm.value = { call, basePath };
  });
  void window.harness.relay.status().then((s) => {
    if (s) status.value = s;
  });
}

export function useRelay(): UseRelay {
  wire();

  return {
    status,
    consent,
    toolConfirm,
    connect: () => window.harness.relay.connect(),
    register: (sessionId, kind = 'FREE', basePath) =>
      window.harness.relay.register(sessionId, kind, basePath),
    setSession: (sessionId, kind = 'FREE') =>
      window.harness.relay.setSession(sessionId, kind),
    disconnect: () => window.harness.relay.disconnect(),
    ensureConnected: async (sessionId, kind = 'FREE') => {
      if (kind === 'STATE') return;
      const s = status.value;
      if (s?.registered && s.sessionId === sessionId) return;
      if (autoInFlight.has(sessionId)) return;
      autoInFlight.add(sessionId);
      try {
        await window.harness.relay.connect();
        await window.harness.relay.register(sessionId, kind);
      } catch {
        // Failures surface through relay status events / the status bar.
      } finally {
        autoInFlight.delete(sessionId);
      }
    },
    respondConsent: async (approved) => {
      const req = consent.value;
      if (!req) return;
      consent.value = null;
      await window.harness.relay.confirmRegistration(req.sessionId, approved);
    },
    respondToolConfirm: async (approved) => {
      const pending = toolConfirm.value;
      if (!pending) return;
      toolConfirm.value = null;
      await window.harness.tool.respondConfirm(pending.call.callId, approved);
    },
    cancelTool: (callId) => window.harness.tool.cancel(callId),
  };
}
