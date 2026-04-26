import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import GeneratePage from '@/app/generate/page';

vi.mock('@/hooks/useImageGeneration', () => ({
  useImageGeneration: () => ({
    generate: vi.fn(),
    enhancePrompt: vi.fn(),
    error: null,
    progress: 0,
    isGenerating: false,
    results: [],
  }),
}));

describe('GeneratePage', () => {
  it('renders prompt input', () => {
    render(<GeneratePage />);
    expect(screen.getByPlaceholderText(/describe what you want/i)).toBeInTheDocument();
  });

  it('renders style selector', () => {
    render(<GeneratePage />);
    expect(screen.getByText('Style')).toBeInTheDocument();
  });

  it('renders generate button', () => {
    render(<GeneratePage />);
    expect(screen.getByText(/generate image/i)).toBeInTheDocument();
  });
});
