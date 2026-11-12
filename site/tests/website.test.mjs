import { readFileSync, existsSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const origin = 'https://streambridgeapp.pages.dev';
for (const [file, path] of [['index.html', '/'], ['news/index.html', '/news/'], ['terms/index.html', '/terms/'], ['privacy/index.html', '/privacy/'], ['party/index.html', '/party/']]) {
  const html = readFileSync(resolve(root, file), 'utf8');
  assert.ok(html.includes(`<link rel="canonical" href="${origin}${path}">`), file + ' canonical');
  assert.match(html, /<meta name="description"/);
  assert.match(html, /property="og:image"/);
  assert.ok(html.includes('Made with ❤️ by Saif'), file + ' credit');
  for (const match of html.matchAll(/(?:src|poster)="([^"#?]+)"/g)) {
    const src = match[1];
    if (/^(?:https?:|data:)/.test(src)) continue;
    const asset = src.startsWith('/') ? resolve(root, '.' + src) : resolve(root, dirname(file), decodeURIComponent(src));
    assert.ok(existsSync(asset), `${file}: ${src}`);
  }
}
const sitemap = readFileSync(resolve(root, 'sitemap.xml'), 'utf8');
for (const path of ['/', '/news/', '/terms/', '/privacy/']) assert.ok(sitemap.includes(`<loc>${origin}${path}</loc>`));
assert.ok(!sitemap.includes('/party/'));
console.log('Website identity, metadata, local assets and sitemap checks passed.');
