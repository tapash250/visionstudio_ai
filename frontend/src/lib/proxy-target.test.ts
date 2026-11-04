import { describe, expect, it } from 'vitest';

import { resolveTarget } from './proxy-target';

describe('resolveTarget (API proxy allowlist)', () => {
  it('allows relative API paths on the backend', () => {
    expect(resolveTarget('/api/projects/')?.href).toBe('http://localhost:8000/api/projects/');
    expect(resolveTarget('/api/generate/abc?x=1')?.pathname).toBe('/api/generate/abc');
  });

  it.each([
    null,
    '',
    'api/projects',
    '//evil.example/api/x',
    'http://evil.example/api/x',
    'https://169.254.169.254/latest/meta-data',
    '@evil.example/api/x',
    '/\\evil.example/api/x',
    '/api/../admin',
    '/api/%2e%2e/admin',
    '/health',
    '/internal/metrics',
    '/api/%ZZ',
  ])('rejects %s', (path) => {
    expect(resolveTarget(path as string | null)).toBeNull();
  });
});
