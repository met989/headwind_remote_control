(function (window, $) {
    'use strict';

    var defaultSettings = {
        portApiHttps: '8089',
        portApiWss: '8989',
        apiSecret: '',
        defaultQuality: 'medium',
        autoTimeoutMs: '2200',
        maxRetries: '12',
        serverUrl: '',
        stunServer: 'stun:stun.l.google.com:19302',
        turnServer: '',
        turnUsername: '',
        turnPassword: ''
    };

    function SettingsManager() {
        this.config = Object.assign({}, defaultSettings);
        this.isLoaded = false;
    }

    SettingsManager.prototype.load = function (callback) {
        var self = this;
        if (self.isLoaded) {
            self.applyToRuntime();
            self.populateForm();
            if (typeof callback === 'function') callback(null, self.config);
            return;
        }
        $.ajax({
            url: 'api/config',
            method: 'GET',
            dataType: 'json',
            cache: false
        }).done(function (resp) {
            if (resp && resp.status === 'OK' && resp.data) {
                self.config = Object.assign({}, defaultSettings, resp.data);
            }
            self.applyToRuntime();
            self.populateForm();
            self.isLoaded = true;
            if (typeof callback === 'function') callback(null, self.config);
        }).fail(function (xhr) {
            console.warn('[SettingsManager] Could not load api/config, using defaults/settings.js:', xhr.status);
            self.applyToRuntime();
            self.populateForm();
            self.isLoaded = true;
            if (typeof callback === 'function') callback(xhr, self.config);
        });
    };

    SettingsManager.prototype.applyToRuntime = function () {
        var c = this.config;
        window.portApiHttps = parseInt(c.portApiHttps, 10) || 8089;
        window.portApiWss = parseInt(c.portApiWss, 10) || 8989;
        if (c.apiSecret) {
            window.apiSecret = c.apiSecret;
        }

        if (typeof window.getJanusServers !== 'function') {
            window.getJanusServers = function () {
                var host = (window && window.location && window.location.hostname) ? window.location.hostname : 'localhost';
                var proto = (window && window.location && window.location.protocol) ? window.location.protocol : 'https:';
                var pWss = window.portApiWss || 8989;
                var pHttps = window.portApiHttps || 8089;
                if (proto === 'https:') {
                    return [
                        'wss://' + host + ':' + pWss,
                        'https://' + host + ':' + pHttps + '/janus'
                    ];
                } else {
                    return [
                        'ws://' + host + ':8188',
                        'http://' + host + ':8088/janus'
                    ];
                }
            };
        }

        window.janusServers = window.getJanusServers();

        if (c.defaultQuality && $('#selectQuality').length) {
            $('#selectQuality').val(c.defaultQuality);
        }
        if (c.defaultQuality && $('#loginQuality').length) {
            $('#loginQuality').val(c.defaultQuality);
        }
    };

    SettingsManager.prototype.populateForm = function () {
        var c = this.config;
        $('#cfgPortWss').val(c.portApiWss || '8989');
        $('#cfgPortHttps').val(c.portApiHttps || '8089');
        $('#cfgApiSecret').val(c.apiSecret || '').attr('type', 'text');
        $('#cfgDefaultQuality').val(c.defaultQuality || 'medium');
        $('#cfgStunServer').val(c.stunServer || 'stun:stun.l.google.com:19302');
        $('#cfgTurnServer').val(c.turnServer || '');
        $('#cfgTurnUser').val(c.turnUsername || '');
        $('#cfgTurnPass').val(c.turnPassword || '');
        $('#cfgAutoTimeout').val(c.autoTimeoutMs || '2200');
        $('#cfgMaxRetries').val(c.maxRetries || '12');
        $('#settingsAlertPlaceholder').empty();
    };

    SettingsManager.prototype.openModal = function () {
        this.populateForm();
        $('#remoteSettingsModal').modal('show');
    };

    SettingsManager.prototype.save = function () {
        var self = this;
        var payload = {
            portApiWss: $('#cfgPortWss').val().trim() || '8989',
            portApiHttps: $('#cfgPortHttps').val().trim() || '8089',
            apiSecret: $('#cfgApiSecret').val().trim(),
            defaultQuality: $('#cfgDefaultQuality').val() || 'medium',
            stunServer: $('#cfgStunServer').val().trim(),
            turnServer: $('#cfgTurnServer').val().trim(),
            turnUsername: $('#cfgTurnUser').val().trim(),
            turnPassword: $('#cfgTurnPass').val().trim(),
            autoTimeoutMs: $('#cfgAutoTimeout').val().trim() || '2200',
            maxRetries: $('#cfgMaxRetries').val().trim() || '12'
        };

        var $btn = $('#btnSaveRemoteSettings');
        $btn.prop('disabled', true).text('Saving...');

        $.ajax({
            url: 'api/config',
            method: 'POST',
            contentType: 'application/json; charset=utf-8',
            data: JSON.stringify(payload),
            dataType: 'json'
        }).done(function (resp) {
            $btn.prop('disabled', false).text('Save Configuration');
            if (resp && resp.status === 'OK') {
                self.config = Object.assign({}, self.config, payload);
                self.applyToRuntime();
                $('#settingsAlertPlaceholder').html(
                    '<div class="alert alert-success alert-dismissible fade show small mb-3">' +
                    '<strong>Saved!</strong> Settings updated successfully. Active sessions will use the new configuration.' +
                    '<button type="button" class="close" data-dismiss="alert">&times;</button></div>'
                );
                if ($('#remoteSettingsModal').length && $('#remoteSettingsModal').hasClass('show')) {
                    setTimeout(function () {
                        $('#remoteSettingsModal').modal('hide');
                    }, 1200);
                }
            } else {
                var msg = (resp && resp.message) ? resp.message : 'Unknown error';
                $('#settingsAlertPlaceholder').html(
                    '<div class="alert alert-danger small mb-3">Save failed: ' + $('<div>').text(msg).html() + '</div>'
                );
            }
        }).fail(function (xhr) {
            $btn.prop('disabled', false).text('Save Configuration');
            var errText = xhr.responseText || xhr.statusText;
            $('#settingsAlertPlaceholder').html(
                '<div class="alert alert-danger small mb-3">Error saving configuration (HTTP ' + xhr.status + '): ' +
                $('<div>').text(errText).html() + '</div>'
            );
        });
    };

    SettingsManager.prototype.testConnection = function () {
        var portHttps = $('#cfgPortHttps').val().trim() || '8089';
        var secret = $('#cfgApiSecret').val().trim();
        var host = window.location.hostname;
        var infoUrl = 'https://' + host + ':' + portHttps + '/janus/info';

        var $alert = $('#settingsAlertPlaceholder');
        $alert.html(
            '<div class="alert alert-info small mb-3">Testing connection to Janus at <code>' +
            infoUrl + '</code>...</div>'
        );

        var reqData = secret ? { apisecret: secret } : {};
        $.ajax({
            url: infoUrl,
            method: 'GET',
            data: reqData,
            dataType: 'json',
            timeout: 5000
        }).done(function (data) {
            if (data && data.janus === 'server_info') {
                var ver = data.version_string || data.version || '1.x';
                $alert.html(
                    '<div class="alert alert-success small mb-3">' +
                    '<strong>Connection Successful!</strong><br/>' +
                    'Janus Gateway responds: <code>' + $('<div>').text(data.name || 'Janus').html() +
                    ' v' + $('<div>').text(ver).html() + '</code>. Signaling is reachable.' +
                    '</div>'
                );
            } else {
                $alert.html(
                    '<div class="alert alert-warning small mb-3">' +
                    'Janus responded, but returned unexpected payload. Verify API secret.' +
                    '</div>'
                );
            }
        }).fail(function (xhr) {
            var reason = 'Could not reach Janus on port ' + portHttps + '. Check if port is open in firewall (8089/tcp, 8989/tcp) and janus service is running.';
            if (xhr.status === 403 || xhr.status === 401) {
                reason = 'Janus rejected the request. Please check that the API Secret matches <code>api_secret</code> in /etc/janus/janus.jcfg.';
            }
            $alert.html(
                '<div class="alert alert-danger small mb-3">' +
                '<strong>Connection Test Failed:</strong> ' + reason +
                '</div>'
            );
        });
    };

    SettingsManager.prototype.randomSecret = function (length) {
        var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
        var arr = new Uint8Array(length || 32);
        if (window.crypto && window.crypto.getRandomValues) {
            window.crypto.getRandomValues(arr);
        } else {
            for (var i = 0; i < arr.length; i++) arr[i] = Math.floor(Math.random() * 256);
        }
        var out = '';
        for (var j = 0; j < arr.length; j++) {
            out += chars[arr[j] % chars.length];
        }
        return out;
    };

    // Attach to DOM
    var settingsManager = new SettingsManager();
    window.settingsManager = settingsManager;

    $(document).ready(function () {
        // Initial load of config
        settingsManager.load();

        $(document).on('click', '#btnOpenSettings, #btnLoginSettings', function (e) {
            e.preventDefault();
            window.location.href = 'settings.html';
        });

        $(document).on('click', '#btnSaveRemoteSettings', function (e) {
            e.preventDefault();
            settingsManager.save();
        });

        $(document).on('click', '#btnTestJanusConnection', function (e) {
            e.preventDefault();
            settingsManager.testConnection();
        });

        $(document).on('click', '#btnGenerateSecret', function (e) {
            e.preventDefault();
            $('#cfgApiSecret').val(settingsManager.randomSecret(32)).attr('type', 'text');
        });

        $(document).on('click', '#btnToggleSecretVis', function (e) {
            e.preventDefault();
            var $inp = $('#cfgApiSecret');
            var isPass = $inp.attr('type') === 'password';
            $inp.attr('type', isPass ? 'text' : 'password');
        });
    });

})(window, window.jQuery);
