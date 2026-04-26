import Link from 'next/link';
import { motion } from 'framer-motion';
import { Sparkles, Wand2, Film, ArrowRight, Star, Shield, Zap, Smartphone } from 'lucide-react';

const features = [
  {
    icon: Sparkles,
    title: 'AI Image Generation',
    desc: 'Create stunning images from text with FLUX, SDXL, and more.',
  },
  {
    icon: Wand2,
    title: 'Advanced Editing',
    desc: 'Inpaint, remove objects, change outfits, enhance faces.',
  },
  {
    icon: Film,
    title: 'Image to Animation',
    desc: 'Bring photos to life with motion, lip sync, and effects.',
  },
  {
    icon: Shield,
    title: 'Private & Secure',
    desc: 'Encrypted processing. Your data stays yours.',
  },
  {
    icon: Zap,
    title: 'Blazing Fast',
    desc: 'GPU-accelerated inference. Results in seconds.',
  },
  {
    icon: Smartphone,
    title: 'Native App Feel',
    desc: 'Install as PWA. Works offline. Mobile-first design.',
  },
];

const styles = [
  'Realistic', 'Anime', 'Cinematic', 'Fantasy',
  'Fashion', 'Cartoon', 'Cyberpunk', 'Portrait',
  'Pixel Art', '3D Render', 'Editorial', 'Product',
];

export default function HomePage() {
  return (
    <div className="flex flex-col">
      {/* Hero */}
      <section className="relative flex min-h-[85dvh] flex-col items-center justify-center px-6 text-center">
        <div className="absolute inset-0 bg-[radial-gradient(ellipse_at_center,_var(--tw-gradient-stops))] from-violet-900/20 via-transparent to-transparent" />

        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
          className="relative z-10"
        >
          <div className="mb-6 inline-flex items-center gap-2 rounded-full border border-violet-500/30 bg-violet-500/10 px-4 py-1.5 text-xs font-medium text-violet-300">
            <Star className="h-3 w-3" />
            No subscriptions. No paywalls. All features free.
          </div>

          <h1 className="mb-4 text-4xl font-extrabold leading-tight tracking-tight sm:text-5xl">
            Create Anything
            <br />
            <span className="gradient-text">With AI</span>
          </h1>

          <p className="mx-auto mb-8 max-w-md text-base text-muted-foreground">
            Generate, edit, and animate images with state-of-the-art AI. 
            Mobile-first. Installable. Completely free.
          </p>

          <div className="flex flex-col gap-3 sm:flex-row sm:justify-center">
            <Link href="/generate" className="btn-primary gap-2 text-base">
              <Sparkles className="h-4 w-4" />
              Start Creating
              <ArrowRight className="h-4 w-4" />
            </Link>
            <Link href="/edit" className="btn-secondary gap-2 text-base">
              <Wand2 className="h-4 w-4" />
              Edit Image
            </Link>
          </div>
        </motion.div>

        {/* Style pills */}
        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ delay: 0.4 }}
          className="relative z-10 mt-12 flex flex-wrap justify-center gap-2 px-4"
        >
          {styles.map((style) => (
            <span
              key={style}
              className="rounded-full border border-border bg-card px-3 py-1 text-xs text-muted-foreground"
            >
              {style}
            </span>
          ))}
        </motion.div>
      </section>

      {/* Features */}
      <section className="px-6 py-16">
        <h2 className="mb-8 text-center text-2xl font-bold">Everything You Need</h2>
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {features.map((feature, i) => {
            const Icon = feature.icon;
            return (
              <motion.div
                key={feature.title}
                initial={{ opacity: 0, y: 20 }}
                whileInView={{ opacity: 1, y: 0 }}
                viewport={{ once: true }}
                transition={{ delay: i * 0.1 }}
                className="card-mobile"
              >
                <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10">
                  <Icon className="h-5 w-5 text-primary" />
                </div>
                <h3 className="mb-1 font-semibold">{feature.title}</h3>
                <p className="text-sm text-muted-foreground">{feature.desc}</p>
              </motion.div>
            );
          })}
        </div>
      </section>

      {/* CTA */}
      <section className="px-6 py-16 text-center">
        <div className="rounded-3xl bg-gradient-to-br from-violet-600 to-fuchsia-600 p-8">
          <h2 className="mb-3 text-2xl font-bold text-white">Ready to create?</h2>
          <p className="mb-6 text-sm text-white/80">
            Join thousands of creators using VisionStudio AI.
          </p>
          <Link
            href="/generate"
            className="inline-flex items-center gap-2 rounded-xl bg-white px-6 py-3 text-sm font-bold text-violet-600"
          >
            <Sparkles className="h-4 w-4" />
            Launch App
          </Link>
        </div>
      </section>
    </div>
  );
}
