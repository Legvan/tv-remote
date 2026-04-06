"""ADB connection wrapper for Android TV / Google TV.

Reads host, port, and display name from config.json at the project root.
All values fall back to sensible defaults if config.json is absent.
"""
import json
import time
from pathlib import Path

_CONFIG_PATH = Path(__file__).resolve().parent.parent / 'config.json'


def _load_config() -> dict:
    if _CONFIG_PATH.exists():
        try:
            return json.loads(_CONFIG_PATH.read_text())
        except Exception:
            pass
    return {}


_cfg = _load_config()

if not _cfg and not _CONFIG_PATH.exists():
    # config.json is written by ./install — guide the user if it's missing
    import sys as _sys
    print("ERROR: config.json not found. Run './install' first to set up your TV.")
    _sys.exit(1)

TV_HOST = _cfg.get('host', '')
TV_PORT = _cfg.get('port', 5555)
TV_NAME = _cfg.get('name', 'Android TV')

KEY_PATH        = Path.home() / '.android' / 'adbkey'
CONNECT_TIMEOUT = 9.0
AUTH_TIMEOUT    = 10.0   # seconds to wait for the RSA-key prompt on the TV screen


def load_signer():
    from adb_shell.auth.sign_pythonrsa import PythonRSASigner
    priv = KEY_PATH.read_text()
    pub  = KEY_PATH.with_suffix('.pub').read_text()
    return PythonRSASigner(pub, priv)


class TVClient:
    """ADB connection to an Android TV with auto-reconnect.

    Args:
        host:         Override the IP from config.json.
        port:         Override the port from config.json.
        auth_timeout: Seconds to wait for RSA-key acceptance on the TV.
                      Increase to 30+ during first-time setup.
    """

    def __init__(self, host: str = None, port: int = None, auth_timeout: float = None):
        from adb_shell.adb_device import AdbDeviceTcp
        self._host         = host or TV_HOST
        self._port         = port or TV_PORT
        self._auth_timeout = auth_timeout if auth_timeout is not None else AUTH_TIMEOUT
        self._signer       = load_signer()
        self._device       = AdbDeviceTcp(
            self._host, self._port, default_transport_timeout_s=CONNECT_TIMEOUT
        )
        self._connected = False

    def connect(self):
        self._device.connect(rsa_keys=[self._signer], auth_timeout_s=self._auth_timeout)
        self._connected = True
        print(f"Connected to {self._host}:{self._port}")

    def shell(self, cmd: str, retries: int = 2) -> str:
        for attempt in range(retries + 1):
            try:
                return self._device.shell(cmd) or ''
            except Exception as e:
                if attempt == retries:
                    raise
                print(f"Connection lost ({e}), reconnecting…")
                self._device.close()
                time.sleep(1)
                self._device.connect(rsa_keys=[self._signer], auth_timeout_s=self._auth_timeout)

    def key(self, code) -> None:
        """Send a keyevent. Accepts int code or KEYCODE_NAME string."""
        self.shell(f'input keyevent {code}')

    def launch_app(self, activity: str) -> None:
        """Launch an app by package/activity string like 'com.netflix.ninja/.MainActivity'."""
        self.shell(f'am start -n {activity}')

    def go_home(self) -> None:
        self.shell('am start -a android.intent.action.MAIN -c android.intent.category.HOME')

    def screen_state(self) -> str:
        """Returns 'ON' or 'OFF'."""
        result = self.shell("dumpsys power | grep 'Display Power'")
        return 'ON' if 'state=ON' in result else 'OFF'

    def wakefulness(self) -> str:
        """Returns 'Awake', 'Asleep', or 'Dreaming'."""
        result = self.shell("dumpsys power | grep mWakefulness=")
        for word in ('Awake', 'Asleep', 'Dreaming'):
            if word in result:
                return word
        return result.strip()

    def current_app(self) -> str:
        """Returns the package name of the currently focused app."""
        result = self.shell("dumpsys activity activities | grep mResumedActivity")
        try:
            part = result.split('u0 ')[-1].strip()
            return part.split('/')[0]
        except Exception:
            return result.strip()

    def device_info(self) -> dict:
        """Return {manufacturer, model, android} from getprop."""
        return {
            'manufacturer': self.shell('getprop ro.product.manufacturer').strip(),
            'model':        self.shell('getprop ro.product.model').strip(),
            'android':      self.shell('getprop ro.build.version.release').strip(),
        }

    def launch_assistant(self) -> None:
        """Launch Google Assistant via am start (most reliable on Google TV).

        Falls back to KEYCODE_VOICE_ASSIST (231) if the intent fails.
        KEYCODE_SEARCH (84) is NOT used — it is a voice-streaming protocol
        trigger, not a UI launcher.
        """
        try:
            result = self.shell(
                'am start -n com.google.android.googlequicksearchbox'
                '/com.google.android.googlequicksearchbox.VoiceSearchActivity'
            )
            if 'Error' in result or 'Exception' in result:
                raise RuntimeError(result.strip())
        except Exception:
            self.key(231)

    def send_text(self, text: str) -> None:
        """Send text to the focused input field (ASCII; all special chars handled).

        Uses $'\\xNN' ANSI-C quoting so that quotes, $, &, ; and other shell
        metacharacters are passed through safely.  Unicode (> 127) is not
        supported by the Android `input text` command and will be skipped.
        """
        parts = []
        for ch in text:
            if ch == ' ':
                parts.append('%s')
            elif ord(ch) < 128:
                parts.append(f'\\x{ord(ch):02x}')
        escaped = "$'" + ''.join(parts) + "'"
        self.shell(f'input text {escaped}')

    def close(self) -> None:
        self._device.close()
        self._connected = False

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *_):
        self.close()
