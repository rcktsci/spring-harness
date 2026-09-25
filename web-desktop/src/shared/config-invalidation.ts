import type { ServerConfig } from './ipc-contract.js';

/**
 * True when a settings change invalidates the stored Keycloak session
 * (issuer or client id — both are bound into the token grant). Everything
 * else (theme, limits, window, confirmCommands, showTray) keeps the
 * session alive.
 */
export function sessionInvalidatingChange(prev: ServerConfig, next: ServerConfig): boolean {
  return (
    prev.keycloakIssuer !== next.keycloakIssuer
    || prev.keycloakClientId !== next.keycloakClientId
  );
}

export interface EndpointChangePlan {
  /** Stored tokens are invalid for the new configuration. */
  invalidateSession: boolean;
  /** Live SSE/relay subscriptions must be torn down for re-subscription. */
  sseResubscribe: boolean;
  /** Network clients capture cfg at construction — rebuild for a new baseUrl. */
  clientsRebuild: boolean;
  /** Renderer must be notified about the endpoint change. */
  notifyConfigChanged: boolean;
}

export function planEndpointChange(prev: ServerConfig, next: ServerConfig): EndpointChangePlan {
  const baseUrlChanged = prev.serverBaseUrl !== next.serverBaseUrl;
  const issuerChanged = prev.keycloakIssuer !== next.keycloakIssuer;
  const clientIdChanged = prev.keycloakClientId !== next.keycloakClientId;
  const sessionInvalidated = issuerChanged || clientIdChanged;
  const endpointChanged = baseUrlChanged || sessionInvalidated;
  return {
    invalidateSession: sessionInvalidated,
    sseResubscribe: endpointChanged,
    clientsRebuild: baseUrlChanged,
    notifyConfigChanged: endpointChanged,
  };
}
