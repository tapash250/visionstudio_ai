'use client';

import { useState } from 'react';
import { motion } from 'framer-motion';
import { 
  User, Bell, Shield, Moon, Sun, Smartphone, 
  Trash2, LogOut, ChevronRight, KeyRound, Eye, EyeOff,
  Fingerprint, Globe, Zap
} from 'lucide-react';
import { useSession, signOut } from 'next-auth/react';
import { useAuthStore } from '@/stores/authStore';

interface SettingSection {
  title: string;
  items: {
    icon: React.ElementType;
    label: string;
    description?: string;
    action?: () => void;
    toggle?: boolean;
    value?: boolean;
    onToggle?: () => void;
    danger?: boolean;
  }[];
}

export default function SettingsPage() {
  const { data: session } = useSession();
  const { matureMode, setMatureMode, maturePin, setMaturePin } = useAuthStore();
  const [darkMode, setDarkMode] = useState(true);
  const [notifications, setNotifications] = useState(true);
  const [showPinInput, setShowPinInput] = useState(false);
  const [pinInput, setPinInput] = useState('');
  const [showPin, setShowPin] = useState(false);

  const handleMatureToggle = () => {
    if (!matureMode) {
      // Enabling — show PIN setup
      setShowPinInput(true);
    } else {
      // Disabling — require PIN
      setShowPinInput(true);
    }
  };

  const confirmMatureToggle = () => {
    if (!matureMode) {
      // Enable
      if (pinInput.length >= 4) {
        setMaturePin(pinInput);
        setMatureMode(true);
        setShowPinInput(false);
        setPinInput('');
      }
    } else {
      // Disable — verify PIN
      if (pinInput === maturePin) {
        setMatureMode(false);
        setMaturePin(null);
        setShowPinInput(false);
        setPinInput('');
      }
    }
  };

  const sections: SettingSection[] = [
    {
      title: 'Account',
      items: [
        {
          icon: User,
          label: session?.user?.name || 'Profile',
          description: session?.user?.email || 'Not signed in',
        },
        {
          icon: KeyRound,
          label: 'Change Password',
          action: () => {},
        },
      ],
    },
    {
      title: 'Preferences',
      items: [
        {
          icon: darkMode ? Moon : Sun,
          label: 'Dark Mode',
          toggle: true,
          value: darkMode,
          onToggle: () => setDarkMode(!darkMode),
        },
        {
          icon: Bell,
          label: 'Push Notifications',
          toggle: true,
          value: notifications,
          onToggle: () => setNotifications(!notifications),
        },
        {
          icon: Globe,
          label: 'Language',
          description: 'English',
        },
      ],
    },
    {
      title: 'Content',
      items: [
        {
          icon: Shield,
          label: 'Mature Content (18+)',
          description: matureMode ? 'Enabled' : 'Disabled',
          toggle: true,
          value: matureMode,
          onToggle: handleMatureToggle,
        },
        {
          icon: Fingerprint,
          label: 'Biometric Lock',
          toggle: true,
          value: false,
        },
      ],
    },
    {
      title: 'App',
      items: [
        {
          icon: Smartphone,
          label: 'Install App',
          description: 'Add to home screen',
        },
        {
          icon: Zap,
          label: 'Low Data Mode',
          toggle: true,
          value: false,
        },
        {
          icon: Trash2,
          label: 'Clear Cache',
          danger: true,
        },
        {
          icon: LogOut,
          label: 'Sign Out',
          danger: true,
          action: () => signOut(),
        },
      ],
    },
  ];

  return (
    <div className="flex flex-col gap-6 px-4 pb-8">
      {/* Profile Card */}
      <div className="card-mobile flex items-center gap-4">
        <div className="flex h-14 w-14 items-center justify-center rounded-full bg-gradient-to-br from-violet-500 to-fuchsia-500 text-lg font-bold text-white">
          {session?.user?.name?.[0] || session?.user?.email?.[0] || '?'}
        </div>
        <div className="min-w-0 flex-1">
          <p className="truncate font-semibold">{session?.user?.name || 'Guest'}</p>
          <p className="truncate text-xs text-muted-foreground">{session?.user?.email || 'Sign in to sync projects'}</p>
        </div>
      </div>

      {/* Settings Sections */}
      {sections.map((section) => (
        <div key={section.title}>
          <h3 className="mb-2 px-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {section.title}
          </h3>
          <div className="flex flex-col gap-1 rounded-2xl border border-border bg-card p-2">
            {section.items.map((item) => {
              const Icon = item.icon;
              return (
                <button
                  key={item.label}
                  onClick={item.action}
                  className={`flex items-center gap-3 rounded-xl px-3 py-3 text-left transition-colors ${
                    item.danger ? 'hover:bg-destructive/10' : 'hover:bg-secondary'
                  }`}
                >
                  <Icon className={`h-5 w-5 shrink-0 ${item.danger ? 'text-destructive' : 'text-muted-foreground'}`} />
                  <div className="min-w-0 flex-1">
                    <p className={`text-sm font-medium ${item.danger ? 'text-destructive' : ''}`}>{item.label}</p>
                    {item.description && (
                      <p className="text-xs text-muted-foreground">{item.description}</p>
                    )}
                  </div>
                  {item.toggle ? (
                    <div
                      onClick={(e) => { e.stopPropagation(); item.onToggle?.(); }}
                      className={`relative h-6 w-11 shrink-0 cursor-pointer rounded-full transition-colors ${
                        item.value ? 'bg-primary' : 'bg-muted'
                      }`}
                    >
                      <div
                        className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${
                          item.value ? 'translate-x-5.5' : 'translate-x-0.5'
                        }`}
                        style={{ transform: item.value ? 'translateX(22px)' : 'translateX(2px)' }}
                      />
                    </div>
                  ) : (
                    <ChevronRight className="h-4 w-4 shrink-0 text-muted-foreground" />
                  )}
                </button>
              );
            })}
          </div>
        </div>
      ))}

      {/* Mature Content PIN Modal */}
      {showPinInput && (
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          className="fixed inset-0 z-[60] flex items-center justify-center bg-black/80 p-6"
          onClick={() => setShowPinInput(false)}
        >
          <div
            className="w-full max-w-sm rounded-2xl bg-card p-6"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 className="mb-1 text-lg font-bold">
              {matureMode ? 'Disable Mature Content' : 'Enable Mature Content'}
            </h3>
            <p className="mb-4 text-sm text-muted-foreground">
              {matureMode 
                ? 'Enter your PIN to disable mature content access.' 
                : 'Set a 4-digit PIN to secure mature content. You must be 18+.'}
            </p>
            <div className="relative mb-4">
              <input
                type={showPin ? 'text' : 'password'}
                value={pinInput}
                onChange={(e) => setPinInput(e.target.value)}
                placeholder={matureMode ? 'Enter PIN' : 'Create 4-digit PIN'}
                maxLength={6}
                className="input-mobile py-3 text-center text-lg tracking-[0.5em]"
              />
              <button
                onClick={() => setShowPin(!showPin)}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground"
              >
                {showPin ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
            <div className="flex gap-2">
              <button
                onClick={() => setShowPinInput(false)}
                className="btn-secondary flex-1"
              >
                Cancel
              </button>
              <button
                onClick={confirmMatureToggle}
                disabled={pinInput.length < 4}
                className="btn-primary flex-1 disabled:opacity-50"
              >
                {matureMode ? 'Disable' : 'Enable'}
              </button>
            </div>
          </div>
        </motion.div>
      )}

      <p className="text-center text-xs text-muted-foreground/60">
        VisionStudio AI v1.0.0
      </p>
    </div>
  );
}
