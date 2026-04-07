# TV Remote APK — Setup & Build Guide

Self-hosted web remote that runs **on the TV itself**.  
Anyone on the LAN opens `http://<TV-IP>:8080` and gets the full remote UI — no PC needed.

## How it works

```
Browser (phone/laptop/TV)
    → HTTP :8080
        → Ktor embedded server (foreground APK service)
            → cgutman/AdbLib connecting to 127.0.0.1:5555
                → TV's own ADB daemon (grants "shell" privilege)
                    → input keyevent / am start / dumpsys
```

The app runs a Ktor CIO HTTP server inside a foreground `Service`.  
For input injection it connects back to the TV's own ADB daemon via the ADB wire protocol
(cgutman/AdbLib via JitPack — no native binary, no root, no system signature).

---

## Prerequisites

| Tool | Version |
|------|---------|
| Android Studio | Meerkat 2024.3+ (AGP 9.1.0 / Gradle 8.13) |
| Android SDK | API 34 (install via SDK Manager) |
| TV | ADB over network enabled (Developer Options → USB Debugging / ADB over network) |

---

## Build

### Android Studio (recommended)

1. Open `tv-remote-apk/` as a project in Android Studio.
2. Wait for Gradle sync to complete (downloads deps ~150 MB first time).
3. **Build → Build APK** or use the run button if connected via ADB.

### Command line

```bash
cd tv-remote-apk

# Debug APK
./gradlew assembleDebug

# APK output: app/build/outputs/apk/debug/app-debug.apk
```

---

## Install on TV

```bash
# Via ADB — TV on LAN at 192.168.1.50:5555
adb connect 192.168.1.50:5555
adb -s 192.168.1.50:5555 install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## First-run setup

1. Open the **TV Remote** app from the TV launcher.
2. Press **Start Server** (or it auto-starts on boot after first manual launch).
3. **A dialog will appear on-screen:** "Allow USB Debugging from this computer?"  
   Select **Allow** (optionally tick "Always allow").
4. The ADB dot in the status bar turns green.
5. On any device on the same LAN, open `http://<TV-IP>:8080`.

> The RSA key pair is generated once and stored in the app's private storage.  
> You will not be prompted again unless you clear app data.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| ADB dot stays red | Developer Options → enable USB Debugging / ADB over network |
| Buttons do nothing | Tap the ADB dot — if red, re-enable ADB debugging |
| Port 8080 unreachable | Check TV firewall / router isolation settings |
| "Allow USB Debugging" dialog never appeared | Open the TV Remote app and press Start; the dialog appears after ~2 seconds |

---

## Project structure

```
tv-remote-apk/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── assets/index.html          ← web remote UI (ported from scripts/static/)
│   │   ├── kotlin/com/porter/tvremote/
│   │   │   ├── AdbController.kt       ← AdbLib loopback + all TV commands
│   │   │   ├── HttpServer.kt          ← Ktor CIO server + REST routes
│   │   │   ├── RemoteService.kt       ← foreground service lifecycle
│   │   │   ├── MainActivity.kt        ← TV launcher activity (D-pad navigable)
│   │   │   └── BootReceiver.kt        ← auto-start on boot
│   │   └── res/
│   │       ├── drawable/              ← shape drawables for UI (card, buttons, dots, icon)
│   │       ├── mipmap-{mdpi..xxxhdpi}/ic_launcher.png  ← app icon (all densities)
│   │       └── drawable-xhdpi/tv_banner.png            ← TV launcher banner (320×180)
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
├── store-assets/                      ← Play Store upload assets (ready)
│   ├── icon_512.png                   ← 512×512 app icon
│   ├── feature_graphic_1024x500.png   ← 1024×500 feature graphic
│   ├── screenshot_tv_1920x1080.png    ← TV screenshot (real)
│   ├── screenshot_phone1.png          ← phone screenshot (lifestyle)
│   ├── screenshot_phone2.png          ← phone screenshot (real UI)
│   └── tv_banner_320x180.png          ← TV launcher banner
├── PLAY_STORE_PUBLISHING.md           ← step-by-step publishing guide + checklist
└── SETUP.md                           ← this file
```

---

## Key dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `io.ktor:ktor-server-cio` | 2.3.12 | Embedded HTTP server (coroutine-based, Android-compatible) |
| `com.github.cgutman:AdbLib` (JitPack) | master-SNAPSHOT | Pure-Java ADB wire protocol client — no native binary |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | JSON for REST responses (Kotlin 2.x compatible) |
| AGP | 9.1.0 | Android Gradle Plugin |
| Kotlin | 2.2.10 | Language + built-in via AGP (`builtInKotlin=true`) |

---

## Port

The HTTP server binds `0.0.0.0:8080`.  
The original Python/Flask server uses port `5052` on the PC — no conflict since they run on different machines.

---

## Notes

- **ADB must remain enabled.** If Developer Options resets after a firmware update, re-enable it.  
  The status dot in the web UI shows ADB state at a glance.
- **Auto-start on boot** is handled by `BootReceiver` — no manual launch needed after reboot.
- **After `adb install -r`** the service is killed and must be started manually once; subsequent reboots are automatic.
- The web UI (`index.html`) is identical to the PC version except for a small ADB indicator dot.

## AGP 9.x build notes

These issues were hit during initial setup and are already resolved in the current `build.gradle.kts`:

| Issue | Resolution |
|-------|-----------|
| `Cannot add extension 'kotlin'` | AGP 9.x applies `kotlin.android` automatically (`builtInKotlin=true`) — do not add it explicitly in `app/build.gradle.kts` |
| `Unresolved reference 'kotlinOptions'` | Removed in AGP 9.x — replaced with top-level `kotlin { jvmToolchain(17) }` |
| `srcDirs()` deprecated | Removed the `sourceSets` block entirely — `src/main/kotlin` and `src/main/assets` are defaults |
| Deprecated `gradle.properties` flags | Removed all AGP-injected deprecated flags; kept only `android.useAndroidX=true` and `kotlin.code.style=official` |
