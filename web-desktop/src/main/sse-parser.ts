/**
 * Pure incremental SSE parser — extracted so both session and task SSE
 * clients can share it, and so unit tests don't need a network or Electron.
 *
 * Conforms to the EventSource wire format:
 *  - `id:` is sticky across frames until overwritten
 *  - `event:` defaults to `message` when omitted
 *  - `data:` is multi-line; joined with `\n`
 *  - `retry:` sets the reconnect delay (in ms)
 *  - Lines starting with `:` are comments and ignored (`: ping`)
 *  - A blank line flushes the current frame to `onFrame`
 */
export type SseFrame = {
  id?: string;
  event: string;
  data: string;
  retryMs?: number;
};

export function createSseParser(onFrame: (frame: SseFrame) => void): {
  push: (chunk: string) => void;
  flush: () => void;
} {
  let buffer = '';
  let dataLines: string[] = [];
  let eventName = '';
  let lastId: string | undefined;
  let retryMs: number | undefined;

  const emit = (): void => {
    if (dataLines.length === 0 && eventName === '') {
      return;
    }
    onFrame({
      id: lastId,
      event: eventName || 'message',
      data: dataLines.join('\n'),
      retryMs,
    });
    dataLines = [];
    eventName = '';
  };

  const handleLine = (raw: string): void => {
    if (raw === '') {
      emit();
      return;
    }
    if (raw.startsWith(':')) {
      return;
    }
    const colon = raw.indexOf(':');
    const field = colon === -1 ? raw : raw.slice(0, colon);
    let value = colon === -1 ? '' : raw.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    switch (field) {
      case 'data':
        dataLines.push(value);
        break;
      case 'event':
        eventName = value;
        break;
      case 'id':
        // Per SSE spec: `id:` (empty value) clears the last-event-id so the
        // next reconnect starts fresh rather than resuming from a stale seq.
        lastId = value === '' ? undefined : value;
        break;
      case 'retry': {
        const n = Number.parseInt(value, 10);
        if (Number.isFinite(n)) retryMs = n;
        break;
      }
      default:
        break;
    }
  };

  return {
    push(chunk: string): void {
      buffer += chunk;
      let idx: number;
      while ((idx = buffer.indexOf('\n')) !== -1) {
        let line = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 1);
        if (line.endsWith('\r')) line = line.slice(0, -1);
        handleLine(line);
      }
    },
    flush(): void {
      if (buffer.length > 0) {
        let line = buffer;
        if (line.endsWith('\r')) line = line.slice(0, -1);
        buffer = '';
        handleLine(line);
      }
      emit();
    },
  };
}
