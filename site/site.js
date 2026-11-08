(function () {
  'use strict';
  var tabs = Array.from(document.querySelectorAll('[role="tab"]'));
  function selectTab(tab) {
    tabs.forEach(function (item) {
      var selected = item === tab;
      item.setAttribute('aria-selected', String(selected));
      item.tabIndex = selected ? 0 : -1;
      document.getElementById(item.getAttribute('aria-controls')).hidden = !selected;
    });
  }
  tabs.forEach(function (tab, index) {
    tab.addEventListener('click', function () { selectTab(tab); });
    tab.addEventListener('keydown', function (event) {
      var next;
      if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
      else if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
      else if (event.key === 'Home') next = 0;
      else if (event.key === 'End') next = tabs.length - 1;
      else return;
      event.preventDefault();
      selectTab(tabs[next]);
      tabs[next].focus();
    });
  });

  // Keep release-page fallback links if the API is unavailable or an asset is missing.
  fetch('https://api.github.com/repos/sascio/MyBridge/releases/latest', {headers: {'Accept': 'application/vnd.github+json'}})
    .then(function (response) { if (!response.ok) throw new Error('Release unavailable'); return response.json(); })
    .then(function (release) {
      if (release.draft || release.prerelease || !Array.isArray(release.assets) || typeof release.tag_name !== 'string') return;
      document.getElementById('release-status').textContent = 'Latest release: ' + release.tag_name;
      function safeAsset(asset) {
        if (!asset || asset.state !== 'uploaded' || typeof asset.browser_download_url !== 'string') return false;
        try {
          var url = new URL(asset.browser_download_url);
          return url.protocol === 'https:' && url.hostname === 'github.com' && url.pathname.indexOf('/sascio/MyBridge/releases/download/') === 0;
        } catch (_) { return false; }
      }
      document.querySelectorAll('[data-asset]').forEach(function (link) {
        var asset = release.assets.find(function (item) { return item.name === link.dataset.asset && safeAsset(item); });
        if (asset) link.href = asset.browser_download_url;
      });
      var ipa = release.assets.find(function (item) { return /^StreamBridge-.+-iOS-unsigned\.ipa$/i.test(item.name) && safeAsset(item); });
      if (ipa) document.getElementById('ipa').href = ipa.browser_download_url;
    }).catch(function () {});
}());
