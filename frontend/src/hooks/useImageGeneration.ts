'use client';

import { useState, useCallback } from 'react';
import { generationApi } from '@/lib/api';
import { useGenerationStore } from '@/stores/generationStore';
import { generateSeed } from '@/lib/utils';

export function useImageGeneration() {
  const store = useGenerationStore();
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);

  const generate = useCallback(async () => {
    if (!store.prompt.trim()) return;

    setError(null);
    setProgress(0);
    store.startGeneration();

    try {
      const seed = store.seed ?? generateSeed();
      const job = await generationApi.create({
        prompt: store.prompt,
        negativePrompt: store.negativePrompt,
        style: store.selectedStyle || undefined,
        aspectRatio: store.aspectRatio,
        seed,
        batchSize: store.batchSize,
      });

      // Poll for status
      const pollInterval = setInterval(async () => {
        try {
          const status = await generationApi.getStatus(job.jobId);
          if (status.status === 'COMPLETED') {
            clearInterval(pollInterval);
            store.setResults(status.resultUrls || []);
            setProgress(100);
          } else if (status.status === 'FAILED') {
            clearInterval(pollInterval);
            setError(status.error || 'Generation failed');
            store.setResults([]);
          } else {
            setProgress((p) => Math.min(p + 10, 90));
          }
        } catch (e) {
          clearInterval(pollInterval);
          setError('Connection error');
        }
      }, 2000);
    } catch (e: any) {
      setError(e.message || 'Failed to start generation');
      store.setResults([]);
    }
  }, [store]);

  const enhancePrompt = useCallback(async () => {
    if (!store.prompt.trim()) return;
    try {
      const result = await generationApi.enhancePrompt(store.prompt);
      store.setPrompt(result.enhanced);
    } catch (e) {
      console.error('Prompt enhancement failed', e);
    }
  }, [store]);

  return {
    generate,
    enhancePrompt,
    error,
    progress,
    isGenerating: store.isGenerating,
    results: store.results,
  };
}
