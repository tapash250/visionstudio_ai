import { NextResponse } from 'next/server';

import { resolveTarget } from '@/lib/proxy-target';

function forwardHeaders(request: Request, json: boolean): HeadersInit {
  const headers: Record<string, string> = {};
  const auth = request.headers.get('Authorization');
  if (auth) headers['Authorization'] = auth;
  if (json) headers['Content-Type'] = 'application/json';
  return headers;
}

async function relay(response: Response) {
  const data = await response.json().catch(() => ({ error: 'Invalid response from API' }));
  return NextResponse.json(data, { status: response.status });
}

export async function GET(request: Request) {
  const target = resolveTarget(new URL(request.url).searchParams.get('path'));
  if (!target) return NextResponse.json({ error: 'Path not allowed' }, { status: 400 });

  try {
    return await relay(
      await fetch(target, { headers: forwardHeaders(request, false), redirect: 'manual' }),
    );
  } catch {
    return NextResponse.json({ error: 'Failed to fetch from API' }, { status: 502 });
  }
}

export async function POST(request: Request) {
  const target = resolveTarget(new URL(request.url).searchParams.get('path'));
  if (!target) return NextResponse.json({ error: 'Path not allowed' }, { status: 400 });

  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: 'Invalid JSON body' }, { status: 400 });
  }

  try {
    return await relay(
      await fetch(target, {
        method: 'POST',
        headers: forwardHeaders(request, true),
        body: JSON.stringify(body),
        redirect: 'manual',
      }),
    );
  } catch {
    return NextResponse.json({ error: 'Failed to post to API' }, { status: 502 });
  }
}
