import { openDB, DBSchema, IDBPDatabase } from 'idb';

interface QueuedRequest {
  id: string;
  url: string;
  method: string;
  body: string;
  headers: Record<string, string>;
  timestamp: number;
  retries: number;
}

interface QueueDB extends DBSchema {
  requests: {
    key: string;
    value: QueuedRequest;
    indexes: { 'by-timestamp': number };
  };
}

const DB_NAME = 'visionstudio-queue';
const DB_VERSION = 1;

class OfflineQueueService {
  private db: IDBPDatabase<QueueDB> | null = null;

  async init(): Promise<void> {
    this.db = await openDB<QueueDB>(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains('requests')) {
          const store = db.createObjectStore('requests', { keyPath: 'id' });
          store.createIndex('by-timestamp', 'timestamp');
        }
      },
    });
  }

  async enqueue(request: Omit<QueuedRequest, 'id' | 'timestamp' | 'retries'>): Promise<string> {
    if (!this.db) await this.init();

    const id = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
    const queued: QueuedRequest = {
      ...request,
      id,
      timestamp: Date.now(),
      retries: 0,
    };

    await this.db!.put('requests', queued);
    return id;
  }

  async dequeue(id: string): Promise<void> {
    if (!this.db) await this.init();
    await this.db!.delete('requests', id);
  }

  async getAll(): Promise<QueuedRequest[]> {
    if (!this.db) await this.init();
    return this.db!.getAllFromIndex('requests', 'by-timestamp');
  }

  async getCount(): Promise<number> {
    if (!this.db) await this.init();
    return this.db!.count('requests');
  }

  async clear(): Promise<void> {
    if (!this.db) await this.init();
    await this.db!.clear('requests');
  }

  async incrementRetries(id: string): Promise<void> {
    if (!this.db) await this.init();
    const req = await this.db!.get('requests', id);
    if (req) {
      req.retries += 1;
      await this.db!.put('requests', req);
    }
  }

  async sync(): Promise<{ success: number; failed: number }> {
    const requests = await this.getAll();
    let success = 0;
    let failed = 0;

    for (const req of requests) {
      try {
        const response = await fetch(req.url, {
          method: req.method,
          headers: req.headers,
          body: req.body,
        });

        if (response.ok) {
          await this.dequeue(req.id);
          success++;
        } else {
          await this.incrementRetries(req.id);
          if (req.retries >= 3) {
            await this.dequeue(req.id);
            failed++;
          }
        }
      } catch {
        await this.incrementRetries(req.id);
        if (req.retries >= 3) {
          await this.dequeue(req.id);
          failed++;
        }
      }
    }

    return { success, failed };
  }
}

export const offlineQueue = new OfflineQueueService();
