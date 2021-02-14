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

    function flash(button, message) {
        var original = button.textContent;
        button.textContent = message;
        button.disabled = true;
        setTimeout(function () {
            button.textContent = original;
            button.disabled = false;
        }, 1500);
    }

    function reveal(button) {
        var cell = button.closest('.key-value');
        var text = cell.querySelector('.key-value-text');

        if (button.dataset.revealed === 'true') {
            text.textContent = button.dataset.masked;
            button.dataset.revealed = 'false';
            button.textContent = 'Show';
            return;
        }

        fetchValue(button.dataset.valueUrl)
            .then(function (value) {
                button.dataset.masked = text.textContent;
                text.textContent = value;
                button.dataset.revealed = 'true';
                button.textContent = 'Hide';
            })
            .catch(function (err) {
                console.error(err);
                text.textContent = 'Unable to load key';
            });
    }

    function copy(button) {
        if (!navigator.clipboard) {
            flash(button, 'Not supported');
            return;
        }
        fetchValue(button.dataset.valueUrl)
            .then(function (value) {
                return navigator.clipboard.writeText(value);
            })
            .then(function () {
                flash(button, 'Copied!');
            })
            .catch(function (err) {
                console.error(err);
                flash(button, 'Failed');
            });
    }

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
