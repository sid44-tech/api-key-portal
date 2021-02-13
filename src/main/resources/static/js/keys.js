(function () {
    'use strict';

    function reveal(button) {
        var cell = button.closest('.key-value');
        var text = cell.querySelector('.key-value-text');

        if (button.dataset.revealed === 'true') {
            text.textContent = button.dataset.masked;
            button.dataset.revealed = 'false';
            button.textContent = 'Show';
            return;
        }

        fetch(button.dataset.valueUrl, {credentials: 'same-origin', headers: {'Accept': 'application/json'}})
            .then(function (response) {
                if (!response.ok) {
                    throw new Error('Request failed with ' + response.status);
                }
                return response.json();
            })
            .then(function (body) {
                button.dataset.masked = text.textContent;
                text.textContent = body.value;
                button.dataset.revealed = 'true';
                button.textContent = 'Hide';
            })
            .catch(function (err) {
                console.error(err);
                text.textContent = 'Unable to load key';
            });
    }

    document.addEventListener('click', function (event) {
        var button = event.target.closest('.js-reveal');
        if (button) {
            reveal(button);
        }
    });
})();
