'use client';

import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Sparkles, Wand2, RefreshCw, Download, ImageIcon, Settings2, X, ChevronDown } from 'lucide-react';
import { useGenerationStore } from '@/stores/generationStore';
import { useImageGeneration } from '@/hooks/useImageGeneration';
import { getAspectRatioClass, generateSeed } from '@/lib/utils';
import type { AspectRatio } from '@/types';

const aspectRatios: { value: AspectRatio; label: string; icon: string }[] = [
  { value: '9:16', label: 'Story', icon: '9:16' },
  { value: '1:1', label: 'Square', icon: '1:1' },
  { value: '16:9', label: 'Wide', icon: '16:9' },
  { value: '4:5', label: 'Post', icon: '4:5' },
  { value: '3:2', label: 'Photo', icon: '3:2' },
];

const stylePresets = [
  { id: 'realistic', label: 'Realistic', color: 'from-amber-500 to-orange-500' },
  { id: 'anime', label: 'Anime', color: 'from-pink-500 to-rose-500' },
  { id: 'cinematic', label: 'Cinematic', color: 'from-blue-500 to-indigo-500' },
  { id: 'fantasy', label: 'Fantasy', color: 'from-emerald-500 to-teal-500' },
  { id: 'fashion', label: 'Fashion', color: 'from-violet-500 to-purple-500' },
  { id: 'cartoon', label: 'Cartoon', color: 'from-yellow-400 to-orange-400' },
  { id: 'cyberpunk', label: 'Cyberpunk', color: 'from-cyan-500 to-blue-500' },
  { id: 'portrait', label: 'Portrait', color: 'from-rose-400 to-pink-500' },
  { id: 'pixel', label: 'Pixel Art', color: 'from-green-400 to-emerald-500' },
  { id: '3d', label: '3D Render', color: 'from-sky-400 to-blue-500' },
  { id: 'editorial', label: 'Editorial', color: 'from-slate-400 to-gray-500' },
  { id: 'product', label: 'Product', color: 'from-stone-400 to-neutral-500' },
];

export default function GeneratePage() {
  const store = useGenerationStore();
  const { generate, enhancePrompt, error, progress, isGenerating, results } = useImageGeneration();
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [selectedResult, setSelectedResult] = useState<number | null>(null);

  return (
    <div className="flex flex-col gap-4 px-4 pb-8">
      {/* Prompt Input */}
      <div className="card-mobile">
        <div className="relative">
          <textarea
            value={store.prompt}
            onChange={(e) => store.setPrompt(e.target.value)}
            placeholder="Describe what you want to create..."
            className="input-mobile min-h-[100px] resize-none"
            maxLength={1000}
          />
          <div className="absolute bottom-2 right-2 flex gap-1">
            <button
              onClick={enhancePrompt}
              disabled={!store.prompt.trim() || isGenerating}
              className="rounded-lg bg-secondary p-2 text-xs font-medium transition-colors hover:bg-accent"
              title="Enhance prompt with AI"
            >
              <Wand2 className="h-3.5 w-3.5" />
            </button>
            <button
              onClick={() => store.setPrompt('')}
              className="rounded-lg bg-secondary p-2 text-xs transition-colors hover:bg-accent"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          </div>
        </div>

        {/* Style Selector */}
        <div className="mt-4">
          <p className="mb-2 text-xs font-medium text-muted-foreground">Style</p>
          <div className="flex gap-2 overflow-x-auto pb-2 -mx-1 px-1 scrollbar-hide">
            <button
              onClick={() => store.setStyle(null)}
              className={`shrink-0 rounded-xl border px-4 py-2 text-xs font-medium transition-all ${
                !store.selectedStyle
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border bg-card text-muted-foreground'
              }`}
            >
              None
            </button>
            {stylePresets.map((style) => (
              <button
                key={style.id}
                onClick={() => store.setStyle(style.id)}
                className={`shrink-0 rounded-xl border px-4 py-2 text-xs font-medium transition-all ${
                  store.selectedStyle === style.id
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-border bg-card text-muted-foreground'
                }`}
              >
                {style.label}
              </button>
            ))}
          </div>
        </div>

        {/* Aspect Ratio */}
        <div className="mt-4">
          <p className="mb-2 text-xs font-medium text-muted-foreground">Aspect Ratio</p>
          <div className="flex gap-2">
            {aspectRatios.map((ar) => (
              <button
                key={ar.value}
                onClick={() => store.setAspectRatio(ar.value)}
                className={`flex flex-1 flex-col items-center gap-1 rounded-xl border py-2 transition-all ${
                  store.aspectRatio === ar.value
                    ? 'border-primary bg-primary/10 text-primary'
                    : 'border-border bg-card text-muted-foreground'
                }`}
              >
                <span className="text-[10px] font-bold">{ar.icon}</span>
                <span className="text-[10px]">{ar.label}</span>
              </button>
            ))}
          </div>
        </div>

        {/* Advanced Settings */}
        <button
          onClick={() => setShowAdvanced(!showAdvanced)}
          className="mt-4 flex w-full items-center justify-between rounded-xl border border-border bg-card px-4 py-3 text-xs font-medium"
        >
          <span className="flex items-center gap-2">
            <Settings2 className="h-3.5 w-3.5" />
            Advanced Settings
          </span>
          <ChevronDown className={`h-3.5 w-3.5 transition-transform ${showAdvanced ? 'rotate-180' : ''}`} />
        </button>

        <AnimatePresence>
          {showAdvanced && (
            <motion.div
              initial={{ height: 0, opacity: 0 }}
              animate={{ height: 'auto', opacity: 1 }}
              exit={{ height: 0, opacity: 0 }}
              className="overflow-hidden"
            >
              <div className="mt-3 space-y-3">
                <div>
                  <label className="mb-1 block text-xs text-muted-foreground">Negative Prompt</label>
                  <input
                    type="text"
                    value={store.negativePrompt}
                    onChange={(e) => store.setNegativePrompt(e.target.value)}
                    placeholder="What to avoid..."
                    className="input-mobile py-2.5 text-sm"
                  />
                </div>
                <div className="flex gap-3">
                  <div className="flex-1">
                    <label className="mb-1 block text-xs text-muted-foreground">Seed</label>
                    <div className="flex gap-1">
                      <input
                        type="number"
                        value={store.seed || ''}
                        onChange={(e) => store.setSeed(e.target.value ? parseInt(e.target.value) : null)}
                        placeholder="Random"
                        className="input-mobile flex-1 py-2.5 text-sm"
                      />
                      <button
                        onClick={() => store.setSeed(generateSeed())}
                        className="rounded-xl bg-secondary px-3"
                      >
                        <RefreshCw className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  </div>
                  <div className="flex-1">
                    <label className="mb-1 block text-xs text-muted-foreground">Batch</label>
                    <select
                      value={store.batchSize}
                      onChange={(e) => store.setBatchSize(parseInt(e.target.value))}
                      className="input-mobile py-2.5 text-sm"
                    >
                      <option value={1}>1 image</option>
                      <option value={2}>2 images</option>
                      <option value={4}>4 images</option>
                    </select>
                  </div>
                </div>
              </div>
            </motion.div>
          )}
        </AnimatePresence>

        {/* Generate Button */}
        <button
          onClick={generate}
          disabled={!store.prompt.trim() || isGenerating}
          className="btn-primary mt-4 w-full gap-2 disabled:opacity-50"
        >
          {isGenerating ? (
            <>
              <RefreshCw className="h-4 w-4 animate-spin" />
              Generating... {progress}%
            </>
          ) : (
            <>
              <Sparkles className="h-4 w-4" />
              Generate Image
            </>
          )}
        </button>

        {error && (
          <p className="mt-2 text-center text-xs text-destructive">{error}</p>
        )}
      </div>

      {/* Results */}
      <AnimatePresence>
        {results.length > 0 && (
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            className="card-mobile"
          >
            <div className="mb-3 flex items-center justify-between">
              <h3 className="font-semibold">Results</h3>
              <button onClick={() => store.setResults([])} className="text-xs text-muted-foreground">
                Clear
              </button>
            </div>
            <div className={`grid gap-2 ${results.length > 1 ? 'grid-cols-2' : 'grid-cols-1'}`}>
              {results.map((url, i) => (
                <div
                  key={i}
                  className={`group relative overflow-hidden rounded-xl ${getAspectRatioClass(store.aspectRatio)}`}
                  onClick={() => setSelectedResult(i)}
                >
                  <img
                    src={url}
                    alt={`Generated ${i + 1}`}
                    className="h-full w-full object-cover"
                    loading="lazy"
                  />
                  <div className="absolute inset-0 flex items-end justify-end gap-1 bg-gradient-to-t from-black/60 to-transparent p-2 opacity-0 transition-opacity group-hover:opacity-100">
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        const a = document.createElement('a');
                        a.href = url;
                        a.download = `visionstudio-${Date.now()}.png`;
                        a.click();
                      }}
                      className="rounded-lg bg-white/20 p-2 backdrop-blur"
                    >
                      <Download className="h-4 w-4 text-white" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      {/* Fullscreen Image Viewer */}
      <AnimatePresence>
        {selectedResult !== null && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-[60] flex items-center justify-center bg-black/90 p-4"
            onClick={() => setSelectedResult(null)}
          >
            <button
              className="absolute right-4 top-4 rounded-full bg-white/10 p-2"
              onClick={() => setSelectedResult(null)}
            >
              <X className="h-5 w-5 text-white" />
            </button>
            <img
              src={results[selectedResult]}
              alt="Full view"
              className="max-h-[85dvh] max-w-full rounded-xl object-contain"
            />
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
