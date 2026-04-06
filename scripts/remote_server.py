#!/usr/bin/env python3
"""Google TV Remote — web GUI server.

Serves an HTML remote control at http://localhost:5052
and handles ADB commands via REST API.
"""
import sys, os, threading, webbrowser, time, logging
from flask import Flask, jsonify, request, send_from_directory

logging.getLogger('werkzeug').setLevel(logging.ERROR)

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from adb_client import TVClient, TV_HOST, TV_PORT, TV_NAME

STATIC = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'static')
app = Flask(__name__, static_folder=STATIC)

_tv: TVClient | None = None
_lock = threading.Lock()

APPS = {
    'youtube':  'com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity',
    'netflix':  'com.netflix.ninja/.MainActivity',
    'prime':    'com.amazon.amazonvideo.livingroom/.MainActivity',
    'disney':   'com.disney.disneyplus/.MainActivity',
    'settings': 'com.android.settings/.Settings',
    'spotify':  'com.spotify.tv.android/.SpotifyTVActivity',
    'kodi':     'org.xbmc.kodi/.Splash',
}


def get_tv() -> TVClient:
    global _tv
    if _tv is None:
        _tv = TVClient()
        _tv.connect()
    return _tv


def drop_tv():
    global _tv
    try:
        if _tv:
            _tv.close()
    except Exception:
        pass
    _tv = None


@app.route('/api/config')
def config():
    return jsonify({'name': TV_NAME, 'host': TV_HOST, 'port': TV_PORT})


@app.route('/')
def index():
    return send_from_directory(STATIC, 'index.html')


@app.route('/api/key/<int:code>', methods=['POST'])
def key(code):
    with _lock:
        try:
            get_tv().key(code)
            return jsonify({'ok': True})
        except Exception as e:
            drop_tv()
            return jsonify({'ok': False, 'error': str(e)}), 500


@app.route('/api/launch/<name>', methods=['POST'])
def launch(name):
    with _lock:
        try:
            tv = get_tv()
            if name in APPS:
                tv.launch_app(APPS[name])
            elif name == 'home':
                tv.go_home()
            else:
                tv.shell(f'monkey -p {name} -c android.intent.category.LAUNCHER 1')
            return jsonify({'ok': True})
        except Exception as e:
            drop_tv()
            return jsonify({'ok': False, 'error': str(e)}), 500


@app.route('/api/assistant', methods=['POST'])
def assistant():
    with _lock:
        try:
            get_tv().launch_assistant()
            return jsonify({'ok': True})
        except Exception as e:
            drop_tv()
            return jsonify({'ok': False, 'error': str(e)}), 500


@app.route('/api/text', methods=['POST'])
def send_text():
    data = request.get_json(silent=True) or {}
    text = data.get('text', '')
    if not text:
        return jsonify({'ok': False, 'error': 'No text provided'}), 400
    with _lock:
        try:
            get_tv().send_text(text)
            return jsonify({'ok': True})
        except Exception as e:
            drop_tv()
            return jsonify({'ok': False, 'error': str(e)}), 500


@app.route('/api/state')
def state():
    with _lock:
        try:
            tv = get_tv()
            return jsonify({'ok': True, 'screen': tv.screen_state(), 'app': tv.current_app()})
        except Exception as e:
            return jsonify({'ok': False, 'error': str(e)}), 500


PORT = 5052


if __name__ == '__main__':
    import socket as _socket
    try:
        _s = _socket.socket(_socket.AF_INET, _socket.SOCK_DGRAM)
        _s.connect(('8.8.8.8', 80))
        _lan_ip = _s.getsockname()[0]
        _s.close()
    except Exception:
        _lan_ip = 'localhost'
    print(f'TV Remote GUI → http://localhost:{PORT}')
    print(f'On your phone  → http://{_lan_ip}:{PORT}')
    with _lock:
        try:
            get_tv()
            print(f'Connected to {TV_HOST}:{TV_PORT}')
        except Exception as e:
            print(f'Warning: {e} — will retry on first button press')

    threading.Thread(
        target=lambda: (time.sleep(1.2), webbrowser.open(f'http://localhost:{PORT}')),
        daemon=True
    ).start()

    app.run(host='0.0.0.0', port=PORT, debug=False, threaded=True)
