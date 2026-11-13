/* Bilibili shell adapter. Uses the site's existing video element and public links;
 * never copies media URLs, reads credentials, or creates another playback page. */
(function () {
  if (!/^(www|m)\.bilibili\.com$/.test(location.hostname) || window.__yingxiaPlayer) return;
  window.__yingxiaPlayer = true;
  var mobile = location.hostname === 'm.bilibili.com';
  var style = document.createElement('style');
  style.id = 'yingxia-shell-style';
  style.textContent = `
    .m-navbar,.m-nav-openapp,.m-fixed-openapp,.m-video-main-launchapp,.m-related-openapp,.openapp-dialog,
    .launch-app-btn,.v-card-toapp .open-app{display:none!important}
    .yx-inline-ready .video-share{display:block!important}
    .yx-inline-ready .m-video-player{position:relative!important;top:auto!important;height:auto!important;padding:0!important;z-index:auto!important}
    .yx-inline-ready .player-container{position:relative!important;width:100%!important;height:56.25vw!important;max-height:70vh!important}
    .yx-inline-ready .player-container>.video-cover{display:none!important}
    .yx-inline-ready .fixed-wrapper{position:static!important;top:auto!important}
    .yx-inline-ready .related-margin{margin-top:12px!important}
    .yx-inline-ready .natural-search-main-video{margin-top:0!important}
    .yx-inline-ready .natural-search-main-video .main-cover,.yx-inline-ready .natural-search-main-video .btn{display:none!important}
    .yx-inline-ready .natural-search-main-video .main-info{width:100%!important;margin:0!important}
    .yx-inline-ready .natural-search-main-video .title{display:block!important;-webkit-line-clamp:unset!important;height:auto!important;max-height:none!important}
    .yx-inline-ready .gsl-area> :not(video),.yx-inline-ready .gsl-sendbar,.yx-inline-ready .gsl-control{display:none!important}
    .yx-inline-ready .gsl-video{position:relative!important;z-index:2!important;object-fit:contain!important}
    .yx-inline-tools{display:flex;align-items:center;flex-wrap:wrap;gap:4px;padding:0 8px;background:#17171b;color:#fff;font:14px sans-serif;position:relative;z-index:3}
    .yx-inline-tools button,.yx-inline-tools select{min-height:48px;min-width:48px;border:0;border-radius:8px;background:transparent;color:inherit;font:inherit;padding:0 10px}
    .yx-inline-tools select{background:#29292e}.yx-inline-tools output{flex:1;min-width:65px;text-align:center;font-size:12px;color:#ddd}
    .yx-inline-error{margin:0;padding:12px;background:#30232a;color:#ffe4ed;font:14px/1.5 sans-serif}
  `;
  (document.head || document.documentElement).appendChild(style);
  // Header space belongs to the app; preserve the site's category/search controls.
  if (mobile) {
    var layout = document.createElement('style');
    layout.textContent = '.m-head{height:auto!important}.suspension,.suspension-box{position:static!important;height:auto!important;min-height:0!important}.m-home{padding-top:0!important}.m-video>div{padding-top:0!important}';
    document.head.appendChild(layout);
  }
  function safeUrl(raw) {
    try {
      var u = new URL(raw, location.href);
      return u.protocol === 'https:' && !u.username && !u.password && (!u.port || u.port === '443') &&
        /(^|\.)bilibili\.com$/.test(u.hostname) ? u : null;
    } catch (_) { return null; }
  }
  function routeFor(node) {
    var universal = node.getAttribute('universallink');
    var u = universal && safeUrl(universal);
    if (u && /^\/(video|bangumi|space|search)(\/|$)/.test(u.pathname)) return u;
    var bvid = node.getAttribute('bvid');
    if (/^BV[0-9A-Za-z]{10}$/.test(bvid || '')) return safeUrl('/video/' + bvid);
    var aid = node.getAttribute('data-aid');
    if (/^[1-9][0-9]*$/.test(aid || '')) return safeUrl('/video/av' + aid);
    var schema = node.getAttribute('schema') || node.getAttribute('href') || '';
    var match = schema.match(/^bilibili:\/\/video\/(BV[0-9A-Za-z]{10}|(?:av)?[1-9][0-9]*)(?:[/?#]|$)/);
    if (match) return safeUrl('/video/' + (/^\d/.test(match[1]) ? 'av' : '') + match[1]);
    return null;
  }
  function currentVideo(node, target) {
    if (!/\/video\//.test(location.pathname) || !/\/video\//.test(target.pathname)) return false;
    if ((target.searchParams.get('p') || '1') !== (new URL(location.href).searchParams.get('p') || '1')) return false;
    var id = location.pathname.split('/').filter(Boolean).pop();
    return target.pathname.replace(/\/$/, '') === location.pathname.replace(/\/$/, '') ||
      node.getAttribute('bvid') === id || !!node.closest('.natural-search-main-video,.m-video-main-launchapp');
  }
  // Capture before the website's custom element starts its App/clipboard/market fallback.
  document.addEventListener('click', function (event) {
    var origin = event.target instanceof Element ? event.target : event.target.parentElement;
    if (!origin || origin.closest('.yx-inline-tools')) return;
    var node = origin.closest('m-open-app,[universallink],.v-card-single[data-aid],a[href]');
    if (!node) return;
    var target = routeFor(node);
    if (!target) {
      var href = node.getAttribute('href');
      var url = href && safeUrl(href);
      if (url && /^(www|m)\.bilibili\.com$/.test(url.hostname) && /^\/video\//.test(url.pathname)) target = url;
    }
    if (!target) return;
    event.preventDefault(); event.stopImmediatePropagation();
    if (currentVideo(node, target)) {
      var v = document.querySelector('video[data-yx-inline]');
      if (v) { v.scrollIntoView({block:'center'}); v.play().catch(function () {}); }
      return;
    }
    if (mobile && target.hostname === 'www.bilibili.com') target.hostname = 'm.bilibili.com';
    location.assign(target.href);
  }, true);
  var active = new Map(), pending = false;
  function install(video) {
    if (active.has(video)) return;
    // Desktop / MediaSource players retain the site's own controls and subtitle renderer.
    if (!mobile || !video.matches('.gsl-video') || !video.closest('.m-video-player')) return;
    video.controls = true; video.playsInline = true;
    video.setAttribute('data-yx-inline', '');
    video.setAttribute('controlsList', 'nodownload noremoteplayback');
    document.documentElement.classList.add('yx-inline-ready');
    var host = video.closest('.m-video-player');
    var tools = document.createElement('div'); tools.className = 'yx-inline-tools'; tools.setAttribute('aria-label', '视频控制');
    var message = document.createElement('p'); message.className = 'yx-inline-error'; message.hidden = true;
    function play() { video.play().catch(function () { message.textContent = '暂时无法播放，请重试或登录后再试。'; message.hidden = false; }); }
    function button(label, fn) {
      var b = document.createElement('button'); b.type = 'button'; b.textContent = label;
      b.addEventListener('click', function (e) { e.stopPropagation(); fn(); }); tools.appendChild(b); return b;
    }
    var toggle = button('播放', function () { if (video.paused) play(); else video.pause(); });
    var output = document.createElement('output'); tools.appendChild(output);
    var speed = document.createElement('select'); speed.setAttribute('aria-label', '播放倍速');
    [0.25,0.5,0.75,1,1.25,1.5,2,3].forEach(function (rate) { var o=document.createElement('option');o.value=rate;o.textContent=rate+'×';o.selected=rate===video.playbackRate;speed.appendChild(o); });
    speed.addEventListener('change', function () { video.playbackRate = Number(speed.value); }); tools.appendChild(speed);
    button('全屏', function () { if (video.requestFullscreen) video.requestFullscreen().catch(function () { message.textContent='系统网页组件暂不支持全屏。';message.hidden=false; }); });
    function time(value) { value=Math.max(0,Math.floor(value||0));return Math.floor(value/60)+':'+String(value%60).padStart(2,'0'); }
    function sync() { toggle.textContent=video.paused?'播放':'暂停';output.textContent=time(video.currentTime)+' / '+time(video.duration); }
    function fail() { message.textContent='视频加载失败，可在右上角刷新或登录后重试。';message.hidden=false; }
    ['play','pause','timeupdate','loadedmetadata','ended'].forEach(function (name) { video.addEventListener(name,sync); });
    video.addEventListener('error', fail);
    ['playing','loadedmetadata','canplay'].forEach(function (name) { video.addEventListener(name, function () { if (!video.error) message.hidden=true; }); });
    host.appendChild(tools); host.appendChild(message); sync();
    active.set(video, {tools:tools,message:message});
  }
  function update() {
    pending=false;
    active.forEach(function (state,video) { if (!video.isConnected) { video.pause();state.tools.remove();state.message.remove();active.delete(video); } });
    document.querySelectorAll('video').forEach(install);
    if (!active.size) document.documentElement.classList.remove('yx-inline-ready');
  }
  function schedule() { if (!pending) { pending=true;setTimeout(update,100); } }
  new MutationObserver(schedule).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});
  document.addEventListener('loadedmetadata',schedule,true);
  document.addEventListener('visibilitychange',function () { if(document.hidden) active.forEach(function (_,v) { v.pause(); }); });
  update();
})();
