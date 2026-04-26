'use client';

import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Bell, BellOff, Check, Loader2 } from 'lucide-react';
import { usePushNotifications } from '@/hooks/usePushNotifications';
import { toast } from '@/components/Toast';

export function PushSubscription() {
  const { supported, subscribed, loading, subscribe, unsubscribe } = usePushNotifications();
  const [showConfirm, setShowConfirm] = useState(false);

  if (!supported) return null;

  const handleToggle = async () => {
    if (subscribed) {
      setShowConfirm(true);
    } else {
      try {
        await subscribe();
        toast('Push notifications enabled!', 'success');
      } catch {
        toast('Failed to enable notifications', 'error');
      }
    }
  };

  const handleUnsubscribe = async () => {
    try {
      await unsubscribe();
      toast('Push notifications disabled', 'info');
    } catch {
      toast('Failed to disable notifications', 'error');
    }
    setShowConfirm(false);
  };

  return (
    <>
      <button
        onClick={handleToggle}
        disabled={loading}
        className="flex w-full items-center justify-between rounded-xl border border-border bg-card px-4 py-3 text-left transition-colors hover:bg-secondary"
      >
        <div className="flex items-center gap-3">
          {subscribed ? (
            <Bell className="h-5 w-5 text-primary" />
          ) : (
            <BellOff className="h-5 w-5 text-muted-foreground" />
          )}
          <div>
            <p className="text-sm font-medium">Push Notifications</p>
            <p className="text-xs text-muted-foreground">
              {subscribed ? 'Enabled' : 'Disabled'} — Get alerts when jobs complete
            </p>
          </div>
        </div>
        {loading ? (
          <Loader2 className="h-4 w-4 animate-spin text-muted-foreground" />
        ) : (
          <div
            className={`relative h-6 w-11 rounded-full transition-colors ${
              subscribed ? 'bg-primary' : 'bg-muted'
            }`}
          >
            <div
              className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${
                subscribed ? 'translate-x-5.5' : 'translate-x-0.5'
              }`}
              style={{ transform: subscribed ? 'translateX(22px)' : 'translateX(2px)' }}
            />
          </div>
        )}
      </button>

      {/* Unsubscribe confirmation */}
      <AnimatePresence>
        {showConfirm && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 z-[60] flex items-center justify-center bg-black/80 p-6"
            onClick={() => setShowConfirm(false)}
          >
            <motion.div
              initial={{ scale: 0.9, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.9, opacity: 0 }}
              className="w-full max-w-sm rounded-2xl bg-card p-6"
              onClick={(e) => e.stopPropagation()}
            >
              <h3 className="mb-2 text-lg font-bold">Disable Notifications?</h3>
              <p className="mb-4 text-sm text-muted-foreground">
                You won't receive alerts when your AI creations are ready.
              </p>
              <div className="flex gap-2">
                <button
                  onClick={() => setShowConfirm(false)}
                  className="btn-secondary flex-1"
                >
                  Keep Enabled
                </button>
                <button
                  onClick={handleUnsubscribe}
                  className="btn-primary flex-1 bg-destructive text-destructive-foreground hover:bg-destructive/90"
                >
                  Disable
                </button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
}
