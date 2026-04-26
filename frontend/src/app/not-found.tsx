import Link from 'next/link';
import { motion } from 'framer-motion';
import { Compass, ArrowLeft } from 'lucide-react';

export default function NotFound() {
  return (
    <div className="flex min-h-[60dvh] flex-col items-center justify-center px-6 text-center">
      <motion.div
        initial={{ scale: 0 }}
        animate={{ scale: 1 }}
        className="mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-primary/10"
      >
        <Compass className="h-8 w-8 text-primary" />
      </motion.div>
      <h1 className="mb-2 text-4xl font-bold">404</h1>
      <p className="mb-6 text-sm text-muted-foreground">
        This page doesn't exist or has been moved.
      </p>
      <Link href="/" className="btn-primary gap-2">
        <ArrowLeft className="h-4 w-4" />
        Go Home
      </Link>
    </div>
  );
}
