import { onMounted, onUnmounted, ref, type Ref } from 'vue';
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
  respondConsent: (approved: boolean) => Promise<void>;
  respondToolConfirm: (approved: boolean) => Promise<void>;
  cancelTool: (callId: string) => Promise<void>;
}

/**
 * Renderer-side relay state: subscribes to main's status/consent/tool
 * events and exposes the IPC actions the UI needs (spec 3.1/3.4).
 */
export function useRelay(): UseRelay {
  const status = ref<RelayStatus | null>(null);
  const consent = ref<RegistrationConsentRequest | null>(null);
  const toolConfirm = ref<PendingToolConfirm | null>(null);
  const unsubs: Array<() => void> = [];

  onMounted(() => {
    unsubs.push(window.harness.relay.onStatus((s) => { status.value = s; }));
    unsubs.push(window.harness.relay.onRegistrationConsent((req) => { consent.value = req; }));
    unsubs.push(window.harness.tool.onCall((call, basePath) => {
      toolConfirm.value = { call, basePath };
    }));
    void window.harness.relay.status().then((s) => {
      if (s) status.value = s;
    });
  });

  onUnmounted(() => {
    for (const unsub of unsubs) unsub();
    unsubs.length = 0;
  });

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
