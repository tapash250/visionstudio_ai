'use client';

import { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { FolderOpen, ImageIcon, Film, Wand2, Trash2, Lock, Globe, MoreVertical, Search, Filter, Grid3X3, List } from 'lucide-react';
import { useProjectStore } from '@/stores/projectStore';
import { projectApi } from '@/lib/api';
import type { Project } from '@/types';

const typeIcons = {
  GENERATION: ImageIcon,
  EDIT: Wand2,
  ANIMATION: Film,
  BATCH: Grid3X3,
};

const statusColors: Record<string, string> = {
  DRAFT: 'bg-muted text-muted-foreground',
  QUEUED: 'bg-amber-500/20 text-amber-400',
  PROCESSING: 'bg-blue-500/20 text-blue-400',
  COMPLETED: 'bg-emerald-500/20 text-emerald-400',
  FAILED: 'bg-destructive/20 text-destructive',
};

export default function ProjectsPage() {
  const { projects, setProjects, deleteProject, filter, setFilter, isLoading, setLoading } = useProjectStore();
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedProject, setSelectedProject] = useState<Project | null>(null);
  const [showMenu, setShowMenu] = useState<string | null>(null);

  useEffect(() => {
    loadProjects();
  }, []);

  const loadProjects = async () => {
    setLoading(true);
    try {
      const data = await projectApi.list();
      setProjects(data.projects);
    } catch (err) {
      console.error('Failed to load projects', err);
    } finally {
      setLoading(false);
    }
  };

  const filteredProjects = projects
    .filter((p) => filter === 'all' || p.type.toLowerCase() === filter)
    .filter((p) => 
      p.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
      p.description?.toLowerCase().includes(searchQuery.toLowerCase())
    );

  const handleDelete = async (id: string) => {
    try {
      await projectApi.delete(id);
      deleteProject(id);
      setShowMenu(null);
    } catch (err) {
      console.error('Delete failed', err);
    }
  };

  return (
    <div className="flex flex-col gap-4 px-4 pb-8">
      {/* Search & Filter */}
      <div className="flex gap-2">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
          <input
            type="text"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search projects..."
            className="input-mobile pl-10 py-2.5 text-sm"
          />
        </div>
        <button
          onClick={() => setViewMode(viewMode === 'grid' ? 'list' : 'grid')}
          className="rounded-xl border border-border bg-card p-3"
        >
          {viewMode === 'grid' ? <List className="h-4 w-4" /> : <Grid3X3 className="h-4 w-4" />}
        </button>
      </div>

      {/* Filter Tabs */}
      <div className="flex gap-2 overflow-x-auto pb-1 -mx-1 px-1">
        {['all', 'generation', 'edit', 'animation'].map((f) => (
          <button
            key={f}
            onClick={() => setFilter(f as any)}
            className={`shrink-0 rounded-full px-4 py-1.5 text-xs font-medium capitalize transition-colors ${
              filter === f ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground'
            }`}
          >
            {f}
          </button>
        ))}
      </div>

      {/* Projects List */}
      {isLoading ? (
        <div className="flex flex-col gap-3">
          {[1, 2, 3].map((i) => (
            <div key={i} className="card-mobile shimmer h-24" />
          ))}
        </div>
      ) : filteredProjects.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <FolderOpen className="mb-3 h-12 w-12 text-muted-foreground/40" />
          <p className="text-sm text-muted-foreground">No projects yet</p>
          <p className="text-xs text-muted-foreground/60">Create your first image to see it here</p>
        </div>
      ) : viewMode === 'grid' ? (
        <div className="grid grid-cols-2 gap-3">
          {filteredProjects.map((project, i) => {
            const Icon = typeIcons[project.type] || ImageIcon;
            return (
              <motion.div
                key={project.id}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: i * 0.05 }}
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
                    <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${statusColors[project.status]}`}>
                      {project.status}
                    </span>
                    {project.isPublic ? (
                      <Globe className="h-3 w-3 text-white/60" />
                    ) : (
                      <Lock className="h-3 w-3 text-white/60" />
                    )}
                  </div>
                </div>
                <button
                  onClick={() => setShowMenu(showMenu === project.id ? null : project.id)}
                  className="absolute top-2 right-2 rounded-lg bg-black/40 p-1.5 text-white opacity-0 backdrop-blur transition-opacity group-hover:opacity-100"
                >
                  <MoreVertical className="h-3.5 w-3.5" />
                </button>

                <AnimatePresence>
                  {showMenu === project.id && (
                    <motion.div
                      initial={{ opacity: 0, scale: 0.9 }}
                      animate={{ opacity: 1, scale: 1 }}
                      exit={{ opacity: 0, scale: 0.9 }}
                      className="absolute top-8 right-2 z-10 w-32 rounded-xl border border-border bg-card p-1 shadow-xl"
                    >
                      <button
                        onClick={() => handleDelete(project.id)}
                        className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-xs text-destructive hover:bg-destructive/10"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                        Delete
                      </button>
                    </motion.div>
                  )}
                </AnimatePresence>
              </motion.div>
            );
          })}
        </div>
      ) : (
        <div className="flex flex-col gap-2">
          {filteredProjects.map((project, i) => {
            const Icon = typeIcons[project.type] || ImageIcon;
            return (
              <motion.div
                key={project.id}
                initial={{ opacity: 0, x: -10 }}
                animate={{ opacity: 1, x: 0 }}
                transition={{ delay: i * 0.05 }}
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
                    <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${statusColors[project.status]}`}>
                      {project.status}
                    </span>
                    <span className="text-[10px] text-muted-foreground">
                      {new Date(project.createdAt).toLocaleDateString()}
                    </span>
                  </div>
                </div>
                <button
                  onClick={() => handleDelete(project.id)}
                  className="rounded-lg p-2 text-muted-foreground hover:text-destructive"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </motion.div>
            );
          })}
        </div>
      )}
    </div>
  );
}
