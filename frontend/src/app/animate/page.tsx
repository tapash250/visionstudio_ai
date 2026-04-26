'use client';

import { useState, useRef } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Upload, Film, Play, Download, Clock, Gauge, Volume2, X, ChevronRight } from 'lucide-react';
import { animationApi } from '@/lib/api';

const animationTypes = [
  { id: 'face_blink', label: 'Blink', icon: '👁️', desc: 'Natural eye blinking' },
  { id: 'smile', label: 'Smile', icon: '😊', desc: 'Gentle smile animation' },
  { id: 'head_motion', label: 'Head Turn', icon: '🔄', desc: 'Subtle head movement' },
  { id: 'talking', label: 'Talking', icon: '🗣️', desc: 'Speaking animation' },
  { id: 'lip_sync', label: 'Lip Sync', icon: '🎤', desc: 'Sync to audio' },
  { id: 'zoom', label: 'Cinematic Zoom', icon: '🔍', desc: 'Slow zoom effect' },
  { id: 'parallax', label: '3D Parallax', icon: '🌊', desc: 'Depth motion' },
  { id: 'dance', label: 'Dance', icon: '💃', desc: 'Character dance' },
  { id: 'pan', label: 'Camera Pan', icon: '📹', desc: 'Smooth pan effect' },
  { id: 'bg_motion', label: 'BG Motion', icon: '🌆', desc: 'Background alive' },
];

const formats = [
  { id: 'mp4', label: 'MP4', desc: 'Best compatibility' },
  { id: 'webm', label: 'WebM', desc: 'Smaller size' },
  { id: 'gif', label: 'GIF', desc: 'Easy sharing' },
];

export default function AnimatePage() {
  const [image, setImage] = useState<string | null>(null);
  const [selectedType, setSelectedType] = useState<string>('face_blink');
  const [duration, setDuration] = useState(3);
  const [fps, setFps] = useState(24);
  const [format, setFormat] = useState('mp4');
  const [isProcessing, setIsProcessing] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [showPreview, setShowPreview] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleUpload = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = (ev) => {
      setImage(ev.target?.result as string);
      setResult(null);
    };
    reader.readAsDataURL(file);
  };

  const handleGenerate = async () => {
    if (!image) return;
    setIsProcessing(true);
    try {
      const job = await animationApi.create({
        sourceUrl: image,
        animationType: selectedType,
        duration,
        fps,
        format,
      });

      const interval = setInterval(async () => {
        const status = await animationApi.getStatus(job.jobId);
        if (status.status === 'COMPLETED') {
          clearInterval(interval);
          setResult(status.resultUrl || null);
          setIsProcessing(false);
        } else if (status.status === 'FAILED') {
          clearInterval(interval);
          setIsProcessing(false);
        }
      }, 3000);
    } catch (err) {
      setIsProcessing(false);
    }
  };

  return (
    <div className="flex flex-col gap-4 px-4 pb-8">
      {/* Upload */}
      {!image ? (
        <button
          onClick={() => fileInputRef.current?.click()}
          className="flex aspect-[4/5] flex-col items-center justify-center gap-3 rounded-2xl border-2 border-dashed border-border bg-card"
        >
          <Film className="h-10 w-10 text-muted-foreground" />
          <p className="text-sm text-muted-foreground">Upload image to animate</p>
        </button>
      ) : (
        <div className="relative overflow-hidden rounded-2xl bg-card">
          <img src={result || image} alt="Animate" className="aspect-[4/5] w-full object-cover" />
          <button
            onClick={() => { setImage(null); setResult(null); }}
            className="absolute top-2 right-2 rounded-lg bg-black/50 p-2 text-white backdrop-blur"
          >
            <X className="h-4 w-4" />
          </button>
          {result && (
            <button
              onClick={() => setShowPreview(true)}
              className="absolute inset-0 flex items-center justify-center bg-black/30"
            >
              <div className="rounded-full bg-white/20 p-4 backdrop-blur">
                <Play className="h-8 w-8 text-white" />
              </div>
            </button>
          )}
        </div>
      )}

      <input ref={fileInputRef} type="file" accept="image/*" onChange={handleUpload} className="hidden" />

      {image && (
        <>
          {/* Animation Types */}
          <div className="card-mobile">
            <p className="mb-3 text-xs font-medium text-muted-foreground">Animation Type</p>
            <div className="grid grid-cols-2 gap-2">
              {animationTypes.map((type) => (
                <button
                  key={type.id}
                  onClick={() => setSelectedType(type.id)}
                  className={`flex items-center gap-3 rounded-xl border p-3 text-left transition-all ${
                    selectedType === type.id
                      ? 'border-primary bg-primary/10'
                      : 'border-border bg-card'
                  }`}
                >
                  <span className="text-xl">{type.icon}</span>
                  <div>
                    <p className={`text-xs font-semibold ${selectedType === type.id ? 'text-primary' : 'text-foreground'}`}>
                      {type.label}
                    </p>
                    <p className="text-[10px] text-muted-foreground">{type.desc}</p>
                  </div>
                </button>
              ))}
            </div>
          </div>

          {/* Settings */}
          <div className="card-mobile space-y-4">
            <div>
              <div className="mb-2 flex items-center justify-between">
                <label className="flex items-center gap-2 text-xs text-muted-foreground">
                  <Clock className="h-3.5 w-3.5" />
                  Duration
                </label>
                <span className="text-xs font-medium">{duration}s</span>
              </div>
              <input
                type="range"
                min={1}
                max={10}
                value={duration}
                onChange={(e) => setDuration(parseInt(e.target.value))}
                className="h-2 w-full appearance-none rounded-full bg-secondary accent-primary"
              />
            </div>

            <div>
              <div className="mb-2 flex items-center justify-between">
                <label className="flex items-center gap-2 text-xs text-muted-foreground">
                  <Gauge className="h-3.5 w-3.5" />
                  Frame Rate
                </label>
                <span className="text-xs font-medium">{fps} fps</span>
              </div>
              <input
                type="range"
                min={12}
                max={60}
                step={12}
                value={fps}
                onChange={(e) => setFps(parseInt(e.target.value))}
                className="h-2 w-full appearance-none rounded-full bg-secondary accent-primary"
              />
            </div>

            <div>
              <label className="mb-2 block text-xs text-muted-foreground">Export Format</label>
              <div className="flex gap-2">
                {formats.map((f) => (
                  <button
                    key={f.id}
                    onClick={() => setFormat(f.id)}
                    className={`flex-1 rounded-xl border py-2 text-center text-xs font-medium transition-all ${
                      format === f.id
                        ? 'border-primary bg-primary/10 text-primary'
                        : 'border-border bg-card text-muted-foreground'
                    }`}
                  >
                    {f.label}
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* Generate */}
          <button
            onClick={handleGenerate}
            disabled={isProcessing}
            className="btn-primary w-full gap-2"
          >
            {isProcessing ? (
              <>
                <Film className="h-4 w-4 animate-spin" />
                Animating...
              </>
            ) : (
              <>
                <Film className="h-4 w-4" />
                Generate Animation
              </>
            )}
          </button>
        </>
      )}

      {/* Video Preview Modal */}
      <AnimatePresence>
        {showPreview && result && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-[60] flex items-center justify-center bg-black/90 p-4"
            onClick={() => setShowPreview(false)}
          >
            <button
              className="absolute right-4 top-4 rounded-full bg-white/10 p-2"
              onClick={() => setShowPreview(false)}
            >
              <X className="h-5 w-5 text-white" />
            </button>
            <video
              src={result}
              controls
              autoPlay
              className="max-h-[80dvh] max-w-full rounded-xl"
              onClick={(e) => e.stopPropagation()}
            />
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
