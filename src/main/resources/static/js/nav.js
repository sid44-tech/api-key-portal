/*
 * The sidebar: collapses to icons on wide screens (remembered per browser), and
 * slides in over the page on narrow ones.
 */
(function () {
    'use strict';

    var root = document.documentElement;
    var sidebar = document.getElementById('kp-sidebar');
    if (!sidebar) {
        return;
    }
    var mobile = window.matchMedia('(max-width: 991.98px)');
    var collapseButton = document.querySelector('.js-nav-collapse');
    var openButton = document.querySelector('.js-nav-open');

    function syncCollapseButton() {
        var collapsed = root.classList.contains('kp-nav-collapsed');
        if (collapseButton) {
            collapseButton.setAttribute('aria-expanded', String(!collapsed));
            collapseButton.title = collapsed ? 'Expand sidebar' : 'Collapse sidebar';
            collapseButton.querySelector('.bi').className =
                'bi ' + (collapsed ? 'bi-chevron-double-right' : 'bi-chevron-double-left');
        }
    }

    function setOpen(open) {
        root.classList.toggle('kp-nav-open', open);
        if (openButton) {
            openButton.setAttribute('aria-expanded', String(open));
        }
        if (open) {
            var first = sidebar.querySelector('a, button');
            if (first) {
                first.focus();
            }
        }
    }

    if (collapseButton) {
        collapseButton.addEventListener('click', function () {
            var collapsed = root.classList.toggle('kp-nav-collapsed');
            try {
                localStorage.setItem('kp-nav-collapsed', collapsed ? '1' : '0');
            } catch (e) {
                // private mode: the choice lasts for this page only
            }
            syncCollapseButton();
        });
    }
    if (openButton) {
        openButton.addEventListener('click', function () {
            setOpen(true);
        });
    }
    document.querySelectorAll('.js-nav-close').forEach(function (element) {
        element.addEventListener('click', function () {
            setOpen(false);
            if (openButton) {
                openButton.focus();
            }
        });
    });
    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && root.classList.contains('kp-nav-open')) {
            setOpen(false);
            if (openButton) {
                openButton.focus();
            }
        }
    });
    var onChange = function () {
        if (!mobile.matches) {
            setOpen(false);
        }
    };
    if (mobile.addEventListener) {
        mobile.addEventListener('change', onChange);
    }

    syncCollapseButton();
})();
