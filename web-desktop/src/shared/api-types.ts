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

// Tree (§5.1)
export type SessionTreeNode = components['schemas']['SessionTreeNode'];
export type SessionTreePage = components['schemas']['SessionTreePage'];

// Task domain (§5.2)
export type TaskDto = components['schemas']['TaskDto'];
export type TaskStatusProjection = components['schemas']['TaskStatusProjection'];
export type TransitionDto = components['schemas']['TransitionDto'];
export type TransitionPage = components['schemas']['TransitionPage'];
export type CommentDto = components['schemas']['CommentDto'];
export type CommentPage = components['schemas']['CommentPage'];
export type TaskPage = components['schemas']['TaskPage'];
export type TaskTransitionEvent = components['schemas']['TaskTransitionEvent'];
export type TaskStatusEvent = components['schemas']['TaskStatusEvent'];
export type SubtaskTerminalEvent = components['schemas']['SubtaskTerminalEvent'];
export type TaskCommentEvent = components['schemas']['TaskCommentEvent'];

// Artifact domain (§5.3) — bare string envelope from §8.
export type WorkspaceDownloadResult = {
  /**
   * Local OS path where the file was placed (save-as target or temp copy
   * for open-in-OS). Renderer must read it via shell from then on.
   */
  localPath: string;
  /** Sanitized basename for display in the success toast. */
  basename: string;
  /** Server-reported content-length when present. */
  bytes?: number;
};
