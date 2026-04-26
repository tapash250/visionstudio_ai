'use client';

import { motion } from 'framer-motion';

interface SkeletonProps {
  className?: string;
  count?: number;
}

export function SkeletonCard({ className = '', count = 1 }: SkeletonProps) {
  return (
    <>
      {Array.from({ length: count }).map((_, i) => (
        <motion.div
          key={i}
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: i * 0.1 }}
          className={`overflow-hidden rounded-2xl bg-card ${className}`}
        >
          <div className="aspect-square shimmer" />
          <div className="space-y-2 p-3">
            <div className="h-4 w-3/4 rounded bg-muted shimmer" />
            <div className="h-3 w-1/2 rounded bg-muted shimmer" />
          </div>
        </motion.div>
      ))}
    </>
  );
}

export function SkeletonText({ lines = 3 }: { lines?: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: lines }).map((_, i) => (
        <div
          key={i}
          className="h-4 rounded bg-muted shimmer"
          style={{ width: `${100 - i * 15}%` }}
        />
      ))}
    </div>
  );
}

export function SkeletonGenerate() {
  return (
    <div className="flex flex-col gap-4 px-4">
      <div className="card-mobile space-y-4">
        <div className="h-24 rounded-xl bg-muted shimmer" />
        <div className="flex gap-2">
          {Array.from({ length: 6 }).map((_, i) => (
            <div key={i} className="h-8 flex-1 rounded-lg bg-muted shimmer" />
          ))}
        </div>
        <div className="h-10 rounded-xl bg-primary/20 shimmer" />
      </div>
      <div className="grid grid-cols-2 gap-3">
        <SkeletonCard count={2} />
      </div>
    </div>
  );
}
