'use client';

import { useState, useRef, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Upload, Wand2, Download, Undo, Redo, Eraser, Paintbrush, Scissors, Sun, Eye, Shirt, Palette, Sparkles, X, ChevronRight } from 'lucide-react';
import { editApi } from '@/lib/api';

interface EditTool {
  id: string;
  label: string;
  icon: React.ElementType;
  category: string;
}

const tools: EditTool[] = [
  { id: 'inpaint', label: 'Inpaint', icon: Paintbrush, category: 'Core' },
  { id: 'remove_object', label: 'Remove Object', icon: Eraser, category: 'Core' },
  { id: 'remove_bg', label: 'Remove BG', icon: Scissors, category: 'Core' },
  { id: 'replace_bg', label: 'Replace BG', icon: Palette, category: 'Core' },
  { id: 'face_restore', label: 'Face Restore', icon: Eye, category: 'Enhance' },
  { id: 'face_enhance', label: 'Face Enhance', icon: Sparkles, category: 'Enhance' },
  { id: 'skin_retouch', label: 'Skin Retouch', icon: Sun, category: 'Enhance' },
  { id: 'replace_clothing', label: 'Change Outfit', icon: Shirt, category: 'Fashion' },
  { id: 'change_style', label: 'Restyle', icon: Wand2, category: 'Fashion' },
  { id: 'color_swap', label: 'Color Swap', icon: Palette, category: 'Fashion' },
];

const categories = ['All', 'Core', 'Enhance', 'Fashion'];

export default function EditPage() {
  const [image, setImage] = useState<string | null>(null);
  const [selectedTool, setSelectedTool] = useState<string>('inpaint');
  const [activeCategory, setActiveCategory] = useState('All');
  const [prompt, setPrompt] = useState('');
  const [isProcessing, setIsProcessing] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [history, setHistory] = useState<string[]>([]);
  const [historyIndex, setHistoryIndex] = useState(-1);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [isDrawing, setIsDrawing] = useState(false);

  const filteredTools = activeCategory === 'All' 
    ? tools 
    : tools.filter(t => t.category === activeCategory);

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    // Preview
    const reader = new FileReader();
    reader.onload = (ev) => {
      const url = ev.target?.result as string;
      setImage(url);
      setResult(null);
      setHistory([url]);
      setHistoryIndex(0);
    };
    reader.readAsDataURL(file);

    // Upload to server
    try {
      const res = await editApi.upload(file);
      // Store server URL if needed
    } catch (err) {
      console.error('Upload failed', err);
    }
  };

  const handleProcess = async () => {
    if (!image) return;
    setIsProcessing(true);

    try {
      const job = await editApi.create({
        sourceUrl: image,
        operation: selectedTool,
        prompt: prompt || undefined,
      });

      // Poll for result
      const interval = setInterval(async () => {
        const status = await editApi.getStatus(job.jobId);
        if (status.status === 'COMPLETED') {
          clearInterval(interval);
          setResult(status.resultUrl || null);
          if (status.resultUrl) {
            setHistory(prev => [...prev.slice(0, historyIndex + 1), status.resultUrl!]);
            setHistoryIndex(prev => prev + 1);
          }
          setIsProcessing(false);
        } else if (status.status === 'FAILED') {
          clearInterval(interval);
          setIsProcessing(false);
        }
      }, 2000);
    } catch (err) {
      setIsProcessing(false);
    }
  };

  const undo = () => {
    if (historyIndex > 0) {
      setHistoryIndex(historyIndex - 1);
      setImage(history[historyIndex - 1]);
    }
  };

  const redo = () => {
    if (historyIndex < history.length - 1) {
      setHistoryIndex(historyIndex + 1);
      setImage(history[historyIndex + 1]);
    }
  };

  // Canvas drawing for mask
  const startDrawing = useCallback((e: React.TouchEvent | React.MouseEvent) => {
    if (!canvasRef.current) return;
    setIsDrawing(true);
    const canvas = canvasRef.current;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.beginPath();
    const rect = canvas.getBoundingClientRect();
    const clientX = 'touches' in e ? e.touches[0].clientX : e.clientX;
    const clientY = 'touches' in e ? e.touches[0].clientY : e.clientY;
    ctx.moveTo(clientX - rect.left, clientY - rect.top);
  }, []);

  const draw = useCallback((e: React.TouchEvent | React.MouseEvent) => {
    if (!isDrawing || !canvasRef.current) return;
    const canvas = canvasRef.current;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    const rect = canvas.getBoundingClientRect();
    const clientX = 'touches' in e ? e.touches[0].clientX : e.clientX;
    const clientY = 'touches' in e ? e.touches[0].clientY : e.clientY;
    ctx.lineTo(clientX - rect.left, clientY - rect.top);
    ctx.strokeStyle = 'rgba(255, 0, 0, 0.5)';
    ctx.lineWidth = 20;
    ctx.lineCap = 'round';
    ctx.stroke();
  }, [isDrawing]);

  const stopDrawing = useCallback(() => {
    setIsDrawing(false);
  }, []);

  return (
    <div className="flex flex-col gap-4 px-4 pb-8">
      {/* Upload or Canvas */}
      {!image ? (
        <button
          onClick={() => fileInputRef.current?.click()}
          className="flex aspect-[4/5] flex-col items-center justify-center gap-3 rounded-2xl border-2 border-dashed border-border bg-card"
        >
          <Upload className="h-10 w-10 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">Tap to upload an image</p>
          <p className="text-xs text-muted-foreground/60">or take a photo</p>
        </button>
      ) : (
        <div className="relative overflow-hidden rounded-2xl bg-card">
          <div className="relative aspect-[4/5]">
            <img
              src={result || image}
              alt="Edit target"
              className="h-full w-full object-contain"
            />
            {/* Drawing canvas overlay for inpainting */}
            {selectedTool === 'inpaint' && (
              <canvas
                ref={canvasRef}
                className="absolute inset-0 h-full w-full touch-none"
                onMouseDown={startDrawing}
                onMouseMove={draw}
                onMouseUp={stopDrawing}
                onMouseLeave={stopDrawing}
                onTouchStart={startDrawing}
                onTouchMove={draw}
                onTouchEnd={stopDrawing}
              />
            )}
          </div>

          {/* Toolbar overlay */}
          <div className="absolute top-2 left-2 right-2 flex justify-between">
            <div className="flex gap-1">
              <button onClick={undo} disabled={historyIndex <= 0} className="rounded-lg bg-black/50 p-2 text-white backdrop-blur disabled:opacity-30">
                <Undo className="h-4 w-4" />
              </button>
              <button onClick={redo} disabled={historyIndex >= history.length - 1} className="rounded-lg bg-black/50 p-2 text-white backdrop-blur disabled:opacity-30">
                <Redo className="h-4 w-4" />
              </button>
            </div>
            <button
              onClick={() => { setImage(null); setResult(null); setHistory([]); }}
              className="rounded-lg bg-black/50 p-2 text-white backdrop-blur"
            >
              <X className="h-4 w-4" />
            </button>
          </div>
        </div>
      )}

      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        capture="environment"
        onChange={handleFileUpload}
        className="hidden"
      />

      {/* Tool Categories */}
      {image && (
        <>
          <div className="flex gap-2 overflow-x-auto pb-1 -mx-1 px-1">
            {categories.map(cat => (
              <button
                key={cat}
                onClick={() => setActiveCategory(cat)}
                className={`shrink-0 rounded-full px-4 py-1.5 text-xs font-medium transition-colors ${
                  activeCategory === cat ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground'
                }`}
              >
                {cat}
              </button>
            ))}
          </div>

          {/* Tools Grid */}
          <div className="grid grid-cols-5 gap-2">
            {filteredTools.map((tool) => {
              const Icon = tool.icon;
              return (
                <button
                  key={tool.id}
                  onClick={() => setSelectedTool(tool.id)}
                  className={`flex flex-col items-center gap-1 rounded-xl border p-2 transition-all ${
                    selectedTool === tool.id
                      ? 'border-primary bg-primary/10 text-primary'
                      : 'border-border bg-card text-muted-foreground'
                  }`}
                >
                  <Icon className="h-5 w-5" />
                  <span className="text-[10px] font-medium leading-tight">{tool.label}</span>
                </button>
              );
            })}
          </div>

          {/* Prompt for tool */}
          <div className="card-mobile">
            <label className="mb-1 block text-xs text-muted-foreground">
              {selectedTool === 'inpaint' ? 'Describe what to add/change in masked area' :
               selectedTool === 'replace_bg' ? 'Describe new background' :
               selectedTool === 'replace_clothing' ? 'Describe new outfit' :
               'Optional prompt'}
            </label>
            <input
              type="text"
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              placeholder="e.g., golden hour lighting, professional portrait..."
              className="input-mobile py-2.5 text-sm"
            />
          </div>

          {/* Process Button */}
          <button
            onClick={handleProcess}
            disabled={isProcessing}
            className="btn-primary w-full gap-2"
          >
            {isProcessing ? (
              <>
                <Sparkles className="h-4 w-4 animate-spin" />
                Processing...
              </>
            ) : (
              <>
                <Wand2 className="h-4 w-4" />
                Apply {tools.find(t => t.id === selectedTool)?.label}
              </>
            )}
          </button>
        </>
      )}
    </div>
  );
}
