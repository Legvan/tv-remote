#!/usr/bin/env python3
"""CLI for controlling TCL 55C645 Android TV via ADB.

Usage:
  python tv.py connect          Test connection
  python tv.py power            Toggle power (on/off)
  python tv.py wake             Wake screen (on only)
  python tv.py sleep            Sleep screen (off only)
  python tv.py state            Show screen state and current app
  python tv.py vol-up [N]       Volume up (N times, default 1)
  python tv.py vol-down [N]     Volume down
  python tv.py mute             Toggle mute
  python tv.py home             Go to home screen
  python tv.py back             Back button
  python tv.py ok               OK / Select
  python tv.py up/down/left/right  D-pad navigation
  python tv.py play-pause       Play/pause
  python tv.py next / prev      Next/previous track
  python tv.py launch <app>     Launch app: netflix, youtube, settings
  python tv.py key <code>       Send raw keycode (number or KEYCODE_NAME)
  python tv.py text <words>     Send text to focused input field (ASCII only)
  python tv.py shell <cmd>      Run arbitrary ADB shell command
  python tv.py discover         Scan network for Android TV / Google TV devices
"""
import sys
import os

sys.path.insert(0, os.path.dirname(__file__))
from adb_client import TVClient

APPS = {
    'netflix': 'com.netflix.ninja/.MainActivity',
    'youtube': 'com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity',
    'settings': 'com.android.settings/.Settings',
    'play': 'com.android.vending/.AssetBrowserActivity',
    'kodi': 'org.xbmc.kodi/.Splash',
    'plex': 'com.plexapp.android/.MainActivity',
    'spotify': 'com.spotify.tv.android/.SpotifyTVActivity',
}

KEYS = {
    'power': 26, 'wake': 224, 'sleep': 223,
    'home': 3, 'back': 4, 'menu': 82, 'settings': 176,
    'up': 19, 'down': 20, 'left': 21, 'right': 22, 'ok': 23,
    'enter': 66, 'del': 67,
    'vol-up': 24, 'vol-down': 25, 'mute': 164,
    'play-pause': 85, 'play': 126, 'pause': 127, 'stop': 86,
    'next': 87, 'prev': 88, 'rewind': 89, 'ff': 90,
    'input': 178, 'hdmi1': 243, 'hdmi2': 244, 'hdmi3': 245, 'hdmi4': 246,
    'red': 183, 'green': 184, 'yellow': 185, 'blue': 186,
}


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        sys.exit(0)

    cmd = args[0]

    if cmd == 'discover':
        import discover as _d
        _d.main()
        return

    if cmd == 'connect':
        with TVClient() as tv:
            state = tv.screen_state()
            wake = tv.wakefulness()
            app = tv.current_app()
            print(f"Screen: {state} / {wake}")
            print(f"App: {app}")
        return

    if cmd == 'state':
        with TVClient() as tv:
            print(f"Screen: {tv.screen_state()} / {tv.wakefulness()}")
            print(f"App: {tv.current_app()}")
        return

    if cmd == 'text':
        if len(args) < 2:
            print("Usage: tv.py text <words to type>")
            sys.exit(1)
        text = ' '.join(args[1:])
        with TVClient() as tv:
            tv.send_text(text)
            print(f"Sent: {text!r}")
        return

    if cmd == 'shell':
        if len(args) < 2:
            print("Usage: tv.py shell <command>")
            sys.exit(1)
        shell_cmd = ' '.join(args[1:])
        with TVClient() as tv:
            result = tv.shell(shell_cmd)
            if result:
                print(result)
        return

    if cmd == 'launch':
        if len(args) < 2:
            print(f"Usage: tv.py launch <app>  (known: {', '.join(APPS.keys())})")
            sys.exit(1)
        app_name = args[1].lower()
        with TVClient() as tv:
            if app_name == 'home':
                tv.go_home()
            elif app_name in APPS:
                tv.launch_app(APPS[app_name])
                print(f"Launched {app_name}")
            else:
                # Treat as package name directly
                tv.shell(f'monkey -p {app_name} -c android.intent.category.LAUNCHER 1')
                print(f"Launched package: {app_name}")
        return

    if cmd == 'key':
        if len(args) < 2:
            print("Usage: tv.py key <code>")
            sys.exit(1)
        code = args[1]
        with TVClient() as tv:
            tv.key(code)
        return

    # Volume with repeat count
    if cmd in ('vol-up', 'vol-down'):
        count = int(args[1]) if len(args) > 1 else 1
        code = KEYS[cmd]
        with TVClient() as tv:
            tv.shell(' ; '.join([f'input keyevent {code}'] * count))
        return

    # Simple key mappings
    if cmd in KEYS:
        with TVClient() as tv:
            tv.key(KEYS[cmd])
        return

    print(f"Unknown command: {cmd}")
    print(__doc__)
    sys.exit(1)


if __name__ == '__main__':
    main()
