import { describe, expect, it } from 'vitest';
import {
  computeCodeChallenge,
  generatePkcePair,
  generateState,
  isValidCodeVerifier,
} from '../../src/main/pkce';

describe('pkce', () => {
  it('generates a verifier matching the RFC 7636 alphabet and length', async () => {
    const { codeVerifier } = await generatePkcePair();
    expect(codeVerifier).toMatch(/^[A-Za-z0-9-._~]{43,128}$/);
  });

  it('produces a base64url S256 challenge (43 chars, no padding)', async () => {
    const { codeChallenge } = await generatePkcePair();
    expect(codeChallenge).toMatch(/^[A-Za-z0-9-_]{43}$/);
    expect(codeChallenge).not.toContain('=');
    expect(codeChallenge).not.toContain('+');
    expect(codeChallenge).not.toContain('/');
  });

  it('matches the RFC 7636 Appendix B test vector', async () => {
    const verifier = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
    const challenge = await computeCodeChallenge(verifier);
    expect(challenge).toBe('E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM');
  });

  it('yields a fresh pair on every call', async () => {
    const a = await generatePkcePair();
    const b = await generatePkcePair();
    expect(a.codeVerifier).not.toBe(b.codeVerifier);
    expect(a.codeChallenge).not.toBe(b.codeChallenge);
  });

  it('validates verifier shape', () => {
    expect(isValidCodeVerifier('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk')).toBe(true);
    // too short
    expect(isValidCodeVerifier('abc')).toBe(false);
    // illegal character
    expect(isValidCodeVerifier('dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk!')).toBe(false);
    expect(isValidCodeVerifier(undefined)).toBe(false);
  });

  it('generates opaque state values', () => {
    const a = generateState();
    const b = generateState();
    expect(a.length).toBeGreaterThan(10);
    expect(a).not.toBe(b);
  });
});
