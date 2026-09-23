import { describe, expect, it, vi } from 'vitest';

vi.mock('../../src/main/logger', () => ({
  log: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() },
}));

import { createSseParser, type SseFrame } from '../../src/main/sse';

function collect(): { frames: SseFrame[]; push: (s: string) => void; flush: () => void } {
  const frames: SseFrame[] = [];
  const parser = createSseParser((f) => frames.push(f));
  return { frames, push: (s) => parser.push(s), flush: () => parser.flush() };
}

describe('createSseParser', () => {
  it('parses a simple message.created frame with id', () => {
    const c = collect();
    c.push('id: 42\nevent: message.created\ndata: {"seq":42}\n\n');
    expect(c.frames).toHaveLength(1);
    expect(c.frames[0]).toMatchObject({
      id: '42',
      event: 'message.created',
      data: '{"seq":42}',
    });
  });

  it('parses the session.status snapshot (first frame)', () => {
    const c = collect();
    c.push('event: session.status\ndata: {"runtimeStatus":"IDLE"}\n\n');
    expect(c.frames).toHaveLength(1);
    expect(c.frames[0]?.event).toBe('session.status');
    expect(c.frames[0]?.data).toBe('{"runtimeStatus":"IDLE"}');
  });

  it('ignores ping comments', () => {
    const c = collect();
    c.push(': ping\n\n');
    expect(c.frames).toHaveLength(0);
  });

  it('captures retry: 5000', () => {
    const c = collect();
    c.push('retry: 5000\n\n');
    // retry alone with no event/data — no frame emitted, but retry is captured on next frame
    c.push('event: session.status\ndata: {}\n\n');
    expect(c.frames[0]?.retryMs).toBe(5000);
  });

  it('handles multi-line data', () => {
    const c = collect();
    c.push('event: message.created\ndata: line1\ndata: line2\n\n');
    expect(c.frames[0]?.data).toBe('line1\nline2');
  });

  it('handles CRLF line endings and chunk boundaries', () => {
    const c = collect();
    c.push('event: message.created\r\nda');
    c.push('ta: {"seq":1}\r\n\r\n');
    expect(c.frames).toHaveLength(1);
    expect(c.frames[0]?.event).toBe('message.created');
    expect(c.frames[0]?.data).toBe('{"seq":1}');
  });

  it('defaults event name to message', () => {
    const c = collect();
    c.push('data: hello\n\n');
    expect(c.frames[0]?.event).toBe('message');
    expect(c.frames[0]?.data).toBe('hello');
  });

  it('flush emits a trailing frame without a blank line', () => {
    const c = collect();
    c.push('event: session.status\ndata: {"runtimeStatus":"TURN_RUNNING"}');
    expect(c.frames).toHaveLength(0);
    c.flush();
    expect(c.frames).toHaveLength(1);
    expect(c.frames[0]?.data).toBe('{"runtimeStatus":"TURN_RUNNING"}');
  });

  it('keeps last event id across frames', () => {
    const c = collect();
    c.push('id: 1\nevent: message.created\ndata: a\n\n');
    c.push('event: message.created\ndata: b\n\n');
    expect(c.frames[0]?.id).toBe('1');
    // id is sticky in SSE — second frame reuses it
    expect(c.frames[1]?.id).toBe('1');
  });
});
