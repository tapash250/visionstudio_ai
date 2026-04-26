import { create } from 'zustand';
import { persist } from 'zustand/middleware';
import type { User } from '@/types';

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  matureMode: boolean;
  maturePin: string | null;
  setUser: (user: User | null) => void;
  setMatureMode: (enabled: boolean) => void;
  setMaturePin: (pin: string | null) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      user: null,
      isAuthenticated: false,
      matureMode: false,
      maturePin: null,
      setUser: (user) => set({ user, isAuthenticated: !!user }),
      setMatureMode: (enabled) => set({ matureMode: enabled }),
      setMaturePin: (pin) => set({ maturePin: pin }),
      logout: () => set({ user: null, isAuthenticated: false, matureMode: false }),
    }),
    {
      name: 'visionstudio-auth',
      partialize: (state) => ({
        matureMode: state.matureMode,
        maturePin: state.maturePin,
      }),
    }
  )
);
