#!/usr/bin/env bash
# 推流模块端到端测试：ffmpeg 生成 H.264(High, 无 B 帧)+AAC 测试流，经项目自带的 RTMP 客户端推给 ffmpeg 接收端，
# 再用 ffprobe 校验接收到的流。覆盖：RTMP、RTMPS（TLS 终结代理 + 自签证书信任）、证书不受信任时报错、断线自动重连。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
W="$(mktemp -d)"
G="gradle -q --no-daemon -p $HERE"
command -v gradle >/dev/null || G="$HERE/../gradlew -q --no-daemon -p $HERE"
trap 'kill $(jobs -p) 2>/dev/null || true' EXIT
fail() { echo "FAIL: $*"; exit 1; }

$G urlChecks

ffmpeg -v error -y -f lavfi -i testsrc2=size=1280x720:rate=30 -t 10 -c:v libx264 -preset veryfast -bf 0 -g 60 \
  -profile:v high -pix_fmt yuv420p -x264-params aud=1 -f h264 "$W/v.h264"
ffmpeg -v error -y -f lavfi -i sine=f=440:sample_rate=44100 -t 10 -c:a aac -b:a 128k -ac 1 -f adts "$W/a.aac"
run() { $G rtmpE2E -Pe2eArgs="$W/v.h264 $W/a.aac $1 30 $2"; }
probe() { ffprobe -v error -show_entries stream=codec_name -of csv=p=0 "$1" | sort | tr '\n' ' '; }

echo "== 1. RTMP 基础推流"
ffmpeg -v error -y -listen 1 -i rtmp://127.0.0.1:19350/live/testkey -c copy "$W/rtmp.flv" & L=$!
sleep 1; run rtmp://127.0.0.1:19350/live/testkey 8 | tee "$W/1.log"; wait $L || true
grep -q "RESULT state=直播中" "$W/1.log" || fail "RTMP 未进入直播中"
[[ "$(probe "$W/rtmp.flv")" == "aac h264 " ]] || fail "接收端缺少音视频流"
DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$W/rtmp.flv"); echo "接收时长 $DUR 秒"
python3 -c "import sys; sys.exit(0 if float('$DUR') > 5 else 1)" || fail "接收时长过短"

echo "== 2. RTMPS（自签证书 + 信任库）"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$W/k.pem" -out "$W/c.pem" -days 1 -subj "/CN=localhost" \
  -addext "subjectAltName=DNS:localhost" 2>/dev/null
keytool -importcert -noprompt -alias t -file "$W/c.pem" -keystore "$W/trust.p12" -storetype PKCS12 -storepass changeit >/dev/null 2>&1
python3 -I "$HERE/tlsproxy.py" 19443 19351 "$W/c.pem" "$W/k.pem" & P=$!
ffmpeg -v error -y -listen 1 -i rtmp://127.0.0.1:19351/live/sk -c copy "$W/rtmps.flv" & L=$!
sleep 1; E2E_TRUST="$W/trust.p12" run rtmps://localhost:19443/live/sk 6 | tee "$W/2.log"; sleep 2; kill $L 2>/dev/null || true
grep -q "RESULT state=直播中" "$W/2.log" || fail "RTMPS 未进入直播中"
[[ "$(probe "$W/rtmps.flv")" == *h264* ]] || fail "RTMPS 接收端没有视频"

echo "== 3. RTMPS 证书不受信任 → 必须报 TLS/证书错误，而不是静默连接"
run rtmps://localhost:19443/live/sk 3 | tee "$W/3.log" || true
grep -q "TLS/证书错误" "$W/3.log" || fail "未报告证书错误"
kill $P 2>/dev/null || true

echo "== 4. 断线自动重连"
ffmpeg -v error -y -listen 1 -i rtmp://127.0.0.1:19352/live/k -c copy "$W/r1.flv" & L1=$!
sleep 1; (run rtmp://127.0.0.1:19352/live/k 28 > "$W/4.log" 2>&1 &)
for i in $(seq 1 40); do grep -q "state=直播中" "$W/4.log" 2>/dev/null && break; sleep 0.5; done
sleep 4; kill -INT $L1; echo "已断开接收端"; sleep 5
ffmpeg -v error -y -listen 1 -i rtmp://127.0.0.1:19352/live/k -c copy "$W/r2.flv" & L2=$!
for i in $(seq 1 60); do [[ $(grep -c "state=直播中" "$W/4.log") -ge 2 ]] && break; sleep 0.5; done
cat "$W/4.log"
[[ $(grep -c "state=直播中" "$W/4.log") -ge 2 ]] || fail "断线后未自动恢复推流"
grep -q "retry=1000ms" "$W/4.log" || fail "重连间隔未从 1 秒开始"
echo "ALL E2E PASSED"
