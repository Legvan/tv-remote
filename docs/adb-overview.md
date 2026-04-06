# ADB Overview — Android TV Network Control

## What is Network ADB

ADB (Android Debug Bridge) is the standard Android developer tool for communicating with Android devices. Google TV and Android TV devices support ADB over TCP/IP on port 5555.

---

## One-Time Setup

### 1. Enable Developer Mode on the TV

Settings → About → tap **Build Number** 7 times rapidly → "You are now a developer"

### 2. Enable ADB over Network

Settings → System → Developer Options → **ADB over network** → On

(The exact menu path varies slightly by manufacturer and Android version.)

### 3. Generate RSA Key Pair (host machine)

```bash
python scripts/keygen.py
# Creates ~/.android/adbkey and ~/.android/adbkey.pub
```

Or with system ADB:
```bash
adb keygen ~/.android/adbkey
```

### 4. First Connection — Accept on TV

On first connection, the TV shows an on-screen prompt:
**"Allow USB debugging from this computer?"**

Accept it with the physical remote or on-screen OK. After acceptance, the key is stored permanently in `/data/misc/adb/adb_keys` on the TV. No further prompts needed.

```bash
adb connect <TV_IP>:5555
adb devices  # Should show: <TV_IP>:5555   device
```

---

## Connection Persistence

Most Google TV / Android TV devices keep ADB-over-network active across reboots once enabled in Developer Options. If the port closes after a reboot, re-toggle the "ADB over network" switch in Developer Options.

---

## Python Connection with adb-shell

`adb-shell` is recommended — pure Python, no system ADB server required.

```python
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner
from pathlib import Path

def load_signer():
    key  = Path.home() / '.android' / 'adbkey'
    pub  = key.with_suffix('.pub')
    return PythonRSASigner(pub.read_text(), key.read_text())

signer = load_signer()
device = AdbDeviceTcp('<TV_IP>', 5555, default_transport_timeout_s=9.0)
device.connect(rsa_keys=[signer], auth_timeout_s=0.1)

result = device.shell('input keyevent 3')

device.close()
```

---

## Auto-Reconnect Pattern

The ADB daemon on Android TVs can restart unexpectedly (firmware updates, memory pressure). Always wrap with reconnect logic:

```python
def safe_shell(device, cmd, signer, retries=2):
    for attempt in range(retries + 1):
        try:
            return device.shell(cmd)
        except Exception:
            if attempt == retries:
                raise
            device.close()
            device.connect(rsa_keys=[signer], auth_timeout_s=0.1)
```

---

## Screen State Detection

```bash
# Check if screen is on (most reliable)
adb shell "(dumpsys power | grep 'Display Power' | grep -q 'state=ON') && echo ON || echo OFF"

# Check wakefulness level
adb shell "dumpsys power | grep mWakefulness"
# Returns: Awake / Asleep / Dreaming (screensaver)
```

---

## Command Performance

Each `adb shell input keyevent` spawns a new process (~100–300ms overhead). For rapid sequences, batch them in a single shell call:

```bash
# Slow: 3 separate calls
adb shell input keyevent 19
adb shell input keyevent 19
adb shell input keyevent 23

# Fast: one call
adb shell "input keyevent 19 ; input keyevent 19 ; input keyevent 23"
```

---

## Power ON from Full Off (Wake-on-LAN)

ADB cannot wake a TV that is completely powered off (no standby). Use Wake-on-LAN:

```bash
wakeonlan <TV_MAC_ADDRESS>
sleep 10  # Wait for boot
adb connect <TV_IP>:5555
```

Find MAC: `adb shell ip -f inet addr show wlan0` or check router DHCP table.

---

## Known Issues

| Issue | Cause | Fix |
|-------|-------|-----|
| "failed to authenticate" | RSA key not yet accepted on TV | Accept prompt on-screen |
| "connection refused" | Port 5555 closed | Re-enable ADB over network in Developer Options |
| Connection drops mid-session | adbd daemon restart | Implement auto-reconnect (see above) |
| Text input fails | No text field focused | Focus a text field first |
| HDMI keycode 243-246 doesn't work | Not all TVs support direct HDMI keycodes | Use KEYCODE_TV_INPUT (178) to cycle instead |
