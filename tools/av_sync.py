#!/usr/bin/env python3
"""
音画同步测量：分析接收端录下的文件（App 已开启“音画同步测试信号”）。

  ffmpeg -i rtmp://<服务器>/live/test -c copy rec.flv      # 录制至少 30 秒，长稳测试录 60 分钟
  python3 tools/av_sync.py rec.flv

原理：App 在每个整秒把画面左上角 1/8 区域从黑变白一帧，同时在音频里放 50ms 的 1kHz 提示音，
两者使用同一个单调时钟。脚本找出每次闪白和提示音在接收端文件里的时间，计算 音频 - 视频 的偏差。
  偏差 > 0：声音晚于画面；偏差 < 0：声音早于画面。
  视频按帧量化，30fps 时单次测量有 0~33ms 的固有误差（均值约 +17ms）。
通过标准（需求）：|偏差| ≤ 200ms，且长时间运行无明显漂移（斜率接近 0）。
依赖：ffmpeg / ffprobe 在 PATH 中。
"""
import re
import subprocess
import sys


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True).stdout


def metadata_series(cmd, key):
    out = run(cmd)
    series, t = [], None
    for line in out.splitlines():
        m = re.search(r"pts_time:([-\d.]+)", line)
        if m:
            t = float(m.group(1))
            continue
        if line.startswith(key + "=") and t is not None:
            v = line.split("=", 1)[1]
            try:
                series.append((t, float(v)))
            except ValueError:
                series.append((t, -120.0))  # -inf
    return series


def onsets(series, high, low):
    res, armed = [], True
    for t, v in series:
        if armed and v >= high:
            res.append(t)
            armed = False
        elif not armed and v <= low:
            armed = True
    return res


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    f = sys.argv[1]
    video = metadata_series(
        ["ffmpeg", "-v", "error", "-i", f, "-an", "-vf",
         "crop=iw/8:ih/8:0:0,signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=-",
         "-f", "null", "-"], "lavfi.signalstats.YAVG")
    audio = metadata_series(
        ["ffmpeg", "-v", "error", "-i", f, "-vn", "-af",
         "asetnsamples=n=240:p=0,astats=metadata=1:reset=1,"
         "ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-",
         "-f", "null", "-"], "lavfi.astats.Overall.RMS_level")
    v_on = onsets(video, 160, 90)
    a_on = onsets(audio, -30, -45)
    if not v_on or not a_on:
        print(f"未检测到测试信号：视频闪白 {len(v_on)} 次，音频提示音 {len(a_on)} 次。请确认 App 已开启“音画同步测试信号”。")
        sys.exit(2)

    pairs = []
    for vt in v_on:
        at = min(a_on, key=lambda a: abs(a - vt))
        if abs(at - vt) <= 0.5:
            pairs.append((vt, (at - vt) * 1000))
    if not pairs:
        print("闪白与提示音无法配对（偏差超过 500ms）")
        sys.exit(2)
    offs = [o for _, o in pairs]
    n = len(offs)
    mean = sum(offs) / n
    worst = max(offs, key=abs)
    # 线性回归：偏差随时间的漂移（ms / 分钟）
    ts = [t for t, _ in pairs]
    mt = sum(ts) / n
    var = sum((t - mt) ** 2 for t in ts)
    slope = (sum((t - mt) * (o - mean) for t, o in pairs) / var * 60) if var > 0 else 0.0
    first = sum(offs[: max(1, n // 10)]) / max(1, n // 10)
    last = sum(offs[-max(1, n // 10):]) / max(1, n // 10)
    print(f"配对 {n} 次（视频闪白 {len(v_on)}，音频提示音 {len(a_on)}），时长 {ts[-1] - ts[0]:.0f} 秒")
    print(f"平均偏差 {mean:+.0f} ms，最大偏差 {worst:+.0f} ms（>0 表示声音晚于画面）")
    print(f"漂移 {slope:+.2f} ms/分钟；开头 10% 平均 {first:+.0f} ms，结尾 10% 平均 {last:+.0f} ms")
    ok = abs(worst) <= 200
    print("结论：" + ("通过（|偏差| ≤ 200ms）" if ok else "不通过（存在 |偏差| > 200ms）"))
    sys.exit(0 if ok else 3)


if __name__ == "__main__":
    main()
