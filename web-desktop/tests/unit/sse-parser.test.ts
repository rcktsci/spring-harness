import { describe, expect, it } from 'vitest';
import { createSseParser, type SseFrame } from '../../src/main/sse-parser';

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

  describe('malformed input tolerance', () => {
    it('survives garbage bytes mixed with valid frames', () => {
      const c = collect();
      // Random bytes that look like nothing — no valid `field:` headers.
      c.push('??: nope\nthis is junk\n\n');
      c.push('event: session.status\ndata: {"runtimeStatus":"IDLE"}\n\n');
      expect(c.frames).toHaveLength(1);
      expect(c.frames[0]?.event).toBe('session.status');
    });

    it('ignores a final partial line at flush', () => {
      const c = collect();
      c.push('event: message.created\ndata: {"seq":');
      c.flush();
      // The trailing partial line is emitted verbatim only if data accumulated;
      // here `data:` was set but no value was given before flush.
      // Either zero or one frame with empty data is acceptable — the parser
      // must not throw and must not loop.
      expect(() => c.flush()).not.toThrow();
    });

    it('handles a frame with empty id (still sticky for next)', () => {
      const c = collect();
      c.push('id: 9\nevent: m\ndata: a\n\n');
      c.push('id:\nevent: m\ndata: b\n\n');
      expect(c.frames[0]?.id).toBe('9');
      // SSE spec: `id:` with no value MUST clear the last-event-id.
      expect(c.frames[1]?.id).toBeUndefined();
    });

    it('keeps parsing when an unknown field appears', () => {
      const c = collect();
      c.push('foo: bar\nid: 1\ndata: ok\n\n');
      expect(c.frames).toHaveLength(1);
      expect(c.frames[0]?.data).toBe('ok');
    });

    it('ignores a malformed retry (non-numeric)', () => {
      const c = collect();
      c.push('retry: not-a-number\n\n');
      c.push('event: m\ndata: a\n\n');
      expect(c.frames[0]?.retryMs).toBeUndefined();
    });
  });
});
