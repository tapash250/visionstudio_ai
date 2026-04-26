'use client';

import { useState, useCallback } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { X, ZoomIn, ZoomOut, RotateCcw, Download, Share2 } from 'lucide-react';

interface ImageViewerProps {
  src: string;
  alt?: string;
  isOpen: boolean;
  onClose: () => void;
}

export function ImageViewer({ src, alt = 'Image', isOpen, onClose }: ImageViewerProps) {
  const [scale, setScale] = useState(1);
  const [position, setPosition] = useState({ x: 0, y: 0 });
  const [isDragging, setIsDragging] = useState(false);
  const [dragStart, setDragStart] = useState({ x: 0, y: 0 });

  const handleZoomIn = () => setScale((s) => Math.min(s + 0.5, 4));
  const handleZoomOut = () => setScale((s) => Math.max(s - 0.5, 0.5));
  const handleReset = () => {
    setScale(1);
    setPosition({ x: 0, y: 0 });
  };

  const handleTouchStart = useCallback((e: React.TouchEvent) => {
    if (e.touches.length === 2) {
      // Pinch zoom start
    } else {
      setIsDragging(true);
      setDragStart({
        x: e.touches[0].clientX - position.x,
        y: e.touches[0].clientY - position.y,
      });
    }
  }, [position]);

  const handleTouchMove = useCallback((e: React.TouchEvent) => {
    if (isDragging && e.touches.length === 1) {
      setPosition({
        x: e.touches[0].clientX - dragStart.x,
        y: e.touches[0].clientY - dragStart.y,
      });
    }
  }, [isDragging, dragStart]);

  const handleTouchEnd = () => setIsDragging(false);

  const handleDownload = () => {
    const a = document.createElement('a');
    a.href = src;
    a.download = `visionstudio-${Date.now()}.png`;
    a.target = '_blank';
    a.click();
  };

  const handleShare = async () => {
    if (navigator.share) {
      try {
        await navigator.share({
          title: 'VisionStudio AI Creation',
          text: 'Check out this AI-generated image!',
          url: src,
        });
      } catch {
        // User cancelled
      }
    }
  };

  return (
    <AnimatePresence>
      {isOpen && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          className="fixed inset-0 z-[60] flex items-center justify-center bg-black/95"
          onClick={onClose}
        >
          {/* Toolbar */}
          <div className="absolute top-0 left-0 right-0 flex items-center justify-between p-4">
            <button
              onClick={onClose}
              className="rounded-full bg-white/10 p-2 text-white backdrop-blur hover:bg-white/20"
            >
              <X className="h-5 w-5" />
            </button>
            <div className="flex gap-2">
              <button
                onClick={(e) => { e.stopPropagation(); handleZoomOut(); }}
                className="rounded-full bg-white/10 p-2 text-white backdrop-blur hover:bg-white/20"
              >
                <ZoomOut className="h-5 w-5" />
              </button>
              <button
                onClick={(e) => { e.stopPropagation(); handleReset(); }}
                className="rounded-full bg-white/10 p-2 text-white backdrop-blur hover:bg-white/20"
              >
                <RotateCcw className="h-5 w-5" />
              </button>
              <button
                onClick={(e) => { e.stopPropagation(); handleZoomIn(); }}
                className="rounded-full bg-white/10 p-2 text-white backdrop-blur hover:bg-white/20"
              >
                <ZoomIn className="h-5 w-5" />
              </button>
              <button
                onClick={(e) => { e.stopPropagation(); handleDownload(); }}
                className="rounded-full bg-white/10 p-2 text-white backdrop-blur hover:bg-white/20"
              >
                <Download className="h-5 w-5" />
              </button>
              <button
                onClick={(e) => { e.stopPropagation(); handleShare(); }}
                className="rounded-full bg-white/10 p-2 text-white backdrop-blur hover:bg-white/20"
              >
                <Share2 className="h-5 w-5" />
              </button>
            </div>
          </div>

          {/* Image */}
          <div
            className="flex h-full w-full items-center justify-center overflow-hidden p-4 pt-16"
            onTouchStart={handleTouchStart}
            onTouchMove={handleTouchMove}
            onTouchEnd={handleTouchEnd}
          >
            <motion.img
              src={src}
              alt={alt}
              className="max-h-full max-w-full object-contain"
              style={{
                transform: `translate(${position.x}px, ${position.y}px) scale(${scale})`,
                transition: isDragging ? 'none' : 'transform 0.2s ease-out',
              }}
              onClick={(e) => e.stopPropagation()}
            />
          </div>

          {/* Scale indicator */}
          <div className="absolute bottom-4 left-1/2 -translate-x-1/2 rounded-full bg-white/10 px-3 py-1 text-xs text-white backdrop-blur">
            {Math.round(scale * 100)}%
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
