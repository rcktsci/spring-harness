import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('electron', () => ({
  app: { getPath: () => '/tmp/harness-test', getVersion: () => '0.0.0' },
  safeStorage: { isEncryptionAvailable: () => false },
}));

vi.mock('../../src/main/logger', () => ({
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() },
}));

import { TaskSseClient, type SseFrame } from '../../src/main/task-sse';
import { DEFAULT_CONFIG, type ServerConfig } from '../../src/shared/ipc-contract';

function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const c of chunks) controller.enqueue(encoder.encode(c));
      controller.close();
    },
  });
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

describe('TaskSseClient', () => {
  const cfg: ServerConfig = { ...DEFAULT_CONFIG, serverBaseUrl: 'http://server.test' };
  let received: Array<{ taskId: string; frame: SseFrame }>;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    received = [];
    fetchMock = vi.fn();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function makeClient(): TaskSseClient {
    return new TaskSseClient(
      cfg,
      (taskId, frame) => received.push({ taskId, frame }),
      async () => 'test-token',
      fetchMock as unknown as typeof fetch,
    );
  }

  it('opens with ?since=0 and forwards snapshot/status/transition/subtask.terminal/comment', async () => {
    fetchMock.mockResolvedValueOnce(
      sseResponse([
        'retry: 5000\n\n',
        'event: task.status\ndata: {"taskId":"t1","currentState":"init","statusProjection":"RUNNING","suspended":false,"taskEventSeq":7}\n\n',
        'id: 8\nevent: task.transition\ndata: {"id":"tr1","taskId":"t1","fromState":"init","toState":"build","kind":"agent","reason":{},"createdAt":"2026-09-23T00:00:00Z"}\n\n',
        'event: subtask.terminal\ndata: {"taskId":"t1","terminalTaskId":"t1-sub","terminalStatus":"SUCCEEDED"}\n\n',
        'event: task.comment\ndata: {"id":"c1","taskId":"t1","body":"hello","author":"me","createdAt":"2026-09-23T00:00:00Z"}\n\n',
        ': ping\n\n',
      ]),
    );
    const client = makeClient();
    await client.subscribe('t1');
    await vi.waitFor(() => expect(received.length).toBeGreaterThanOrEqual(4));

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/tasks/t1/events?since=0');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer test-token');

    expect(received.map((r) => r.frame.event)).toEqual([
      'task.status',
      'task.transition',
      'subtask.terminal',
      'task.comment',
    ]);
    // lastEventId was set to 8 — sticky for future reconnect frames.
    expect(received[1]?.frame.id).toBe('8');
    // ping comment must not surface
    expect(received.some((r) => r.frame.event === 'ping')).toBe(false);
    await client.unsubscribe();
  });

  it('uses explicit sinceSeq on the first connect', async () => {
    fetchMock.mockResolvedValueOnce(sseResponse(['event: task.status\ndata: {"statusProjection":"RUNNING"}\n\n']));
    const client = makeClient();
    await client.subscribe('t1', 42);
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('since=42');
    await client.unsubscribe();
  });

  it('reconnects with Last-Event-ID after stream end', async () => {
    vi.useFakeTimers();
    fetchMock
      .mockResolvedValueOnce(sseResponse(['id: 5\nevent: task.transition\ndata: {"id":"tr5"}\n\n']))
      .mockResolvedValueOnce(sseResponse(['event: task.status\ndata: {"statusProjection":"RUNNING"}\n\n']));

    const client = makeClient();
    await client.subscribe('t1');
    await vi.advanceTimersByTimeAsync(0);
    await vi.waitFor(() => expect(received.length).toBeGreaterThanOrEqual(1));
    expect(received[0]?.frame.id).toBe('5');

    await vi.advanceTimersByTimeAsync(5_000);
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    const [url2, init2] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(url2).not.toContain('since=');
    expect((init2.headers as Record<string, string>)['Last-Event-ID']).toBe('5');
    await client.unsubscribe();
  });

  it('unsubscribe stops reconnects (task switch)', async () => {
    const client = makeClient();
    fetchMock.mockReturnValueOnce(new Promise<Response>(() => undefined));
    void client.subscribe('t1');
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await client.unsubscribe();
    await Promise.resolve();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
