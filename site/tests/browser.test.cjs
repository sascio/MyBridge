// Browser smoke tests for the static Pages output. Requires playwright and
// @axe-core/playwright from NODE_PATH (installed only in the CI workspace).
const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const { default: AxeBuilder } = require('@axe-core/playwright');

(async () => {
  const root = path.resolve(__dirname, '..');
  const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.svg': 'image/svg+xml', '.png': 'image/png', '.webp': 'image/webp', '.mp4': 'video/mp4' };
  const server = http.createServer((request, response) => {
    const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    const filename = path.resolve(root, '.' + (pathname.endsWith('/') ? pathname + 'index.html' : pathname));
    if (!filename.startsWith(root + path.sep)) { response.writeHead(403).end(); return; }
    fs.readFile(filename, (error, bytes) => {
      if (error) { response.writeHead(404).end(); return; }
      response.writeHead(200, { 'content-type': types[path.extname(filename)] || 'application/octet-stream' }).end(bytes);
    });
  });
  await new Promise(resolve => server.listen(4173, '127.0.0.1', resolve));
  let browser;
  try {
    browser = await chromium.launch({ headless: true });
    for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }, { width: 320, height: 720 }]) {
      const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
      const page = await context.newPage();
      await page.route('https://**/*', route => route.abort()); // deterministic; never contact external services
      await page.goto('http://127.0.0.1:4173/', { waitUntil: 'domcontentloaded' });
      assert.equal(await page.locator('h1').innerText(), 'StreamBridge');
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'horizontal overflow');
      for (const id of ['phone', 'tv', 'desktop', 'smart']) {
        const tab = page.locator('#tab-' + id);
        await tab.click();
        assert.equal(await tab.getAttribute('aria-selected'), 'true');
        assert.equal(await page.locator('#panel-' + id).isVisible(), true);
        for (const other of ['phone', 'tv', 'desktop', 'smart'].filter(x => x !== id)) {
          assert.equal(await page.locator('#panel-' + other).isVisible(), false);
        }
      }
      await page.locator('#tab-phone').focus();
      await page.keyboard.press('ArrowRight');
      assert.equal(await page.locator('#tab-tv').getAttribute('aria-selected'), 'true');
      const faq = page.locator('.faq-list details').first();
      await faq.locator('summary').click();
      assert.equal(await faq.evaluate(el => el.open), true);
      await faq.locator('summary').click();
      assert.equal(await faq.evaluate(el => el.open), false);
      for (const theme of ['light', 'dark']) {
        await page.evaluate(value => document.documentElement.setAttribute('data-theme', value), theme);
        const result = await new AxeBuilder({ page }).include('.feature-stories').analyze();
        if (result.violations.length) {
          throw new Error(`Accessibility ${theme}: ${JSON.stringify(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.target) })))}`);
        }
      }
      if (viewport.width >= 700) {
        const themeButton = page.locator('.theme-toggle');
        await themeButton.click();
        assert.equal(await page.locator('html').getAttribute('data-theme'), 'light');
        assert.equal(await themeButton.getAttribute('aria-pressed'), 'false');
        await themeButton.click();
        assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark');
      }
      if (viewport.width < 700) {
        const toggle = page.locator('.menu-toggle');
        await toggle.click();
        assert.equal(await toggle.getAttribute('aria-expanded'), 'true');
        await page.locator('#nav-menu a[href="#faq"]').click();
        assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
        assert.equal(await page.evaluate(() => location.hash), '#faq');
      }
      console.log(`Browser checks passed at ${viewport.width}x${viewport.height}`);
      await context.close();
    }
  } finally {
    if (browser) await browser.close();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
