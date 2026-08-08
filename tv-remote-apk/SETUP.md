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

Yatse (phone/tablet)
    → UDP :5600 (`YatseStart-Xbmc`)
        → fixed wake + Kodi launch action through the same AdbController
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
| TV | Network debugging / ADB over network enabled; on Nvidia Shield, the separate USB debugging option is not required |

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

1. Enable **Network debugging / ADB over network** in the TV's Developer Options and
   leave it enabled. On Nvidia Shield, do not enable the separate USB debugging option;
   Network debugging is sufficient and attached USB storage can remain connected.
2. Open the **TV Remote** app from the TV launcher.
3. Press **Start Server**. Despite its label, this starts both the HTTP server and the
   Yatse UDP listener. The service auto-starts on subsequent boots.
4. **A dialog will appear on-screen:** "Allow USB Debugging from this computer?"
   This generic Android label is also used for Network debugging connections. Select
   **Allow** and enable **Always allow** when available.
5. Confirm that the ADB indicator turns green.
6. Optionally, open `http://<TV-IP>:8080` from another device on the same LAN, replacing
   `<TV-IP>` with the TV's address. This HTTP remote is separate from Yatse support.

> The RSA key pair is generated once and stored in the app's private storage.  
> You will not be prompted again unless you clear app data.

### Yatse Remote Starter

There is no Yatse-specific setting in the TV Remote app after completing the first-run
steps above.

1. In Yatse's host settings, select the TV's IP address and enable Wake-on-LAN/Remote
   Starter.
2. Set the Remote Starter UDP port to `5600`.
3. Keep the phone/tablet and TV on the same trusted LAN.
4. Put the TV into normal standby/sleep (not fully powered off).
5. Use Yatse's **Wake on LAN** action.
6. Expected result: the TV wakes and Kodi opens in the foreground.

No computer or external ADB client is needed after installation. Network debugging on
the TV must remain enabled. If HTTP port `8080` is already in use (for example, by
Kodi's web server), the HTTP remote may be unavailable while the Yatse listener on
UDP `5600` continues to work.

The compatibility listener recognizes only the fixed `YatseStart-Xbmc` marker and
does not expose packet-controlled ADB or shell commands. The protocol is
unauthenticated, so keep the APK reachable only from a trusted local network.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| ADB dot stays red | Enable Network debugging / ADB over network, then stop and restart the TV Remote service |
| Buttons do nothing | If the ADB indicator is red, re-enable Network debugging and restart the service |
| Port 8080 unreachable | Another app may already use port 8080; this does not disable the Yatse listener |
| Authorization dialog never appeared | Press Start; if needed, toggle Network debugging off and on, then try again. The dialog may still be labelled "Allow USB Debugging" |
| Yatse does not wake the TV | Confirm the service is running, both devices are on the same LAN, Yatse uses UDP port 5600, and the TV is in standby rather than fully powered off |

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
│   │   │   ├── YatseStarter.kt        ← UDP 5600 compatibility listener
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

## Ports

The HTTP server binds `0.0.0.0:8080`.  
The original Python/Flask server uses port `5052` on the PC — no conflict since they run on different machines.
The Yatse compatibility listener binds UDP port `5600` on the TV.

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
