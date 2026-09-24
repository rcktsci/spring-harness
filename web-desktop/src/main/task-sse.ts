/**
 * Task event-stream client — thin wrapper over the shared `SseStream`.
 * Contract: api-contracts §3.2 (task.status snapshot first, task.transition,
 * subtask.terminal, task.comment; `: ping` comments).
 */
import type { ServerConfig } from '../shared/ipc-contract.js';
import { SseStream, type SseFrame } from './sse-stream.js';

export type { SseFrame };

export type TaskSseEventHandler = (taskId: string, frame: SseFrame) => void;

export class TaskSseClient {
  private stream: SseStream;

  constructor(
    cfg: ServerConfig,
    private readonly onFrame: TaskSseEventHandler,
    getAccessToken: () => Promise<string | null> = () => Promise.resolve(null),
    fetchImpl: typeof fetch = fetch,
  ) {
    const base = cfg.serverBaseUrl.replace(/\/+$/, '');
    this.stream = new SseStream(cfg, {
      label: 'task',
      urlFor: (id, _lastEventId, initialSince) => {
        let url = `${base}/api/v1/tasks/${encodeURIComponent(id)}/events`;
        if (_lastEventId === null) url += `?since=${initialSince}`;
        return url;
      },
      emit: (id, frame) => this.onFrame(id, frame),
    }, {
      getAccessToken,
      fetchImpl,
      defaultRetryMs: cfg.taskSseRetryDefaultMs,
    });
  }

  subscribe(taskId: string, sinceSeq = 0): Promise<void> {
    return this.stream.subscribe(taskId, sinceSeq);
  }

  unsubscribe(): Promise<void> {
    return this.stream.unsubscribe();
  }

  shutdown(): void {
    this.stream.shutdown();
  }
}
