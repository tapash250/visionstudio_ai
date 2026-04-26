import { getSession } from 'next-auth/react';

const API_BASE = process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8000';

async function getHeaders(): Promise<Record<string, string>> {
  const session = await getSession();
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
  };
  if (session?.accessToken) {
    headers['Authorization'] = `Bearer ${session.accessToken}`;
  }
  return headers;
}

class APIError extends Error {
  constructor(
    message: string,
    public status: number,
    public data?: any
  ) {
    super(message);
    this.name = 'APIError';
  }
}

async function fetchWithRetry(
  url: string,
  options: RequestInit,
  retries = 3,
  delay = 1000
): Promise<Response> {
  try {
    const response = await fetch(url, options);

    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      throw new APIError(
        errorData.detail || `HTTP ${response.status}`,
        response.status,
        errorData
      );
    }

    return response;
  } catch (error) {
    if (retries > 0 && !(error instanceof APIError)) {
      await new Promise((resolve) => setTimeout(resolve, delay));
      return fetchWithRetry(url, options, retries - 1, delay * 2);
    }
    throw error;
  }
}

export async function apiGet<T>(path: string): Promise<T> {
  const res = await fetchWithRetry(`${API_BASE}${path}`, {
    headers: await getHeaders(),
  });
  return res.json();
}

export async function apiPost<T>(path: string, body: unknown): Promise<T> {
  const res = await fetchWithRetry(`${API_BASE}${path}`, {
    method: 'POST',
    headers: await getHeaders(),
    body: JSON.stringify(body),
  });
  return res.json();
}

export async function apiUpload(path: string, file: File, metadata?: Record<string, string>): Promise<{ url: string }> {
  const formData = new FormData();
  formData.append('file', file);
  if (metadata) {
    Object.entries(metadata).forEach(([key, value]) => {
      formData.append(key, value);
    });
  }

  const session = await getSession();
  const headers: Record<string, string> = {};
  if (session?.accessToken) {
    headers['Authorization'] = `Bearer ${session.accessToken}`;
  }

  const res = await fetchWithRetry(`${API_BASE}${path}`, {
    method: 'POST',
    headers,
    body: formData,
  });
  return res.json();
}

export async function apiDelete<T>(path: string): Promise<T> {
  const res = await fetchWithRetry(`${API_BASE}${path}`, {
    method: 'DELETE',
    headers: await getHeaders(),
  });
  return res.json();
}

export async function apiPatch<T>(path: string, body: unknown): Promise<T> {
  const res = await fetchWithRetry(`${API_BASE}${path}`, {
    method: 'PATCH',
    headers: await getHeaders(),
    body: JSON.stringify(body),
  });
  return res.json();
}

// Generation API
export const generationApi = {
  create: (data: {
    prompt: string;
    negativePrompt?: string;
    model?: string;
    style?: string;
    aspectRatio?: string;
    seed?: number;
    steps?: number;
    cfgScale?: number;
    batchSize?: number;
  }) => apiPost<{ jobId: string; status: string }>('/api/generate', data),

  getStatus: (jobId: string) => apiGet<{ status: string; resultUrls?: string[]; error?: string }>(`/api/generate/${jobId}`),

  enhancePrompt: (prompt: string) => apiPost<{ enhanced: string }>('/api/generate/enhance', { prompt }),

  getStyles: () => apiGet<{ styles: Array<{ id: string; name: string; label: string; category: string; thumbnailUrl?: string }> }>('/api/styles'),
};

// Edit API
export const editApi = {
  create: (data: {
    sourceUrl: string;
    operation: string;
    prompt?: string;
    maskUrl?: string;
    strength?: number;
    preserveFace?: boolean;
  }) => apiPost<{ jobId: string }>('/api/edit', data),

  getStatus: (jobId: string) => apiGet<{ status: string; resultUrl?: string }>(`/api/edit/${jobId}`),

  upload: (file: File) => apiUpload('/api/upload', file),
};

// Animation API
export const animationApi = {
  create: (data: {
    sourceUrl: string;
    animationType: string;
    duration?: number;
    fps?: number;
    audioUrl?: string;
    format?: string;
  }) => apiPost<{ jobId: string }>('/api/animate', data),

  getStatus: (jobId: string) => apiGet<{ status: string; resultUrl?: string; previewUrl?: string }>(`/api/animate/${jobId}`),
};

// Project API
export const projectApi = {
  list: (type?: string) => apiGet<{ projects: any[] }>(`/api/projects${type ? `?type=${type}` : ''}`),
  get: (id: string) => apiGet<any>(`/api/projects/${id}`),
  delete: (id: string) => apiPost<void>(`/api/projects/${id}/delete`, {}),
  update: (id: string, data: Partial<any>) => apiPost<any>(`/api/projects/${id}`, data),
};

// Auth API
export const authApi = {
  register: (data: { email: string; password: string; name: string }) => 
    apiPost<{ id: string; email: string }>('/api/auth/register', data),

  refreshToken: (refreshToken: string) => 
    apiPost<{ access_token: string }>('/api/auth/refresh', { refresh_token: refreshToken }),

  matureConsent: (confirmed: boolean) => 
    apiPost<{ mature_enabled: boolean }>('/api/auth/mature-consent', { confirmed }),
};

// Push API
export const pushApi = {
  register: (data: {
    fingerprint: string;
    pushToken: string;
    deviceName?: string;
    deviceType?: string;
    os?: string;
    browser?: string;
  }) => apiPost<{ message: string }>('/api/push/register', data),

  send: (data: { title: string; body: string; url?: string }) => 
    apiPost<{ sent: number }>('/api/push/send', data),
};

export { APIError };
