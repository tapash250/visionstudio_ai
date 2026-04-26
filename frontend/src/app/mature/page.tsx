'use client';

import { useState } from 'react';
import { motion } from 'framer-motion';
import { Shield, Lock, Eye, EyeOff, AlertTriangle, Check } from 'lucide-react';
import { useAuthStore } from '@/stores/authStore';
import Link from 'next/link';

export default function MaturePage() {
  const { matureMode, setMatureMode, maturePin, setMaturePin } = useAuthStore();
  const [pin, setPin] = useState('');
  const [showPin, setShowPin] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState('');

  const handleEnable = () => {
    if (!confirmed) {
      setError('You must confirm you are 18+');
      return;
    }
    if (pin.length < 4) {
      setError('PIN must be at least 4 digits');
      return;
    }
    setMaturePin(pin);
    setMatureMode(true);
    setError('');
  };

  const handleDisable = () => {
    if (pin !== maturePin) {
      setError('Incorrect PIN');
      return;
    }
    setMatureMode(false);
    setMaturePin(null);
    setPin('');
    setError('');
  };

  return (
    <div className="flex flex-col gap-6 px-4 pb-8">
      <div className="text-center">
        <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-amber-500/10">
          <Shield className="h-8 w-8 text-amber-500" />
        </div>
        <h1 className="text-2xl font-bold">Mature Content</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          This section contains content intended for adults 18+
        </p>
      </div>

      {!matureMode ? (
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          className="card-mobile space-y-4"
        >
          <div className="rounded-xl bg-amber-500/10 p-4">
            <div className="flex items-start gap-3">
              <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-500" />
              <div>
                <p className="text-sm font-medium text-amber-400">Age Verification Required</p>
                <p className="text-xs text-amber-400/80">
                  By enabling mature content, you confirm that you are at least 18 years old 
                  and consent to viewing adult-oriented material.
                </p>
              </div>
            </div>
          </div>

          <button
            onClick={() => setConfirmed(!confirmed)}
            className="flex items-start gap-3 text-left"
          >
            <div className={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded border ${
              confirmed ? 'border-primary bg-primary' : 'border-border'
            }`}>
              {confirmed && <Check className="h-3.5 w-3.5 text-primary-foreground" />}
            </div>
            <span className="text-sm text-muted-foreground">
              I confirm that I am 18 years of age or older and agree to the{' '}
              <Link href="/terms" className="text-primary hover:underline">Terms of Service</Link>
              {' '}and{' '}
              <Link href="/privacy" className="text-primary hover:underline">Privacy Policy</Link>.
            </span>
          </button>

          <div>
            <label className="mb-1.5 block text-sm font-medium">Create Security PIN</label>
            <div className="relative">
              <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <input
                type={showPin ? 'text' : 'password'}
                value={pin}
                onChange={(e) => setPin(e.target.value)}
                placeholder="Min 4 digits"
                maxLength={6}
                className="input-mobile pl-10 pr-10 text-center tracking-[0.5em]"
              />
              <button
                type="button"
                onClick={() => setShowPin(!showPin)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground"
              >
                {showPin ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">
              This PIN will be required to access mature content
            </p>
          </div>

          {error && (
            <p className="text-sm text-destructive">{error}</p>
          )}

          <button
            onClick={handleEnable}
            className="btn-primary w-full gap-2"
          >
            <Shield className="h-4 w-4" />
            Enable Mature Content
          </button>
        </motion.div>
      ) : (
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          className="card-mobile space-y-4"
        >
          <div className="rounded-xl bg-emerald-500/10 p-4 text-center">
            <Check className="mx-auto mb-2 h-6 w-6 text-emerald-500" />
            <p className="text-sm font-medium text-emerald-400">Mature Content Enabled</p>
            <p className="text-xs text-emerald-400/80">
              You can now access mature style presets and features
            </p>
          </div>

          <div>
            <label className="mb-1.5 block text-sm font-medium">Enter PIN to Disable</label>
            <div className="relative">
              <Lock className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
              <input
                type={showPin ? 'text' : 'password'}
                value={pin}
                onChange={(e) => setPin(e.target.value)}
                placeholder="Enter your PIN"
                maxLength={6}
                className="input-mobile pl-10 pr-10 text-center tracking-[0.5em]"
              />
              <button
                type="button"
                onClick={() => setShowPin(!showPin)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground"
              >
                {showPin ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>

          {error && (
            <p className="text-sm text-destructive">{error}</p>
          )}

          <button
            onClick={handleDisable}
            className="w-full rounded-xl border border-destructive/30 bg-destructive/10 px-6 py-3 text-sm font-semibold text-destructive transition-colors hover:bg-destructive/20"
          >
            Disable Mature Content
          </button>
        </motion.div>
      )}

      <div className="rounded-xl border border-border bg-card p-4">
        <h3 className="mb-2 text-sm font-semibold">Safety Guidelines</h3>
        <ul className="space-y-2 text-xs text-muted-foreground">
          <li className="flex items-start gap-2">
            <span className="text-primary">•</span>
            No minors or age-ambiguous subjects in generated content
          </li>
          <li className="flex items-start gap-2">
            <span className="text-primary">•</span>
            No non-consensual or coercive content
          </li>
          <li className="flex items-start gap-2">
            <span className="text-primary">•</span>
            No illegal content of any kind
          </li>
          <li className="flex items-start gap-2">
            <span className="text-primary">•</span>
            All content is logged for moderation review
          </li>
        </ul>
      </div>
    </div>
  );
}
