# VisionStudio AI — Mobile-First UI Wireframes

## Design Philosophy
- **Mobile-first**: All screens designed for 390×844 viewport first
- **Bottom navigation**: Native Android app feel
- **Touch-optimized**: Minimum 44×44pt tap targets
- **Gesture-driven**: Pinch zoom, swipe, drag for editing
- **Dark mode default**: OLED-friendly deep blacks

---

## 1. LANDING PAGE

```
┌─────────────────────────────┐
│  [Logo] VisionStudio        │  ← TopBar (fixed, glass)
├─────────────────────────────┤
│                             │
│    ⭐ No subscriptions...   │  ← Badge
│                             │
│   Create Anything           │
│   With AI                   │  ← Hero (gradient text)
│                             │
│   Generate stunning images  │
│   with state-of-the-art AI  │
│                             │
│   [⚡ Start Creating →]     │  ← Primary CTA
│   [✏️ Edit Image]           │  ← Secondary CTA
│                             │
│  Realistic Anime Cinematic  │  ← Style pills (scrollable)
│  Fantasy Fashion Cartoon... │
│                             │
├─────────────────────────────┤
│   Everything You Need       │  ← Features section
│                             │
│ ┌─────┐ ┌─────┐ ┌─────┐   │
│ │ ⚡  │ │ ✏️  │ │ 🎬  │   │  ← Feature cards (grid)
│ │ AI  │ │Adv  │ │Anim │   │
│ │Gen  │ │Edit │ │     │   │
│ └─────┘ └─────┘ └─────┘   │
│                             │
│ ┌─────┐ ┌─────┐ ┌─────┐   │
│ │ 🔒  │ │ ⚡  │ │ 📱  │   │
│ │Priv │ │Fast │ │PWA  │   │
│ └─────┘ └─────┘ └─────┘   │
│                             │
├─────────────────────────────┤
│  ┌─────────────────────┐    │
│  │  Ready to create?   │    │  ← CTA card (gradient bg)
│  │  [⚡ Launch App]    │    │
│  └─────────────────────┘    │
├─────────────────────────────┤
│  ⚡ Gen  ✏️ Edit  🎬 Anim  │  ← BottomNav (fixed)
│  📁 Proj  ⚙️ Set           │
└─────────────────────────────┘
```

---

## 2. GENERATE PAGE

```
┌─────────────────────────────┐
│  [⚡] Generate              │  ← TopBar
├─────────────────────────────┤
│  ┌─────────────────────┐    │
│  │ Describe what you   │    │  ← Prompt textarea
│  │ want to create...   │    │
│  │              [✨][✕]│    │  ← Enhance + Clear
│  └─────────────────────┘    │
│                             │
│  Style                      │
│  ┌──┬──┬──┬──┬──┬──┬──┐   │  ← Horizontal scroll
│  │None│Real│Anime│Cine│...│
│  └──┴──┴──┴──┴──┴──┴──┘   │
│                             │
│  Aspect Ratio               │
│  ┌────┬────┬────┬────┬────┐│
│  │9:16│1:1 │16:9│4:5 │3:2 ││  ← 5 options
│  │Story│Sq  │Wide│Post│Photo│
│  └────┴────┴────┴────┴────┘│
│                             │
│  [▼ Advanced Settings]      │  ← Expandable
│                             │
│  [⚡ Generate Image]        │  ← Full-width button
│                             │
│  ┌─────────────────────┐    │
│  │ Results             │    │  ← Results card
│  │ ┌─────┐ ┌─────┐     │    │
│  │ │     │ │     │     │    │  ← Image grid
│  │ │ IMG │ │ IMG │     │    │
│  │ │     │ │     │     │    │
│  │ └─────┘ └─────┘     │    │
│  └─────────────────────┘    │
├─────────────────────────────┤
│  ⚡ Gen  ✏️ Edit  🎬 Anim  │
│  📁 Proj  ⚙️ Set           │
└─────────────────────────────┘
```

---

## 3. EDIT PAGE

```
┌─────────────────────────────┐
│  [✏️] Edit                  │  ← TopBar
├─────────────────────────────┤
│  ┌─────────────────────┐    │
│  │                     │    │
│  │   [📤 Upload Image] │    │  ← Upload area / Canvas
│  │   or take a photo   │    │
│  │                     │    │
│  └─────────────────────┘    │
│                             │
│  ┌──┬──┬──┬──┬──┐         │
│  │All│Core│Enh│Fash│       │  ← Category tabs
│  └──┴──┴──┴──┴──┘         │
│                             │
│  ┌──┬──┬──┬──┬──┐         │
│  │🖌️ │🧽 │✂️ │🎨 │👕 │       │  ← Tool grid (5 cols)
│  │Inp│Rem│Rem│Rep│Chg│       │
│  └──┴──┴──┴──┴──┘         │
│                             │
│  ┌─────────────────────┐    │
│  │ Optional prompt...  │    │  ← Tool-specific prompt
│  └─────────────────────┘    │
│                             │
│  [✨ Apply Inpaint]         │  ← Process button
├─────────────────────────────┤
│  ⚡ Gen  ✏️ Edit  🎬 Anim  │
│  📁 Proj  ⚙️ Set           │
└─────────────────────────────┘
```

---

## 4. ANIMATE PAGE

```
┌─────────────────────────────┐
│  [🎬] Animate               │  ← TopBar
├─────────────────────────────┤
│  ┌─────────────────────┐    │
│  │                     │    │
│  │  [🎬 Upload Image]  │    │  ← Upload area
│  │   to animate        │    │
│  │                     │    │
│  └─────────────────────┘    │
│                             │
│  Animation Type             │
│  ┌──────────┬──────────┐    │
│  │ 👁️ Blink │ 😊 Smile │    │  ← 2-col grid
│  │ Natural  │ Gentle   │    │
│  ├──────────┼──────────┤    │
│  │ 🔄 Head  │ 🗣️ Talk  │    │
│  │ Movement │ Avatar   │    │
│  ├──────────┼──────────┤    │
│  │ 🎤 Lip   │ 🔍 Zoom  │    │
│  │ Sync     │ Cinematic│    │
│  └──────────┴──────────┘    │
│                             │
│  Duration: [━━━●━━━━] 3s    │  ← Slider
│  FPS:      [━━●━━━━━] 24    │  ← Slider
│                             │
│  Format: [MP4] [WebM] [GIF] │  ← Format selector
│                             │
│  [🎬 Generate Animation]    │
├─────────────────────────────┤
│  ⚡ Gen  ✏️ Edit  🎬 Anim  │
│  📁 Proj  ⚙️ Set           │
└─────────────────────────────┘
```

---

## 5. PROJECTS PAGE

```
┌─────────────────────────────┐
│  [📁] Projects              │  ← TopBar
├─────────────────────────────┤
│  ┌─────────────────────┐    │
│  │ 🔍 Search...     [≡]│    │  ← Search + view toggle
│  └─────────────────────┘    │
│                             │
│  ┌──┬──┬──┬──┐            │
│  │All│Gen│Edi│Ani│          │  ← Filter tabs
│  └──┴──┴──┴──┘            │
│                             │
│  ┌────────┐ ┌────────┐     │
│  │        │ │        │     │  ← Grid view (2 cols)
│  │  IMG   │ │  IMG   │     │
│  │        │ │        │     │
│  │ Title  │ │ Title  │     │
│  │ ● Done │ │ ● Proc │     │  ← Status badge
│  └────────┘ └────────┘     │
│  ┌────────┐ ┌────────┐     │
│  │        │ │        │     │
│  │  IMG   │ │  IMG   │     │
│  │        │ │        │     │
│  │ Title  │ │ Title  │     │
│  └────────┘ └────────┘     │
│                             │
├─────────────────────────────┤
│  ⚡ Gen  ✏️ Edit  🎬 Anim  │
│  📁 Proj  ⚙️ Set           │
└─────────────────────────────┘
```

---

## 6. SETTINGS PAGE

```
┌─────────────────────────────┐
│  [⚙️] Settings              │  ← TopBar
├─────────────────────────────┤
│  ┌─────────────────────┐    │
│  │  [A]                │    │
│  │  Guest              │    │  ← Profile card
│  │  Sign in to sync    │    │
│  └─────────────────────┘    │
│                             │
│  ACCOUNT                    │  ← Section header
│  ┌─────────────────────┐    │
│  │ 👤 Profile          │>   │
│  │ 🔑 Change Password  │>   │
│  └─────────────────────┘    │
│                             │
│  PREFERENCES                │
│  ┌─────────────────────┐    │
│  │ 🌙 Dark Mode     [●]│    │  ← Toggle
│  │ 🔔 Notifications [●]│    │
│  │ 🌍 Language      EN │>   │
│  └─────────────────────┘    │
│                             │
│  CONTENT                    │
│  ┌─────────────────────┐    │
│  │ 🔞 Mature (18+) [○]│    │  ← Toggle (PIN protected)
│  │ 🔒 Biometric    [○]│    │
│  └─────────────────────┘    │
│                             │
│  APP                        │
│  ┌─────────────────────┐    │
│  │ 📱 Install App      │>   │
│  │ ⚡ Low Data     [○] │    │
│  │ 🗑️ Clear Cache      │>   │
│  │ 🚪 Sign Out         │>   │
│  └─────────────────────┘    │
│                             │
│     VisionStudio v1.0.0     │  ← Version
├─────────────────────────────┤
│  ⚡ Gen  ✏️ Edit  🎬 Anim  │
│  📁 Proj  ⚙️ Set           │
└─────────────────────────────┘
```

---

## 7. AUTH FLOW

### Login
```
┌─────────────────────────────┐
│                             │
│      [⚡ Logo]              │
│      Welcome back           │
│      Sign in to continue    │
│                             │
│  ┌────────┐ ┌────────┐     │
│  │ 🌐 G   │ │ 🐙 GH  │     │  ← OAuth buttons
│  └────────┘ └────────┘     │
│      ── or with email ──    │
│                             │
│  ┌─────────────────────┐    │
│  │ 📧 you@example.com  │    │
│  └─────────────────────┘    │
│  ┌─────────────────────┐    │
│  │ 🔒 ••••••••      👁 │    │
│  └─────────────────────┘    │
│                             │
│  [→ Sign In]                │
│                             │
│  Don't have account? Create │
│                             │
└─────────────────────────────┘
```

---

## 8. MATURE CONTENT PIN MODAL

```
┌─────────────────────────────┐
│  ┌─────────────────────┐    │
│  │  Enable Mature      │    │
│  │  Content (18+)      │    │
│  │                     │    │
│  │  Set a 4-digit PIN  │    │
│  │  to secure access   │    │
│  │                     │    │
│  │  ┌───────────────┐  │    │
│  │  │ • • • •    👁 │  │    │  ← PIN input
│  │  └───────────────┘  │    │
│  │                     │    │
│  │  [Cancel] [Enable]  │    │
│  └─────────────────────┘    │
└─────────────────────────────┘
```

---

## 9. OFFLINE STATE

```
┌─────────────────────────────┐
│                             │
│  ┌─────────────────────┐    │
│  │ 📡 You're offline   │    │  ← Banner (fixed, amber)
│  │ Changes will sync   │    │
│  └─────────────────────┘    │
│                             │
│  [Rest of UI is cached]     │
│  [Queued tasks shown]       │
│                             │
├─────────────────────────────┤
│  ⚡ Gen  ✏️ Edit  🎬 Anim  │
│  📁 Proj  ⚙️ Set           │
└─────────────────────────────┘
```

---

## 10. INSTALL PROMPT

```
┌─────────────────────────────┐
│                             │
│  ┌─────────────────────┐    │
│  │ [↓] Install         │    │  ← Floating card (primary bg)
│  │     VisionStudio    │    │
│  │     Add to home     │    │
│  │     [Install] [✕]   │    │
│  └─────────────────────┘    │
│                             │
│  [Content behind]           │
│                             │
├─────────────────────────────┤
│  ⚡ Gen  ✏️ Edit  🎬 Anim  │
│  📁 Proj  ⚙️ Set           │
└─────────────────────────────┘
```

---

## Responsive Breakpoints

| Breakpoint | Width | Layout Changes |
|-----------|-------|---------------|
| Mobile (default) | < 640px | Bottom nav, single column, full-width cards |
| Tablet | 640–1024px | Side nav, 2-col grids, larger canvas |
| Desktop | > 1024px | Side nav, 3-col grids, split-pane editor |

## Touch Targets
- All buttons: min 44×44pt
- Bottom nav items: 56pt height
- Tool grid buttons: 48×48pt
- Sliders: 24pt thumb, 8pt track

## Animation Specs
- Page transitions: 300ms ease-out
- Bottom nav indicator: spring(500, 30)
- Card hover: scale(1.02), 200ms
- Modal: fade + scale from 0.95
- Skeleton shimmer: 1.5s linear infinite
