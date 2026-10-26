// Browser test for the Music player switch: it reaches the build request, the
// pre-build check explains it, and uploaded pages using CastelMedia are recognised.
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const html = await readFile(resolve('factory/index.html'), 'utf8');
const server = createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
  res.end(html);
});
await new Promise(r => server.listen(4179, '127.0.0.1', r));

const results = [];
const assert = (ok, msg) => { if (!ok) throw new Error('FAIL: ' + msg); results.push('PASS: ' + msg); };
const errors = [];
const builds = [];
const PLANS = { ok: true, currency: 'PHP', paymentsEnabled: true, plans: [
  { id: 'free', name: 'Free', tagline: 'Build and test.', features: ['Test APKs'], prices: { monthly: 0, annual: 0 } },
  { id: 'pro', name: 'Pro', tagline: 'Publish.', features: ['Signed AAB'], prices: { monthly: 49900, annual: 499000 } },
  { id: 'business', name: 'Business', tagline: 'Companies.', features: ['Kit'], prices: { monthly: 169000, annual: 1690000 } } ] };

async function api(route) {
  const req = route.request(), url = req.url(), body = req.postData() ? JSON.parse(req.postData()) : {};
  const json = (d, status = 200) => route.fulfill({ status, headers: { 'access-control-allow-origin': '*', 'content-type': 'application/json' }, body: JSON.stringify(d) });
  if (url.endsWith('/plans')) return json(PLANS);
  if (url.endsWith('/build') && req.method() === 'POST') { builds.push(body); return json({ ok: true, buildId: '11111111-2222-3333-4444-555555555555' }); }
  if (url.includes('/build/')) return json({ status: 'success', buildApkUrl: 'x', buildAabUrl: 'y' });
  return json({ ok: true });
}

const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext({ viewport: { width: 1400, height: 950 } });
  await context.route('https://api.instapped.com/**', api);
  const page = await context.newPage();
  page.on('pageerror', e => errors.push('pageerror: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
  await page.goto('http://127.0.0.1:4179/');
  await page.waitForTimeout(300);

  // Website app with the switch on.
  await page.locator('.seg[data-k=stype] button[data-v=url]').click();
  await page.locator('[data-k=url]').fill('music.example.com');
  await page.locator('#steps button[data-i="1"]').click();
  await page.locator('[data-k=name]').fill('Web Music');
  await page.locator('[data-k=pkg]').fill('com.castel.webmusic');
  const toggle = page.locator('.row:has([data-k=music])');
  assert(await toggle.isVisible() && (await toggle.textContent()).includes('Music player'), 'the Music player switch is shown with the permissions');
  assert(!(await page.locator('[data-k=music]').isChecked()), 'it is off by default');
  await toggle.click();
  assert(await page.locator('[data-k=music]').isChecked(), 'it turns on (no plan needed)');
  assert(!(await page.locator('#plansDlg').evaluate(d => d.open)), 'it does not open the Plans pop-up');

  await page.locator('#steps button[data-i="5"]').click();
  await page.locator('#runCheck').click();
  await page.waitForTimeout(300);
  const checks = await page.locator('#checks').textContent();
  assert(checks.includes('Music player') && checks.includes('Media playback') && checks.includes('window.CastelMedia'), 'the pre-build check explains the Play declaration and the website requirement');

  await page.locator('#steps button[data-i="6"]').click();
  await page.locator('#buildBtn').click();
  await page.waitForTimeout(6500);
  assert(builds.length === 1 && builds[0].mediaPlayer === true, 'the build request carries mediaPlayer: true');

  // Switch off: the key is left out so uploaded pages can still be auto-detected.
  await page.locator('#steps button[data-i="1"]').click();
  await page.locator('.row:has([data-k=music])').click();
  await page.locator('[data-k=vcode]').fill('2');
  await page.locator('#steps button[data-i="6"]').click();
  await page.locator('#buildBtn').click();
  await page.waitForTimeout(6500);
  assert(builds.length === 2 && !('mediaPlayer' in builds[1]), 'with the switch off the request leaves mediaPlayer out');

  // Setting persists with the project.
  await page.locator('#steps button[data-i="1"]').click();
  await page.locator('.row:has([data-k=music])').click();
  await page.reload(); await page.waitForTimeout(400);
  await page.locator('#steps button[data-i="1"]').click();
  assert(await page.locator('[data-k=music]').isChecked(), 'the switch is saved with the project');

  // Uploaded page that uses CastelMedia: recognised automatically.
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await page.locator('#steps button[data-i="0"]').click();
  await page.locator('.seg[data-k=stype] button[data-v=file]').click();
  await page.locator('#srcFile').setInputFiles({ name: 'player.html', mimeType: 'text/html', buffer: Buffer.from('<!doctype html><html><body><h1>Player</h1><script>if (window.CastelMedia) CastelMedia.scan("cb");</script></body></html>') });
  await page.waitForTimeout(400);
  await page.locator('#steps button[data-i="1"]').click();
  await page.locator('.row:has([data-k=music])').click(); // off
  await page.locator('#steps button[data-i="5"]').click();
  await page.locator('#runCheck').click();
  await page.waitForTimeout(300);
  assert((await page.locator('#checks').textContent()).includes('added automatically'), 'an uploaded page using CastelMedia is recognised in the pre-build check');

  if (errors.length) throw new Error('Browser errors:\n' + errors.join('\n'));
  results.push('PASS: no browser errors');
  console.log(results.join('\n'));
} finally {
  await browser.close();
  await new Promise(r => server.close(r));
}
