# Python Libraries for Android TV ADB Control

## Recommended Stack

**Primary**: `adb-shell` — pure Python, no system ADB server dependency, supports async  
**Optional**: `androidtvremote2` — faster navigation via alternative protocol (no shell overhead)

---

## Library Comparison

| Library | Protocol | ADB server needed | Async | Status | Best for |
|---------|----------|-------------------|-------|--------|----------|
| `adb-shell` | Direct ADB | No | Yes | Active | Pure Python, no system deps |
| `pure-python-adb` (ppadb) | Via ADB server (port 5037) | Yes | Yes | Moderate | Simple wrapper around system ADB |
| `androidtv` | ADB via adb-shell | No | Yes | Maintenance | High-level TV state machine |
| `adbutils` | Via ADB server | Yes | Partial | Active | General ADB automation |
| `androidtvremote2` | Android TV Remote v2 (NOT ADB) | No | Yes (asyncio) | Active | Fast navigation, no shell access |

---

## adb-shell

### Installation
```bash
pip install adb-shell[async]
```

### Sync Usage
```python
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.sign_pythonrsa import PythonRSASigner
from adb_shell.auth.keygen import keygen

# Generate key (one-time)
keygen('~/.android/adbkey')

# Load key
with open('~/.android/adbkey') as f:
    priv = f.read()
with open('~/.android/adbkey.pub') as f:
    pub = f.read()
signer = PythonRSASigner(pub, priv)

# Connect
device = AdbDeviceTcp('<TV_IP>', 5555, default_transport_timeout_s=9.0)
device.connect(rsa_keys=[signer], auth_timeout_s=0.1)

# Send command
device.shell('input keyevent 3')

# Close
device.close()
```

### Async Usage
```python
import asyncio
from adb_shell.adb_device_async import AdbDeviceTcpAsync
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

async def main():
    with open('~/.android/adbkey') as f:
        priv = f.read()
    with open('~/.android/adbkey.pub') as f:
        pub = f.read()
    signer = PythonRSASigner(pub, priv)

    device = AdbDeviceTcpAsync('<TV_IP>', 5555, default_transport_timeout_s=9.0)
    await device.connect(rsa_keys=[signer], auth_timeout_s=0.1)
    await device.shell('input keyevent 3')
    await device.close()

asyncio.run(main())
```

---

## androidtvremote2

**Important**: This library does NOT use ADB. It uses the Android TV Remote Service (pairing via PIN, then persistent connection). No developer mode required.

### When to use it
- Faster navigation (no process-spawn overhead per keypress)
- No developer mode needed on the TV
- Cannot run arbitrary shell commands or install apps

### Installation
```bash
pip install androidtvremote2
```

### Usage
```python
import asyncio
from androidtvremote2 import AndroidTVRemote

async def main():
    atv = AndroidTVRemote("MyClient", '<TV_IP>')

    # First time: pair with PIN shown on TV
    await atv.async_start_pairing()
    code = input("Enter PIN from TV screen: ")
    await atv.async_finish_pairing(code)

    # Send key
    atv.send_key_command("VOLUME_UP")
    atv.send_key_command("DPAD_CENTER")

    # Launch app
    atv.send_launch_app_command("com.netflix.ninja")

asyncio.run(main())
```

### Available key names (TvKeys)
`KEYCODE_POWER`, `KEYCODE_HOME`, `KEYCODE_BACK`, `KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT/CENTER`, `KEYCODE_VOLUME_UP/DOWN/MUTE`, `KEYCODE_MEDIA_PLAY_PAUSE`, etc. — same names as Android keycodes.

---

## pure-python-adb (ppadb)

Requires system `adb` running as a server locally.

```bash
pip install pure-python-adb
```

```python
from ppadb.client import Client as AdbClient

client = AdbClient(host="127.0.0.1", port=5037)
client.remote_connect('<TV_IP>', 5555)
device = client.device("<TV_IP>:5555")
device.shell("input keyevent 3")
client.remote_disconnect('<TV_IP>', 5555)
```

---

## androidtv (high-level state machine)

Best for getting structured state (power, current app, playback status). In maintenance mode.

```bash
pip install androidtv
```

```python
from androidtv import setup

atv, _ = setup('<TV_IP>', 5555, adbkey='~/.android/adbkey')
atv.update()

print(atv.state)        # 'playing', 'paused', 'idle', 'standby', 'off'
print(atv.app_id)       # Current foreground app package
print(atv.volume_level) # Current volume

atv.turn_on()
atv.turn_off()
atv.media_play_pause()
```

---

## Installation Summary

```bash
# Minimum required
pip install adb-shell[async]

# Full stack
pip install adb-shell[async] androidtv androidtvremote2
```
