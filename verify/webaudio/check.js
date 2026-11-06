// 网页音频桥检查：在 Chromium 里注入 app/src/main/assets/webaudio/bridge.js，验证 Web Audio 与 <audio> 的声音被转到桥接口。
// 用法：bash verify/webaudio/run.sh（需要 node、playwright、python3）
const { chromium } = require('playwright');
const fs = require('fs');
(async () => {
  const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
  const p = await b.newPage();
  await p.addInitScript(() => {
    window.__calls = []; window.__disc = 0; window.__last = null;
    window.__lcAudio = { pcm: (b64, rate, ch) => {
      const s = atob(b64); const n = s.length / 2; let peak = 0, cross = 0, prev = 0, last = window.__last;
      for (let i = 0; i < n; i += 2) { let v = (s.charCodeAt(2*i) | (s.charCodeAt(2*i+1) << 8)); if (v > 32767) v -= 65536;
        peak = Math.max(peak, Math.abs(v)); if ((v < 0) !== (prev < 0)) cross++; prev = v;
        if (last !== null && Math.abs(v - last) > 3000) window.__disc++; last = v; }
      window.__last = last;
      window.__calls.push({ rate, ch, frames: n / 2, peak, cross });
    }, status: (id, json) => { window.__status = JSON.parse(json); }, monitor: () => 0 };
  });
  await p.addInitScript({ content: fs.readFileSync(process.argv[2], 'utf8') });
  await p.goto(process.argv[3]);
  console.log('WA state', await p.evaluate(() => startWA()));
  await p.waitForTimeout(1500);
  const wa = await p.evaluate(() => { const c = window.__calls; window.__calls = []; return c; });
  console.log('WebAudio calls', wa.length, JSON.stringify(wa.slice(2, 4)));
  if (wa.length < 10 || Math.abs(wa[3].peak - 16383) > 300 || Math.abs(wa[3].cross - 93) > 4) { console.log('FAIL webaudio'); process.exit(1); }
  // 主线程卡顿（模拟网页动画 / WebGL 繁忙）：每 300ms 阻塞 120ms，声音必须连续、不丢数据
  await p.evaluate(() => { window.__disc = 0; window.__j = setInterval(() => { const t = performance.now(); while (performance.now() - t < 120) {} }, 300); });
  await p.waitForTimeout(3000);
  const disc = await p.evaluate(() => { clearInterval(window.__j); return window.__disc; });
  console.log('discontinuities under main-thread jank', disc);
  if (disc > 0) { console.log('FAIL audio dropped while main thread busy'); process.exit(1); }
  console.log('el', await p.evaluate(() => startEl()));
  await p.waitForTimeout(1500);
  const el = await p.evaluate(() => window.__calls);
  console.log('Element+WA calls', el.length, JSON.stringify(el.slice(-2)));
  if (!el.some(c => Math.abs(c.peak - 11999) < 300 && Math.abs(c.cross - 61) < 5)) { console.log('FAIL media element'); process.exit(1); }
  const off = await p.evaluate(() => offline());
  console.log('offline peak', off);
  if (!(off > 0.9)) { console.log('FAIL offline context was hijacked'); process.exit(1); }
  await p.evaluate(() => { window.__calls = []; });
  console.log('stream', await p.evaluate(() => startStream()));
  await p.waitForTimeout(1500);
  const sc = await p.evaluate(() => window.__calls);
  if (!sc.some(c => c.cross > 30 && c.cross < 50)) { console.log('FAIL MediaStream element'); process.exit(1); }
  const status = await p.evaluate(() => window.__status);
  console.log('status', JSON.stringify(status));
  if (!status || status.el < 1 || status.stream < 1 || status.ctx < 1 || status.worklet < 1) { console.log('FAIL status'); process.exit(1); }
  console.log('WEB AUDIO BRIDGE OK');
  await b.close();
})();
