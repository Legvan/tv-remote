#!/usr/bin/env python3
"""Generate ADB RSA key pair for authenticating with the TV.

Run this once. Creates:
  ~/.android/adbkey      (private key)
  ~/.android/adbkey.pub  (public key)

On first connection, the TV will show an on-screen prompt to accept this key.
"""
import os
from pathlib import Path

KEY_PATH = Path.home() / '.android' / 'adbkey'

def main():
    if KEY_PATH.exists():
        print(f"Key already exists at {KEY_PATH}")
        answer = input("Regenerate? [y/N]: ").strip().lower()
        if answer != 'y':
            print("Keeping existing key.")
            return

    KEY_PATH.parent.mkdir(parents=True, exist_ok=True)

    from adb_shell.auth.keygen import keygen
    keygen(str(KEY_PATH))

    print(f"Generated ADB key pair:")
    print(f"  Private: {KEY_PATH}")
    print(f"  Public:  {KEY_PATH}.pub")
    print()
    print("Next step: run `python scripts/tv.py connect` and accept the prompt on the TV screen.")

if __name__ == '__main__':
    main()
