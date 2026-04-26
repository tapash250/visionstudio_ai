'use client';

import { motion } from 'framer-motion';
import { Sparkles } from 'lucide-react';

const suggestions = [
  'A serene Japanese garden with cherry blossoms',
  'Cyberpunk cityscape at night with neon lights',
  'Portrait of a warrior in ornate armor',
  'Underwater coral reef with bioluminescent creatures',
  'Medieval castle on a misty mountain peak',
  'Futuristic spaceship interior with holographic displays',
  'A cozy cabin in a snowy forest at sunset',
  'Elegant ballroom with crystal chandeliers',
];

interface PromptSuggestionsProps {
  onSelect: (prompt: string) => void;
}

export function PromptSuggestions({ onSelect }: PromptSuggestionsProps) {
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        <Sparkles className="h-3 w-3" />
        <span>Try these prompts</span>
      </div>
      <div className="flex flex-wrap gap-2">
        {suggestions.map((suggestion, i) => (
          <motion.button
            key={i}
            initial={{ opacity: 0, y: 10 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ delay: i * 0.05 }}
            onClick={() => onSelect(suggestion)}
            className="rounded-full border border-border bg-card px-3 py-1.5 text-xs text-muted-foreground transition-colors hover:border-primary hover:text-primary"
          >
            {suggestion.length > 40 ? suggestion.slice(0, 40) + '...' : suggestion}
          </motion.button>
        ))}
      </div>
    </div>
  );
}
