import type { MessageDto, MessageKind } from '@shared/api-types';

export type ToolStatus = 'OK' | 'ERROR' | 'ASYNC_ACCEPTED' | 'CANCELLED' | 'LOST';

export type ToolBlock = {
  callId: string;
  tool: string;
  args: Record<string, unknown>;
  result?: MessageDto;
  /** Waiting for a final result (no result yet, or only ASYNC_ACCEPTED). */
  pending: boolean;
  late: boolean;
};

export type FeedEntry =
  | { type: 'message'; message: MessageDto }
  | { type: 'tool'; block: ToolBlock };

type ToolCallPayload = {
  callId?: string;
  tool?: string;
  arguments?: Record<string, unknown>;
};

type ToolResultPayload = {
  callId?: string;
  tool?: string;
  status?: string;
  output?: string;
  exitCode?: number;
  truncated?: boolean;
  timedOut?: boolean;
  late?: boolean;
};

export function toolCallPayload(m: MessageDto): ToolCallPayload {
  return m.payload as ToolCallPayload;
}

export function toolResultPayload(m: MessageDto): ToolResultPayload {
  return m.payload as ToolResultPayload;
}

export function textPayload(m: MessageDto): string {
  const p = m.payload as { text?: string; summary?: string };
  if (typeof p.text === 'string') return p.text;
  if (typeof p.summary === 'string') return p.summary;
  return '';
}

const FINAL_STATUSES = new Set(['OK', 'ERROR', 'CANCELLED', 'LOST']);

function isFinalResult(m: MessageDto | undefined): boolean {
  if (!m) return false;
  const status = toolResultPayload(m).status;
  return status !== undefined && FINAL_STATUSES.has(status);
}

/**
 * Groups TOOL_CALL / TOOL_RESULT / ASYNC_ACCEPTED by callId into collapsible
 * tool blocks and leaves the other kinds as plain entries, preserving order.
 *
 * A block is `pending` while its result is missing or only ASYNC_ACCEPTED;
 * a late result (`late=true`) replaces the pending state with the final output.
 */
export function buildFeed(messages: MessageDto[]): FeedEntry[] {
  const entries: FeedEntry[] = [];
  const blocksByCallId = new Map<string, ToolBlock>();

  const ensureBlock = (callId: string, tool: string, args: Record<string, unknown>): ToolBlock => {
    let block = blocksByCallId.get(callId);
    if (!block) {
      block = { callId, tool, args, pending: true, late: false };
      blocksByCallId.set(callId, block);
      entries.push({ type: 'tool', block });
    }
    return block;
  };

  for (const m of messages) {
    const kind: MessageKind = m.kind;
    if (kind === 'TOOL_CALL') {
      const p = toolCallPayload(m);
      const callId = m.callId ?? p.callId ?? m.id;
      const block = ensureBlock(callId, p.tool ?? 'tool', p.arguments ?? {});
      block.args = p.arguments ?? block.args;
      block.tool = p.tool ?? block.tool;
      continue;
    }
    if (kind === 'ASYNC_ACCEPTED') {
      const p = toolResultPayload(m) as ToolResultPayload & { tool?: string };
      const callId = m.callId ?? p.callId ?? '';
      if (callId) {
        const block = ensureBlock(callId, p.tool ?? 'tool', {});
        block.pending = true;
        block.late = block.late || m.late === true;
      }
      continue;
    }
    if (kind === 'TOOL_RESULT') {
      const p = toolResultPayload(m);
      const callId = m.callId ?? p.callId ?? '';
      if (callId) {
        const block = ensureBlock(callId, p.tool ?? 'tool', {});
        block.tool = p.tool ?? block.tool;
        block.result = m;
        block.late = m.late === true || p.late === true;
        block.pending = !isFinalResult(m);
        continue;
      }
      // Result without a matching call — show as a plain tool message.
      entries.push({ type: 'message', message: m });
      continue;
    }
    entries.push({ type: 'message', message: m });
  }

  return entries;
}

/** True when the feed should show «агент работает…» for this runtime status. */
export function isAgentWorking(runtimeStatus: string | undefined): boolean {
  return runtimeStatus === 'TURN_RUNNING' || runtimeStatus === 'PARKED_ASYNC';
}
