/*
 * LiveComposer 网页音频桥：在网页里把声音转给 App 混进直播，而不是从手机外放。
 * - Web Audio：拦截所有连到 AudioContext.destination 的连接（离线渲染的 OfflineAudioContext 除外），
 *   改接到一个采样节点，PCM 经 JS 接口送给 App；本机输出被静音（避免外放、避免麦克风再收进去形成回声）。
 * - <audio> / <video> / new Audio()：播放时接入一个共享 AudioContext，走同样的路径；
 *   用 MediaStream 播放的（srcObject，例如 WebRTC）用 createMediaStreamSource 接入，并把元素本身静音。
 * - 接不进来的媒体元素直接静音，保证手机不外放；跨域且服务器没开 CORS 的媒体，浏览器出于安全只给静音数据，采不到。
 * - 每个 frame（含 iframe）各自运行一份，并把接管情况通过 __lcAudio.status 报给 App 显示。
 * 由 App 在页面开始加载时注入（并在加载完成时补注入一次）；重复注入无副作用。
 */
(function () {
  if (window.__lcAudioHooked) return;
  var B = window.__lcAudio;
  if (!B || !window.AudioNode) return;
  window.__lcAudioHooked = true;

  var frameId = Math.random().toString(36).slice(2);
  var st = { ctx: 0, el: 0, stream: 0, muted: 0, cors: 0, err: '' };
  var statusTimer = 0;
  function report() {
    if (statusTimer) return;
    statusTimer = setTimeout(function () {
      statusTimer = 0;
      try { B.status(frameId, JSON.stringify(st)); } catch (_) {}
    }, 300);
  }

  var origConnect = AudioNode.prototype.connect;
  var taps = new WeakMap();

  function toB64(i16) {
    var u8 = new Uint8Array(i16.buffer), s = '', CH = 0x8000;
    for (var i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
    return btoa(s);
  }

  function tapFor(ctx) {
    var t = taps.get(ctx);
    if (t) return t;
    var sp = ctx.createScriptProcessor(2048, 2, 2);
    var mute = ctx.createGain();
    mute.gain.value = 0;
    t = { node: sp, active: false };
    sp.onaudioprocess = function (e) {
      var ib = e.inputBuffer;
      var L = ib.getChannelData(0), R = ib.numberOfChannels > 1 ? ib.getChannelData(1) : L;
      var n = L.length, out = new Int16Array(n * 2), any = false;
      for (var i = 0; i < n; i++) {
        var l = L[i], r = R[i];
        if (l !== 0 || r !== 0) any = true;
        out[2 * i] = Math.max(-1, Math.min(1, l)) * 32767;
        out[2 * i + 1] = Math.max(-1, Math.min(1, r)) * 32767;
      }
      // 静音时不传（省桥接开销），但声音刚停时多传一块静音收尾
      if (any || t.active) { try { B.pcm(toB64(out), ctx.sampleRate | 0, 2); } catch (_) {} }
      t.active = any;
    };
    origConnect.call(sp, mute);
    origConnect.call(mute, ctx.destination);
    taps.set(ctx, t);
    st.ctx++; report();
    return t;
  }

  function isRealtime(ctx) {
    return !(typeof OfflineAudioContext !== 'undefined' && ctx instanceof OfflineAudioContext);
  }

  AudioNode.prototype.connect = function (dest) {
    if (dest && typeof AudioDestinationNode !== 'undefined' && dest instanceof AudioDestinationNode && isRealtime(dest.context)) {
      var args = Array.prototype.slice.call(arguments);
      args[0] = tapFor(dest.context).node;
      return origConnect.apply(this, args);
    }
    return origConnect.apply(this, arguments);
  };

  var shared = null;
  function sharedCtx() {
    if (!shared) shared = new (window.AudioContext || window.webkitAudioContext)();
    if (shared.state === 'suspended') shared.resume();
    return shared;
  }

  function crossOriginWithoutCors(el) {
    var src = el.currentSrc || el.src || '';
    if (!/^https?:/i.test(src) || el.crossOrigin) return false;
    try { return new URL(src, location.href).origin !== location.origin; } catch (_) { return false; }
  }

  var hooked = new WeakSet();
  function hookEl(el) {
    if (!el || hooked.has(el)) return;
    hooked.add(el);
    try {
      var c = sharedCtx();
      var ms = el.srcObject;
      if (ms && typeof MediaStream !== 'undefined' && ms instanceof MediaStream) {
        if (ms.getAudioTracks().length > 0) c.createMediaStreamSource(ms).connect(c.destination);
        el.muted = true; // 元素自己的输出静音，声音走上面的流
        st.stream++;
      } else {
        c.createMediaElementSource(el).connect(c.destination); // 经上面的拦截接到采样节点
        st.el++;
        if (crossOriginWithoutCors(el)) st.cors++;
      }
    } catch (e) {
      // 接不进来：至少保证手机不外放
      try { el.muted = true; } catch (_) {}
      st.muted++; st.err = String((e && (e.name + ': ' + e.message)) || e).slice(0, 120);
    }
    report();
  }

  if (window.HTMLMediaElement) {
    var origPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () { hookEl(this); return origPlay.apply(this, arguments); };
    var onPlay = function (e) { if (e.target instanceof HTMLMediaElement) hookEl(e.target); };
    document.addEventListener('play', onPlay, true);
    document.addEventListener('playing', onPlay, true);
    // 兜底：定期扫描正在播放的元素（autoplay、Shadow DOM 里的播放器不一定触发上面的路径）
    var scanRoot = function (root) {
      var list = root.querySelectorAll('audio,video');
      for (var i = 0; i < list.length; i++) if (!list[i].paused) hookEl(list[i]);
    };
    var scan = function () {
      if (!document.documentElement) return;
      scanRoot(document);
      var w = document.createTreeWalker(document.documentElement, NodeFilter.SHOW_ELEMENT);
      for (var n = w.currentNode; n; n = w.nextNode()) if (n.shadowRoot) scanRoot(n.shadowRoot);
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scan); else scan();
    setInterval(scan, 2000);
  }
  report();
})();
