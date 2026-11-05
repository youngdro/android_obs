#!/usr/bin/env bash
# 网页音频桥检查（Chromium 无头浏览器）
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
[ -f tone.wav ] || python3 -c "
import wave,struct,math
w=wave.open('tone.wav','wb');w.setnchannels(1);w.setsampwidth(2);w.setframerate(44100)
w.writeframes(b''.join(struct.pack('<h',int(12000*math.sin(2*math.pi*660*i/44100))) for i in range(88200)));w.close()"
python3 -m http.server 8765 --bind 127.0.0.1 >/dev/null 2>&1 & S=$!
trap 'kill $S 2>/dev/null || true' EXIT
sleep 1
node check.js "$HERE/../../app/src/main/assets/webaudio/bridge.js" http://127.0.0.1:8765/page.html
