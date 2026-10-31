(function () {
    'use strict';

    function fetchValue(url) {
        return fetch(url, {credentials: 'same-origin', headers: {'Accept': 'application/json'}})
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('Request failed with ' + response.status);
                }
                return response.json();
            })
            .then(function (body) {
                return body.value;
            });
    }

    function setIcon(button, icon, label) {
        button.querySelector('.bi').className = 'bi ' + icon;
        button.querySelector('.visually-hidden').textContent = label;
        button.title = label;
    }

    function flash(button, icon, label) {
        var originalIcon = button.querySelector('.bi').className.replace('bi ', '');
        var originalLabel = button.title;
        setIcon(button, icon, label);
        button.disabled = true;
        setTimeout(function () {
            setIcon(button, originalIcon, originalLabel);
            button.disabled = false;
        }, 1500);
    }

    function reveal(button) {
        var cell = button.closest('.key-value');
        var text = cell.querySelector('.key-value-text');

        if (button.dataset.revealed === 'true') {
            text.textContent = button.dataset.masked;
            text.classList.remove('is-revealed');
            button.dataset.revealed = 'false';
            setIcon(button, 'bi-eye', 'Show value');
            return;
        }

        fetchValue(button.dataset.valueUrl)
            .then(function (value) {
                button.dataset.masked = text.textContent;
                text.textContent = value;
                // the styling shift is the cue that what's on screen is now the real secret
                text.classList.add('is-revealed');
                button.dataset.revealed = 'true';
                setIcon(button, 'bi-eye-slash', 'Hide value');
            })
            .catch(function (err) {
                console.error(err);
                text.textContent = 'Unable to load key';
            });
    }

    function copy(button) {
        if (!navigator.clipboard) {
            flash(button, 'bi-x-lg', 'Clipboard not supported');
            return;
        }
        fetchValue(button.dataset.valueUrl)
            .then(function (value) {
                return navigator.clipboard.writeText(value);
            })
            .then(function () {
                flash(button, 'bi-check2', 'Copied!');
            })
            .catch(function (err) {
                console.error(err);
                flash(button, 'bi-x-lg', 'Copy failed');
            });
    }

    var deleteModal = document.getElementById('deleteModal');
    if (deleteModal) {
        deleteModal.addEventListener('show.bs.modal', function (event) {
            var button = event.relatedTarget;
            document.getElementById('deleteKeyName').textContent = button.dataset.keyName;
            document.getElementById('deleteForm').action = button.dataset.deleteUrl;
        });
    }

    // a switch that is really a form: flipping it saves straight away
    document.querySelectorAll('.js-submit-on-change').forEach(function (toggle) {
        toggle.addEventListener('change', function () {
            toggle.form.submit();
        });
    });

    document.querySelectorAll('.js-autosubmit').forEach(function (select) {
        select.addEventListener('change', function () {
            select.form.submit();
        });
    });

    document.addEventListener('submit', function (event) {
        var form = event.target.closest('.js-confirm');
        if (form && !window.confirm(form.dataset.confirm)) {
            event.preventDefault();
        }
    });

    document.addEventListener('click', function (event) {
        var revealButton = event.target.closest('.js-reveal');
        if (revealButton) {
            reveal(revealButton);
            return;
        }
        var copyButton = event.target.closest('.js-copy');
        if (copyButton) {
            copy(copyButton);
        }
    });
})();
