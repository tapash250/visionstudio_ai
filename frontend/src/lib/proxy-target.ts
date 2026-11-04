/**
 * Same-origin proxy to the VisionStudio API.
 *
 * Only relative paths under /api/ on the configured backend are allowed. The target URL is
 * resolved and its origin re-checked, so values like "@evil.com/", "//evil.com", absolute
 * URLs, encoded dot-segments or backslashes can never redirect the request to another host
 * (SSRF / open proxy).
 */
const ALLOWED_PREFIXES = ['/api/'];

function apiBase(): URL {
  return new URL(process.env.API_URL || process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8000');
}

export function resolveTarget(rawPath: string | null): URL | null {
  if (!rawPath || rawPath.length > 2048) return null;
  if (!rawPath.startsWith('/') || rawPath.startsWith('//')) return null;
  if (/[\\\u0000-\u001f]/.test(rawPath)) return null;

  let decoded: string;
  try {
    decoded = decodeURIComponent(rawPath);
  } catch {
    return null;
  }
  if (decoded.split(/[/?#]/).some((segment) => segment === '..' || segment === '.')) return null;

  const base = apiBase();
  let target: URL;
  try {
    target = new URL(rawPath, base);
  } catch {
    return null;
  }
  if (target.origin !== base.origin) return null;
  if (!ALLOWED_PREFIXES.some((prefix) => target.pathname.startsWith(prefix))) return null;
  return target;
}
