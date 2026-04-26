'use client';

import { usePathname } from 'next/navigation';
import { useSession } from 'next-auth/react';
import Link from 'next/link';
import { motion, AnimatePresence } from 'framer-motion';
import { Menu, User, Zap } from 'lucide-react';
import { useState } from 'react';
import { cn } from '@/lib/utils';

const pageTitles: Record<string, string> = {
  '/generate': 'Generate',
  '/edit': 'Edit',
  '/animate': 'Animate',
  '/projects': 'Projects',
  '/settings': 'Settings',
  '/auth/login': 'Sign In',
  '/auth/register': 'Create Account',
};

export function TopBar() {
  const pathname = usePathname() || '/';
  const { data: session } = useSession();
  const [menuOpen, setMenuOpen] = useState(false);

  const title = pageTitles[pathname] || 'VisionStudio';
  const isHome = pathname === '/';

  return (
    <header className="fixed top-0 left-0 right-0 z-50 safe-top">
      <div className="glass-strong mx-0 flex h-14 items-center justify-between px-4">
        <div className="flex items-center gap-3">
          {isHome ? (
            <Link href="/" className="flex items-center gap-2">
              <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-violet-500 to-fuchsia-500">
                <Zap className="h-4 w-4 text-white" />
              </div>
              <span className="text-lg font-bold gradient-text">VisionStudio</span>
            </Link>
          ) : (
            <>
              <Link href="/" className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-violet-500 to-fuchsia-500">
                <Zap className="h-4 w-4 text-white" />
              </Link>
              <motion.h1
                key={title}
                initial={{ opacity: 0, y: -10 }}
                animate={{ opacity: 1, y: 0 }}
                className="text-lg font-semibold"
              >
                {title}
              </motion.h1>
            </>
          )}
        </div>

        <div className="flex items-center gap-2">
          {session?.user ? (
            <button
              onClick={() => setMenuOpen(!menuOpen)}
              className="relative flex h-9 w-9 items-center justify-center rounded-full bg-secondary"
            >
              {session.user.image ? (
                <img
                  src={session.user.image}
                  alt="Profile"
                  className="h-9 w-9 rounded-full object-cover"
                />
              ) : (
                <User className="h-4 w-4" />
              )}
            </button>
          ) : (
            <Link
              href="/auth/login"
              className="rounded-lg bg-primary px-3 py-1.5 text-xs font-semibold text-primary-foreground"
            >
              Sign In
            </Link>
          )}
        </div>
      </div>

      <AnimatePresence>
        {menuOpen && (
          <motion.div
            initial={{ opacity: 0, y: -10 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -10 }}
            className="absolute right-4 top-16 w-48 rounded-xl border border-border bg-card p-2 shadow-xl"
          >
            <div className="px-3 py-2 text-sm font-medium text-muted-foreground">
              {session?.user?.email}
            </div>
            <hr className="my-1 border-border" />
            <Link href="/settings" className="block rounded-lg px-3 py-2 text-sm hover:bg-secondary">
              Settings
            </Link>
            <button className="w-full rounded-lg px-3 py-2 text-left text-sm text-destructive hover:bg-destructive/10">
              Sign Out
            </button>
          </motion.div>
        )}
      </AnimatePresence>
    </header>
  );
}
