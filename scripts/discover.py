#!/usr/bin/env python3
"""Discover Android TV / Google TV devices on the local network.

Two-step process:
  1. mDNS scan for _androidtvremote2._tcp  — all Google TV / Android TV devices
     advertise this service; no ADB or pairing required.
  2. Model identification from TLS certificate on port 6467 — reads the device
     name and MAC without pairing or developer-mode access.

Fallback: async TCP scan on port 5555 for devices with ADB enabled but not
advertising via mDNS (e.g. older Android TV with 'adb tcpip 5555').

Returns a list of dicts:
  {ip, name, mac, source}   source = 'mdns' | 'adb_scan'

Usage:
  python scripts/discover.py           # prints found devices
  from discover import discover_async  # import for use in other scripts
  ./tv discover
"""

import asyncio
import socket
import sys
import os

CERT = os.path.expanduser('~/.android/atv_cert.pem')
KEY  = os.path.expanduser('~/.android/atv_key.pem')

MDNS_TIMEOUT     = 4.0   # seconds to listen for mDNS announcements
PORT_TIMEOUT     = 0.45  # seconds per TCP probe
PORT_CONCURRENCY = 60    # parallel connections for port scan


# ── helpers ──────────────────────────────────────────────────────────────────

def _local_subnet() -> str:
    """Return the first three octets of the machine's outbound IP (best-effort)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(('8.8.8.8', 80))
            ip = s.getsockname()[0]
        return '.'.join(ip.split('.')[:3])
    except Exception:
        return '192.168.1'


async def _mdns_scan() -> dict[str, str]:
    """Return {ip: label} found via _androidtvremote2._tcp mDNS."""
    try:
        from zeroconf import ServiceBrowser, ServiceListener, Zeroconf
    except ImportError:
        print('  [!] zeroconf not installed — skipping mDNS. Run: pip install zeroconf')
        return {}

    found: dict[str, str] = {}
    suffix = '._androidtvremote2._tcp.local.'

    class _Listener(ServiceListener):
        def add_service(self, zc, type_, name):
            info = zc.get_service_info(type_, name)
            if info and info.addresses:
                ip    = socket.inet_ntoa(info.addresses[0])
                label = name.removesuffix(suffix)
                found[ip] = label

        def update_service(self, *_): pass
        def remove_service(self, *_): pass

    zc = Zeroconf()
    browser = ServiceBrowser(zc, '_androidtvremote2._tcp.local.', _Listener())
    await asyncio.sleep(MDNS_TIMEOUT)
    browser.cancel()
    zc.close()
    return found


async def _tls_model(ip: str) -> tuple[str, str] | None:
    """Read (name, mac) from the device's TLS cert on port 6467 — no pairing needed."""
    try:
        from androidtvremote2 import AndroidTVRemote
        atv = AndroidTVRemote('tv-remote-setup', CERT, KEY, ip)
        await atv.async_generate_cert_if_missing()
        name, mac = await atv.async_get_name_and_mac()
        return name, mac
    except Exception:
        return None


async def _port_open(ip: str, port: int = 5555) -> bool:
    try:
        _, w = await asyncio.wait_for(asyncio.open_connection(ip, port), PORT_TIMEOUT)
        w.close()
        await w.wait_closed()
        return True
    except Exception:
        return False


async def _adb_port_scan(subnet: str, skip: set[str]) -> list[str]:
    sem = asyncio.Semaphore(PORT_CONCURRENCY)

    async def probe(i: int) -> str | None:
        ip = f'{subnet}.{i}'
        if ip in skip:
            return None
        async with sem:
            return ip if await _port_open(ip) else None

    results = await asyncio.gather(*[probe(i) for i in range(1, 255)])
    return [ip for ip in results if ip]


# ── main discovery function ───────────────────────────────────────────────────

async def discover_async() -> list[dict]:
    """Discover devices. Returns list of {ip, name, mac, source} dicts."""
    devices: list[dict] = []

    # Step 1 — mDNS
    print(f'  [1/2] mDNS scan ({MDNS_TIMEOUT:.0f}s) ...', end='', flush=True)
    mdns = await _mdns_scan()
    print(f' {len(mdns)} found' if mdns else ' none')

    for ip, label in mdns.items():
        info = await _tls_model(ip)
        if info:
            name, mac = info
        else:
            name, mac = label, None
        devices.append({'ip': ip, 'name': name, 'mac': mac, 'source': 'mdns'})

    # Step 2 — ADB port scan fallback
    subnet = _local_subnet()
    known  = set(mdns.keys())
    print(f'  [2/2] ADB port scan ({subnet}.1–254) ...', end='', flush=True)
    adb_ips = await _adb_port_scan(subnet, known)
    print(f' {len(adb_ips)} found' if adb_ips else ' none')

    for ip in adb_ips:
        info = await _tls_model(ip)
        if info:
            name, mac = info
        else:
            name, mac = 'Unknown Android TV', None
        devices.append({'ip': ip, 'name': name, 'mac': mac, 'source': 'adb_scan'})

    return devices


def discover() -> list[dict]:
    """Synchronous wrapper around discover_async()."""
    return asyncio.run(discover_async())


# ── CLI ───────────────────────────────────────────────────────────────────────

def main() -> None:
    print('Scanning for Android TV / Google TV devices ...\n')
    devices = discover()

    if not devices:
        print('No devices found.\n')
        print('Make sure:')
        print('  • TV is on the same network')
        print('  • Developer options are enabled (Settings > About > tap Build Number 7×)')
        print('  • ADB over network is turned on (Developer options > ADB over network)')
        sys.exit(1)

    print(f'\nFound {len(devices)} device(s):\n')
    for i, d in enumerate(devices, 1):
        mac_str = f'  MAC: {d["mac"]}' if d['mac'] else ''
        tag     = '  [via ADB port scan — developer mode required]' if d['source'] == 'adb_scan' else ''
        print(f'  [{i}]  {d["name"]}')
        print(f'        IP:  {d["ip"]}{mac_str}{tag}')
    print()


if __name__ == '__main__':
    main()
