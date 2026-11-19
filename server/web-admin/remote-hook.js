(function () {
  'use strict';

  var lastDeviceNumber = null;
  var lastClickTs = 0;

  function randomDigits(len) {
    var out = '';
    for (var i = 0; i < len; i++) {
      out += Math.floor(Math.random() * 10).toString();
    }
    return out;
  }

  function showToast(message, isError) {
    var existing = document.getElementById('hmdm-remote-toast');
    if (existing) existing.remove();

    var toast = document.createElement('div');
    toast.id = 'hmdm-remote-toast';
    toast.textContent = message;
    toast.style.cssText =
      'position:fixed;bottom:24px;right:24px;z-index:99999;' +
      'padding:12px 20px;border-radius:8px;color:#fff;font-family:Roboto,sans-serif;font-size:14px;' +
      'box-shadow:0 4px 12px rgba(0,0,0,0.25);transition:opacity 0.3s ease;' +
      'background:' + (isError ? '#c62828' : '#2e7d32') + ';';
    document.body.appendChild(toast);

    setTimeout(function () {
      toast.style.opacity = '0';
      setTimeout(function () {
        if (toast.parentNode) toast.parentNode.removeChild(toast);
      }, 300);
    }, 4500);
  }

  var lastConfigTs = 0;
  var cachedRemoteConfig = null;

  function getRemoteConfig() {
    var now = Date.now();
    // Cache for at most 3 seconds to avoid stale secrets when settings change
    if (cachedRemoteConfig && (now - lastConfigTs < 3000)) {
      return Promise.resolve(cachedRemoteConfig);
    }
    return fetch('/web-admin/api/config?_t=' + now, {
      credentials: 'same-origin',
      cache: 'no-store'
    })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (json) {
        if (json && json.status === 'OK' && json.data) {
          cachedRemoteConfig = json.data;
          lastConfigTs = Date.now();
        }
        return cachedRemoteConfig || {};
      })
      .catch(function () {
        return cachedRemoteConfig || {};
      });
  }

  function startRemoteControl(deviceNumber) {
    // Close Material menu overlay
    var backdrop = document.querySelector('.cdk-overlay-backdrop');
    if (backdrop) backdrop.click();

    var sessionId = randomDigits(8);
    var pin = randomDigits(4);

    // Open web-admin immediately inside user gesture so popup blocker never blocks it
    var remoteUrl =
      '/web-admin/?device=' + encodeURIComponent(deviceNumber) +
      '&session=' + encodeURIComponent(sessionId) +
      '&pin=' + encodeURIComponent(pin) +
      '&auto=1';
    window.open(remoteUrl, '_blank');

    getRemoteConfig().then(function (cfg) {
      var httpsPort = (cfg && cfg.portApiHttps) ? cfg.portApiHttps : '8089';
      var serverUrl = (cfg && cfg.serverUrl) ? cfg.serverUrl :
        (window.location.protocol + '//' + window.location.hostname + ':' + httpsPort + '/');
      var secret = (cfg && cfg.apiSecret) ? cfg.apiSecret : '';

      var extras = {
        sessionId: sessionId,
        password: pin
      };
      if (serverUrl) extras.serverUrl = serverUrl;
      if (secret) extras.secret = secret;

      // Send native Headwind MDM Push (runApp) with dynamic configuration
      fetch('/rest/private/plugin-push/send', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          scope: 'device',
          deviceNumber: deviceNumber,
          messageType: 'runApp',
          payload: JSON.stringify({
            pkg: 'com.hmdm.control',
            extra: extras
          }),
          configurationId: null,
          groupId: null,
          customMessageType: ''
        })
      })
        .then(function (resp) {
          return resp.json();
        })
        .then(function (json) {
          if (json && json.status === 'OK') {
            showToast('Remote Control started on ' + deviceNumber + ' (Session ' + sessionId + ')', false);
          } else {
            showToast('Remote window opened, but push delivery to ' + deviceNumber + ' was not confirmed.', true);
          }
        })
        .catch(function (err) {
          console.error('[Headwind Remote Hook] Push error:', err);
          showToast('Remote window opened (network error sending push to ' + deviceNumber + ')', true);
        });
    });
  }

  // Capture clicks on the 3-dots menu button in the Devices table
  document.addEventListener(
    'click',
    function (event) {
      var target = event.target;
      if (!target || !target.closest) return;

      var actionsCell = target.closest('core-actions-cell');
      if (!actionsCell) return;

      var menuTrigger = target.closest('button');
      if (!menuTrigger) return;

      var icon = menuTrigger.querySelector('mat-icon');
      if (!icon || icon.textContent.trim() !== 'more_vert') return;

      var row = actionsCell.closest('tr');
      if (!row) return;

      var numCell = row.querySelector('.mat-column-number, .cdk-column-number');
      if (numCell) {
        lastDeviceNumber = numCell.textContent.trim();
        lastClickTs = Date.now();
      }
    },
    true
  );

  function injectMenuItem(menuContent) {
    if (!menuContent || menuContent.querySelector('.hmdm-remote-menu-item')) return;
    if (!lastDeviceNumber || Date.now() - lastClickTs > 1500) return;

    var deviceNum = lastDeviceNumber;

    var btn = document.createElement('button');
    btn.type = 'button';
    btn.setAttribute('mat-menu-item', '');
    btn.setAttribute('role', 'menuitem');
    btn.className = 'mat-mdc-menu-item mdc-list-item hmdm-remote-menu-item';
    btn.style.cssText = 'border-top: 1px solid rgba(0,0,0,0.08);';
    btn.innerHTML =
      '<mat-icon role="img" class="mat-icon notranslate material-icons mat-ligature-font mat-icon-no-color" aria-hidden="true">desktop_windows</mat-icon>' +
      '<span class="mat-mdc-menu-item-text">Remote Control</span>';

    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      startRemoteControl(deviceNum);
    });

    menuContent.appendChild(btn);
  }

  function injectFunctionsMenuItem() {
    var existing = document.querySelector('.hmdm-remote-sidebar-link');
    var tree = document.querySelector('mat-tree');
    if (!tree) {
      if (existing) existing.remove();
      return;
    }

    var nodes = tree.querySelectorAll('mat-tree-node');
    if (!nodes || nodes.length === 0) return;

    var functionsNode = null;
    var lastChildNode = null;

    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      var textEl = n.querySelector('.menu-text');
      if (!textEl) continue;
      var txt = textEl.textContent.trim().toLowerCase();
      if (txt === 'funzioni' || txt === 'functions' || txt === 'menu.functions') {
        functionsNode = n;
        for (var j = i + 1; j < nodes.length; j++) {
          var sibling = nodes[j];
          var level = sibling.getAttribute('aria-level');
          if (level === '2' && !sibling.classList.contains('hmdm-remote-sidebar-link')) {
            lastChildNode = sibling;
          } else if (level !== '2') {
            break;
          }
        }
        break;
      }
    }

    if (!functionsNode) {
      if (existing) existing.remove();
      return;
    }

    // Check if Funzioni is expanded
    var isExpanded = false;
    var icon = functionsNode.querySelector('mat-icon');
    if (icon && icon.textContent.trim() === 'expand_more') isExpanded = true;
    var btn = functionsNode.querySelector('button');
    if (btn && btn.getAttribute('aria-expanded') === 'true') isExpanded = true;
    if (lastChildNode) isExpanded = true;

    if (!isExpanded) {
      if (existing) existing.remove();
      return;
    }

    if (existing) return; // Already present in tree

    var item;
    if (lastChildNode) {
      item = lastChildNode.cloneNode(true);
      item.classList.remove('active', 'node-locked');
      item.classList.add('hmdm-remote-sidebar-link');
      item.removeAttribute('href');
      item.removeAttribute('routerlink');

      var lockBadge = item.querySelector('.badge-locked');
      if (lockBadge) lockBadge.remove();

      var iconEl = item.querySelector('.menu-icon, mat-icon');
      if (iconEl) {
        iconEl.textContent = 'settings_remote';
      }

      var textEl = item.querySelector('.menu-text');
      if (textEl) {
        textEl.textContent = 'Controllo remoto';
      }
    } else {
      item = document.createElement('mat-tree-node');
      item.className = 'mat-tree-node hmdm-remote-sidebar-link';
      item.setAttribute('role', 'treeitem');
      item.setAttribute('aria-level', '2');
      item.setAttribute('tabindex', '-1');
      item.innerHTML =
        '<mat-icon role="img" class="mat-icon notranslate material-icons mat-ligature-font menu-icon" aria-hidden="true">settings_remote</mat-icon>' +
        '<span class="menu-text">Controllo remoto</span>';
    }

    item.style.cursor = 'pointer';

    item.addEventListener('mouseenter', function () {
      item.style.backgroundColor = 'var(--mat-sys-primary, #1976d2)';
      item.style.color = 'var(--mat-sys-on-primary, #ffffff)';
      var ic = item.querySelector('.menu-icon');
      if (ic) ic.style.color = 'var(--mat-sys-on-primary, #ffffff)';
    });
    item.addEventListener('mouseleave', function () {
      item.style.backgroundColor = '';
      item.style.color = '';
      var ic = item.querySelector('.menu-icon');
      if (ic) ic.style.color = '';
    });

    item.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      window.location.href = '/web-admin/settings.html';
    });

    if (lastChildNode && lastChildNode.parentNode) {
      lastChildNode.parentNode.insertBefore(item, lastChildNode.nextSibling);
    } else if (functionsNode && functionsNode.parentNode) {
      functionsNode.parentNode.insertBefore(item, functionsNode.nextSibling);
    }
  }

  var observer = new MutationObserver(function (mutations) {
    for (var i = 0; i < mutations.length; i++) {
      var added = mutations[i].addedNodes;
      for (var j = 0; j < added.length; j++) {
        var node = added[j];
        if (node.nodeType !== 1) continue;
        if (node.classList && node.classList.contains('mat-mdc-menu-content')) {
          injectMenuItem(node);
        } else if (node.querySelector) {
          var content = node.querySelector('.mat-mdc-menu-content');
          if (content) injectMenuItem(content);
        }
      }
    }
    injectFunctionsMenuItem();
  });

  if (document.body) {
    observer.observe(document.body, { childList: true, subtree: true });
    injectFunctionsMenuItem();
  } else {
    document.addEventListener('DOMContentLoaded', function () {
      observer.observe(document.body, { childList: true, subtree: true });
      injectFunctionsMenuItem();
    });
  }
})();
