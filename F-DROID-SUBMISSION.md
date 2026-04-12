# F-Droid Submission Guide

Everything prepared automatically by Claude Code. The steps below require your action (GitLab account, git operations on GitHub).

## What was done automatically

- [x] `LICENSE` (MIT) added to repo root and `tv-remote-apk/`
- [x] AdbLib pinned from `master-SNAPSHOT` to commit `d6937951eb` in `app/build.gradle.kts`
- [x] Fastlane metadata created at `tv-remote-apk/fastlane/metadata/android/en-US/`
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

```bash
pip install fdroidserver
cd ~/fdroiddata
fdroid readmeta
fdroid lint com.porter.tvremote
fdroid checkupdates --allow-dirty com.porter.tvremote
```

Fix any warnings before submitting. Common issues:
- Tag `v1.1` not pushed yet → do Step 1 first
- YAML indentation errors → YAML is space-sensitive, use 2 spaces

---

## Step 6 — Commit and open the Merge Request

```bash
cd ~/fdroiddata
git add metadata/com.porter.tvremote.yml
git commit -m "New app: com.porter.tvremote"
git push origin com.porter.tvremote
```

Then go to https://gitlab.com/fdroid/fdroiddata and you'll see a banner offering to open an MR.

**MR title:** `New app: com.porter.tvremote`  
**Target branch:** `master`

The CI/CD pipeline will run automatically. Wait for it to pass before requesting review.

---

## Step 7 — Monitor the build

After the MR is merged (typically a few days), check:
- https://monitor.f-droid.org/builds/build — search for `com.porter.tvremote`

Build cycle runs approximately every 24–48 hours. Once a green build appears, the app goes live in the F-Droid repository.

---

## Notes

### AGP 9.x
F-Droid upgraded their build server hardware on 2025-12-30. AGP 9.1.0 should build cleanly. If the build fails on their end, the error log at monitor.f-droid.org will show why — most likely cause would be a missing `signingConfigs` block issue (F-Droid builds unsigned, then signs itself; the current `signingConfigs` block in `build.gradle.kts` reads from Gradle properties that won't exist on their server). If this happens, move the `signingConfig` assignment inside an `if` guard or add `buildFeatures { ... }` per reviewer feedback.

### Future releases
When you release v1.2 or later:
1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`
2. Add `tv-remote-apk/fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`
3. Commit, push, then `git tag v1.2 && git push origin v1.2`
4. F-Droid picks up new tags automatically via `AutoUpdateMode: Version` — no MR needed.

### Slow-queue alternative
If you'd rather not deal with GitLab, file a ticket at:
https://gitlab.com/fdroid/rfp/issues
with the GitHub URL and license. A volunteer will handle the metadata, but expect weeks to months.
