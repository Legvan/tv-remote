# Android TV Keycode Reference

All codes usable with:
```bash
adb shell input keyevent <CODE>
adb shell input keyevent <NAME>   # e.g. KEYCODE_HOME
```

---

## Power & Screen

| Key | Code | Name |
|-----|------|------|
| Power toggle | 26 | KEYCODE_POWER |
| Wake (screen on only) | 224 | KEYCODE_WAKEUP |
| Sleep (screen off only) | 223 | KEYCODE_SLEEP |

**Note**: KEYCODE_POWER (26) toggles on/off. Use 224 to wake-only, 223 to sleep-only.

---

## Navigation

| Key | Code | Name |
|-----|------|------|
| Home | 3 | KEYCODE_HOME |
| Back | 4 | KEYCODE_BACK |
| Menu | 82 | KEYCODE_MENU |
| Settings | 176 | KEYCODE_SETTINGS |
| Search | 84 | KEYCODE_SEARCH |
| App Switch (recents) | 187 | KEYCODE_APP_SWITCH |
| D-Pad Up | 19 | KEYCODE_DPAD_UP |
| D-Pad Down | 20 | KEYCODE_DPAD_DOWN |
| D-Pad Left | 21 | KEYCODE_DPAD_LEFT |
| D-Pad Right | 22 | KEYCODE_DPAD_RIGHT |
| D-Pad Center / OK | 23 | KEYCODE_DPAD_CENTER |
| Enter | 66 | KEYCODE_ENTER |
| Backspace / Delete | 67 | KEYCODE_DEL |

---

## Volume

| Key | Code | Name |
|-----|------|------|
| Volume Up | 24 | KEYCODE_VOLUME_UP |
| Volume Down | 25 | KEYCODE_VOLUME_DOWN |
| Volume Mute (speaker) | 164 | KEYCODE_VOLUME_MUTE |
| Mute (microphone) | 91 | KEYCODE_MUTE |

Set absolute volume (stream 3 = STREAM_MUSIC):
```bash
adb shell media volume --set 8 --stream 3   # 0–15 range
```

---

## Media Playback

| Key | Code | Name |
|-----|------|------|
| Play/Pause | 85 | KEYCODE_MEDIA_PLAY_PAUSE |
| Play | 126 | KEYCODE_MEDIA_PLAY |
| Pause | 127 | KEYCODE_MEDIA_PAUSE |
| Stop | 86 | KEYCODE_MEDIA_STOP |
| Next | 87 | KEYCODE_MEDIA_NEXT |
| Previous | 88 | KEYCODE_MEDIA_PREVIOUS |
| Rewind | 89 | KEYCODE_MEDIA_REWIND |
| Fast Forward | 90 | KEYCODE_MEDIA_FAST_FORWARD |

---

## Input / Source Selection

| Key | Code | Name |
|-----|------|------|
| TV Input (cycle all) | 178 | KEYCODE_TV_INPUT |
| HDMI 1 | 243 | KEYCODE_TV_INPUT_HDMI_1 |
| HDMI 2 | 244 | KEYCODE_TV_INPUT_HDMI_2 |
| HDMI 3 | 245 | KEYCODE_TV_INPUT_HDMI_3 |
| HDMI 4 | 246 | KEYCODE_TV_INPUT_HDMI_4 |
| AV Input | 247 | KEYCODE_TV_INPUT_VGA_1 |

**Note**: Direct HDMI keycodes (243-246) work on some Android TVs but not all. Test on the TCL 55C645 — if they don't work, use KEYCODE_TV_INPUT (178) to cycle.

---

## Channels & Info

| Key | Code | Name |
|-----|------|------|
| Channel Up | 166 | KEYCODE_CHANNEL_UP |
| Channel Down | 167 | KEYCODE_CHANNEL_DOWN |
| Info / Details | 165 | KEYCODE_INFO |
| Guide | 172 | KEYCODE_GUIDE |
| Captions / Subtitles | 175 | KEYCODE_CAPTIONS |

---

## Colored Buttons (Teletext / Smart Functions)

| Key | Code | Name |
|-----|------|------|
| Red | 183 | KEYCODE_PROG_RED |
| Green | 184 | KEYCODE_PROG_GREEN |
| Yellow | 185 | KEYCODE_PROG_YELLOW |
| Blue | 186 | KEYCODE_PROG_BLUE |

---

## Numbers

| Key | Code | Name |
|-----|------|------|
| 0 | 7 | KEYCODE_0 |
| 1 | 8 | KEYCODE_1 |
| 2 | 9 | KEYCODE_2 |
| 3 | 10 | KEYCODE_3 |
| 4 | 11 | KEYCODE_4 |
| 5 | 12 | KEYCODE_5 |
| 6 | 13 | KEYCODE_6 |
| 7 | 14 | KEYCODE_7 |
| 8 | 15 | KEYCODE_8 |
| 9 | 16 | KEYCODE_9 |

---

## Python Helper

```python
KEYS = {
    'power': 26, 'wake': 224, 'sleep': 223,
    'home': 3, 'back': 4, 'menu': 82, 'settings': 176,
    'up': 19, 'down': 20, 'left': 21, 'right': 22, 'ok': 23,
    'enter': 66, 'del': 67,
    'vol_up': 24, 'vol_down': 25, 'mute': 164,
    'play_pause': 85, 'play': 126, 'pause': 127, 'stop': 86,
    'next': 87, 'prev': 88, 'rewind': 89, 'ff': 90,
    'input': 178, 'hdmi1': 243, 'hdmi2': 244, 'hdmi3': 245, 'hdmi4': 246,
}

def key(device, name):
    code = KEYS.get(name, name)  # Accept name or raw int
    device.shell(f'input keyevent {code}')
```
