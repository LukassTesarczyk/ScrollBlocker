# Reels Blocker (Android)

A small accessibility-service app that detects when Instagram shows the
Reels tab and automatically presses "back" to exit it. Everything else
in Instagram (DMs, feed, profile) works normally.

## What you need before starting

- **Android Studio** (free, from developer.android.com) — this is a real
  Android project, not something you can run from a browser.
- Basic comfort following step-by-step instructions in Android Studio.
  You don't need to know Kotlin to get this running, just to tweak it.
- A USB cable (or wireless debugging) to install the app on your Redmi
  Note 14 Pro+, since this isn't going on the Play Store.
- Your phone with **Developer Options** and **USB debugging** enabled
  (Settings → About phone → tap "MIUI/HyperOS version" 7 times, then
  Settings → Additional settings → Developer options → USB debugging).

## How to build and install

1. Open Android Studio → "Open" → select this `ReelsBlocker` folder.
2. Let Gradle sync (first time will download dependencies — needs internet).
3. Connect your phone via USB, accept the debugging prompt on the phone.
4. Click the green Run ▶ button, select your device.
5. The app installs and opens automatically.

## First-run setup on the phone

1. In the app, tap **"Open Accessibility Settings"** → find "Reels
   Blocker Service" → enable it → confirm the warning dialog.
2. Tap **"Open App Battery Settings"** → set battery usage to **"No
   restrictions"** and turn on **Autostart**. On HyperOS/MIUI, background
   services get killed aggressively otherwise, and the whole thing stops
   working silently after a while.
3. Open Instagram, tap the Reels tab — it should immediately bounce back.

## Keeping detection working (important)

Instagram changes its internal view IDs across app updates, so the
detection in `ReelsAccessibilityService.kt` may stop working after an
Instagram update. When that happens:

1. Enable Developer Options → "Layout Inspector" isn't needed on-device;
   instead use `adb shell uiautomator dump` while Reels is open:
   ```
   adb shell uiautomator dump /sdcard/window_dump.xml
   adb pull /sdcard/window_dump.xml
   ```
2. Open `window_dump.xml` and search for elements with
   `resource-id` or `content-desc` containing "reel" or "clips".
3. Add whatever you find to `RESOURCE_ID_CANDIDATES` or
   `TEXT_CANDIDATES` at the top of `ReelsAccessibilityService.kt`.
4. Re-run the app from Android Studio (Run ▶) to reinstall the update.

## Build bez počítače (jen z telefonu)

Kompilace neběží na telefonu — poběží zdarma na GitHubu. Telefon jen
nahraje kód nahoru a stáhne hotové .apk zpátky. Potřebuješ:

- **Termux** — nainstaluj z F-Droid (ne z Play Store, ta verze je
  zastaralá). F-Droid appku si stáhneš z f-droid.org v prohlížeči.
- Účet na **github.com** (zdarma).

### Postup (jednorázově)

1. Na github.com si v prohlížeči na telefonu vytvoř nový **prázdný**
   repozitář (např. `reels-blocker`), veřejný nebo soukromý — bez
   README, bez .gitignore.
2. V Termuxu:
   ```
   pkg update && pkg install git unzip -y
   ```
3. Stáhni si tenhle zip (odkaz z chatu) do složky Stažené soubory,
   pak v Termuxu:
   ```
   termux-setup-storage
   cd ~
   unzip /sdcard/Download/ReelsBlocker.zip
   cd ReelsBlocker
   git init
   git add .
   git commit -m "initial"
   git branch -M main
   git remote add origin https://github.com/TVOJE_JMENO/reels-blocker.git
   git push -u origin main
   ```
   Při push tě Git požádá o přihlášení — nepoužívej heslo účtu, ale
   **Personal Access Token** (GitHub → Settings → Developer settings →
   Personal access tokens → generovat s právem `repo`, zkopírovat a
   vložit místo hesla).
4. Jakmile push proběhne, GitHub automaticky spustí build (soubor
   `.github/workflows/build.yml` v repu se o to postará). Sleduj to na
   `github.com/TVOJE_JMENO/reels-blocker/actions`.
5. Po pár minutách klikni na proběhlý běh → dole v sekci "Artifacts" →
   stáhni `reels-blocker-apk` (je to .zip s .apk uvnitř).
6. Rozbal a nainstaluj .apk — telefon tě požádá o povolení "instalace z
   neznámých zdrojů" pro appku, kterou soubor otevíráš (prohlížeč nebo
   Soubory) — povol to jen pro tuhle jednu instalaci.

### Aktualizace kódu (např. oprava resource ID po update Instagramu)

Po prvním nahrání už Termux nepotřebuješ. Stačí přímo na github.com v
mobilním prohlížeči otevřít soubor `ReelsAccessibilityService.kt`,
kliknout na tužku (edit), upravit, commitnout — GitHub Actions build se
spustí automaticky znova a nové .apk si zase stáhneš z Actions.



The app now allows you to watch **one reel per "opening"** (whether you
got there from a friend's DM, the Reels tab, or a profile), and kicks
you back out the moment you swipe/scroll to a second reel in the same
viewing session. Close it and open another reel later, and you get a
fresh "one allowed reel" again.

Note: Instagram doesn't expose whether a reel was opened from a DM vs.
the main feed, so the app can't literally check "was this shared by a
friend". It uses the next best thing — session + scroll counting —
which in practice behaves exactly the way you'd want: friend sends a
reel, you watch it, and the instant you try to swipe to whatever comes
next, you're back out.

## How it works (short version)

Android's Accessibility API lets an app (with the user's explicit
permission) read the on-screen UI structure of other apps and simulate
button presses — that's the same mechanism screen readers and apps like
AppBlock/Opal use. This app only looks at Instagram's screens
(`android:packageNames="com.instagram.android"` in the service config)
and only ever performs one action: a "back" press when it thinks Reels
is on screen. It doesn't read messages, contacts, or anything outside
Instagram, and it doesn't send data anywhere.

## Limitations

- Detection is heuristic, not perfect — it can occasionally misfire or
  miss (e.g. if Instagram A/B-tests a new layout for some accounts).
- Xiaomi/HyperOS battery management can still kill the background
  service even with the settings above; if it stops working, check
  Settings → Apps → Reels Blocker → Battery again after any HyperOS
  update, since those settings sometimes reset.
- This only works on Android. iOS does not allow this kind of
  cross-app UI access without a jailbreak.
