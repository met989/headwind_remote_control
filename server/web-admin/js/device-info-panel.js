function DeviceInfoPanel(sidebarEl, bodyEl, titleEl) {
    this.sidebarEl = sidebarEl;
    this.bodyEl = bodyEl;
    this.titleEl = titleEl;
    this.deviceNumber = null;
    this.deviceModel = null;
    this.isLoading = false;

    var obj = this;

    // Capture ?device= immediately before ui.js cleans the URL query string
    try {
        var params = new URLSearchParams(window.location.search);
        var devParam = params.get('device');
        if (devParam && devParam.trim()) {
            this.deviceNumber = devParam.trim();
        }
    } catch (e) {}

    function esc(val) {
        if (val === null || val === undefined || val === '') return '';
        return String(val)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function fmtVal(val) {
        var s = esc(val);
        return s ? s : '<span class="info-muted">&mdash;</span>';
    }

    function fmtBool(val, warnIfFalse) {
        if (val === null || val === undefined) {
            return '<span class="info-muted">&mdash;</span>';
        }
        if (val === true || val === 1 || val === 'yes' || val === 'true') {
            return '<span class="info-badge info-badge-ok">Yes</span>';
        }
        return '<span class="info-badge ' + (warnIfFalse ? 'info-badge-warn' : 'info-badge-neutral') + '">No</span>';
    }

    function fmtDateTime(ts) {
        if (!ts || ts <= 0) return '<span class="info-muted">&mdash;</span>';
        var d = new Date(ts);
        if (isNaN(d.getTime())) return '<span class="info-muted">&mdash;</span>';
        var pad = function (n) { return n < 10 ? '0' + n : '' + n; };
        return pad(d.getDate()) + '.' + pad(d.getMonth() + 1) + '.' + String(d.getFullYear()).slice(-2) +
            ' ' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
    }

    function fmtRelative(interval, unit) {
        if (interval === null || interval === undefined) return '';
        var u = unit || 'min';
        if (u === 'min') u = 'min';
        else if (u === 'hour') u = interval === 1 ? 'hr' : 'hrs';
        else if (u === 'day') u = interval === 1 ? 'day' : 'days';
        return ' (' + interval + ' ' + u + ' ago)';
    }

    function fmtStatus(code) {
        var map = {
            green:  { label: 'Online',  cls: 'info-badge-ok' },
            yellow: { label: 'Warning', cls: 'info-badge-warn' },
            red:    { label: 'Offline', cls: 'info-badge-err' },
            brown:  { label: 'Stale',   cls: 'info-badge-warn' },
            grey:   { label: 'Unknown', cls: 'info-badge-neutral' }
        };
        var item = map[(code || '').toLowerCase()] || map.grey;
        return '<span class="info-badge ' + item.cls + '">' + item.label + '</span>';
    }

    function fmtBattery(level) {
        if (level === null || level === undefined) return '<span class="info-muted">&mdash;</span>';
        var cls = level >= 40 ? 'info-badge-ok' : (level >= 20 ? 'info-badge-warn' : 'info-badge-err');
        return '<span class="info-badge ' + cls + '">' + esc(level) + '%</span>';
    }

    function computePermissionStatus(detail, dev) {
        if (detail && detail.adminPermission !== undefined) {
            var ok = detail.adminPermission && detail.overlapPermission && detail.historyPermission;
            return ok
                ? '<span class="info-badge info-badge-ok">OK</span>'
                : '<span class="info-badge info-badge-warn">Incomplete</span>';
        }
        var perms = dev && dev.info && dev.info.permissions;
        if (Array.isArray(perms) && perms.length >= 3) {
            var allOk = perms[0] === 1 && perms[1] === 1 && perms[2] === 1;
            return allOk
                ? '<span class="info-badge info-badge-ok">OK</span>'
                : '<span class="info-badge info-badge-warn">Incomplete</span>';
        }
        return '<span class="info-muted">&mdash;</span>';
    }

    function computeInstallStatus(detail, dev, cfg) {
        var apps = (detail && Array.isArray(detail.applications)) ? detail.applications : null;
        if (apps && apps.length > 0) {
            var issues = 0;
            for (var i = 0; i < apps.length; i++) {
                var a = apps[i];
                if (a.versionRequired && a.versionInstalled && a.versionRequired !== a.versionInstalled) {
                    issues++;
                } else if (a.versionRequired && !a.versionInstalled) {
                    issues++;
                }
            }
            return issues === 0
                ? '<span class="info-badge info-badge-ok">OK</span>'
                : '<span class="info-badge info-badge-warn">' + issues + ' pending</span>';
        }
        if (dev && dev.info && Array.isArray(dev.info.applications)) {
            return '<span class="info-badge info-badge-ok">' + dev.info.applications.length + ' apps</span>';
        }
        return '<span class="info-muted">&mdash;</span>';
    }

    function computeFileStatus(dev, cfg) {
        var cfgFiles = (cfg && Array.isArray(cfg.files)) ? cfg.files : [];
        var devFiles = (dev && dev.info && Array.isArray(dev.info.files)) ? dev.info.files : [];
        if (cfgFiles.length === 0) {
            return '<span class="info-badge info-badge-ok">OK</span>';
        }
        return devFiles.length >= cfgFiles.length
            ? '<span class="info-badge info-badge-ok">OK (' + devFiles.length + ')</span>'
            : '<span class="info-badge info-badge-warn">' + devFiles.length + '/' + cfgFiles.length + '</span>';
    }

    function row(label, htmlVal) {
        return '<div class="info-row">' +
            '<span class="info-key">' + esc(label) + '</span>' +
            '<span class="info-val">' + htmlVal + '</span>' +
            '</div>';
    }

    function section(title, rowsHtml) {
        return '<div class="info-section">' +
            '<div class="info-section-title">' + esc(title) + '</div>' +
            rowsHtml +
            '</div>';
    }

    this.updateHeaderTitle = function (modelName) {
        if (modelName) {
            this.deviceModel = modelName;
        }
        if (!this.titleEl || !this.titleEl.length) return;
        if (this.deviceNumber && this.deviceModel && this.deviceModel !== this.deviceNumber) {
            this.titleEl.text(this.deviceNumber + ' \u00B7 ' + this.deviceModel);
        } else if (this.deviceNumber) {
            this.titleEl.text(this.deviceNumber);
        } else if (this.deviceModel) {
            this.titleEl.text(this.deviceModel);
        }
    };

    this.render = function (dev, cfg, detail) {
        if (!dev && !detail) {
            this.bodyEl.html('<div class="info-empty">No MDM details available for this session.</div>');
            return;
        }

        var d = dev || {};
        var info = d.info || {};
        var det = detail || {};

        var devNum = det.deviceNumber || d.number || this.deviceNumber || '';
        var model = det.model || info.model || this.deviceModel || '';
        this.updateHeaderTitle(model);

        var groupsArr = det.groups || d.groups || [];
        var groupNames = Array.isArray(groupsArr) && groupsArr.length > 0
            ? groupsArr.map(function (g) { return g.name || g; }).join(', ')
            : '';

        var lastSyncTs = det.latestUpdateTime || d.lastUpdate;
        var lastSyncHtml = fmtDateTime(lastSyncTs);
        if (det.latestUpdateInterval !== undefined && det.latestUpdateInterval !== null) {
            lastSyncHtml += '<span class="info-sub">' + esc(fmtRelative(det.latestUpdateInterval, det.latestUpdateIntervalType)) + '</span>';
        }

        var battery = det.batteryLevel !== undefined ? det.batteryLevel : info.batteryLevel;
        var androidVer = det.osVersion || d.androidVersion || info.androidVersion;
        var mdmMode = det.mdmMode !== undefined ? det.mdmMode : (d.mdmMode !== undefined ? d.mdmMode : info.mdmMode);
        var kioskMode = det.kioskMode !== undefined ? det.kioskMode : (d.kioskMode !== undefined ? d.kioskMode : info.kioskMode);
        var serial = det.serial || d.serial || info.serial;
        var imeiActual = det.imeiActual || info.imei || d.imei;
        var imeiRequired = det.imeiRequired !== undefined ? det.imeiRequired : d.imei;
        var phoneActual = det.phoneNumberActual || info.phone;
        var phoneRequired = det.phoneNumberRequired !== undefined ? det.phoneNumberRequired : d.phone;
        var description = det.description !== undefined ? det.description : d.description;
        var cfgName = cfg && cfg.name ? cfg.name : (d.configurationId ? '#' + d.configurationId : '');

        var html = '';

        html += section('Overview',
            row('Status', fmtStatus(d.statusCode)) +
            row('Last Sync', lastSyncHtml) +
            row('Device #', fmtVal(devNum)) +
            row('Model', fmtVal(model)) +
            row('Android', fmtVal(androidVer)) +
            row('Battery', fmtBattery(battery)) +
            row('Config', fmtVal(cfgName)) +
            row('Groups', fmtVal(groupNames)) +
            row('Description', fmtVal(description)) +
            row('Enrolled', fmtDateTime(d.enrollTime))
        );

        html += section('MDM & Status',
            row('Permissions', computePermissionStatus(det, d)) +
            row('Apps Status', computeInstallStatus(det, d, cfg)) +
            row('Files Status', computeFileStatus(d, cfg)) +
            row('MDM Mode', fmtBool(mdmMode, true)) +
            row('Kiosk Mode', fmtBool(kioskMode, false)) +
            row('Launcher Ver', fmtVal(d.launcherVersion)) +
            row('Variant', fmtVal(det.launcherType)) +
            row('Default App', fmtVal(det.launcherPackage || d.launcherPkg))
        );

        html += section('Device Permissions',
            row('Device Admin', fmtBool(det.adminPermission, true)) +
            row('Overlay', fmtBool(det.overlapPermission, true)) +
            row('Usage History', fmtBool(det.historyPermission, true)) +
            row('Accessibility', fmtBool(det.accessibilityPermission, false))
        );

        html += section('Hardware & Network',
            row('Serial', fmtVal(serial)) +
            row('IMEI', fmtVal(imeiActual)) +
            row('IMEI (Req)', fmtVal(imeiRequired)) +
            row('IMEI 2nd SIM', fmtVal(det.imei2)) +
            row('Phone', fmtVal(phoneActual)) +
            row('Phone (Req)', fmtVal(phoneRequired)) +
            row('ICCID', fmtVal(det.iccid)) +
            row('MAC Address', fmtVal(det.mac)) +
            row('CPU Arch', fmtVal(det.cpu)) +
            row('Public IP', fmtVal(d.publicIp))
        );

        this.bodyEl.html(html);
    };

    this.load = function (deviceNumberOverride) {
        if (deviceNumberOverride) {
            this.deviceNumber = deviceNumberOverride;
        }
        if (!this.deviceNumber) {
            this.bodyEl.html('<div class="info-empty">Launch from Headwind MDM to display full device details.</div>');
            return;
        }
        if (this.isLoading) return;
        this.isLoading = true;
        var num = this.deviceNumber;
        this.updateHeaderTitle();

        var searchReq = fetch('/rest/private/devices/search', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ value: num, pageNum: 1, pageSize: 20 })
        }).then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });

        var detailReq = fetch('/rest/private/plugin-deviceinfo/info/device/' + encodeURIComponent(num), {
            method: 'GET',
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' }
        }).then(function (r) { return r.ok ? r.json() : null; }).catch(function () { return null; });

        Promise.all([searchReq, detailReq]).then(function (results) {
            obj.isLoading = false;
            var searchJson = results[0];
            var detailJson = results[1];

            var matchedDev = null;
            var matchedCfg = null;
            if (searchJson && searchJson.status === 'OK' && searchJson.data) {
                var items = (searchJson.data.devices && searchJson.data.devices.items) || [];
                for (var i = 0; i < items.length; i++) {
                    if (items[i].number && items[i].number.toLowerCase() === num.toLowerCase()) {
                        matchedDev = items[i];
                        break;
                    }
                }
                if (!matchedDev && items.length === 1) {
                    matchedDev = items[0];
                }
                var cfgs = searchJson.data.configurations;
                if (matchedDev && cfgs) {
                    if (Array.isArray(cfgs)) {
                        for (var j = 0; j < cfgs.length; j++) {
                            if (cfgs[j].id === matchedDev.configurationId) {
                                matchedCfg = cfgs[j];
                                break;
                            }
                        }
                    } else {
                        matchedCfg = cfgs[matchedDev.configurationId] || cfgs[String(matchedDev.configurationId)] || null;
                    }
                }
            }

            var detailData = (detailJson && detailJson.status === 'OK' && detailJson.data) ? detailJson.data : null;
            obj.render(matchedDev, matchedCfg, detailData);
        }).catch(function () {
            obj.isLoading = false;
            obj.bodyEl.html('<div class="info-empty">Could not load MDM device details.</div>');
        });
    };
}
