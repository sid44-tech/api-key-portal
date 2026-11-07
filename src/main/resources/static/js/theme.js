/*
 * Follows the operating system's light/dark setting.
 *
 * Bootstrap 5.3 switches its own component colours on the data-bs-theme
 * attribute rather than a media query, so something has to set it. This loads
 * in <head>, before first paint, so the page never flashes light then darken.
 * There is no in-app toggle on purpose: matching the OS is the behaviour people
 * already expect, and one fewer setting to explain.
 */
(function () {
    'use strict';

    var media = window.matchMedia('(prefers-color-scheme: dark)');

    function apply() {
        document.documentElement.setAttribute('data-bs-theme', media.matches ? 'dark' : 'light');
    }

    apply();

    if (media.addEventListener) {
        media.addEventListener('change', apply);
    } else if (media.addListener) {
        // Safari before 14
        media.addListener(apply);
    }
})();

/* The sidebar's collapsed state, also before first paint, so it never jumps. */
(function () {
    'use strict';
    try {
        if (localStorage.getItem('kp-nav-collapsed') === '1') {
            document.documentElement.classList.add('kp-nav-collapsed');
        }
    } catch (e) {
        // storage unavailable: start expanded
    }
})();
