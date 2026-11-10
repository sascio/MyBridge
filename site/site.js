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

  // An intro interval must be supplied explicitly in the markup; never infer one.
  var showcase = document.querySelector('.video-showcase');
  if (showcase) {
    var video = showcase.querySelector('video');
    var skipButton = showcase.querySelector('.skip-intro');
    var cursor = showcase.querySelector('.demo-cursor');
    var motion = window.matchMedia('(prefers-reduced-motion: reduce)');
    var startValue = showcase.dataset.introStart;
    var endValue = showcase.dataset.introEnd;
    var introStart = startValue === '' ? NaN : Number(startValue);
    var introEnd = endValue === '' ? NaN : Number(endValue);
    var intervalReady = false;
    var attempted = false;
    var lastTime = 0;
    function cancelCursor() { cursor.classList.remove('is-running'); }
    function updateSkip() {
      var current = video.currentTime;
      if (current < lastTime - .5) attempted = false;
      lastTime = current;
      var inIntro = intervalReady && current >= introStart && current < introEnd;
      skipButton.hidden = !inIntro;
      if (!inIntro || video.paused || motion.matches) { cancelCursor(); return; }
      if (!attempted) {
        attempted = true;
        var remaining = (introEnd - current) / Math.max(video.playbackRate, .1);
        if (remaining > .2) {
          cursor.style.setProperty('--cursor-duration', Math.min(1.3, remaining - .1) + 's');
          cursor.classList.add('is-running');
        }
      }
    }
    video.addEventListener('loadedmetadata', function () {
      intervalReady = Number.isFinite(introStart) && Number.isFinite(introEnd) && introStart >= 0 && introEnd > introStart && introEnd < video.duration;
      updateSkip();
    });
    video.addEventListener('timeupdate', updateSkip);
    video.addEventListener('play', updateSkip);
    video.addEventListener('pause', cancelCursor);
    video.addEventListener('seeking', cancelCursor);
    cursor.addEventListener('animationend', function () {
      cancelCursor();
      if (!motion.matches && !video.paused && !skipButton.hidden && video.currentTime >= introStart && video.currentTime < introEnd) skipButton.click();
    });
    skipButton.addEventListener('click', function () {
      if (!intervalReady || video.currentTime < introStart || video.currentTime >= introEnd) { updateSkip(); return; }
      attempted = true;
      cancelCursor();
      video.currentTime = introEnd;
      skipButton.hidden = true;
    });
    function applyMotionPreference() {
      if (motion.matches) { video.autoplay = false; video.pause(); cancelCursor(); }
      else { video.muted = true; video.autoplay = true; video.play().catch(function () {}); }
      updateSkip();
    }
    if (motion.addEventListener) motion.addEventListener('change', applyMotionPreference);
    applyMotionPreference();
  }

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

  // One shared navigation for all breakpoints, with a disclosure on small screens.
  var nav = document.querySelector('.nav');
  var menuButton = document.querySelector('.menu-toggle');
  var menu = document.getElementById('nav-menu');
  var navItems = Array.from(menu.querySelectorAll('a[href^="#"]'));
  function closeMenu() {
    menuButton.setAttribute('aria-expanded', 'false');
    menuButton.setAttribute('aria-label', 'Open navigation');
    menu.classList.remove('is-open');
  }
  menuButton.addEventListener('click', function () {
    var open = menuButton.getAttribute('aria-expanded') !== 'true';
    menuButton.setAttribute('aria-expanded', String(open));
    menuButton.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
    menu.classList.toggle('is-open', open);
  });
  document.addEventListener('click', function (event) {
    if (!nav.contains(event.target)) closeMenu();
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && menuButton.getAttribute('aria-expanded') === 'true') {
      closeMenu(); menuButton.focus();
    }
  });
  var navigationTarget = null;
  var navigationTimer;
  function setActive(hash) {
    navItems.forEach(function (item) {
      var active = item.getAttribute('href') === hash;
      item.classList.toggle('nav-current', active);
      if (active) item.setAttribute('aria-current', 'location');
      else item.removeAttribute('aria-current');
    });
  }
  // Keep the chosen destination active while a smooth scroll passes other sections.
  function beginNavigation(hash) {
    if (!navItems.some(function (item) { return item.getAttribute('href') === hash; })) return;
    navigationTarget = hash;
    setActive(hash);
    clearTimeout(navigationTimer);
    navigationTimer = setTimeout(function () { navigationTarget = null; updateNav(); }, 3000);
  }
  nav.querySelector('.brand').addEventListener('click', function () { closeMenu(); beginNavigation('#top'); });
  menu.addEventListener('click', function (event) {
    var link = event.target.closest('a');
    if (!link) return;
    closeMenu();
    if (link.hash) beginNavigation(link.hash);
  });
  function updateNav() {
    if (navigationTarget) return;
    var current = '#top';
    ['#get', '#news', '#faq'].forEach(function (hash) {
      var target = document.querySelector(hash);
      if (target && target.getBoundingClientRect().top <= 130) current = hash;
    });
    setActive(current);
  }
  window.addEventListener('scroll', updateNav, {passive: true});
  window.addEventListener('scrollend', function () {
    if (!navigationTarget) return;
    var target = document.querySelector(navigationTarget);
    if (target && Math.abs(target.getBoundingClientRect().top) <= 140) {
      navigationTarget = null; clearTimeout(navigationTimer); updateNav();
    }
  });
  window.addEventListener('hashchange', function () { beginNavigation(location.hash || '#top'); });
  ['wheel', 'touchstart'].forEach(function (eventName) {
    window.addEventListener(eventName, function () { navigationTarget = null; clearTimeout(navigationTimer); }, {passive: true});
  });
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
      var card = document.createElement('article'); card.className = 'news-item';
      var details = document.createElement('div'); details.className = 'news-item-content';
      var label = document.createElement('span'); label.className = 'news-label'; label.textContent = release.prerelease ? 'StreamBridge · Prerelease' : 'StreamBridge';
      var title = document.createElement('h3'); title.textContent = release.name || release.tag_name;
      var meta = document.createElement('p'); meta.className = 'news-meta';
      var date = new Date(release.published_at);
      meta.textContent = release.tag_name + ' · ' + (release.published_at && !isNaN(date.getTime()) ? date.toLocaleDateString('en', {year:'numeric', month:'short', day:'numeric', timeZone:'UTC'}) : 'Date unavailable');
      var body = document.createElement('p'); body.className = 'news-summary';
      var summary = (release.body || '').replace(/!\[[^\]]*\]\([^)]*\)/g, '').replace(/\[([^\]]+)\]\([^)]*\)/g, '$1').replace(/[#*_`>]/g, '').replace(/\s+/g, ' ').trim() || 'No release notes provided.';
      var shortText = summary.slice(0, 260) + (summary.length > 260 ? '…' : '');
      body.textContent = shortText;
      var actions = document.createElement('div'); actions.className = 'news-actions';
      if (summary.length > 260) {
        var expand = document.createElement('button'); expand.type = 'button'; expand.textContent = 'Show more'; expand.setAttribute('aria-expanded', 'false');
        expand.addEventListener('click', function () {
          var expanded = expand.getAttribute('aria-expanded') !== 'true';
          expand.setAttribute('aria-expanded', String(expanded));
          expand.textContent = expanded ? 'Show less' : 'Show more';
          body.textContent = expanded ? summary : shortText;
        });
        actions.appendChild(expand);
      }
      var link = document.createElement('a'); link.textContent = 'Open on GitHub';
      try { var url = new URL(release.html_url); link.href = url.protocol === 'https:' && url.hostname === 'github.com' && url.pathname.indexOf('/sascio/MyBridge/releases/') === 0 ? url.href : 'https://github.com/sascio/MyBridge/releases'; } catch (_) { link.href = 'https://github.com/sascio/MyBridge/releases'; }
      details.append(label, title, meta, body); actions.appendChild(link); card.append(details, actions); newsList.appendChild(card);
    });
  }
  function loadReleases(page, all) {
    return fetch('https://api.github.com/repos/sascio/MyBridge/releases?per_page=100&page=' + page, {headers: {'Accept': 'application/vnd.github+json'}})
      .then(function (response) { if (!response.ok) throw new Error('Release feed unavailable'); return response.json(); })
      .then(function (items) { if (!Array.isArray(items)) throw new Error('Invalid release feed'); var combined = all.concat(items); return items.length === 100 ? loadReleases(page + 1, combined) : combined; });
  }
  loadReleases(1, []).then(function (releases) {
    renderReleases(releases);
    if (location.hash === '#faq') document.getElementById('faq').scrollIntoView({behavior: 'instant'});
    updateNav();
  }).catch(function () {
    newsList.textContent = 'Release information could not be loaded. Visit GitHub for the complete version history.';
    if (location.hash === '#faq') document.getElementById('faq').scrollIntoView({behavior: 'instant'});
    updateNav();
  });
}());
