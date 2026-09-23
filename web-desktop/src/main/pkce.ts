/**
 * RFC 7636 PKCE helpers — code_verifier / code_challenge (S256).
 *
 * Electron's sandboxed renderer cannot touch crypto.subtle for these, and
 * per D-91 all auth happens in main anyway, so this runs on the Node side
 * using the Web Crypto implementation available in utility process.
 */

import { webcrypto } from 'node:crypto';

const RANDOM_BYTES = 32;
const VERIFIER_RE = /^[A-Za-z0-9-._~]{43,128}$/;

export interface PkcePair {
  codeVerifier: string;
  codeChallenge: string;
}

function base64url(bytes: ArrayBuffer): string {
  return Buffer.from(bytes).toString('base64url');
}

export async function generatePkcePair(): Promise<PkcePair> {
  const bytes = webcrypto.getRandomValues(new Uint8Array(RANDOM_BYTES));
  const codeVerifier = base64url(bytes.buffer);
  const digest = await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode(codeVerifier));
  return { codeVerifier, codeChallenge: base64url(digest) };
}

export function isValidCodeVerifier(verifier: unknown): verifier is string {
  return typeof verifier === 'string' && VERIFIER_RE.test(verifier);
}

export async function computeCodeChallenge(verifier: string): Promise<string> {
  const digest = await webcrypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64url(digest);
}

export function generateState(length = 24): string {
  return base64url(webcrypto.getRandomValues(new Uint8Array(length)).buffer);
}
