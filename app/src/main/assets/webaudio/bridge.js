/*
 * LiveComposer 网页音频桥：在网页里把声音转给 App 混进直播，而不是从手机外放。
 * - Web Audio：拦截所有连到 AudioContext.destination 的连接（离线渲染的 OfflineAudioContext 除外），
 *   改接到一个采样节点（优先 AudioWorklet，在音频线程采样，主线程卡顿也不丢数据；不支持时退回 ScriptProcessor），PCM 经 JS 接口送给 App；本机输出被静音（避免外放、避免麦克风再收进去形成回声）。
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
  var st = { ctx: 0, el: 0, stream: 0, muted: 0, cors: 0, worklet: 0, err: '' };
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
  var allTaps = [];
  // 本机监听音量：App 设置“在手机上也播放”时 > 0，默认 0（只进推流、不外放）
  var monitor = 0;
  function readMonitor() {
    var g = 0;
    try { g = +B.monitor() || 0; } catch (_) {}
    if (g !== monitor) {
      monitor = g;
      for (var i = 0; i < allTaps.length; i++) allTaps[i].mute.gain.value = g;
    }
  }

  function toB64(i16) {
    var u8 = new Uint8Array(i16.buffer), s = '', CH = 0x8000;
    for (var i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH));
    return btoa(s);
  }

  function send(t, ctx, L, R) {
    var n = L.length, out = new Int16Array(n * 2);
    for (var i = 0; i < n; i++) {
      out[2 * i] = Math.max(-1, Math.min(1, L[i])) * 32767;
      out[2 * i + 1] = Math.max(-1, Math.min(1, R[i])) * 32767;
    }
    try { B.pcm(toB64(out), ctx.sampleRate | 0, 2); } catch (_) {}
  }

  // AudioWorklet 采样：在音频线程攒满 2048 帧后 postMessage 到主线程。
  // 主线程忙（网页动画、WebGL 渲染）时消息只是排队晚到、不会丢；
  // ScriptProcessor 在主线程跟不上时会直接丢数据，声音就一卡一卡的，所以只作兜底。
  var WORKLET_SRC =
    "class LcTap extends AudioWorkletProcessor{constructor(){super();this.n=0;this.L=new Float32Array(2048);this.R=new Float32Array(2048);this.any=false;this.active=false;}" +
    "process(ins,outs){var i=ins[0]||[],o=outs[0]||[],l=i[0],r=i.length>1?i[1]:l,m=o.length?o[0].length:128;" +
    "for(var k=0;k<m;k++){var a=l?l[k]:0,b=r?r[k]:0;if(o[0])o[0][k]=a;if(o[1])o[1][k]=b;if(a!==0||b!==0)this.any=true;this.L[this.n]=a;this.R[this.n]=b;this.n++;" +
    "if(this.n===2048){if(this.any||this.active){this.port.postMessage({l:this.L,r:this.R},[this.L.buffer,this.R.buffer]);this.L=new Float32Array(2048);this.R=new Float32Array(2048);}" +
    "this.active=this.any;this.any=false;this.n=0;}}return true;}}registerProcessor('lc-tap',LcTap);";
  var workletUrl = null;
  function workletFor(ctx) {
    if (!ctx.audioWorklet || typeof AudioWorkletNode === 'undefined') return Promise.reject(new Error('no worklet'));
    if (!workletUrl) workletUrl = URL.createObjectURL(new Blob([WORKLET_SRC], { type: 'application/javascript' }));
    return ctx.audioWorklet.addModule(workletUrl);
  }

  function tapFor(ctx) {
    var t = taps.get(ctx);
    if (t) return t;
    // 所有连到 destination 的连接都改接到 input；input 后面先挂 ScriptProcessor，Worklet 就绪后换掉
    var input = ctx.createGain();
    var mute = ctx.createGain();
    mute.gain.value = monitor;
    t = { node: input, mute: mute, active: false, worklet: false };
    allTaps.push(t);
    var sp = ctx.createScriptProcessor(2048, 2, 2);
    sp.onaudioprocess = function (e) {
      if (t.worklet) return;
      var ib = e.inputBuffer, ob = e.outputBuffer;
      var L = ib.getChannelData(0), R = ib.numberOfChannels > 1 ? ib.getChannelData(1) : L;
      // 透传给本机监听（音量由 mute 控制，默认 0）
      ob.getChannelData(0).set(L); if (ob.numberOfChannels > 1) ob.getChannelData(1).set(R);
      var any = false;
      for (var i = 0; i < L.length; i++) if (L[i] !== 0 || R[i] !== 0) { any = true; break; }
      // 静音时不传（省桥接开销），但声音刚停时多传一块静音收尾
      if (any || t.active) send(t, ctx, L, R);
      t.active = any;
    };
    origConnect.call(input, sp);
    origConnect.call(sp, mute);
    origConnect.call(mute, ctx.destination);
    taps.set(ctx, t);
    st.ctx++; report();
    workletFor(ctx).then(function () {
      var wn = new AudioWorkletNode(ctx, 'lc-tap', { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [2], channelCount: 2, channelCountMode: 'explicit' });
      wn.port.onmessage = function (e) { send(t, ctx, e.data.l, e.data.r); };
      t.worklet = true;
      origConnect.call(input, wn);
      origConnect.call(wn, mute);
      try { input.disconnect(sp); sp.disconnect(); } catch (_) {}
      sp.onaudioprocess = null;
      st.worklet = (st.worklet || 0) + 1; report();
    }).catch(function (e) {
      st.err = st.err || ('worklet: ' + String((e && e.message) || e)).slice(0, 120); report();
    });
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
  readMonitor();
  setInterval(readMonitor, 500);
  report();
})();
