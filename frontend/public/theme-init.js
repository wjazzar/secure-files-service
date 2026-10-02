// Applies the stored appearance before the first paint. The application only
// renders once authentication has answered: without this, a dark preference
// would show the light theme for that whole time.
//
// A file rather than an inline script, so that a Content-Security-Policy
// without 'unsafe-inline' stays possible. The key is the one ThemeProvider
// writes; theme-init.test.ts fails if the two drift apart.
(function () {
  try {
    if (localStorage.getItem('praxedo-theme') === 'dark') document.documentElement.classList.add('dark');
  } catch {
    // Storage unavailable: the default light theme applies.
  }
})();
