import { computed, onUnmounted, ref, type Ref } from 'vue';
import type { MessageDto, SessionDto, SessionStatusEvent } from '@shared/api-types';
import { buildFeed, type FeedEntry } from '../lib/feed';

export interface UseChat {
  activeId: Ref<string | null>;
  activeSession: Ref<SessionDto | null>;
  messages: Ref<MessageDto[]>;
  entries: Ref<FeedEntry[]>;
  runtimeStatus: Ref<string | undefined>;
  lastTurnOutcome: Ref<string | undefined>;
  sseConnected: Ref<boolean>;
  error: Ref<string | null>;
  openSession: (id: string) => Promise<void>;
  closeSession: () => Promise<void>;
  send: (text: string) => Promise<void>;
  compact: () => Promise<void>;
  stop: () => Promise<void>;
}

/**
 * Chat feed for one active session: REST history walk (since=0 → tail) then
 * live SSE append; Compact/Stop go through main's REST client (D-91).
 */
export function useChat(): UseChat {
  const activeId = ref<string | null>(null);
  const activeSession = ref<SessionDto | null>(null);
  const messages = ref<MessageDto[]>([]);
  const runtimeStatus = ref<string | undefined>(undefined);
  const lastTurnOutcome = ref<string | undefined>(undefined);
  const sseConnected = ref(false);
  const error = ref<string | null>(null);
  let sseUnsub: (() => void) | null = null;
  let openToken = 0;

  const entries = computed(() => buildFeed(messages.value));

  function sortFeed(list: MessageDto[]): MessageDto[] {
    return [...list].sort((a, b) => a.seq - b.seq);
  }

  function upsertMessage(m: MessageDto): void {
    const idx = messages.value.findIndex((x) => x.id === m.id);
    if (idx >= 0) {
      const next = [...messages.value];
      next[idx] = m;
      messages.value = next;
      return;
    }
    if (messages.value.some((x) => x.seq === m.seq)) {
      // Same seq, different id — prefer the newer (late result) payload.
      const next = messages.value.map((x) => (x.seq === m.seq ? m : x));
      messages.value = sortFeed(next);
      return;
    }
    messages.value = sortFeed([...messages.value, m]);
  }

  async function loadHistory(sessionId: string, token: number): Promise<number> {
    const cfg = await window.harness.config.get();
    let since = 0;
    let pages = 0;
    let tail = 0;
    for (;;) {
      if (token !== openToken) return tail;
      const page = await window.harness.session.messages(sessionId, {
        since,
        limit: cfg.chatPageLimit,
      });
      if (token !== openToken) return tail;
      for (const m of page.items) {
        upsertMessage(m);
        if (m.seq > tail) tail = m.seq;
      }
      pages += 1;
      if (page.nextCursor === undefined || page.nextCursor === null) break;
      if (page.nextCursor <= since) break;
      since = page.nextCursor;
      if (pages >= cfg.chatHistoryMaxPages) break;
    }
    return tail;
  }

  function handleSseFrame(frame: { sessionId: string; id?: string; event: string; data: string }): void {
    if (frame.sessionId !== activeId.value) return;
    sseConnected.value = true;
    if (frame.event === 'message.created') {
      try {
        const m = JSON.parse(frame.data) as MessageDto;
        upsertMessage(m);
      } catch {
        /* malformed frame — ignore */
      }
      return;
    }
    if (frame.event === 'session.status') {
      try {
        const st = JSON.parse(frame.data) as SessionStatusEvent;
        runtimeStatus.value = st.runtimeStatus;
        lastTurnOutcome.value = st.lastTurnOutcome;
      } catch {
        /* ignore */
      }
    }
  }

  async function openSession(id: string): Promise<void> {
    const token = ++openToken;
    // Teardown previous subscription before starting a new one.
    sseUnsub?.();
    sseUnsub = null;
    sseConnected.value = false;
    if (activeId.value === id) {
      // Re-open (refresh) — clear and reload.
    }
    activeId.value = id;
    messages.value = [];
    error.value = null;
    runtimeStatus.value = undefined;
    lastTurnOutcome.value = undefined;
    activeSession.value = null;

    try {
      const session = await window.harness.session.get(id);
      if (token !== openToken) return;
      activeSession.value = session;
      runtimeStatus.value = session.runtimeStatus;
      lastTurnOutcome.value = session.lastTurnOutcome;
      const tail = await loadHistory(id, token);
      if (token !== openToken) return;
      sseUnsub = window.harness.sse.subscribe(id, tail, handleSseFrame);
      sseConnected.value = true;
    } catch (err) {
      if (token !== openToken) return;
      error.value = err instanceof Error ? err.message : String(err);
    }
  }

  async function closeSession(): Promise<void> {
    openToken += 1;
    sseUnsub?.();
    sseUnsub = null;
    sseConnected.value = false;
    activeId.value = null;
    activeSession.value = null;
    messages.value = [];
  }

  async function send(text: string): Promise<void> {
    const id = activeId.value;
    if (!id) return;
    error.value = null;
    try {
      await window.harness.session.send(id, text);
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err);
      throw err;
    }
  }

  async function compact(): Promise<void> {
    const id = activeId.value;
    if (!id) return;
    error.value = null;
    try {
      await window.harness.session.compact(id);
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err);
      throw err;
    }
  }

  async function stop(): Promise<void> {
    const id = activeId.value;
    if (!id) return;
    error.value = null;
    try {
      await window.harness.session.stop(id);
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err);
      throw err;
    }
  }

  onUnmounted(() => {
    openToken += 1;
    sseUnsub?.();
    sseUnsub = null;
  });

  return {
    activeId,
    activeSession,
    messages,
    entries,
    runtimeStatus,
    lastTurnOutcome,
    sseConnected,
    error,
    openSession,
    closeSession,
    send,
    compact,
    stop,
  };
}
