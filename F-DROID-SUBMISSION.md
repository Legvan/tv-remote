# F-Droid Submission Guide

Everything prepared automatically by Claude Code. The steps below require your action (GitLab account, git operations on GitHub).

## What was done automatically

- [x] `LICENSE` (MIT) added to repo root and `tv-remote-apk/`
- [x] AdbLib pinned from `master-SNAPSHOT` to commit `d6937951eb` in `app/build.gradle.kts`
- [x] Fastlane metadata created at `fastlane/metadata/android/en-US/` (repo root)
- [x] F-Droid metadata YAML prepared at `tv-remote-apk/fdroid/com.porter.tvremote.yml`
- [x] Changes committed and pushed to GitHub

---

## Step 1 — Push a Git tag for v1.1

F-Droid identifies versions by Git tags. The YAML references `commit: v1.1`.

```bash
cd ~/PROJECTS/tv-remote
git tag v1.1
git push origin v1.1
```

> If you want to tag a specific earlier commit: `git tag v1.1 <commit-hash>`

---

## Step 2 — Create a GitLab account

Go to https://gitlab.com/users/sign_up and register.  
(If you already have one, skip this step.)

---

## Step 3 — Fork fdroiddata

1. Go to https://gitlab.com/fdroid/fdroiddata
2. Click **Fork** (top right)
3. Select your namespace — fork it to your personal account

---

## Step 4 — Clone your fork and add the metadata

```bash
git clone --depth=1 https://gitlab.com/YOUR_GITLAB_USERNAME/fdroiddata ~/fdroiddata
cd ~/fdroiddata
git checkout -b com.porter.tvremote
```

Copy the prepared YAML:
```bash
cp ~/PROJECTS/tv-remote/tv-remote-apk/fdroid/com.porter.tvremote.yml metadata/
```

---

## Step 5 — (Optional) Validate locally

Install fdroidserver via apt (do not use pip — system Python is externally managed):

```bash
sudo apt install fdroidserver
```

The apt version (2.2.1) is old and crashes on fdroiddata's `config.yml` format.
Temporarily move it out of the way before running any `fdroid` command:

```bash
cd ~/fdroiddata
mv config.yml config.yml.bak
fdroid readmeta
fdroid lint com.porter.tvremote
fdroid checkupdates --allow-dirty com.porter.tvremote
mv config.yml.bak config.yml   # restore afterwards
```

Fix any warnings before submitting. Common issues:
- Tag `v1.1` not pushed yet → do Step 1 first
- YAML indentation errors → YAML is space-sensitive, use 2 spaces

> If validation is still awkward, skip this step entirely — it is optional.
> The GitLab CI pipeline runs the same checks automatically when you open the MR.

---

## Step 6 — Commit and open the Merge Request

```bash
cd ~/fdroiddata
git add metadata/com.porter.tvremote.yml
git commit -m "New app: com.porter.tvremote"
git push origin com.porter.tvremote
```

Then go to **your fork** at `https://gitlab.com/YOUR_GITLAB_USERNAME/fdroiddata` — you'll see a yellow banner offering to create an MR. If the banner is gone, go to Merge Requests → New merge request, set source to your fork's `com.porter.tvremote` branch and target to `fdroid/fdroiddata` `master`.

**MR title:** `New app: com.porter.tvremote`  
**Target branch:** `master`

The CI/CD pipeline will run automatically. Wait for it to pass before requesting review.

---

## Step 7 — Monitor the build

**MR submitted 2026-04-12:** https://gitlab.com/fdroid/fdroiddata/-/merge_requests/36373

### MR history

| Date | Event |
|------|-------|
| 2026-04-13 | @linsui review: use `Remote Controller` category, `subdir: app` — fixed |
| 2026-05-15 | @linsui: use full commit hash instead of tag, add `Binaries` + `AllowedAPKSigningKeys` for reproducible build — fixed 2026-06-02 |
| 2026-06-23 | @linsui: move fastlane metadata to the repo root |
| 2026-06-27 | fastlane moved to repo root (`2e39108`), but the MR was never updated |
| 2026-07-21 | MR **closed by @linsui** for inactivity ("feel free to re-open") |
| 2026-07-28 | Released v1.3 / versionCode 4, metadata pointed at `9ea658c`, **MR reopened** |

The build commit must contain `fastlane/` at the repo root — commits before `2e39108` do not.
The published release APK must be built from exactly the commit in `Builds:` or reproducible-build
verification against `Binaries:` will fail. v1.2 violated this (release predated the last source change).

After the MR is merged (typically a few days), check:
- https://monitor.f-droid.org/builds/build — search for `com.porter.tvremote`

Build cycle runs approximately every 24–48 hours. Once a green build appears, the app goes live in the F-Droid repository.

---

## Notes

### AGP 9.x
F-Droid upgraded their build server hardware on 2025-12-30. AGP 9.1.0 should build cleanly. If the build fails on their end, the error log at monitor.f-droid.org will show why — most likely cause would be a missing `signingConfigs` block issue (F-Droid builds unsigned, then signs itself; the current `signingConfigs` block in `build.gradle.kts` reads from Gradle properties that won't exist on their server). If this happens, move the `signingConfig` assignment inside an `if` guard or add `buildFeatures { ... }` per reviewer feedback.

### Future releases
From v1.8 on, GitHub Actions (`.github/workflows/release-apk.yml`) builds, signs and attaches the
release APK, from exactly the tagged commit:

1. Bump `versionCode` and `versionName` in `tv-remote-apk/app/build.gradle.kts`
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
3. Commit, then `git tag vX.Y && git push origin master vX.Y` (the tag must be `v` + `versionName`)
4. The workflow runs `./gradlew assembleRelease` on the tag (ubuntu-24.04, Temurin JDK 21) and checks that
   the tag matches `versionName`, that the APK is signed by the upload key (certificate SHA-256
   `4e2335afa65e6a6f0b42a30ef91f32fcad61c95d8ae153696f3293fdf8cbf563`) and that it equals an unsigned build
   apart from the signature (what F-Droid checks). Then it attaches the APK as `tv-remote-vX.Y.apk`:
   - to the release for that tag, if there is one (its description is left as it is);
   - otherwise to a new **draft** release prefilled with the changelog. Write the notes and publish it:
     F-Droid cannot download the APK of a draft.
   An APK that is already attached is never replaced; the workflow only checks that it matches.
5. F-Droid picks up new tags automatically via `AutoUpdateMode: Version` — no MR needed. It rebuilds the
   tag and publishes the attached APK if its own build is identical apart from the signature.

The Play Store AAB is still built by hand (`./gradlew bundleRelease`, see `tv-remote-apk/PLAY_STORE_PUBLISHING.md`).

**Actions → Release APK → Run workflow** rebuilds a published release (default: the latest) without signing
and compares it with its APK. It uses no secrets and publishes nothing. A pull request that changes the
workflow runs the same check.

#### Signing secrets
Four repository secrets (Settings → Secrets and variables → Actions). The workflow decodes the keystore to a
temporary file, passes the values to Gradle as `ORG_GRADLE_PROJECT_TV_REMOTE_KEYSTORE_PATH`,
`ORG_GRADLE_PROJECT_TV_REMOTE_KEYSTORE_PASSWORD`, `ORG_GRADLE_PROJECT_TV_REMOTE_KEY_ALIAS` and
`ORG_GRADLE_PROJECT_TV_REMOTE_KEY_PASSWORD` (the properties `hasReleaseSigning` in `app/build.gradle.kts`
needs) and deletes the keystore right after the build.

| Secret | Value |
|---|---|
| `TV_REMOTE_KEYSTORE_BASE64` | the upload keystore (`~/tv-remote-upload-key.jks` on mint), base64-encoded |
| `TV_REMOTE_KEYSTORE_PASSWORD` | the keystore password (`TV_REMOTE_KEYSTORE_PASSWORD` in `~/.gradle/gradle.properties`) |
| `TV_REMOTE_KEY_ALIAS` | the key alias (`TV_REMOTE_KEY_ALIAS`) |
| `TV_REMOTE_KEY_PASSWORD` | the key password (`TV_REMOTE_KEY_PASSWORD`) |

With `gh` on the machine that has the keystore (the last three commands prompt for the value):

```bash
base64 -w0 ~/tv-remote-upload-key.jks | gh secret set TV_REMOTE_KEYSTORE_BASE64 -R Legvan/tv-remote
gh secret set TV_REMOTE_KEYSTORE_PASSWORD -R Legvan/tv-remote
gh secret set TV_REMOTE_KEY_ALIAS -R Legvan/tv-remote
gh secret set TV_REMOTE_KEY_PASSWORD -R Legvan/tv-remote
```

Line breaks in the base64 value are ignored. If a secret is missing or wrong, the tag run fails and attaches
nothing: fix the secret, then **Re-run jobs** on that run.

#### Building by hand (fallback)
Build on Linux from the tagged commit (`cd tv-remote-apk && ./gradlew clean assembleRelease`, with the four
properties in `~/.gradle/gradle.properties`) and upload the APK as `tv-remote-vX.Y.apk`. Not on Windows: with
`core.autocrlf=true` the checkout turns the LF line endings of `assets/index.html` into CRLF, so the APK differs
from F-Droid's build and F-Droid will not publish it.

### Slow-queue alternative
If you'd rather not deal with GitLab, file a ticket at:
https://gitlab.com/fdroid/rfp/issues
with the GitHub URL and license. A volunteer will handle the metadata, but expect weeks to months.
