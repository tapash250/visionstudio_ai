'use client';

import { motion } from 'framer-motion';
import { cn } from '@/lib/utils';

interface StyleCardProps {
  id: string;
  label: string;
  color: string;
  isSelected: boolean;
  onClick: () => void;
  isMature?: boolean;
}

export function StyleCard({ id, label, color, isSelected, onClick, isMature }: StyleCardProps) {
  return (
    <motion.button
      whileTap={{ scale: 0.95 }}
      onClick={onClick}
      className={cn(
        'relative flex flex-col items-center gap-2 rounded-xl border p-3 transition-all',
        isSelected
          ? 'border-primary bg-primary/10 ring-2 ring-primary/20'
          : 'border-border bg-card hover:bg-secondary'
      )}
    >
      <div
        className={cn(
          'h-10 w-10 rounded-lg bg-gradient-to-br',
          color
        )}
      />
      <span className={cn(
        'text-xs font-medium',
        isSelected ? 'text-primary' : 'text-muted-foreground'
      )}>
        {label}
      </span>
      {isMature && (
        <span className="absolute -top-1 -right-1 flex h-4 w-4 items-center justify-center rounded-full bg-amber-500 text-[8px] font-bold text-white">
          18+
        </span>
      )}
    </motion.button>
  );
}
