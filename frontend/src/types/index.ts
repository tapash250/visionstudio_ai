export interface User {
  id: string;
  email: string;
  name?: string;
  avatar?: string;
  theme: string;
  matureEnabled: boolean;
}

export interface Project {
  id: string;
  title: string;
  description?: string;
  thumbnailUrl?: string;
  sourceUrl?: string;
  resultUrl?: string;
  type: 'GENERATION' | 'EDIT' | 'ANIMATION' | 'BATCH';
  status: 'DRAFT' | 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';
  isPublic: boolean;
  isMature: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface GenerationJob {
  id: string;
  prompt: string;
  negativePrompt?: string;
  enhancedPrompt?: string;
  model: string;
  style?: string;
  aspectRatio: string;
  seed?: number;
  steps: number;
  cfgScale: number;
  batchSize: number;
  resultUrls: string[];
  status: string;
  error?: string;
}

export interface StylePreset {
  id: string;
  name: string;
  label: string;
  category: string;
  description?: string;
  thumbnailUrl?: string;
  colorAccent?: string;
  isMature: boolean;
}

export interface EditJob {
  id: string;
  sourceUrl: string;
  operation: string;
  prompt?: string;
  strength: number;
  preserveFace: boolean;
  resultUrl?: string;
  status: string;
}

export interface AnimationJob {
  id: string;
  sourceUrl: string;
  animationType: string;
  duration: number;
  fps: number;
  resultUrl?: string;
  previewUrl?: string;
  format: string;
  status: string;
}

export type AspectRatio = '1:1' | '16:9' | '9:16' | '4:5' | '3:2';

export type EditOperation =
  | 'INPAINT'
  | 'OUTPAINT'
  | 'REMOVE_OBJECT'
  | 'REMOVE_OUTFIT'
  | 'REMOVE_BACKGROUND'
  | 'REPLACE_BACKGROUND'
  | 'FACE_RESTORE'
  | 'FACE_ENHANCE'
  | 'SKIN_RETOUCH'
  | 'LIGHTING'
  | 'SHARPEN'
  | 'REPLACE_CLOTHING'
  | 'CHANGE_STYLE'
  | 'COLOR_SWAP'
  | 'ADD_ACCESSORY';

export type AnimationType =
  | 'face_blink'
  | 'smile'
  | 'head_motion'
  | 'talking'
  | 'lip_sync'
  | 'zoom'
  | 'parallax'
  | 'dance'
  | 'pan'
  | 'bg_motion';
