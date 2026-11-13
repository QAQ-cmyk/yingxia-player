/* Original, deliberately narrow cosmetic rules; no account or form data is read. */
(function () {
  if (!/^(www|m)\.bilibili\.com$/.test(location.hostname)) return;
  if (window.__yingxiaClean) return;
  window.__yingxiaClean = true;
  var style = document.createElement('style');
  style.id = 'yingxia-clean-style';
  style.textContent = '.adcard,.ad-banner,.slide-ad,.ad-floor-exp,.bili-video-card.is-ad,[data-yx-ad],.m-navbar__openapp,.launch-app-btn,.m-nav-openapp,.m-video-main-launchapp{display:none!important}';
  (document.head || document.documentElement).appendChild(style);
  var pending = false;
  function clean() {
    pending = false;
    document.querySelectorAll('.bili-video-card__info--ad,.bili-video-card__info--creative-ad').forEach(function (tag) {
      var card = tag.closest('.bili-video-card');
      if (card && !card.hasAttribute('data-yx-ad')) card.setAttribute('data-yx-ad', '');
    });
  }
  clean();
  new MutationObserver(function () {
    if (!pending) { pending = true; setTimeout(clean, 200); }
  }).observe(document.documentElement, { childList: true, subtree: true });
})();
