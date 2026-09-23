import type { components } from '../api/generated/openapi';

export type SessionDto = components['schemas']['SessionDto'];
export type SessionPage = components['schemas']['SessionPage'];
export type SessionKind = components['schemas']['SessionKind'];
export type SessionRuntimeStatus = components['schemas']['SessionRuntimeStatus'];
export type MessageDto = components['schemas']['MessageDto'];
export type MessagePage = components['schemas']['MessagePage'];
export type MessageKind = components['schemas']['MessageKind'];
export type AgentCatalog = components['schemas']['AgentCatalog'];
export type AgentCatalogItem = components['schemas']['AgentCatalogItem'];
export type CreateSessionRequest = components['schemas']['CreateSessionRequest'];
export type SendMessageAccepted = components['schemas']['SendMessageAccepted'];
export type SessionStatusEvent = components['schemas']['SessionStatusEvent'];
export type TurnOutcome = components['schemas']['TurnOutcome'];
