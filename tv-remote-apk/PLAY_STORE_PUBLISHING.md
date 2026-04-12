# Google Play Store Publishing Guide

Step-by-step guide for publishing the TV Remote app on Google Play Store.  
Reflects current policy as of 2025/2026. Read completely before starting — some steps have long lead times.

---

## Overview & Timeline

| Step | Estimated time |
|------|---------------|
| Create Play Console account + identity verification | 2–5 days |
| Code changes for release | 1–2 hours |
| Build release AAB + store assets | 2–4 hours |
| Internal testing | Same day |
| Closed testing (mandatory for personal accounts) | 14+ days (12 testers required) |
| Production review | 3–7 business days |
| **Total (personal account)** | **~4 weeks** |
| **Total (organization account)** | **~1 week** |

---

## Step 1 — Create a Google Play Console Account

1. Go to [play.google.com/console](https://play.google.com/console) and sign in with a Google account.
2. Pay the one-time **$25 USD** registration fee (non-refundable).
3. Choose account type:

   **Personal account** — individual developer
   - Requires: government-issued photo ID + proof of address + credit card matching your legal name
   - Subject to the **12-tester / 14-day closed testing requirement** before production (see Step 8)
   - Verification takes a few days

   **Organization account** — business or LLC
   - Requires: D-U-N-S number (free from [dnb.com](https://www.dnb.com/get-a-duns-number.html), takes 1–5 days), verified business website
   - **Exempt from the 12-tester requirement** — can go to production immediately after review
   - Recommended if you have a business entity

4. Submit verification documents and wait for approval email.

---

## Step 2 — Required Code Changes Before Release

Several changes are needed before the app is Play Store compliant. Make these before building a release AAB.

### 2a. Switch foreground service type to `connectedDevice`

The current manifest uses `dataSync` which has background time limits in Android 15+. The correct type for a persistent local server + ADB socket is `connectedDevice`.

In `AndroidManifest.xml`, replace:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
...
<service
    android:name=".RemoteService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```
with:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
...
<service
    android:name=".RemoteService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

### 2b. Create a proper TV banner image

The current `tv_banner.xml` is a placeholder vector. Replace it with a real PNG:

- **File:** `app/src/main/res/drawable-xhdpi/tv_banner.png`
- **Dimensions:** exactly **320 × 180 px**
- **Format:** PNG (24-bit, no alpha)
- **Must contain:** the app name as readable text within the image
- **Note:** the vector in `res/drawable/tv_banner.xml` is used by the adaptive icon — keep it; the `tv_banner.png` in `drawable-xhdpi/` is what appears in the TV launcher row

Update `AndroidManifest.xml` if needed to point to the right resource:
```xml
android:banner="@drawable/tv_banner"
```

### 2c. Verify D-pad navigation

Every button in `activity_main.xml` must be focusable and reachable by D-pad.  
The current layout has `android:focusable="true"` on both buttons — verify this works on the TV before submission.

### 2d. Set up release signing

Generate a release keystore (do this **once** — losing it means you cannot update the app):

```bash
keytool -genkey -v \
  -keystore ~/tv-remote-upload-key.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias upload
```

Store credentials outside the project (never commit them):

```properties
# Add to ~/.gradle/gradle.properties
TV_REMOTE_KEYSTORE_PATH=/home/legvan/tv-remote-upload-key.jks
TV_REMOTE_KEY_ALIAS=upload
TV_REMOTE_KEYSTORE_PASSWORD=yourpassword
TV_REMOTE_KEY_PASSWORD=yourpassword
```

Add a `release` signing config in `app/build.gradle.kts`:

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file(providers.gradleProperty("TV_REMOTE_KEYSTORE_PATH").get())
            storePassword = providers.gradleProperty("TV_REMOTE_KEYSTORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("TV_REMOTE_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("TV_REMOTE_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

---

## Step 3 — Build a Release AAB

> **The Play Store does not accept APKs for new app submissions.** You must upload an AAB (Android App Bundle).

```bash
cd tv-remote-apk
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

The AAB is signed with your upload key. Google Play re-signs it with its own app signing key (enrolled automatically on first upload — see Step 5).

---

## Step 4 — Prepare Store Assets

Prepare all of the following before opening Play Console:

### Graphics

| Asset | Dimensions | Format | Notes |
|-------|-----------|--------|-------|
| TV banner | 320 × 180 px | PNG | Must contain app name text |
| TV screenshot | 1280 × 720 px | JPEG or PNG (no alpha) | At least 1 required |
| Phone screenshot | 320–3840 px on short side | JPEG or PNG | At least 2 required (even for TV-only apps) |
| Feature graphic | 1024 × 500 px | JPEG or PNG (no alpha) | Required for all apps |
| App icon | 512 × 512 px | PNG (32-bit with alpha) | Store icon (separate from launcher icon) |

**Tip:** take the TV screenshot directly from the TV using `adb exec-out screencap -p > screenshot.png` while the app is running.

### Text

| Field | Limit | Suggested content |
|-------|-------|------------------|
| App title | 30 chars | "TV Remote — Web Control" |
| Short description | 80 chars | "Control your Android TV from any browser on your home network" |
| Full description | 4,000 chars | Features, setup steps, requirements (ADB over network), supported devices |

### Privacy Policy

You **must** have a privacy policy URL. For a local-network-only app:
- Host a simple page (GitHub Pages, Notion, etc.)
- State that the app: collects no personal data, runs a local HTTP server only accessible on the LAN, uses a local ADB connection (user-enabled) to send media commands, stores only an RSA key pair in the app's private storage
- Explicitly state no data leaves the device or local network

---

## Step 5 — Create the App in Play Console

1. In Play Console: **All apps → Create app**
2. App name, default language, app/game toggle → **App**
3. Free/paid → **Free** (cannot be changed to paid later without a new app)
4. Confirm policies and submit

### Enroll in Play App Signing
On first AAB upload, Play Console will prompt you to enroll in Play App Signing. **Accept.** Google stores the actual signing key; your upload key is only for submitting builds. You can rotate the upload key if it's ever compromised.

---

## Step 6 — Complete Required Declarations

These must be done before any track (even internal testing):

### Content rating (Policy → App content → Content rating)
- Complete the IARC questionnaire
- For this app: no violence, no user-generated content, no location, no personal data collection
- Expected rating: **Everyone / PEGI 3**
- Rating is issued immediately

### Foreground service declaration (Policy → App content → App declarations → Foreground service types)
- Type: **Connected device**
- Description (example):
  > "The app runs a persistent foreground service to maintain a local HTTP server (port 8080) and an ADB socket connection to the device's own ADB daemon (127.0.0.1:5555). This allows any browser on the local network to send remote control commands to the TV. The service is started by the user and displays a persistent notification. The user can stop it at any time from the app's main screen."
- **Demo video required:** record a short screen capture showing the app starting, the notification appearing, a browser connecting, and a button press controlling the TV

### Privacy policy
Enter the URL of your privacy policy page.

### Ads declaration
Declare that the app contains no ads.

---

## Step 7 — Configure the Store Listing

Play Console → **Store presence → Main store listing**:

1. Upload all graphics (icon, feature graphic, screenshots)
2. Fill in title, short description, full description
3. Select category: **Apps → Tools**
4. Add contact email and privacy policy URL
5. Save

---

## Step 8 — Release Tracks

### Internal testing (start here)
- Play Console → **Release → Internal testing → Create new release**
- Upload `app-release.aab`
- Add up to 100 testers by email
- Available within a few hours — **no review gate**
- Test thoroughly on the TV before moving forward

### Closed testing (mandatory for personal accounts created after Nov 2023)

**Personal accounts must:**
1. Create a closed testing track
2. Get **at least 12 testers** to opt in to the test
3. Keep them opted in for **14 continuous days**
4. After 14 days, apply for production access in Play Console

**Finding testers:** Ask friends, post in Android/TV developer communities, or use a service like PrimeTestLab. You need 12 real accounts with Play Store-capable devices.

**Organization accounts** skip this requirement entirely.

### Open testing (optional)
Publicly listed on Play but marked as "Early access". Anyone can join. Useful for broader beta testing before production.

### Production
- Play Console → **Release → Production → Create new release**
- Personal accounts: apply for production access first (button appears after 14-day closed test requirement is met)
- Review takes **3–7 business days** for new apps
- You can set a staged rollout (e.g. 10% → 50% → 100%) to catch issues before full launch

---

## Step 9 — Policy Considerations Specific to This App

### ADB loopback connection
The app connects to `localhost:5555` (the device's own ADB daemon) to inject key events. This is **not prohibited** by Play policy — it requires the user to have explicitly enabled USB debugging (a deliberate, non-default developer setting). Precedent: [Remote ADB Shell](https://play.google.com/store/apps/details?id=com.cgutman.androidremotedebugger) is listed on Play and uses ADB connections.

Risk mitigation:
- Be transparent in the store listing: "requires ADB over network to be enabled"
- Document it clearly in the app's UI and description
- State in the privacy policy that no data leaves the device

### Local HTTP server
Running a local server is not a policy violation. Apps in the Tools category that expose LAN endpoints exist on Play. The key is that the server is local-only and not acting as a proxy to third parties.

### Permissions
Audit your declared permissions — only request what you actually use. Remove any unused permissions before submission. Google's review checks for permission overreach.

---

## Quick Reference Checklist

Before submitting to closed testing:

- [x] Foreground service type changed to `connectedDevice` in manifest
- [x] Real TV banner PNG created (320 × 180 px with text) — AI-generated, in `store-assets/tv_banner_320x180.png`
- [x] D-pad navigation works on actual TV hardware — verified
- [x] Release keystore generated (`~/tv-remote-upload-key.jks`) and credentials in `~/.gradle/gradle.properties`
- [x] Release AAB built and signed — `app/build/outputs/bundle/release/app-release.aab`
- [x] Privacy policy page live — https://legvan.github.io/tv-remote/privacy.html
- [x] App icon updated — AI-generated, PNG mipmaps at all densities
- [x] App UI polished — dark theme, rounded card, circular status dots, proper button styles
- [x] All store assets prepared in `store-assets/`:
      - `icon_512.png` (512×512)
      - `feature_graphic_1024x500.png` (1024×500)
      - `screenshot_tv_1920x1080.png` (1920×1080, real)
      - `screenshot_phone1.png` (720×1383, lifestyle)
      - `screenshot_phone2.png` (1080×2194, real)
      - `tv_banner_320x180.png` (320×180)
- [x] **Foreground service demo videos recorded** — 3 clips in `store-assets/`:
      - `VID_20260408_181606.mp4` — starting the server on the TV (shows foreground notification)
      - `screen-20260408-181708~2.mp4` — phone screen recording of the web remote in use
      - `VID_20260408_181831.mp4` — laptop browser controlling TV (TV reacting in background)
- [x] Store listing complete (all graphics, text, category)
- [x] IARC content rating completed — Everyone / PEGI 3
- [x] Foreground service declaration submitted with demo video
- [x] Ads declaration submitted
- [x] Play Console account verified by Google
- [x] AAB uploaded — versionCode=2, versionName="1.1", targetSdk=35, R8 enabled
- [x] Mapping file (mapping.txt) uploaded alongside AAB
- [x] Internal testing track live — opt-in: https://play.google.com/apps/testing/com.porter.tvremote
- [x] Tester recruitment post live — r/TestersCommunity
- [ ] Reach 12 opted-in testers (currently waiting)
- [ ] 14-day closed testing window complete
- [ ] Apply for production access in Play Console
