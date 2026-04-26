'use client';

import { motion } from 'framer-motion';
import { ImageIcon, Wand2, Film, Lock, Globe, MoreVertical, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { cn } from '@/lib/utils';
import type { Project } from '@/types';

const typeIcons = {
  GENERATION: ImageIcon,
  EDIT: Wand2,
  ANIMATION: Film,
};

const statusColors: Record<string, string> = {
  DRAFT: 'bg-muted text-muted-foreground',
  QUEUED: 'bg-amber-500/20 text-amber-400',
  PROCESSING: 'bg-blue-500/20 text-blue-400',
  COMPLETED: 'bg-emerald-500/20 text-emerald-400',
  FAILED: 'bg-destructive/20 text-destructive',
};

interface ProjectCardProps {
  project: Project;
  viewMode: 'grid' | 'list';
  onDelete: (id: string) => void;
}

export function ProjectCard({ project, viewMode, onDelete }: ProjectCardProps) {
  const [showMenu, setShowMenu] = useState(false);
  const Icon = typeIcons[project.type] || ImageIcon;

  if (viewMode === 'grid') {
    return (
      <motion.div
        layout
        initial={{ opacity: 0, scale: 0.9 }}
        animate={{ opacity: 1, scale: 1 }}
        exit={{ opacity: 0, scale: 0.9 }}
        className="group relative overflow-hidden rounded-2xl bg-card"
      >
        <div className="aspect-square">
          {project.thumbnailUrl || project.resultUrl ? (
            <img
              src={project.thumbnailUrl || project.resultUrl}
              alt={project.title}
              className="h-full w-full object-cover"
              loading="lazy"
            />
          ) : (
            <div className="flex h-full items-center justify-center bg-secondary">
              <Icon className="h-8 w-8 text-muted-foreground/40" />
            </div>
          )}
        </div>
        <div className="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/80 to-transparent p-3">
          <p className="truncate text-xs font-medium text-white">{project.title}</p>
          <div className="mt-1 flex items-center gap-1.5">
            <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-medium', statusColors[project.status])}>
              {project.status}
            </span>
            {project.isPublic ? <Globe className="h-3 w-3 text-white/60" /> : <Lock className="h-3 w-3 text-white/60" />}
          </div>
        </div>
        <button
          onClick={(e) => { e.stopPropagation(); setShowMenu(!showMenu); }}
          className="absolute top-2 right-2 rounded-lg bg-black/40 p-1.5 text-white opacity-0 backdrop-blur transition-opacity group-hover:opacity-100"
        >
          <MoreVertical className="h-3.5 w-3.5" />
        </button>
        {showMenu && (
          <motion.div
            initial={{ opacity: 0, scale: 0.9 }}
            animate={{ opacity: 1, scale: 1 }}
            className="absolute top-8 right-2 z-10 w-32 rounded-xl border border-border bg-card p-1 shadow-xl"
          >
            <button
              onClick={() => { onDelete(project.id); setShowMenu(false); }}
              className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-xs text-destructive hover:bg-destructive/10"
            >
              <Trash2 className="h-3.5 w-3.5" />
              Delete
            </button>
          </motion.div>
        )}
      </motion.div>
    );
  }

  return (
    <motion.div
      layout
      initial={{ opacity: 0, x: -10 }}
      animate={{ opacity: 1, x: 0 }}
      exit={{ opacity: 0, x: -10 }}
      className="flex items-center gap-3 rounded-xl border border-border bg-card p-3"
    >
      <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-lg bg-secondary">
        {project.thumbnailUrl ? (
          <img src={project.thumbnailUrl} alt="" className="h-full w-full rounded-lg object-cover" />
        ) : (
          <Icon className="h-5 w-5 text-muted-foreground" />
        )}
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium">{project.title}</p>
        <div className="flex items-center gap-2">
          <span className={cn('rounded px-1.5 py-0.5 text-[10px] font-medium', statusColors[project.status])}>
            {project.status}
          </span>
          <span className="text-[10px] text-muted-foreground">
            {new Date(project.createdAt).toLocaleDateString()}
          </span>
        </div>
      </div>
      <button
        onClick={() => onDelete(project.id)}
        className="rounded-lg p-2 text-muted-foreground hover:text-destructive"
      >
        <Trash2 className="h-4 w-4" />
      </button>
    </motion.div>
  );
}
