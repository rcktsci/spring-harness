import type { RelayStatus } from '@shared/ipc-contract';

export function relayLabel(status: RelayStatus | null, sessionKind?: string): string {
  if (sessionKind === 'STATE') {
    return 'релей доступен только для root-сессий';
  }
  if (!status) return 'релей: —';
  if (status.code === 'superseded' || (status.phase === 'fatal' && status.code === 'superseded')) {
    return 'сессия открыта в другом месте';
  }
  if (status.registered && status.toolCount !== undefined) {
    return `подключён (${status.toolCount} инструментов)`;
  }
  if (status.registered) return 'подключён';
  if (status.reason) return status.reason;
  if (
    status.phase === 'connecting'
    || status.phase === 'handshake'
    || status.phase === 'registering'
  ) {
    return `релей: ${status.phase}…`;
  }
  if (status.connected) return 'релей: ожидание регистрации…';
  return 'не подключён';
}
