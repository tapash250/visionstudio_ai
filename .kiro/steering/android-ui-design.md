---
inclusion: fileMatch
fileMatchPattern: "android/**"
---

# VisionStudio Android: UI design direction ("Obsidian Prism")

Every screen must feel modern, stylish and unmistakably VisionStudio, not stock Material.
Use the components in `:core:designsystem` and do not hand-roll Material defaults.

## Signature elements
- **Canvas:** `AuroraBackground`, an Obsidian `#0A0B0E` base with slowly drifting indigo, violet and cyan light pools. Never use flat black screens.
- **Surfaces:** frosted glass (`GlassPanel`, `Modifier.frostedGlass`) with gradient hairline borders. Active or focused elements gain a `glowingBorder`.
- **Accent:** the prism gradient (indigo → violet → cyan) is reserved for primary CTAs (`PrismButton`), active indicators and progress. Use at most one prism CTA per screen.
- **Type:** Space Grotesk for display, titles and labels; JetBrains Mono for technical values (seeds, model IDs, URLs, job IDs). Section headers use the uppercase `eyebrow` style with a gradient rule (`SectionHeader`).
- **Selection:** `PrismSegmentedControl` (glass track, spring-sliding glowing indicator, haptic tick). Do not use Material `TabRow` or `RadioButton`.
- **Inputs:** `GlowTextField`, which glows on focus, uses a mono style for technical fields, and masks and reveals secrets.
- **Status:** `StatusOrb` (idle, working pulse, success, failure) for anything asynchronous.

## Motion and feel
- Spring physics for every positional change (`Spring.DampingRatioLowBouncy` to `MediumBouncy`). Use tweens only for colour, alpha and looping ambient motion.
- Every tap on a tactile control gives haptic feedback (`tactileClickable`, `StudioHaptics`).
- Content enters with a staggered fade and slide. Avoid hard cuts between states (`AnimatedContent`).

## Performance rules (non-negotiable)
- Mark UI state classes `@Immutable`.
- Never allocate Paths, Paints, Brushes or Strokes inside a DrawScope. Build them in `drawWithCache` and move them with `translate` or `rotate` instead of rebuilding them.
- Read animated values inside draw or `graphicsLayer` lambdas so that animation does not trigger recomposition.
