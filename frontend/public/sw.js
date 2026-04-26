const CACHE_NAME = 'visionstudio-v1';
const STATIC_ASSETS = [
  '/',
  '/generate',
  '/edit',
  '/animate',
  '/projects',
  '/settings',
  '/manifest.json',
  '/icons/icon-192x192.png',
  '/icons/icon-512x512.png',
];

// Install: Precache shell
self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(STATIC_ASSETS);
    }).then(() => self.skipWaiting())
  );
});

// Activate: Clean old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((cacheNames) => {
      return Promise.all(
        cacheNames
          .filter((name) => name !== CACHE_NAME)
          .map((name) => caches.delete(name))
      );
    }).then(() => self.clients.claim())
  );
});

// Background Sync for generation jobs
self.addEventListener('sync', (event) => {
  if (event.tag === 'generate-sync') {
    event.waitUntil(syncGenerationQueue());
  } else if (event.tag === 'edit-sync') {
    event.waitUntil(syncEditQueue());
  }
});

async function syncGenerationQueue() {
  const db = await openDB('visionstudio-queue', 1);
  const tx = db.transaction('generations', 'readonly');
  const store = tx.objectStore('generations');
  const requests = await store.getAll();

  for (const req of requests) {
    try {
      const response = await fetch('/api/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(req.payload),
      });
      if (response.ok) {
        const deleteTx = db.transaction('generations', 'readwrite');
        await deleteTx.objectStore('generations').delete(req.id);
        const clients = await self.clients.matchAll();
        clients.forEach((client) => {
          client.postMessage({ type: 'SYNC_COMPLETE', jobId: req.id });
        });
      }
    } catch (err) {
      console.error('Sync failed for job', req.id, err);
    }
  }
}

async function syncEditQueue() {
  // Similar pattern for edit jobs
}

// Push notifications for job completion
self.addEventListener('push', (event) => {
  const data = event.data?.json() ?? {};
  event.waitUntil(
    self.registration.showNotification(data.title || 'VisionStudio AI', {
      body: data.body || 'Your creation is ready!',
      icon: '/icons/icon-192x192.png',
      badge: '/icons/icon-72x72.png',
      tag: data.tag || 'job-complete',
      requireInteraction: true,
      data: { url: data.url || '/' },
      actions: [
        { action: 'open', title: 'View' },
        { action: 'dismiss', title: 'Dismiss' },
      ],
    })
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const { url } = event.notification.data;
  if (event.action === 'open' || !event.action) {
    event.waitUntil(self.clients.openWindow(url));
  }
});

// Fetch: Stale-while-revalidate for UI, Network-first for API
self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Next.js static assets (critical for PWA)
  if (url.pathname.startsWith('/_next/static/')) {
    event.respondWith(cacheFirst(request, 'next-static'));
    return;
  }

  // Next.js optimized images
  if (url.pathname.startsWith('/_next/image')) {
    event.respondWith(staleWhileRevalidate(request, 'next-image'));
    return;
  }

  // API requests: Network first with offline queue
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(networkFirstWithOfflineQueue(request));
    return;
  }

  // Static assets: Cache first
  if (request.destination === 'image' || request.destination === 'font' || request.destination === 'style') {
    event.respondWith(cacheFirst(request, 'static-assets'));
    return;
  }

  // Navigation: Stale while revalidate
  if (request.mode === 'navigate') {
    event.respondWith(staleWhileRevalidate(request, 'pages'));
    return;
  }

  // Default: Network with cache fallback
  event.respondWith(networkWithCacheFallback(request));
});

async function networkFirstWithOfflineQueue(request) {
  try {
    const networkResponse = await fetch(request);
    if (networkResponse.ok) {
      const cache = await caches.open(CACHE_NAME);
      cache.put(request, networkResponse.clone());
    }
    return networkResponse;
  } catch (error) {
    if (request.method === 'POST') {
      const body = await request.clone().json().catch(() => null);
      if (body) {
        await queueRequest(request.url, body);
        return new Response(JSON.stringify({ queued: true }), {
          headers: { 'Content-Type': 'application/json' },
        });
      }
    }
    const cached = await caches.match(request);
    if (cached) return cached;
    throw error;
  }
}

async function queueRequest(url, payload) {
  const db = await openDB('visionstudio-queue', 1, {
    upgrade(db) {
      if (!db.objectStoreNames.contains('generations')) {
        db.createObjectStore('generations', { keyPath: 'id' });
      }
    },
  });
  const tx = db.transaction('generations', 'readwrite');
  await tx.objectStore('generations').put({
    id: crypto.randomUUID(),
    url,
    payload,
    timestamp: Date.now(),
  });
}

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);
  if (cached) return cached;

  try {
    const networkResponse = await fetch(request);
    if (networkResponse.ok) {
      cache.put(request, networkResponse.clone());
    }
    return networkResponse;
  } catch {
    return new Response('Offline', { status: 503 });
  }
}

async function staleWhileRevalidate(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);

  const fetchPromise = fetch(request).then((networkResponse) => {
    if (networkResponse.ok) {
      cache.put(request, networkResponse.clone());
    }
    return networkResponse;
  }).catch(() => cached);

  return cached || fetchPromise;
}

async function networkWithCacheFallback(request) {
  try {
    return await fetch(request);
  } catch {
    const cached = await caches.match(request);
    return cached || new Response('Offline', { status: 503 });
  }
}

// IndexedDB helper
function openDB(name, version, config) {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(name, version);
    request.onerror = () => reject(request.error);
    request.onsuccess = () => resolve(request.result);
    if (config?.upgrade) request.onupgradeneeded = (e) => config.upgrade(e.target.result);
  });
}
