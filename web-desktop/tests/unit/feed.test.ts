import { describe, expect, it } from 'vitest';
// Renderer pure helpers — imported for unit coverage; not part of tsconfig.node
// project sources, so keep this file out of strict project include (see below).
import { buildFeed, isAgentWorking, textPayload } from '../../src/renderer/src/lib/feed';
import type { MessageDto } from '../../src/shared/api-types';

function msg(partial: Partial<MessageDto> & Pick<MessageDto, 'id' | 'seq' | 'kind'>): MessageDto {
  return {
    payload: {},
    createdAt: '2026-09-23T00:00:00Z',
    ...partial,
  } as MessageDto;
}

describe('buildFeed', () => {
  it('keeps USER/ASSISTANT/SYSTEM/COMPACT as message entries', () => {
    const feed = buildFeed([
      msg({ id: '1', seq: 1, kind: 'USER', payload: { text: 'hi' }, author: 'alice' }),
      msg({ id: '2', seq: 2, kind: 'ASSISTANT', payload: { text: 'hello' } }),
      msg({ id: '3', seq: 3, kind: 'SYSTEM', payload: { text: 'boom' } }),
      msg({ id: '4', seq: 4, kind: 'COMPACT', payload: { covers: [], summary: 's' } }),
    ]);
    expect(feed).toHaveLength(4);
    expect(feed.every((e) => e.type === 'message')).toBe(true);
    expect(textPayload((feed[0] as { message: MessageDto }).message)).toBe('hi');
    expect(textPayload((feed[3] as { message: MessageDto }).message)).toBe('s');
  });

  it('groups TOOL_CALL + TOOL_RESULT into one non-pending block', () => {
    const feed = buildFeed([
      msg({
        id: 'c1',
        seq: 1,
        kind: 'TOOL_CALL',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash', arguments: { command: 'ls' } },
      }),
      msg({
        id: 'r1',
        seq: 2,
        kind: 'TOOL_RESULT',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash', status: 'OK', output: 'file.txt' },
      }),
    ]);
    expect(feed).toHaveLength(1);
    const block = (
      feed[0] as {
        type: string;
        block: {
          tool: string;
          pending: boolean;
          late: boolean;
          result: MessageDto;
        };
      }
    ).block;
    expect(block.tool).toBe('bash');
    expect(block.pending).toBe(false);
    expect(block.late).toBe(false);
    expect(block.result.payload).toMatchObject({ output: 'file.txt' });
  });

  it('marks pending when TOOL_CALL has no result yet', () => {
    const feed = buildFeed([
      msg({
        id: 'c1',
        seq: 1,
        kind: 'TOOL_CALL',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash', arguments: {} },
      }),
    ]);
    const block = (feed[0] as { block: { pending: boolean } }).block;
    expect(block.pending).toBe(true);
  });

  it('marks pending for TOOL_RESULT status=ASYNC_ACCEPTED and late replaces it', () => {
    const feed = buildFeed([
      msg({
        id: 'c1',
        seq: 1,
        kind: 'TOOL_CALL',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash', arguments: {} },
      }),
      msg({
        id: 'a1',
        seq: 2,
        kind: 'TOOL_RESULT',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash', status: 'ASYNC_ACCEPTED' },
      }),
    ]);
    expect((feed[0] as { block: { pending: boolean } }).block.pending).toBe(true);

    // late final result closes the placeholder
    const feed2 = buildFeed([
      msg({
        id: 'c1',
        seq: 1,
        kind: 'TOOL_CALL',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash', arguments: {} },
      }),
      msg({
        id: 'a1',
        seq: 2,
        kind: 'TOOL_RESULT',
        callId: 'call-1',
        late: true,
        payload: { callId: 'call-1', tool: 'bash', status: 'OK', late: true, output: 'done' },
      }),
    ]);
    const b = (feed2[0] as { block: { pending: boolean; late: boolean } }).block;
    expect(b.pending).toBe(false);
    expect(b.late).toBe(true);
  });

  it('treats ASYNC_ACCEPTED kind as a pending placeholder', () => {
    const feed = buildFeed([
      msg({
        id: 'c1',
        seq: 1,
        kind: 'TOOL_CALL',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash', arguments: {} },
      }),
      msg({
        id: 'as',
        seq: 2,
        kind: 'ASYNC_ACCEPTED',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash' },
      }),
    ]);
    expect(feed).toHaveLength(1);
    expect((feed[0] as { block: { pending: boolean } }).block.pending).toBe(true);
  });

  it('keeps ERROR results non-pending with late marker', () => {
    const feed = buildFeed([
      msg({
        id: 'c1',
        seq: 1,
        kind: 'TOOL_CALL',
        callId: 'call-1',
        payload: { callId: 'call-1', tool: 'bash', arguments: {} },
      }),
      msg({
        id: 'r1',
        seq: 2,
        kind: 'TOOL_RESULT',
        callId: 'call-1',
        late: true,
        payload: { callId: 'call-1', tool: 'bash', status: 'ERROR', output: 'fail' },
      }),
    ]);
    const b = (feed[0] as { block: { pending: boolean; late: boolean } }).block;
    expect(b.pending).toBe(false);
    expect(b.late).toBe(true);
  });
});

describe('isAgentWorking', () => {
  it('is true for TURN_RUNNING and PARKED_ASYNC', () => {
    expect(isAgentWorking('TURN_RUNNING')).toBe(true);
    expect(isAgentWorking('PARKED_ASYNC')).toBe(true);
    expect(isAgentWorking('IDLE')).toBe(false);
    // D-84: PARKED_CLIENT is reserved — not treated as working
    expect(isAgentWorking('PARKED_CLIENT')).toBe(false);
  });
});
