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
  // Persist an explicit preference without making storage a requirement.
  var themeButton = document.querySelector('.theme-toggle');
  function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    themeButton.setAttribute('aria-label', theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme');
    themeButton.setAttribute('aria-pressed', String(theme === 'dark'));
    try { localStorage.setItem('streambridge-theme', theme); } catch (_) {}
  }
  var savedTheme;
  try { savedTheme = localStorage.getItem('streambridge-theme'); } catch (_) {}
  applyTheme(savedTheme === 'dark' ? 'dark' : 'light');
  themeButton.addEventListener('click', function () {
    applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');
  });

  // The external GitHub link opens separately; section links remain on this page.
  var navItems = Array.from(document.querySelectorAll('.nav-item[href^="#"]'));
  function updateNav() {
    var current = '#top';
    ['#news', '#faq'].forEach(function (hash) {
      var target = document.querySelector(hash);
      if (target && target.getBoundingClientRect().top <= 120) current = hash;
    });
    navItems.forEach(function (item) {
      var active = item.getAttribute('href') === current;
      item.classList.toggle('nav-current', active);
      if (active) item.setAttribute('aria-current', 'location');
      else item.removeAttribute('aria-current');
    });
  }
  window.addEventListener('scroll', updateNav, {passive: true});
  updateNav();

  // GitHub's release API is paginated; request every page, never synthesize a release.
  var newsList = document.getElementById('news-list');
  function renderReleases(releases) {
    newsList.replaceChildren();
    if (!releases.length) {
      newsList.textContent = 'No published releases were returned by GitHub.';
      return;
    }
    releases.forEach(function (release) {
      if (release.draft) return;
      var card = document.createElement('article'); card.className = 'news-card';
      var label = document.createElement('span'); label.className = 'news-label'; label.textContent = release.prerelease ? 'STREAMBRIDGE · PRERELEASE' : 'STREAMBRIDGE · RELEASE';
      var title = document.createElement('h3'); title.textContent = release.name || release.tag_name;
      var meta = document.createElement('p'); meta.className = 'news-meta';
      var date = new Date(release.published_at);
      meta.textContent = release.tag_name + ' · ' + (isNaN(date.getTime()) ? 'Date unavailable' : date.toLocaleDateString('en', {year:'numeric', month:'short', day:'numeric', timeZone:'UTC'}));
      var body = document.createElement('p'); body.className = 'news-summary';
      var summary = (release.body || '').replace(/!\[[^\]]*\]\([^)]*\)/g, '').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/[#*_`>]/g, '').replace(/\s+/g, ' ').trim();
      body.textContent = summary ? summary.slice(0, 260) + (summary.length > 260 ? '…' : '') : 'No release notes provided.';
      var link = document.createElement('a'); link.textContent = 'Open on GitHub ↗';
      try { var url = new URL(release.html_url); link.href = url.protocol === 'https:' && url.hostname === 'github.com' && url.pathname.indexOf('/sascio/MyBridge/releases/') === 0 ? url.href : 'https://github.com/sascio/MyBridge/releases'; } catch (_) { link.href = 'https://github.com/sascio/MyBridge/releases'; }
      card.append(label, title, meta, body, link); newsList.appendChild(card);
    });
  }
  function loadReleases(page, all) {
    return fetch('https://api.github.com/repos/sascio/MyBridge/releases?per_page=100&page=' + page, {headers: {'Accept': 'application/vnd.github+json'}})
      .then(function (response) { if (!response.ok) throw new Error('Release feed unavailable'); return response.json(); })
      .then(function (items) { if (!Array.isArray(items)) throw new Error('Invalid release feed'); var combined = all.concat(items); return items.length === 100 ? loadReleases(page + 1, combined) : combined; });
  }
  loadReleases(1, []).then(renderReleases).catch(function () {
    newsList.textContent = 'Release information could not be loaded. Visit GitHub for the complete version history.';
  });
}());
