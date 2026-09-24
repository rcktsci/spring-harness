/**
 * useTask — drives a single task panel: statusProjection (GET /tasks/{id})
 * + history walk (cursor envelope) → live SSE for `task.transition`,
 * `task.status`, `subtask.terminal`, `task.comment`.
 *
 * Comments are managed separately (`useTaskComments`).
 */
import { computed, onUnmounted, ref, watch, type Ref } from 'vue';
import type {
  CommentDto,
  TaskDto,
  TaskStatusProjection,
  TransitionDto,
} from '@shared/api-types';

export interface UseTask {
  task: Ref<TaskDto | null>;
  history: Ref<TransitionDto[]>;
  comments: Ref<CommentDto[]>;
  statusProjection: Ref<TaskStatusProjection | null>;
  loading: Ref<boolean>;
  error: Ref<string | null>;
  setTask: (id: string | null) => Promise<void>;
  refresh: () => Promise<void>;
}

export function useTask(): UseTask {
  const task = ref<TaskDto | null>(null);
  const history = ref<TransitionDto[]>([]);
  const comments = ref<CommentDto[]>([]);
  const statusProjection = ref<TaskStatusProjection | null>(null);
  const loading = ref(false);
  const error = ref<string | null>(null);
  let sseUnsub: (() => void) | null = null;
  let requestId = 0;
  let pageLimit = 50;
  let maxPages = 200;

  async function loadHistory(taskId: string, token: number): Promise<void> {
    history.value = [];
    const cfg = await window.harness.config.get();
    pageLimit = cfg.taskHistoryLimit;
    maxPages = cfg.taskHistoryMaxPages;
    let since: string | undefined;
    let pages = 0;
    for (;;) {
      if (token !== requestId) return;
      const page = await window.harness.task.history(taskId, {
        since,
        limit: pageLimit,
      });
      if (token !== requestId) return;
      history.value = [...history.value, ...page.items];
      if (!page.nextCursor) break;
      since = page.nextCursor;
      pages += 1;
      if (pages >= maxPages) break;
    }
  }

  async function loadComments(taskId: string, token: number): Promise<void> {
    comments.value = [];
    let cursor: string | undefined;
    let pages = 0;
    for (;;) {
      if (token !== requestId) return;
      const page = await window.harness.task.comments.list(taskId, {
        cursor,
        limit: pageLimit,
      });
      if (token !== requestId) return;
      comments.value = [...comments.value, ...page.items];
      if (!page.nextCursor) break;
      cursor = page.nextCursor;
      pages += 1;
      if (pages >= maxPages) break;
    }
  }

  async function setTask(id: string | null): Promise<void> {
    requestId += 1;
    sseUnsub?.();
    sseUnsub = null;
    task.value = null;
    history.value = [];
    comments.value = [];
    statusProjection.value = null;
    error.value = null;
    if (!id) return;
    const token = ++requestId;
    loading.value = true;
    try {
      const dto = await window.harness.task.get(id);
      if (token !== requestId) return;
      task.value = dto;
      statusProjection.value = dto.statusProjection;
      void loadComments(id, token); // detached — updates `comments` ref
      await loadHistory(id, token);
      if (token !== requestId) return;
      sseUnsub = window.harness.task.subscribe({ taskId: id, sinceSeq: 0 }, (frame) => {
        try {
          if (frame.event === 'task.status') {
            const e = JSON.parse(frame.data) as TaskStatusProjection | { statusProjection: TaskStatusProjection; currentState?: string; suspended?: boolean };
            const next = (e as { statusProjection: TaskStatusProjection }).statusProjection ?? (e as TaskStatusProjection);
            statusProjection.value = next;
            if (task.value && (e as { currentState?: string }).currentState) {
              task.value = {
                ...task.value,
                statusProjection: next,
                currentState: (e as { currentState?: string }).currentState as string,
                suspended: (e as { suspended?: boolean }).suspended ?? task.value.suspended,
              };
            }
            return;
          }
          if (frame.event === 'task.transition') {
            const t = JSON.parse(frame.data) as TransitionDto;
            history.value = [...history.value, t];
            return;
          }
          if (frame.event === 'task.comment') {
            const c = JSON.parse(frame.data) as CommentDto;
            if (!comments.value.some((x) => x.id === c.id)) {
              comments.value = [...comments.value, c];
            }
            return;
          }
          // subtask.terminal: re-fetch statusProjection for a fresh view
          if (frame.event === 'subtask.terminal') {
            void window.harness.task.get(id).then((dto2) => {
              if (token === requestId) {
                task.value = dto2;
                statusProjection.value = dto2.statusProjection;
              }
            });
          }
        } catch {
          /* malformed frame */
        }
      });
    } catch (err) {
      if (token === requestId) error.value = err instanceof Error ? err.message : String(err);
    } finally {
      if (token === requestId) loading.value = false;
    }
  }

  async function refresh(): Promise<void> {
    if (!task.value) return;
    const id = task.value.id;
    requestId += 1;
    const token = ++requestId;
    try {
      const dto = await window.harness.task.get(id);
      if (token !== requestId) return;
      task.value = dto;
      statusProjection.value = dto.statusProjection;
    } catch (err) {
      if (token === requestId) error.value = err instanceof Error ? err.message : String(err);
    }
  }

  onUnmounted(() => {
    requestId += 1;
    sseUnsub?.();
    sseUnsub = null;
  });

  watch(task, () => {
    /* keep reactivity lively */
  });
  void computed;

  return {
    task,
    history,
    comments,
    statusProjection,
    loading,
    error,
    setTask,
    refresh,
  };
}
