import { describe, expect, it } from 'vitest';
import { DEFAULT_CONFIG } from '../../src/shared/ipc-contract';
import { planEndpointChange, sessionInvalidatingChange } from '../../src/shared/config-invalidation';

const base = { ...DEFAULT_CONFIG };

describe('sessionInvalidatingChange', () => {
  it('flags keycloak issuer and client id changes', () => {
    expect(sessionInvalidatingChange(base, { ...base, keycloakIssuer: 'http://kc-other/realms/harness' })).toBe(true);
    expect(sessionInvalidatingChange(base, { ...base, keycloakClientId: 'other-client' })).toBe(true);
  });

  it('ignores non-keycloak settings changes', () => {
    expect(sessionInvalidatingChange(base, { ...base, serverBaseUrl: 'http://backend-other:8080' })).toBe(false);
    expect(sessionInvalidatingChange(base, { ...base, theme: 'light' })).toBe(false);
    expect(sessionInvalidatingChange(base, { ...base, confirmCommands: 'never' })).toBe(false);
    expect(sessionInvalidatingChange(base, { ...base, showTray: false })).toBe(false);
    expect(sessionInvalidatingChange(base, { ...base, sessionListLimit: 10 })).toBe(false);
    expect(sessionInvalidatingChange(base, { ...base, windowHeight: 600 })).toBe(false);
  });

  it('is false for an identical configuration', () => {
    expect(sessionInvalidatingChange(base, { ...base })).toBe(false);
  });
});

describe('planEndpointChange', () => {
  it('plans a full rebuild only for a baseUrl change', () => {
    const plan = planEndpointChange(base, { ...base, serverBaseUrl: 'http://backend-other:8080' });
    expect(plan).toEqual({
      invalidateSession: false,
      sseResubscribe: true,
      clientsRebuild: true,
      notifyConfigChanged: true,
    });
  });

  it('plans invalidation and resubscription for issuer/clientId changes without rebuild', () => {
    const issuer = planEndpointChange(base, { ...base, keycloakIssuer: 'http://kc-other/realms/harness' });
    expect(issuer).toEqual({
      invalidateSession: true,
      sseResubscribe: true,
      clientsRebuild: false,
      notifyConfigChanged: true,
    });
    const clientId = planEndpointChange(base, { ...base, keycloakClientId: 'other-client' });
    expect(clientId).toEqual({
      invalidateSession: true,
      sseResubscribe: true,
      clientsRebuild: false,
      notifyConfigChanged: true,
    });
  });

  it('plans nothing for non-keycloak settings changes', () => {
    const theme = planEndpointChange(base, { ...base, theme: 'light' });
    expect(theme).toEqual({
      invalidateSession: false,
      sseResubscribe: false,
      clientsRebuild: false,
      notifyConfigChanged: false,
    });
    const tray = planEndpointChange(base, { ...base, showTray: false });
    expect(tray).toEqual({
      invalidateSession: false,
      sseResubscribe: false,
      clientsRebuild: false,
      notifyConfigChanged: false,
    });
  });

  it('combines baseUrl and issuer changes into one full plan', () => {
    const plan = planEndpointChange(base, {
      ...base,
      serverBaseUrl: 'http://backend-other:8080',
      keycloakIssuer: 'http://kc-other/realms/harness',
    });
    expect(plan.invalidateSession).toBe(true);
    expect(plan.clientsRebuild).toBe(true);
  });
});
