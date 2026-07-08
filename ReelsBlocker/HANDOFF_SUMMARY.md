# ReelsBlocker v1.27 Handoff Summary

## Current Status
**Version:** v1.27 (versionCode 28)  
**Last Build:** Stable APK on GitHub Actions  
**Branch:** Development complete on `claude/repo-root-instructions-pkfkto`

## What's Been Done in v1.27

### Major Features Added
1. **Screen Classification System** - Measured resource IDs for detecting Instagram screens (FEED, DMS, STORY, REELS, OTHER)
2. **TikTok Blocking** - Basic detection via viewpager + long_press_layout, with 1-video allowance
3. **PIN Lock Protection** - 4-6 digit PIN to prevent accidental disabling of blocking
4. **Home Screen Widget** - Shows blocked count + time spent donut chart
5. **Statistics Tracking** - Per-category time tracking (Reels/Feed/DM/Stories/Other)

### Code Changes
- `TimeStats.kt` - Added TimeCategory.REELS enum
- `ReelsAccessibilityService.kt` - classifyScreen() with screen detection, TikTok handling
- `MainActivity.kt` - PIN dialog logic with EditText 
- `StatsWidgetProvider.kt` - New AppWidgetProvider rendering donut chart
- All `strings.xml` (values, values-cs, values-sk, values-de, values-es, values-fr) - Added PIN and time tracking strings
- Widget layouts (widget_stats.xml, bg_widget.xml) and res configuration

### Version Bump
- versionCode: 27 → 28
- versionName: "1.26" → "1.27"

## Known Issues / Pending Work

### User Feedback from v1.27 Testing
The user tested v1.27 on Xiaomi Redmi Note 14 Pro+ (HyperOS) and flagged these issues:

1. **Widget Design** - Combined widget needs to be split into TWO separate widgets (donut chart + time summary as one; bar chart as separate)
2. **PIN Dialog** - Material Design AlertDialog has outdated look ("looks like old Android"), needs custom styling to match dark app aesthetic
3. **Chart Colors** - Vibrant colors needed (currently not visually distinct enough)
4. **TikTok Issues:**
   - Detection showing as "completely broken"
   - Yellow "not implemented" text should be removed
   - Exit behavior broken (GLOBAL_ACTION_BACK sends to wrong screen)
   - Should go to Inbox screen on exit, not arbitrary back
5. **Instagram Location Detection** - Debug badge needs to show current screen (Inbox/Profile/Videos/Camera detection)
6. **Graph Reset** - Daily reset per app (each app gets its own graph, resets at midnight)
7. **Screenshot Reference** - Inbox/DMs screen screenshot provided showing it's misclassified as FEED instead of DMS

### Still Not Implemented (From CLAUDE.md)
- TikTok and Snapchat detection logic (UI only, Run/Stop saved for future)
- Reorder hub via physical drag gesture (only "Move" action sheet)
- Overlay/1-reel logic handles celoobrazovkový Reels without caring how accessed
- Potential issue: swipe-from-side into Reels not detected in all cases

## Next Steps for New Session

### Immediate Priorities (v1.28)
1. **Fix Inbox Classification** - Adjust resource ID matching so Inbox shows as DMS not FEED
2. **Redesign PIN Dialog** - Custom Material Design AlertDialog with:
   - Dark theme background matching app (#121212 or #1A1A1A)
   - Teal accent color (#26A69A) for confirm button
   - White/light text
   - Custom EditText styling (underline, teal cursor)
3. **Split Widget into Two** - Separate APK updates (two different widget types)
4. **Increase Chart Color Vibrancy** - Boost saturation of category colors
5. **Fix TikTok:**
   - Remove yellow status text
   - Fix exit to actually go to Inbox (proper action routing)
   - Verify detection works
6. **Add Instagram Location Detection** - Expand debug badge to show:
   - Inbox, Profile, Videos (IGTV), Camera, Stories
   - Current detection: FEED/DMS/STORY/REELS/OTHER

### Build & Test Process
1. Bump versionCode/versionName in `app/build.gradle`
2. Update `ReelsBlocker/README.md` with v1.X changes (styl existing sections)
3. Commit with message: `v1.X: <description>`
4. Push to branch: `git push -u origin <branch-name>`
5. GitHub Actions builds APK automatically on push
6. Download from Artifacts, install via `adb install` or manual transfer
7. User tests on Xiaomi, provides Debug tab export if issues

### Design System Reference
**Colors (dark theme):**
- Background: #121212
- Cards: #1A1A1A-#181818  
- Accent: #26A69A (teal, active states)
- Destructive: #C62828 (red)
- Text: white primary, #808080-#909090 secondary

**Components:**
- Cards: `@drawable/bg_card` (18dp corners, 18dp padding)
- Buttons: Material flat, 14dp corners (via Widget.App.Button)
- Menu items: `@drawable/bg_menu_item` (14dp ripple)
- Drawers: scrim #99000000 + animation 160-220ms

## Important Conventions (From CLAUDE.md)

1. **Always bump version** - versionCode +1, versionName follows (1.5 → 1.6)
2. **Update README.md** - Add version section at top with change description
3. **No blind changes to Instagram detection** - Always request fresh log via Debug tab before modifying screen detection logic
4. **String resources only** - New UI text → `res/values/strings.xml` (source of truth), translate to cs/sk/de/es/fr minimum
5. **Keep design system** - Dark minimalist aesthetic, established color palette, animation conventions
6. **Don't change without reason:**
   - `gradle.properties` (android.useAndroidX, android.enableJetifier)
   - App icon (adaptive icon system, currently using processed Icon.jpg)
   - Debug keystore (committed, enables stable CI signing)
   - `accessibility_service_config.xml` must have `android:canTakeScreenshot="true"`

## User Testing Setup

**Device:** Xiaomi Redmi Note 14 Pro+ (HyperOS/MIUI)  
**Debug Method:** Integrated Debug tab in app (exports as .txt)  
**No local Android Studio** - All builds via GitHub Actions workflow  

When issues arise:
- Request Debug tab export, not adb logcat (hard for user to access)
- Request fresh logs for Instagram detection logic changes
- Structural changes (performance, threading, lifecycle) don't require logs

## Repository Structure
```
scrollblocker/                      (GitHub repo root)
├── CLAUDE.md                       (This file - project context)
├── ReelsBlocker/                   (Android project root)
│   ├── app/build.gradle            (version info, dependencies)
│   ├── app/src/main/
│   │   ├── java/com/example/reelsblocker/
│   │   │   ├── ReelsAccessibilityService.kt
│   │   │   ├── MainActivity.kt
│   │   │   ├── TimeStats.kt
│   │   │   ├── AppLog.kt
│   │   │   └── ...
│   │   ├── res/
│   │   │   ├── values/strings.xml
│   │   │   ├── values-cs/strings.xml
│   │   │   ├── layout/activity_main.xml
│   │   │   └── ...
│   ├── README.md                   (version history, changes)
│   └── .github/workflows/android.yml
├── .github/workflows/android.yml   (same as above, mirrored)
└── Icon.jpg                        (app icon source, used in v1.14+)
```

## Questions for Next Session

If picking up work on TikTok or Instagram detection:
1. Request fresh Debug tab export showing the specific scenario
2. Compare resource IDs and tree structure against existing patterns
3. Don't assume Instagram's tree hasn't changed since last version
4. Use step-by-step approach: log, analyze, implement, test

---

**Session closed:** 2025-07-08  
**Ready for:** Next developer handoff or user testing phase
