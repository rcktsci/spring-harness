import { describe, expect, it } from 'vitest';
import {
  RELAY_CLOSE_CODE,
  RELAY_PROTOCOL_VERSION,
  RELAY_REGISTRATION_ERROR,
  classifyCloseCode,
  parseRelayFrame,
  type ServerFrame,
} from '../../src/main/ws-frames';

describe('ws-frames: §5 parse', () => {
  it('accepts a welcome frame with a matching protocol version', () => {
    const f = parseRelayFrame({ type: 'welcome', protocol: 1 });
    expect(f).toEqual({ type: 'welcome', protocol: RELAY_PROTOCOL_VERSION });
  });

  it('rejects a welcome frame with an unsupported protocol version', () => {
    // §5.1: version outside support → 4403 protocol-mismatch; the client
    // must not proceed on a mismatched wire.
    expect(parseRelayFrame({ type: 'welcome', protocol: 2 })).toBeUndefined();
    expect(parseRelayFrame({ type: 'welcome', protocol: 0 })).toBeUndefined();
  });

  it('accepts a registered frame', () => {
    const f = parseRelayFrame({ type: 'registered', sessionId: 'sess-1' });
    expect(f?.type).toBe('registered');
    expect((f as Extract<ServerFrame, { type: 'registered' }>).sessionId).toBe('sess-1');
  });

  it('accepts an error frame carrying a registration refusal code', () => {
    for (const code of RELAY_REGISTRATION_ERROR) {
      const f = parseRelayFrame({ type: 'error', code, message: 'refused' });
      expect(f).toMatchObject({ type: 'error', code, message: 'refused' });
    }
  });

  it('accepts a tool.call frame and defaults missing args to an object', () => {
    const f = parseRelayFrame({
      type: 'tool.call',
      callId: 'c1',
      sessionId: 'sess-1',
      tool: 'bash',
      args: { command: 'ls' },
    });
    expect(f).toMatchObject({ type: 'tool.call', callId: 'c1', tool: 'bash' });
    expect((f as Extract<ServerFrame, { type: 'tool.call' }>).args).toEqual({ command: 'ls' });
  });

  it('substitutes an empty object for a non-object args payload', () => {
    const f = parseRelayFrame({
      type: 'tool.call',
      callId: 'c2',
      sessionId: 'sess-1',
      tool: 'glob',
      args: null,
    });
    expect((f as Extract<ServerFrame, { type: 'tool.call' }>).args).toEqual({});
  });

  it('ignores client-only frames the server must never send', () => {
    // progress/result/pong/hello/register are client→server per §5.1–5.3
    expect(parseRelayFrame({ type: 'tool.progress', callId: 'c1', chunk: 'partial' })).toBeUndefined();
    expect(parseRelayFrame({ type: 'tool.result', callId: 'c1', output: '', exitCode: 0 })).toBeUndefined();
    expect(parseRelayFrame({ type: 'pong' })).toBeUndefined();
  });

  it('accepts a tool.cancel frame', () => {
    const f = parseRelayFrame({ type: 'tool.cancel', callId: 'c1' });
    expect(f).toEqual({ type: 'tool.cancel', callId: 'c1' });
  });

  it('accepts a bare ping frame', () => {
    expect(parseRelayFrame({ type: 'ping' })).toEqual({ type: 'ping' });
  });

  it('rejects frames missing required fields', () => {
    expect(parseRelayFrame({ type: 'registered' })).toBeUndefined();
    expect(parseRelayFrame({ type: 'error', code: 'x' })).toBeUndefined();
    expect(parseRelayFrame({ type: 'tool.cancel' })).toBeUndefined();
    expect(parseRelayFrame({ type: 'welcome' })).toBeUndefined();
  });

  it('rejects unknown frame types', () => {
    expect(parseRelayFrame({ type: 'bogus' })).toBeUndefined();
    expect(parseRelayFrame({ type: 'hello', protocol: 1 })).toBeUndefined();
  });

  it('rejects non-object payloads', () => {
    expect(parseRelayFrame(null)).toBeUndefined();
    expect(parseRelayFrame('welcome')).toBeUndefined();
    expect(parseRelayFrame(42)).toBeUndefined();
    expect(parseRelayFrame([1, 2])).toBeUndefined();
  });
});

describe('ws-frames: §5.5 close codes', () => {
  it('classifies 4401 as a recoverable auth failure', () => {
    expect(classifyCloseCode(RELAY_CLOSE_CODE.UNAUTHENTICATED)).toEqual({
      kind: 'unauthenticated',
      recoverable: true,
    });
  });

  it('classifies 4403 as fatal protocol mismatch', () => {
    expect(classifyCloseCode(RELAY_CLOSE_CODE.PROTOCOL)).toEqual({
      kind: 'protocol',
      recoverable: false,
    });
  });

  it('classifies 4409 as a registration refusal', () => {
    expect(classifyCloseCode(RELAY_CLOSE_CODE.SESSION_REGISTRATION)).toEqual({
      kind: 'registration',
      recoverable: false,
    });
  });

  it('treats unexpected codes as recoverable and unknown', () => {
    expect(classifyCloseCode(1006)).toEqual({ kind: 'unknown', recoverable: true });
    expect(classifyCloseCode(1011)).toEqual({ kind: 'unknown', recoverable: true });
  });

  it('exposes the frozen numeric close codes', () => {
    expect(RELAY_CLOSE_CODE.UNAUTHENTICATED).toBe(4401);
    expect(RELAY_CLOSE_CODE.PROTOCOL).toBe(4403);
    expect(RELAY_CLOSE_CODE.SESSION_REGISTRATION).toBe(4409);
  });

  it('lists exactly the registration error codes from §5.2', () => {
    expect(RELAY_REGISTRATION_ERROR).toEqual([
      'session-not-found',
      'wrong-session-kind',
      'workspace-occupied',
      'duplicate-tool-name',
      'superseded',
    ]);
  });
});
