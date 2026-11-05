/*
 * LiveComposer 网页音频桥：在网页里把声音转给 App 混进直播，而不是从手机外放。
 * - Web Audio：拦截所有连到 AudioContext.destination 的连接，改接到一个采样节点，PCM 经 JS 接口送给 App；
 *   本机输出被静音（避免麦克风再收进去形成回声）。
 * - <audio> / <video> / new Audio()：播放时接入一个共享 AudioContext，走同样的路径。
 *   跨域且服务器没开 CORS 的媒体，浏览器出于安全只给静音数据，这种声音采不到。
 * 由 App 在页面开始加载时注入；重复注入无副作用。
 */
(function () {
  if (window.__lcAudioHooked) return;
  var B = window.__lcAudio;
  if (!B || !window.AudioNode) return;
  window.__lcAudioHooked = true;

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
    return t;
  }

  AudioNode.prototype.connect = function (dest) {
    if (dest && typeof AudioDestinationNode !== 'undefined' && dest instanceof AudioDestinationNode) {
      var args = Array.prototype.slice.call(arguments);
      args[0] = tapFor(this.context).node;
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
  var hooked = new WeakSet();
  function hookEl(el) {
    if (!el || hooked.has(el)) return;
    hooked.add(el);
    try {
      var c = sharedCtx();
      c.createMediaElementSource(el).connect(c.destination); // 经上面的拦截接到采样节点
    } catch (_) {}
  }
  if (window.HTMLMediaElement) {
    var origPlay = HTMLMediaElement.prototype.play;
    HTMLMediaElement.prototype.play = function () { hookEl(this); return origPlay.apply(this, arguments); };
    document.addEventListener('play', function (e) { if (e.target instanceof HTMLMediaElement) hookEl(e.target); }, true);
    var scan = function () {
      Array.prototype.forEach.call(document.querySelectorAll('audio,video'), function (el) { if (!el.paused) hookEl(el); });
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scan); else scan();
  }
})();
