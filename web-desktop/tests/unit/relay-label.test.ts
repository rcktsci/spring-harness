import { describe, expect, it } from 'vitest';
import type { RelayStatus } from '../../src/shared/ipc-contract';
import { relayLabel } from '../../src/renderer/src/lib/relay-label';

const DECLINED: RelayStatus = {
  connected: true,
  registered: false,
  phase: 'connected',
  sessionId: 'sess-a',
  code: 'consent-declined',
  reason: 'User declined local execution.',
};

describe('relayLabel', () => {
  it('shows the tool count for a registered relay', () => {
    expect(relayLabel({ connected: true, registered: true, phase: 'connected', toolCount: 6 })).toBe(
      'подключён (6 инструментов)',
    );
  });

  it('never shows «подключён» for an unregistered relay', () => {
    expect(relayLabel(DECLINED)).toBe('User declined local execution.');
    expect(relayLabel({ connected: true, registered: false, phase: 'connected' })).toBe(
      'релей: ожидание регистрации…',
    );
    expect(relayLabel({ connected: false, registered: false, phase: 'disconnected' })).toBe(
      'не подключён',
    );
  });

  it('surfaces the takeover notice', () => {
    expect(relayLabel({ connected: false, registered: false, phase: 'disconnected', code: 'superseded' })).toBe(
      'сессия открыта в другом месте',
    );
  });

  it('pins the STATE-session indicator over any relay status', () => {
    expect(relayLabel({ connected: true, registered: true, phase: 'connected', toolCount: 6 }, 'STATE')).toBe(
      'релей доступен только для root-сессий',
    );
    expect(relayLabel(DECLINED, 'STATE')).toBe('релей доступен только для root-сессий');
  });

  it('labels in-flight phases and the missing-status placeholder', () => {
    expect(relayLabel({ connected: false, registered: false, phase: 'connecting' })).toBe('релей: connecting…');
    expect(relayLabel(null)).toBe('релей: —');
  });
});
