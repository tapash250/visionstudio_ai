'use client';

import { useEffect, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { WifiOff, Wifi } from 'lucide-react';

export function OfflineBanner() {
  const [isOnline, setIsOnline] = useState(true);

  useEffect(() => {
    setIsOnline(navigator.onLine);
    const handleOnline = () => setIsOnline(true);
    const handleOffline = () => setIsOnline(false);
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  return (
    <AnimatePresence>
      {!isOnline && (
        <motion.div
          initial={{ y: 50, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={{ y: 50, opacity: 0 }}
          className="fixed bottom-20 left-4 right-4 z-40"
        >
          <div className="flex items-center gap-2 rounded-xl bg-amber-500/90 px-4 py-3 text-sm font-medium text-white shadow-lg backdrop-blur">
            <WifiOff className="h-4 w-4" />
            <span>You're offline. Changes will sync when you reconnect.</span>
          </div>
        </motion.div>
      )}
      {isOnline && (
        <motion.div
          initial={{ y: 50, opacity: 0 }}
          animate={{ y: 0, opacity: 1 }}
          exit={{ y: 50, opacity: 0 }}
          className="fixed bottom-20 left-4 right-4 z-40"
        >
          <div className="flex items-center gap-2 rounded-xl bg-emerald-500/90 px-4 py-3 text-sm font-medium text-white shadow-lg backdrop-blur">
            <Wifi className="h-4 w-4" />
            <span>Back online! Syncing queued tasks...</span>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
