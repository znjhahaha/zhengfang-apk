# Zhengfang App Promo Video Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and deliver a polished 45-second Hyperframes product-launch video for Zhengfang Assistant that demonstrates both its core student workflows and its liquid-glass UI/UX.

**Architecture:** A self-contained `promo-video/` Hyperframes project owns frozen app captures, an original procedural soundtrack, composition HTML/CSS/JS, and QA artifacts. The 45-second master composition is divided into eight timed scene elements, while deterministic timeline tests and Hyperframes checks guard duration, asset availability, layout, and rendered output.

**Tech Stack:** Hyperframes CLI 0.7.109, HTML5/CSS, seekable JavaScript animations, Android Debug Bridge, Node.js 22.17.1, Chrome/Puppeteer, FFmpeg through the Hyperframes render toolchain.

## Global Constraints

- Master output is 1920 x 1080, 30 fps, H.264 MP4, and 42-48 seconds long.
- The product name or another unmistakable product signal appears within four seconds.
- Liquid-glass UI/UX receives a complete scene supported by real interaction footage.
- Courses, grabbing, schedule, and grades each have explicit visual evidence.
- Key Chinese copy remains readable for at least 1.5 seconds and never clips or overlaps.
- No account, credential, Cookie, real grade, personal timetable, school identity, or device identifier appears.
- No runtime network requests are allowed; every visual and audio asset is stored locally.
- The final delivery includes the MP4, editable Hyperframes project, asset ledger, technical metadata, usage instructions, and copyright information.
- The root-level delivery file is `D:/zfapk/zhengfang-app-promo.mp4`.

---

## File Structure

- `promo-video/hyperframes.json`: project metadata and composition defaults.
- `promo-video/index.html`: 45-second master composition and semantic scene markup.
- `promo-video/styles.css`: design tokens, frame layout, glass treatment, typography, and responsive-safe fixed canvas rules.
- `promo-video/timeline.js`: seek-safe scene and micro-interaction animation definitions.
- `promo-video/assets/app/`: sanitized PNG screenshots and MP4 interaction captures.
- `promo-video/assets/brand/`: launcher icon and local font assets when needed.
- `promo-video/assets/audio/`: original music bed and synthesized UI sound effects.
- `promo-video/scripts/build-audio.ps1`: deterministic soundtrack and UI sound generation.
- `promo-video/scripts/validate-project.mjs`: duration, scene, copy, and local-asset contract checks.
- `promo-video/ASSETS.md`: source, license, transformation, and privacy ledger for every media file.
- `promo-video/README.md`: preview, validation, render, and reuse commands.
- `promo-video/snapshots/`: Hyperframes key-frame QA output.
- `promo-video/renders/zhengfang-app-promo.mp4`: project-local rendered master.
- `zhengfang-app-promo.mp4`: root-level final delivery copy.

### Task 1: Bootstrap and verify the Hyperframes toolchain

**Files:**
- Create: `promo-video/hyperframes.json`
- Create: `promo-video/index.html`
- Create: `promo-video/styles.css`
- Create: `promo-video/timeline.js`

**Interfaces:**
- Consumes: Node.js 22.17.1 and Hyperframes CLI 0.7.109.
- Produces: a landscape project attributed to `product-launch-video`, renderable by all later tasks.

- [ ] **Step 1: Install the current Hyperframes core and product workflow skills**

Run:

```powershell
cmd.exe /c npx --yes hyperframes skills update product-launch-video
```

Expected: the command reports the core Hyperframes skills and `product-launch-video` installed or already current.

- [ ] **Step 2: Read the installed router and product-launch instructions completely**

Run `Get-ChildItem -Recurse -Filter SKILL.md` under the installed project skill directory, locate `hyperframes` and `product-launch-video`, then read both with `Get-Content -Raw` before authoring.

- [ ] **Step 3: Scaffold the composition**

Run:

```powershell
cmd.exe /c npx --yes hyperframes init promo-video --example blank --resolution landscape --skill product-launch-video --non-interactive
```

Expected: `promo-video/index.html` and `promo-video/hyperframes.json` exist and declare a 1920 x 1080 composition.

- [ ] **Step 4: Run dependency diagnostics**

Run:

```powershell
cmd.exe /c npx --yes hyperframes doctor promo-video
```

Expected: Node and Chrome pass. If FFmpeg is absent, install or place the Hyperframes-supported executable in a workspace-local tool directory and rerun until the render dependency passes.

- [ ] **Step 5: Commit the working scaffold**

```powershell
git add promo-video/hyperframes.json promo-video/index.html promo-video/styles.css promo-video/timeline.js
git commit -m "chore: scaffold promo video"
```

### Task 2: Capture and sanitize current app visuals

**Files:**
- Create: `promo-video/assets/app/ui-home.png`
- Create: `promo-video/assets/app/ui-courses.png`
- Create: `promo-video/assets/app/ui-course-filter.mp4`
- Create: `promo-video/assets/app/ui-liquid-nav.mp4`
- Create: `promo-video/assets/app/ui-grab.png`
- Create: `promo-video/assets/app/ui-schedule.png`
- Create: `promo-video/assets/app/ui-grades-picker.mp4`
- Create: `promo-video/assets/app/ui-grades.png`
- Create: `promo-video/assets/brand/app-icon.png`
- Create: `promo-video/ASSETS.md`

**Interfaces:**
- Consumes: emulator `127.0.0.1:16416`, package `com.tyust.course`, and the debug APK at `app/build/outputs/apk/debug/app-debug.apk`.
- Produces: local media files with no private data, referenced by exact relative paths from `index.html`.

- [ ] **Step 1: Confirm the installed build and foreground activity**

Run ADB package and activity inspection. Install the existing debug APK only if the installed package is stale. Do not clear app data unless the current state cannot be sanitized without it.

- [ ] **Step 2: Capture the liquid navigation interaction**

Record a 4-6 second sequence that moves across two or three bottom tabs and visibly shows lens sliding, spring settling, refraction, and touch response. Crop no closer than the complete phone viewport so the interaction retains context.

- [ ] **Step 3: Capture course discovery and filtering**

Record a 4-6 second search/filter interaction and capture a static course list frame showing course name, teacher, category, and capacity. Replace or conceal any personally identifying course enrollment data before it reaches `assets/app/`.

- [ ] **Step 4: Capture grabbing, schedule, and grades**

Capture one clean feature frame each for grabbing and schedule, plus one grades frame and a 4-6 second semester-picker interaction. Prefer empty or synthetic states where real data would be identifiable.

- [ ] **Step 5: Copy the highest-resolution launcher icon**

Use `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` as the input, render or copy it to `promo-video/assets/brand/app-icon.png`, and verify transparency and edge quality.

- [ ] **Step 6: Write and verify the asset ledger**

For every asset record: relative path, source, capture date, transformation, privacy review, and license. Mark emulator captures and synthesized assets as project-created; mark repository assets with the project license.

- [ ] **Step 7: Commit the sanitized asset set**

```powershell
git add promo-video/assets promo-video/ASSETS.md
git commit -m "assets: add sanitized promo captures"
```

### Task 3: Create the visual system and original audio bed

**Files:**
- Modify: `promo-video/styles.css`
- Create: `promo-video/scripts/build-audio.ps1`
- Create: `promo-video/assets/audio/ambient-bed.wav`
- Create: `promo-video/assets/audio/glass-hit.wav`
- Create: `promo-video/assets/audio/ui-tick.wav`
- Modify: `promo-video/ASSETS.md`

**Interfaces:**
- Consumes: frozen app captures and app palette sampled from the current UI.
- Produces: CSS classes `.scene`, `.glass`, `.device`, `.copy`, `.feature-chip`, plus 45-second original audio assets.

- [ ] **Step 1: Define fixed video design tokens**

Create CSS custom properties for a cold white base, ice blue, cyan, coral red, warm yellow, near-black type, three glass opacity levels, 8/16/24/40/64 pixel spacing, and restrained shadow and blur values. Use a fixed 1920 x 1080 stage; do not scale type with viewport width.

- [ ] **Step 2: Implement the liquid-glass camera treatment**

Build `.glass` from translucent fills, backdrop blur where supported, a one-pixel luminous border, inner highlight, soft shadow, and a subtle chromatic rim. Use masks and moving highlights only to reveal hierarchy; never place every text block inside a glass card.

- [ ] **Step 3: Implement device and typography primitives**

Create an unframed device crop with stable aspect ratio, high-resolution media rendering, and overflow containment. Use a local Chinese system font stack and lock title, subtitle, and annotation sizes so the longest copy fits at 1920 x 1080.

- [ ] **Step 4: Generate the soundtrack and UI effects**

Use deterministic FFmpeg audio sources and filters in `build-audio.ps1` to create a 45-second ambient electronic bed, a short glass impact, and a quiet UI tick. The bed must leave headroom for effects and must not use third-party samples.

- [ ] **Step 5: Inspect the generated waveforms**

Verify all WAV files open, match the expected duration, remain below 0 dBFS, and contain non-silent audio. Record generation method and project-created copyright status in `ASSETS.md`.

- [ ] **Step 6: Commit the visual and audio system**

```powershell
git add promo-video/styles.css promo-video/scripts/build-audio.ps1 promo-video/assets/audio promo-video/ASSETS.md
git commit -m "feat: add promo visual and audio system"
```

### Task 4: Author the 45-second seekable composition

**Files:**
- Modify: `promo-video/index.html`
- Modify: `promo-video/timeline.js`
- Create: `promo-video/scripts/validate-project.mjs`

**Interfaces:**
- Consumes: `.scene`, `.glass`, `.device`, `.copy`, `.feature-chip` and all frozen media assets.
- Produces: composition `zhengfang-promo`, duration 45 seconds, with scene starts at 0, 4, 10, 17, 25, 32, 38, and 42 seconds.

- [ ] **Step 1: Write the failing composition-contract validator**

The validator reads `index.html` and asserts: root width 1920, height 1080, fps 30, duration 45, exactly eight named scenes, no `http://` or `https://` asset URLs, and the required copy strings `正方教务助手`, `真实折射`, `立即 · 定时 · 捡漏`, `课表、地点、时间，一眼掌握。`, and `成绩、GPA、考试安排，一处查看。`.

- [ ] **Step 2: Run the validator and confirm failure**

Run:

```powershell
node promo-video/scripts/validate-project.mjs
```

Expected: non-zero exit describing missing scene or copy contracts.

- [ ] **Step 3: Add semantic scene markup and media timing**

Author eight scene elements covering opening, liquid-glass UI/UX, course discovery, grabbing, schedule, grades/exams, trust, and closing. Each scene declares an explicit `data-start`, `data-duration`, and track index. Add music and UI effects as local audio elements with explicit timing and volume.

- [ ] **Step 4: Add seek-safe motion**

Implement a paused timeline registered under `window.__timelines.zhengfangPromo`. Animate only opacity, transform, masks, blur, and highlight positions. Include the lens glide and tab spring, feature-chip cadence, schedule block stagger, semester-picker snap, and final logo settle.

- [ ] **Step 5: Run the contract validator until it passes**

Run:

```powershell
node promo-video/scripts/validate-project.mjs
```

Expected: `PASS: promo composition contract` and exit code 0.

- [ ] **Step 6: Commit the complete composition**

```powershell
git add promo-video/index.html promo-video/timeline.js promo-video/scripts/validate-project.mjs
git commit -m "feat: compose 45 second app promo"
```

### Task 5: Validate, snapshot, and render the master

**Files:**
- Create: `promo-video/snapshots/*.png`
- Create: `promo-video/renders/zhengfang-app-promo.mp4`
- Create: `zhengfang-app-promo.mp4`

**Interfaces:**
- Consumes: validated Hyperframes composition `zhengfang-promo`.
- Produces: checked snapshots and the final H.264 delivery.

- [ ] **Step 1: Run Hyperframes static and runtime gates**

```powershell
cmd.exe /c npx --yes hyperframes lint promo-video
cmd.exe /c npx --yes hyperframes check promo-video --at 1.5,6.5,12.5,20.5,28.5,35,40,43.5 --at-transitions --snapshots --strict
```

Expected: no errors, no actionable overlap or contrast warnings, and readable snapshots.

- [ ] **Step 2: Capture exact storyboard checkpoints**

```powershell
cmd.exe /c npx --yes hyperframes snapshot promo-video --at 1.5,6.5,12.5,20.5,28.5,35,40,43.5 --output promo-video/snapshots
```

Expected: eight nonblank 1920 x 1080 PNG files covering every narrative beat.

- [ ] **Step 3: Visually inspect all checkpoints**

Open the snapshots and reject any frame with clipped Chinese copy, poor hierarchy, missing media, privacy leakage, muddy glass contrast, or accidental overlap. Correct the composition and repeat Steps 1-3 until all checkpoints pass.

- [ ] **Step 4: Render the high-quality master**

```powershell
cmd.exe /c npx --yes hyperframes render promo-video --output promo-video/renders/zhengfang-app-promo.mp4 --fps 30 --quality high --strict --video-frame-format png --skill product-launch-video
```

Expected: a playable H.264 MP4 between 42 and 48 seconds, with 1920 x 1080 video and an audio stream.

- [ ] **Step 5: Copy the approved master to the workspace root**

```powershell
Copy-Item -LiteralPath promo-video/renders/zhengfang-app-promo.mp4 -Destination zhengfang-app-promo.mp4 -Force
```

- [ ] **Step 6: Commit reproducible source and QA artifacts**

Do not commit duplicate root-level delivery unless repository policy allows binary releases. Commit the editable source, snapshots, and project-local master only if their sizes fit repository policy.

### Task 6: Final technical and editorial handoff

**Files:**
- Create: `promo-video/README.md`
- Modify: `promo-video/ASSETS.md`

**Interfaces:**
- Consumes: final master and completed QA artifacts.
- Produces: a self-contained handoff with verified commands and rights information.

- [ ] **Step 1: Inspect output metadata**

Use the available FFprobe binary to record duration, dimensions, frame rate, video codec, audio codec, sample rate, channel count, and file size. Verify duration is 42-48 seconds, dimensions are 1920 x 1080, and frame rate is 30 fps.

- [ ] **Step 2: Perform full-playback review**

Watch the final video from beginning to end with sound. Check narrative flow, music level, scene cuts, UI legibility, final hold, and absence of sensitive information. Also inspect opening, middle, and closing frames with a pixel-level image viewer.

- [ ] **Step 3: Write the usage and reproduction guide**

Document exact `preview`, `check`, `snapshot`, and `render` commands, project structure, final output location, font fallback behavior, audio generation, known limitations, and how to change copy without breaking timing.

- [ ] **Step 4: Finalize rights and attribution information**

State that Hyperframes is Apache-2.0, the app repository is GPL-3.0, emulator captures derive from the app, the soundtrack and effects were procedurally created for this video, and no third-party stock media is included.

- [ ] **Step 5: Run final verification**

```powershell
node promo-video/scripts/validate-project.mjs
cmd.exe /c npx --yes hyperframes check promo-video --at-transitions --strict
```

Expected: both commands exit 0. Confirm `D:/zfapk/zhengfang-app-promo.mp4` exists and is non-empty.

- [ ] **Step 6: Commit final documentation**

```powershell
git add promo-video/README.md promo-video/ASSETS.md
git commit -m "docs: add promo video handoff"
```
