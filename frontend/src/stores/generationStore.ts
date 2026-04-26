import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { GenerationJob, StylePreset, AspectRatio } from '@/types';

interface GenerationState {
  // Input
  prompt: string;
  negativePrompt: string;
  selectedStyle: string | null;
  aspectRatio: AspectRatio;
  seed: number | null;
  batchSize: number;

  // UI
  isGenerating: boolean;
  currentJob: GenerationJob | null;
  results: string[];
  history: GenerationJob[];

  // Actions
  setPrompt: (prompt: string) => void;
  setNegativePrompt: (prompt: string) => void;
  setStyle: (style: string | null) => void;
  setAspectRatio: (ratio: AspectRatio) => void;
  setSeed: (seed: number | null) => void;
  setBatchSize: (size: number) => void;
  startGeneration: () => void;
  setResults: (urls: string[]) => void;
  addToHistory: (job: GenerationJob) => void;
  reset: () => void;
}

const initialState = {
  prompt: '',
  negativePrompt: '',
  selectedStyle: null,
  aspectRatio: '9:16' as AspectRatio,
  seed: null,
  batchSize: 1,
  isGenerating: false,
  currentJob: null,
  results: [],
};

export const useGenerationStore = create<GenerationState>()(
  persist(
    (set) => ({
      ...initialState,
      history: [],
      setPrompt: (prompt) => set({ prompt }),
      setNegativePrompt: (negativePrompt) => set({ negativePrompt }),
      setStyle: (selectedStyle) => set({ selectedStyle }),
      setAspectRatio: (aspectRatio) => set({ aspectRatio }),
      setSeed: (seed) => set({ seed }),
      setBatchSize: (batchSize) => set({ batchSize }),
      startGeneration: () => set({ isGenerating: true, results: [] }),
      setResults: (results) => set({ results, isGenerating: false }),
      addToHistory: (job) => set((state) => ({ history: [job, ...state.history].slice(0, 50) })),
      reset: () => set(initialState),
    }),
    {
      name: 'visionstudio-generation',
      partialize: (state) => ({
        history: state.history,
        aspectRatio: state.aspectRatio,
        batchSize: state.batchSize,
      }),
    }
  )
);
