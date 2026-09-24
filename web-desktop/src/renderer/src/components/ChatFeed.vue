<script setup lang="ts">
import { ref } from 'vue';
import type { MessageDto } from '@shared/api-types';
import { renderMarkdown } from '../lib/markdown';
import { textPayload, toolResultPayload, type FeedEntry, type ToolBlock } from '../lib/feed';
import { DEFAULT_EXT_HINT, extractArtifactPaths } from '../lib/artifact-paths';

const props = defineProps<{
  entries: FeedEntry[];
  artifactExtensions?: string;
}>();

const emit = defineEmits<{
  'open-artifact': [path: string];
}>();

const openTools = ref<Set<string>>(new Set());

function toggleTool(callId: string): void {
  const next = new Set(openTools.value);
  if (next.has(callId)) next.delete(callId);
  else next.add(callId);
  openTools.value = next;
}

function isOpen(callId: string): boolean {
  return openTools.value.has(callId);
}

function md(text: string): string {
  return renderMarkdown(text);
}

function resultStatus(block: ToolBlock): string {
  if (block.pending || !block.result) return 'ожидает результат';
  const p = toolResultPayload(block.result);
  return p.status ?? 'OK';
}

function resultOutput(block: ToolBlock): string {
  if (!block.result) return '';
  const p = toolResultPayload(block.result);
  return p.output ?? '';
}

function resultExit(block: ToolBlock): number | undefined {
  if (!block.result) return undefined;
  return toolResultPayload(block.result).exitCode;
}

function isTruncated(block: ToolBlock): boolean {
  if (!block.result) return false;
  return toolResultPayload(block.result).truncated === true;
}

function callArgsJson(block: ToolBlock): string {
  try {
    return JSON.stringify(block.args, null, 2);
  } catch {
    return String(block.args);
  }
}

function authorOf(m: MessageDto): string {
  return m.author ?? '';
}

function extensionList(): string[] {
  const hint = (props.artifactExtensions ?? DEFAULT_EXT_HINT.join(',')).split(',').map((s) => s.trim()).filter(Boolean);
  return hint;
}

/** Splits tool output into text-and-path segments for the clickable render. */
function outputSegments(text: string): Array<{ kind: 'text'; text: string } | { kind: 'path'; text: string }> {
  if (!text) return [];
  const paths = new Set(extractArtifactPaths(text, extensionList()));
  if (paths.size === 0) {
    return [{ kind: 'text', text }];
  }
  const out: Array<{ kind: 'text'; text: string } | { kind: 'path'; text: string }> = [];
  const sorted = [...paths].sort((a, b) => b.length - a.length);
  let remaining = text;
  while (remaining.length > 0) {
    let firstIdx = -1;
    let firstPath = '';
    for (const p of sorted) {
      const idx = remaining.indexOf(p);
      if (idx >= 0 && (firstIdx === -1 || idx < firstIdx)) {
        firstIdx = idx;
        firstPath = p;
      }
    }
    if (firstIdx === -1) {
      out.push({ kind: 'text', text: remaining });
      break;
    }
    if (firstIdx > 0) {
      out.push({ kind: 'text', text: remaining.slice(0, firstIdx) });
    }
    out.push({ kind: 'path', text: firstPath });
    remaining = remaining.slice(firstIdx + firstPath.length);
  }
  return out;
}
</script>

<template>
  <!-- eslint-disable vue/no-v-html -- HTML comes from renderMarkdown() (markdown-it + DOMPurify) -->
  <div
    class="feed"
    data-testid="feed"
  >
    <template
      v-for="entry in props.entries"
      :key="entry.type === 'tool' ? entry.block.callId : entry.message.id"
    >
      <!-- USER -->
      <div
        v-if="entry.type === 'message' && entry.message.kind === 'USER'"
        class="msg user"
        data-kind="USER"
      >
        <span class="who">{{ authorOf(entry.message) }}</span>
        <div
          class="body"
          v-html="md(textPayload(entry.message))"
        />
      </div>

      <!-- ASSISTANT -->
      <div
        v-else-if="entry.type === 'message' && entry.message.kind === 'ASSISTANT'"
        class="msg assistant"
        data-kind="ASSISTANT"
      >
        <div
          class="body"
          v-html="md(textPayload(entry.message))"
        />
      </div>

      <!-- SYSTEM -->
      <div
        v-else-if="entry.type === 'message' && entry.message.kind === 'SYSTEM'"
        class="msg system"
        data-kind="SYSTEM"
      >
        <div class="body">
          {{ textPayload(entry.message) }}
        </div>
      </div>

      <!-- COMPACT -->
      <div
        v-else-if="entry.type === 'message' && entry.message.kind === 'COMPACT'"
        class="msg compact"
        data-kind="COMPACT"
      >
        <span class="label">— compact —</span>
        <div class="body">
          {{ textPayload(entry.message) }}
        </div>
      </div>

      <!-- leftover TOOL_* without grouping -->
      <div
        v-else-if="entry.type === 'message'"
        class="msg tool-raw"
        :data-kind="entry.message.kind"
      >
        <div class="body">
          <pre>{{ entry.message.kind }} {{ JSON.stringify(entry.message.payload) }}</pre>
        </div>
      </div>

      <!-- grouped tool block -->
      <div
        v-else
        class="tool-block"
        data-kind="TOOL"
        :data-call-id="entry.block.callId"
        :data-pending="entry.block.pending ? '1' : '0'"
        :data-late="entry.block.late ? '1' : '0'"
      >
        <button
          type="button"
          class="tool-toggle"
          :aria-expanded="isOpen(entry.block.callId)"
          @click="toggleTool(entry.block.callId)"
        >
          <span class="chev">{{ isOpen(entry.block.callId) ? '▾' : '▸' }}</span>
          <span class="tool-name">{{ entry.block.tool }}</span>
          <span
            v-if="entry.block.late"
            class="late-marker"
          >late</span>
          <span
            v-if="entry.block.pending"
            class="pending-marker"
          >ожидает результат</span>
          <span
            v-else
            class="status"
            :class="'st-' + resultStatus(entry.block)"
          >{{ resultStatus(entry.block) }}</span>
        </button>
        <div
          v-if="isOpen(entry.block.callId)"
          class="tool-body"
        >
          <div class="args">
            <div class="section-label">
              args
            </div>
            <pre>{{ callArgsJson(entry.block) }}</pre>
          </div>
          <div
            v-if="!entry.block.pending || entry.block.result"
            class="result"
          >
            <div class="section-label">
              result
              <span
                v-if="resultExit(entry.block) !== undefined"
                class="exit"
              >exit={{ resultExit(entry.block) }}</span>
              <span
                v-if="isTruncated(entry.block)"
                class="truncated"
              >truncated</span>
            </div>
            <pre
              v-if="outputSegments(resultOutput(entry.block)).length === 1 && outputSegments(resultOutput(entry.block))[0]?.kind === 'text'"
            >{{ resultOutput(entry.block) }}</pre>
            <pre
              v-else
              data-testid="tool-output"
            >
              <template
                v-for="(seg, i) in outputSegments(resultOutput(entry.block))"
                :key="i"
              >
                <button
                  v-if="seg.kind === 'path'"
                  type="button"
                  class="artifact-link"
                  :data-path="seg.text"
                  @click="emit('open-artifact', seg.text)"
                >{{ seg.text }}</button>
                <template v-else>{{ seg.text }}</template>
              </template>
            </pre>
          </div>
        </div>
      </div>
    </template>
  </div>
  <!-- eslint-enable vue/no-v-html -->
</template>

<style scoped>
.feed {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  overflow-y: auto;
  padding: 4px 0;
}
.msg {
  padding: 8px 10px;
  border-radius: 6px;
  max-width: 92%;
}
.msg.user {
  align-self: flex-end;
  background: #1f3a6e;
}
.msg.user .who {
  font-size: 11px;
  color: #9cf;
}
.msg.assistant {
  align-self: flex-start;
  background: #2a2a2a;
}
.msg.system {
  align-self: center;
  background: #222;
  color: #999;
  font-size: 12px;
  max-width: 100%;
}
.msg.compact {
  align-self: stretch;
  max-width: 100%;
  background: #1a2a1a;
  border: 1px dashed #3a5a3a;
  font-size: 12px;
  color: #aca;
}
.msg.compact .label {
  display: block;
  font-size: 10px;
  text-transform: uppercase;
  color: #7a7;
}
.body :deep(p) {
  margin: 0 0 6px;
}
.body :deep(p:last-child) {
  margin-bottom: 0;
}
.body :deep(pre) {
  background: #111;
  padding: 6px;
  border-radius: 4px;
  overflow-x: auto;
  font-size: 12px;
}
.body :deep(code) {
  font-size: 12px;
}
.tool-block {
  align-self: stretch;
  background: #1e1e1e;
  border: 1px solid #333;
  border-radius: 4px;
  font-size: 12px;
}
.tool-toggle {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 6px 8px;
  background: transparent;
  border: 0;
  color: #ccc;
  cursor: pointer;
  text-align: left;
}
.tool-name {
  font-family: ui-monospace, monospace;
  color: #8cf;
}
.pending-marker {
  color: #fc6;
  font-style: italic;
}
.late-marker {
  background: #5a3a1a;
  color: #fc8;
  padding: 0 6px;
  border-radius: 8px;
  font-size: 10px;
}
.status {
  margin-left: auto;
  font-size: 10px;
  color: #8a8;
}
.status.st-ERROR {
  color: #f88;
}
.status.st-CANCELLED,
.status.st-LOST {
  color: #fa6;
}
.tool-body {
  padding: 0 8px 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.section-label {
  font-size: 10px;
  text-transform: uppercase;
  color: #777;
  margin-bottom: 2px;
}
.exit,
.truncated {
  margin-left: 6px;
  color: #fa6;
}
.tool-body pre {
  margin: 0;
  background: #111;
  padding: 6px;
  border-radius: 4px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow-y: auto;
}
.msg.tool-raw {
  background: #222;
  font-size: 12px;
}
</style>
