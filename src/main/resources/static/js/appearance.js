/*
 * The appearance page: keeps each colour picker and its hex field in step, and
 * recolours the preview beside them as the values change.
 *
 * The CSP forbids inline styles, but not the CSSOM: setting custom properties
 * on an element from script is allowed, and that is how the preview is
 * recoloured - the same variables app.css reads, set on the preview alone.
 */
(function () {
    'use strict';

    var HEX = /^#?[0-9a-fA-F]{6}$/;

    function normalize(value) {
        var v = (value || '').trim();
        if (!HEX.test(v)) {
            return null;
        }
        return (v.charAt(0) === '#' ? v : '#' + v).toLowerCase();
    }

    function rgba(hex, alpha) {
        var r = parseInt(hex.slice(1, 3), 16), g = parseInt(hex.slice(3, 5), 16), b = parseInt(hex.slice(5, 7), 16);
        return 'rgba(' + r + ', ' + g + ', ' + b + ', ' + alpha + ')';
    }

    function mix(a, b, weight) {
        var out = '#';
        for (var i = 1; i < 7; i += 2) {
            var ca = parseInt(a.slice(i, i + 2), 16), cb = parseInt(b.slice(i, i + 2), 16);
            var v = Math.round(ca * weight + cb * (1 - weight));
            out += ('0' + v.toString(16)).slice(-2);
        }
        return out;
    }

    function fieldsOf(mode) {
        return document.querySelectorAll('.js-color-field[data-mode="' + mode + '"]');
    }

    function valuesOf(mode) {
        var values = {};
        fieldsOf(mode).forEach(function (field) {
            values[field.dataset.color] = normalize(field.querySelector('.js-color-hex').value);
        });
        return values;
    }

    // Mirrors ThemePalette.toCss closely enough for a preview; the server is
    // the source of truth for what everyone else sees.
    function paint(mode) {
        var preview = document.querySelector('.kp-theme-preview[data-preview="' + mode + '"]');
        if (!preview) {
            return;
        }
        var dark = mode === 'dark';
        var c = valuesOf(mode);
        var style = preview.style;
        if (c.canvas) {
            style.setProperty('--kp-canvas', c.canvas);
        }
        if (c.surface) {
            style.setProperty('--kp-surface', c.surface);
        }
        if (c.ink) {
            style.setProperty('--kp-ink', c.ink);
            if (c.canvas) {
                style.setProperty('--kp-ink-2', mix(c.ink, c.canvas, 0.75));
            }
        }
        if (c.accent) {
            style.setProperty('--kp-accent', c.accent);
            style.setProperty('--kp-accent-hover', dark ? mix(c.accent, '#ffffff', 0.85) : mix(c.accent, '#000000', 0.85));
            style.setProperty('--bs-primary-rgb', rgbList(c.accent));
        }
        if (c.success) {
            style.setProperty('--kp-green', c.success);
            style.setProperty('--kp-green-soft', rgba(c.success, dark ? 0.16 : 0.12));
        }
        if (c.warning) {
            style.setProperty('--kp-amber', c.warning);
            style.setProperty('--kp-amber-soft', rgba(c.warning, dark ? 0.18 : 0.16));
        }
        if (c.danger) {
            style.setProperty('--kp-red', c.danger);
            style.setProperty('--kp-red-soft', rgba(c.danger, dark ? 0.16 : 0.11));
        }
    }

    function rgbList(hex) {
        return parseInt(hex.slice(1, 3), 16) + ', ' + parseInt(hex.slice(3, 5), 16) + ', ' + parseInt(hex.slice(5, 7), 16);
    }

    document.querySelectorAll('.js-color-field').forEach(function (field) {
        var picker = field.querySelector('.js-color-picker');
        var hex = field.querySelector('.js-color-hex');
        var mode = field.dataset.mode;

        // the swatch starts on whatever the field holds
        var initial = normalize(hex.value);
        if (initial) {
            picker.value = initial;
        }

        // typing a valid hex moves the picker (which is the swatch) and the preview
        hex.addEventListener('input', function () {
            var value = normalize(hex.value);
            hex.classList.toggle('is-invalid', hex.value.trim() !== '' && value === null);
            if (value) {
                picker.value = value;
            }
            paint(mode);
        });
        // choosing from the picker writes the hex and the preview
        picker.addEventListener('input', function () {
            hex.value = picker.value;
            hex.classList.remove('is-invalid');
            paint(mode);
        });
    });

    document.querySelectorAll('.js-use-defaults').forEach(function (button) {
        button.addEventListener('click', function () {
            var defaults = button.dataset.defaults.split(',');
            var order = ['accent', 'canvas', 'surface', 'ink', 'success', 'warning', 'danger'];
            fieldsOf(button.dataset.mode).forEach(function (field) {
                var value = defaults[order.indexOf(field.dataset.color)];
                field.querySelector('.js-color-hex').value = value;
                field.querySelector('.js-color-picker').value = value;
                field.querySelector('.js-color-hex').classList.remove('is-invalid');
            });
            paint(button.dataset.mode);
        });
    });

    paint('light');
    paint('dark');
})();
