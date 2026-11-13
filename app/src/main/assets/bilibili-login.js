/* Layout only: retain all official controls and legal text; never read form values. */
(function () {
  if (location.hostname !== 'passport.bilibili.com' ||
      location.pathname.replace(/\/$/, '') !== '/h5-app/passport/login') return;
  if (document.getElementById('yingxia-login-layout')) return;
  var style = document.createElement('style');
  style.id = 'yingxia-login-layout';
  // The official absolute footer overlaps the login-method button in a short WebView.
  // Normal flow keeps the explanation below the form and lets the page scroll.
  style.textContent = '.login-wrap .explain-tips{position:static!important;margin-top:.64rem!important;padding-bottom:.64rem!important}';
  (document.head || document.documentElement).appendChild(style);
})();
