// Browser smoke tests for the static Pages output. Requires playwright and
// @axe-core/playwright from NODE_PATH (installed only in the CI workspace).
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const { default: AxeBuilder } = require('@axe-core/playwright');

(async () => {
  const browser = await chromium.launch({ headless: true });
  try {
    for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }, { width: 320, height: 720 }]) {
      const page = await browser.newPage({ viewport, reducedMotion: 'reduce' });
      await page.route('https://**/*', route => route.abort()); // deterministic; never contact external services
      await page.goto('http://127.0.0.1:4173/', { waitUntil: 'domcontentloaded' });
      assert.equal(await page.locator('h1').innerText(), 'StreamBridge');
      const widths = [];
      for (const name of ['Adaptive', 'Expanded', 'Compact', 'Classic']) {
        await page.locator('.layout-choices label').filter({ hasText: name }).click();
        assert.equal(await page.getByLabel(name, { exact: true }).isChecked(), true);
        widths.push(await page.locator('.preview-nav').evaluate(el => Math.round(el.getBoundingClientRect().width)));
      }
      assert.ok(new Set(widths).size >= 3, 'layout controls must change the preview');
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'horizontal overflow');
      for (const theme of ['light', 'dark']) {
        await page.evaluate(value => document.documentElement.setAttribute('data-theme', value), theme);
        const result = await new AxeBuilder({ page }).include('.feature-stories').analyze();
        assert.deepEqual(result.violations.map(v => ({ id: v.id, nodes: v.nodes.map(n => n.target) })), [], `accessibility: ${theme}`);
      }
      if (viewport.width < 700) {
        const toggle = page.locator('.menu-toggle');
        await toggle.click();
        assert.equal(await toggle.getAttribute('aria-expanded'), 'true');
        await page.locator('#nav-menu a[href="#features"]').click();
        assert.equal(await toggle.getAttribute('aria-expanded'), 'false');
        assert.equal(await page.evaluate(() => location.hash), '#features');
      }
      console.log(`Browser checks passed at ${viewport.width}x${viewport.height}; preview widths ${widths.join(', ')}`);
      await page.close();
    }
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
