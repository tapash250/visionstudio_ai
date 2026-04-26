'use client';

import { useState, useEffect, useCallback } from 'react';

interface PushState {
  supported: boolean;
  subscribed: boolean;
  loading: boolean;
}

export function usePushNotifications() {
  const [state, setState] = useState<PushState>({
    supported: false,
    subscribed: false,
    loading: true,
  });

  useEffect(() => {
    if (typeof window === 'undefined') return;

    const supported = 'serviceWorker' in navigator && 'PushManager' in window;
    setState((s) => ({ ...s, supported, loading: false }));

    if (supported) {
      checkSubscription();
    }
  }, []);

  const checkSubscription = async () => {
    try {
      const registration = await navigator.serviceWorker.ready;
      const subscription = await registration.pushManager.getSubscription();
      setState((s) => ({ ...s, subscribed: !!subscription }));
    } catch (e) {
      console.error('Failed to check push subscription', e);
    }
  };

  const subscribe = useCallback(async () => {
    if (!state.supported) return;

    setState((s) => ({ ...s, loading: true }));

    try {
      const registration = await navigator.serviceWorker.ready;

      // Get VAPID public key from server
      const response = await fetch('/api/push/vapid-public-key');
      const { publicKey } = await response.json();

      const subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(publicKey),
      });

      // Send subscription to server
      await fetch('/api/push/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fingerprint: await getDeviceFingerprint(),
          pushToken: JSON.stringify(subscription),
          deviceName: navigator.userAgent,
          deviceType: /Mobile|Android|iPhone/.test(navigator.userAgent) ? 'mobile' : 'desktop',
          os: navigator.platform,
          browser: navigator.userAgent,
        }),
      });

      setState((s) => ({ ...s, subscribed: true, loading: false }));
    } catch (e) {
      console.error('Push subscription failed', e);
      setState((s) => ({ ...s, loading: false }));
    }
  }, [state.supported]);

  const unsubscribe = useCallback(async () => {
    try {
      const registration = await navigator.serviceWorker.ready;
      const subscription = await registration.pushManager.getSubscription();
      if (subscription) {
        await subscription.unsubscribe();
      }
      setState((s) => ({ ...s, subscribed: false }));
    } catch (e) {
      console.error('Push unsubscription failed', e);
    }
  }, []);

  return { ...state, subscribe, unsubscribe };
}

function urlBase64ToUint8Array(base64String: string): Uint8Array {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/\-/g, '+').replace(/_/g, '/');
  const rawData = window.atob(base64);
  const outputArray = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; ++i) {
    outputArray[i] = rawData.charCodeAt(i);
  }
  return outputArray;
}

async function getDeviceFingerprint(): Promise<string> {
  const components = [
    navigator.userAgent,
    navigator.language,
    screen.colorDepth,
    screen.width + 'x' + screen.height,
    new Date().getTimezoneOffset(),
  ];
  const text = components.join('');
  const hashBuffer = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, '0')).join('');
}
