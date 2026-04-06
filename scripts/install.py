#!/usr/bin/env python3
"""TV Remote — setup wizard.

Scans the network, finds your Android TV / Google TV, tests the ADB
connection, writes config.json, and creates a .desktop launcher.

Steps:
  1. Scan network (mDNS + ADB port fallback)
  2. Select TV (auto if one found, list if many, manual fallback)
  3. Generate ADB key if needed
  4. Test ADB connection (user accepts RSA prompt on TV)
  5. Write config.json + create .desktop launcher

Usage:
    python scripts/install.py
    ./install
"""

import asyncio
import json
import os
import socket
import sys
import time
from pathlib import Path

# ── paths ─────────────────────────────────────────────────────────────────────

SCRIPT_DIR   = Path(__file__).resolve().parent
PROJECT_DIR  = SCRIPT_DIR.parent
CONFIG_PATH  = PROJECT_DIR / 'config.json'
REMOTE_GUI   = PROJECT_DIR / 'remote-gui'
ADB_KEY      = Path.home() / '.android' / 'adbkey'
DESKTOP_DIR  = Path.home() / '.local' / 'share' / 'applications'
DESKTOP_FILE = DESKTOP_DIR / 'tv-remote.desktop'

TOTAL = 5

# ── terminal helpers ──────────────────────────────────────────────────────────

R = '\033[0m'
BOLD  = '\033[1m'
DIM   = '\033[2m'
GREEN = '\033[92m'
YEL   = '\033[93m'
RED   = '\033[91m'
CYAN  = '\033[96m'
WHITE = '\033[97m'

def _ok(msg):    print(f'  {GREEN}✓{R}  {msg}')
def _warn(msg):  print(f'  {YEL}⚠{R}  {msg}')
def _err(msg):   print(f'  {RED}✗{R}  {msg}')
def _info(msg):  print(f'  {DIM}·{R}  {msg}')
def _step(n, msg): print(f'\n{BOLD}{CYAN}[{n}/{TOTAL}]{R}  {WHITE}{msg}{R}')


def _banner():
    print(f"""
{BOLD}{CYAN}╔══════════════════════════════════════════╗
║        TV Remote — Setup Wizard          ║
║              made by Legvan              ║
╚══════════════════════════════════════════╝{R}

  This wizard will:
    {DIM}•{R} Scan your network for Android TV / Google TV
    {DIM}•{R} Verify the ADB connection
    {DIM}•{R} Write config.json and create a desktop shortcut

  Prerequisites on your TV:
    {DIM}1.{R} Settings → About → tap {BOLD}Build Number{R} 7 times
    {DIM}2.{R} Developer options → enable {BOLD}ADB over network{R}

""")
    input(f'  {DIM}Press Enter to start…{R}  ')


# ── step 1 — scan ─────────────────────────────────────────────────────────────

MDNS_TIMEOUT    = 4.0
PORT_TIMEOUT    = 0.45
PORT_CONCURRENCY = 60


def _local_subnet() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(('8.8.8.8', 80))
            ip = s.getsockname()[0]
        return '.'.join(ip.split('.')[:3])
    except Exception:
        return '192.168.1'


async def _mdns_scan() -> dict[str, str]:
    try:
        from zeroconf import ServiceBrowser, ServiceListener, Zeroconf
    except ImportError:
        return {}

    found: dict[str, str] = {}
    suffix = '._androidtvremote2._tcp.local.'

    class _L(ServiceListener):
        def add_service(self, zc, type_, name):
            info = zc.get_service_info(type_, name)
            if info and info.addresses:
                ip    = socket.inet_ntoa(info.addresses[0])
                label = name.removesuffix(suffix)
                found[ip] = label
        def update_service(self, *_): pass
        def remove_service(self, *_): pass

    zc = Zeroconf()
    browser = ServiceBrowser(zc, '_androidtvremote2._tcp.local.', _L())
    await asyncio.sleep(MDNS_TIMEOUT)
    browser.cancel()
    zc.close()
    return found


async def _tls_model(ip: str) -> tuple[str, str] | None:
    cert = Path.home() / '.android' / 'atv_cert.pem'
    key  = Path.home() / '.android' / 'atv_key.pem'
    try:
        from androidtvremote2 import AndroidTVRemote
        atv = AndroidTVRemote('tv-remote-setup', str(cert), str(key), ip)
        await atv.async_generate_cert_if_missing()
        name, mac = await atv.async_get_name_and_mac()
        return name, mac
    except Exception:
        return None


async def _port_open(ip: str) -> bool:
    try:
        _, w = await asyncio.wait_for(asyncio.open_connection(ip, 5555), PORT_TIMEOUT)
        w.close(); await w.wait_closed()
        return True
    except Exception:
        return False


async def _scan_async() -> list[dict]:
    devices: list[dict] = []

    print(f'  {DIM}mDNS ({MDNS_TIMEOUT:.0f}s)…{R}', end='', flush=True)
    mdns = await _mdns_scan()
    print(f' {len(mdns)} found' if mdns else ' none')

    for ip, label in mdns.items():
        info = await _tls_model(ip)
        name, mac = info if info else (label, None)
        devices.append({'ip': ip, 'name': name, 'mac': mac})

    subnet = _local_subnet()
    known  = set(mdns.keys())
    print(f'  {DIM}Port scan {subnet}.1–254…{R}', end='', flush=True)
    sem = asyncio.Semaphore(PORT_CONCURRENCY)
    async def probe(i):
        ip = f'{subnet}.{i}'
        if ip in known: return None
        async with sem:
            return ip if await _port_open(ip) else None
    hits = [ip for ip in await asyncio.gather(*[probe(i) for i in range(1, 255)]) if ip]
    print(f' {len(hits)} found' if hits else ' none')

    for ip in hits:
        info = await _tls_model(ip)
        name, mac = info if info else ('Unknown Android TV', None)
        devices.append({'ip': ip, 'name': name, 'mac': mac})

    return devices


def step_scan() -> dict:
    _step(1, 'Scan network')
    devices = asyncio.run(_scan_async())

    if not devices:
        _warn('No devices found automatically.')
        return _manual_entry()

    if len(devices) == 1:
        d = devices[0]
        _ok(f'Found: {BOLD}{d["name"]}{R}  —  {d["ip"]}')
        answer = input(f'\n  Use this TV? [Y/n]: ').strip().lower()
        if answer in ('', 'y', 'yes'):
            return d
        return _manual_entry()

    print(f'\n  Found {len(devices)} devices:\n')
    for i, d in enumerate(devices, 1):
        mac = f'  [{d["mac"]}]' if d.get('mac') else ''
        print(f'  {BOLD}[{i}]{R}  {d["name"]}  —  {d["ip"]}{mac}')

    while True:
        raw = input(f'\n  Select [1–{len(devices)}, M=manual]: ').strip().upper()
        if raw == 'M':
            return _manual_entry()
        try:
            idx = int(raw) - 1
            if 0 <= idx < len(devices):
                return devices[idx]
        except ValueError:
            pass
        _warn('Invalid choice.')


def _manual_entry() -> dict:
    print()
    while True:
        ip = input('  TV IP address: ').strip()
        try:
            socket.inet_aton(ip)
            break
        except socket.error:
            _warn('Not a valid IP — try again.')
    name = input('  TV name (e.g. "TCL 55C645"): ').strip() or 'Android TV'
    return {'ip': ip, 'name': name, 'mac': None}


# ── step 2 — ADB key ──────────────────────────────────────────────────────────

def step_adb_key():
    _step(2, 'ADB key')

    if ADB_KEY.exists():
        _ok(f'Key found: {ADB_KEY}')
        return

    _info('Generating RSA key pair…')
    ADB_KEY.parent.mkdir(parents=True, exist_ok=True)
    try:
        from adb_shell.auth.keygen import keygen
        keygen(str(ADB_KEY))
        _ok(f'Key created: {ADB_KEY}')
    except Exception as e:
        _err(f'Key generation failed: {e}')
        sys.exit(1)


# ── step 3 — connection test ──────────────────────────────────────────────────

def step_connect(device: dict) -> dict:
    """Connect via ADB, return enriched device dict with real model info."""
    _step(3, 'Test ADB connection')

    ip   = device['ip']
    port = 5555

    sys.path.insert(0, str(SCRIPT_DIR))
    from adb_client import TVClient

    print(f"""
  {YEL}ACTION REQUIRED:{R}
  A connection prompt will appear on your TV screen.
  Use the TV remote to {BOLD}Accept / Allow{R} the ADB connection.
  You have 30 seconds.
""")
    input(f'  {DIM}Press Enter when ready…{R}  ')

    for attempt in range(1, 4):
        print(f'  Connecting to {ip}:{port}… (attempt {attempt}/3)', end='', flush=True)
        try:
            tv = TVClient(host=ip, port=port, auth_timeout=30.0)
            tv.connect()
            print(f' {GREEN}OK{R}')

            info = tv.device_info()
            manufacturer = info.get('manufacturer', '').strip()
            model        = info.get('model', '').strip()
            android      = info.get('android', '').strip()
            tv.close()

            full_name = f'{manufacturer} {model}'.strip() if manufacturer else (model or device['name'])
            _ok(f'Model:    {BOLD}{full_name}{R}')
            _ok(f'Android:  {android}')

            return {**device, 'name': full_name, 'manufacturer': manufacturer, 'model': model, 'android': android}

        except Exception as e:
            print(f' {RED}failed{R}')
            _warn(str(e))
            if attempt < 3:
                retry = input(f'\n  {DIM}Retry? [Y/n]: {R}').strip().lower()
                if retry not in ('', 'y', 'yes'):
                    break
                print(f'\n  Make sure the TV shows the RSA key prompt and tap {BOLD}Allow{R}.\n')

    _err('Could not connect. config.json will use the IP you entered.')
    _info('You can re-run ./install to try again, or connect manually.')
    return device


# ── step 4 — write config ─────────────────────────────────────────────────────

def step_config(device: dict):
    _step(4, 'Write configuration')

    cfg = {
        'host':         device['ip'],
        'port':         5555,
        'name':         device['name'],
        'mac':          device.get('mac'),
        'manufacturer': device.get('manufacturer'),
        'model':        device.get('model'),
        'android':      device.get('android'),
    }
    CONFIG_PATH.write_text(json.dumps(cfg, indent=2))
    _ok(f'config.json → {CONFIG_PATH}')


# ── step 5 — desktop file ─────────────────────────────────────────────────────

def step_desktop(device: dict):
    _step(5, 'Create desktop shortcut')

    name    = device['name']
    ip      = device['ip']
    exec_   = str(REMOTE_GUI.resolve())

    desktop = f"""\
[Desktop Entry]
Version=1.0
Type=Application
Name=TV Remote — {name}
Comment=Web remote control for {name} at {ip}
Exec={exec_}
Icon=video-display
Terminal=false
Categories=Utility;AudioVideo;
StartupNotify=false
"""
    DESKTOP_DIR.mkdir(parents=True, exist_ok=True)
    DESKTOP_FILE.write_text(desktop)
    DESKTOP_FILE.chmod(0o755)
    _ok(f'Desktop file → {DESKTOP_FILE}')

    # Update desktop database so the launcher picks it up immediately
    try:
        import subprocess
        subprocess.run(['update-desktop-database', str(DESKTOP_DIR)],
                       capture_output=True, timeout=5)
    except Exception:
        pass


# ── done ──────────────────────────────────────────────────────────────────────

def _done(device: dict):
    name = device['name']
    print(f"""
{GREEN}{BOLD}╔══════════════════════════════════════════╗
║          Setup complete!                 ║
╚══════════════════════════════════════════╝{R}

  {BOLD}{name}{R} is ready.

  Launch the remote:
    {CYAN}./remote-gui{R}            (terminal)
    {CYAN}TV Remote — {name}{R}  (app launcher / Activities)

  CLI quick-control:
    {CYAN}./tv connect{R}            (test connection)
    {CYAN}./tv power{R}              (toggle power)
    {CYAN}./tv vol-up{R}             (volume up)
    {CYAN}./tv launch youtube{R}     (open YouTube)
    {CYAN}./tv discover{R}           (re-scan network)

  Re-run {CYAN}./install{R} any time to switch TV or update config.
""")


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    _banner()
    device  = step_scan()
    step_adb_key()
    device  = step_connect(device)
    step_config(device)
    step_desktop(device)
    _done(device)


if __name__ == '__main__':
    main()
