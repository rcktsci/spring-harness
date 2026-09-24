import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionSseClient, type SseFrame } from '../../src/main/sse';
import { DEFAULT_CONFIG, type ServerConfig } from '../../src/shared/ipc-contract';

vi.mock('../../src/main/logger', () => ({
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() },
}));

function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) {
        controller.enqueue(encoder.encode(chunk));
      }
      controller.close();
    },
  });
  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

describe('SessionSseClient', () => {
  const cfg: ServerConfig = { ...DEFAULT_CONFIG, serverBaseUrl: 'http://server.test' };
  let received: Array<{ sessionId: string; frame: SseFrame }>;
  let fetchMock: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    received = [];
    fetchMock = vi.fn();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function makeClient(): SessionSseClient {
    return new SessionSseClient(
      cfg,
      (sessionId, frame) => received.push({ sessionId, frame }),
      async () => 'test-token',
      fetchMock as unknown as typeof fetch,
    );
  }

  it('opens with ?since=0 on first connect and forwards frames', async () => {
    fetchMock.mockResolvedValueOnce(
      sseResponse([
        'retry: 5000\n\n',
        'event: session.status\ndata: {"runtimeStatus":"IDLE"}\n\n',
        'id: 7\nevent: message.created\ndata: {"seq":7,"kind":"USER"}\n\n',
        ': ping\n\n',
      ]),
    );
    const client = makeClient();
    await client.subscribe('sess-1');
    // allow read loop to finish + not reconnect immediately (retry 5000)
    await vi.waitFor(() => expect(received.length).toBeGreaterThanOrEqual(2));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain('/api/v1/sessions/sess-1/events?since=0');
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer test-token');
    expect(received[0]?.sessionId).toBe('sess-1');
    expect(received[0]?.frame.event).toBe('session.status');
    expect(received[1]?.frame.event).toBe('message.created');
    expect(received[1]?.frame.id).toBe('7');
    // ping comment must not surface
    expect(received.some((r) => r.frame.event === 'ping')).toBe(false);
    await client.unsubscribe();
  });

  it('passes sinceSeq from history tail on first connect', async () => {
    fetchMock.mockResolvedValueOnce(sseResponse(['event: session.status\ndata: {}\n\n']));
    const client = makeClient();
    await client.subscribe('sess-1', 42);
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toContain('since=42');
    await client.unsubscribe();
  });

  it('reconnects with Last-Event-ID after stream end', async () => {
    vi.useFakeTimers();
    fetchMock
      .mockResolvedValueOnce(
        sseResponse(['id: 5\nevent: message.created\ndata: {"seq":5}\n\n']),
      )
      .mockResolvedValueOnce(sseResponse(['event: session.status\ndata: {}\n\n']));

    const client = makeClient();
    await client.subscribe('sess-1');
    // first stream: open + read to completion
    await vi.advanceTimersByTimeAsync(0);
    await vi.waitFor(() => expect(received.length).toBe(1));
    expect(received[0]?.frame.id).toBe('5');

    // reconnect after retry:5000 (captured from stream) — advance timers
    await vi.advanceTimersByTimeAsync(5000);
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));

    const [url2, init2] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(url2).not.toContain('since=');
    expect((init2.headers as Record<string, string>)['Last-Event-ID']).toBe('5');
    await client.unsubscribe();
  });

  it('unsubscribe stops the stream (session switch)', async () => {
    const client = makeClient();
    const never = new Promise<Response>(() => {
      /* hanging stream */
    });
    fetchMock.mockReturnValueOnce(never);
    void client.subscribe('sess-1');
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await client.unsubscribe();
    // no further connects
    await Promise.resolve();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  describe('HTTP error handling', () => {
    it('401 on connect: warns, retries after retryMs, gives up only on unsubscribe', async () => {
      vi.useFakeTimers();
      fetchMock
        .mockResolvedValueOnce(new Response('{"code":"unauthenticated"}', { status: 401 }))
        .mockResolvedValueOnce(new Response('', { status: 401 }));

      const client = makeClient();
      await client.subscribe('sess-1');
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

      // Default fallback retry: cfg.sseRetryDefaultMs = 5_000.
      await vi.advanceTimersByTimeAsync(5_000);
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
      // Stop retrying on unsubscribe.
      await client.unsubscribe();
      await vi.advanceTimersByTimeAsync(60_000);
      expect(fetchMock).toHaveBeenCalledTimes(2);
    });

    it('500 on connect: same retry path as 401', async () => {
      vi.useFakeTimers();
      fetchMock.mockResolvedValue(new Response('', { status: 500 }));
      const client = makeClient();
      await client.subscribe('sess-1');
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
      await vi.advanceTimersByTimeAsync(5_000);
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
      await client.unsubscribe();
    });

    it('204 No Content on connect: treats as failure (no body) and reconnects', async () => {
      vi.useFakeTimers();
      fetchMock
        .mockResolvedValueOnce(new Response(null, { status: 204 }))
        .mockResolvedValueOnce(new Response(null, { status: 204 }));
      const client = makeClient();
      await client.subscribe('sess-1');
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
      await vi.advanceTimersByTimeAsync(5_000);
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
      await client.unsubscribe();
    });
  });
});
