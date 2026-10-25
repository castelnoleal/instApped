// Browser test for the one-time App Pass: buying it for the open app,
// unlocking Pro features only for that package ID, and managing passes.
// The build service and PayMongo are mocked.
import { chromium } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

const html = await readFile(resolve('factory/index.html'), 'utf8');
const fakeCheckout = "<!doctype html><title>PayMongo test</title><script>setTimeout(()=>location.href='/#purchase=success',200)</script>";
const server = createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
  res.end(req.url.startsWith('/fake-checkout') ? fakeCheckout : html);
});
await new Promise(r => server.listen(4176, '127.0.0.1', r));

const results = [];
const assert = (ok, msg) => { if (!ok) throw new Error('FAIL: ' + msg); results.push('PASS: ' + msg); };
const errors = [];
const state = { builds: [] };
const PASS_EXP = Date.UTC(2100, 0, 1);
const passKey = pkg => 'IAL1.' + Buffer.from(JSON.stringify({ v: 1, plan: 'app', exp: PASS_EXP, ref: 'cs_pass', pkg })).toString('base64url') + '.sig';
const PLANS = { ok: true, currency: 'PHP', paymentsEnabled: true, plans: [
  { id: 'free', name: 'Free', tagline: 'Build and test.', features: ['Test APKs'], prices: { monthly: 0, annual: 0 } },
  { id: 'app', name: 'App Pass', oneTime: true, tagline: 'Pay once to publish one app. No subscription.', features: ['Pro features for one app, for good'], prices: { once: 149000 } },
  { id: 'pro', name: 'Pro', tagline: 'Publish on Google Play.', features: ['Signed AAB'], prices: { monthly: 49900, annual: 499000 } },
  { id: 'business', name: 'Business', tagline: 'For companies.', features: ['Publishing kit'], prices: { monthly: 169000, annual: 1690000 } } ] };

async function api(route) {
  const req = route.request(), url = req.url(), body = req.postData() ? JSON.parse(req.postData()) : {};
  const json = (d, status = 200) => route.fulfill({ status, headers: { 'access-control-allow-origin': '*', 'content-type': 'application/json' }, body: JSON.stringify(d) });
  if (url.endsWith('/plans')) return json(PLANS);
  if (url.endsWith('/checkout')) { state.checkout = body; return json({ ok: true, sessionId: 'cs_pass', checkoutUrl: 'http://127.0.0.1:4176/fake-checkout', reference: 'IA-PASS00000001' }); }
  if (url.endsWith('/license/claim')) {
    state.claim = body;
    return json({ ok: true, key: passKey(state.checkout.packageName), plan: 'app', pkg: state.checkout.packageName, exp: PASS_EXP, reference: 'IA-PASS00000001' });
  }
  if (url.endsWith('/license/verify')) {
    const key = String(body.key || '');
    if (!key.startsWith('IAL1.')) return json({ ok: true, valid: false, plan: 'free', reason: "That isn't an instApped license key." });
    const p = JSON.parse(Buffer.from(key.split('.')[1], 'base64url').toString());
    return json({ ok: true, valid: true, plan: p.plan, exp: p.exp, ...(p.pkg ? { pkg: p.pkg } : {}), name: p.plan === 'app' ? 'App Pass' : p.plan });
  }
  if (url.endsWith('/build') && req.method() === 'POST') {
    state.builds.push(body);
    return json({ ok: true, buildId: '11111111-2222-3333-4444-555555555555' });
  }
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
  await page.goto('http://127.0.0.1:4176/');
  await page.waitForTimeout(300);
  const plansOpen = () => page.locator('#plansDlg').evaluate(d => d.open);
  const chip = () => page.locator('#planChip').textContent();

  // Without a package ID, the App Pass asks for one first.
  await page.locator('.seg[data-k=stype] button[data-v=url]').click();
  await page.locator('[data-k=url]').fill('example.com');
  await page.locator('#planChip').click();
  assert(await page.locator('.plan').count() === 4, 'four plans are shown, including the App Pass');
  const card = page.locator('.plan[data-plan=app]');
  assert((await card.locator('.price').textContent()).includes('₱1,490') && (await card.locator('.price').textContent()).includes('one-time'), 'the App Pass shows a one-time price in pesos');
  await page.locator('#billSeg button[data-v=annual]').click();
  assert((await card.locator('.price').textContent()).includes('₱1,490'), 'the yearly/monthly switch does not change the one-time price');
  await page.locator('#billSeg button[data-v=monthly]').click();
  await card.locator('[data-goto-pkg]').click();
  assert(!(await plansOpen()), 'asking for a package ID closes the pop-up');
  assert(await page.locator('[data-k=pkg]').isVisible(), 'and opens the Details step with the package field');
  await page.locator('[data-k=name]').fill('Shop App');
  await page.locator('[data-k=pkg]').fill('com.shop.app');
  await page.locator('[data-k=pkg]').dispatchEvent('change');

  // Signing is gated, and the pop-up mentions the App Pass.
  await page.locator('#steps button[data-i="6"]').click();
  await page.locator('.row:has([data-k=sign])').click();
  assert(await plansOpen(), 'signing on Free opens the Plans pop-up');
  assert((await page.locator('#plansWhy').textContent()).includes('App Pass'), 'the pop-up offers the App Pass as a way to unlock it');
  assert((await card.locator('.passfor').textContent()).includes('com.shop.app'), 'the App Pass is for the open app');

  // Buy it.
  await Promise.all([page.waitForURL('**/fake-checkout'), card.locator('[data-buy=app]').click()]);
  assert(state.checkout.plan === 'app' && state.checkout.packageName === 'com.shop.app' && state.checkout.period === 'once', 'checkout is requested for an App Pass for this package ID');
  await page.locator('#okDlg').waitFor({ state: 'visible', timeout: 15000 });
  assert((await page.locator('#okTitle').textContent()) === 'Your App Pass is active', 'returning from checkout activates the App Pass');
  assert((await page.locator('#okBody').textContent()).includes('com.shop.app'), 'the confirmation names the app');
  assert(state.claim.currentKey === '', 'claiming a pass does not send a subscription key');
  await page.locator('#okDone').click();
  assert((await chip()) === 'App Pass', 'the plan badge shows App Pass for this app');
  assert(await page.locator('#ad-slot').isHidden(), 'no ads while working on the passed app');

  // Pro options work for this app, and the build sends the pass.
  await page.locator('#steps button[data-i="6"]').click();
  await page.locator('.row:has([data-k=sign])').click();
  assert(await page.locator('[data-k=sign]').isChecked(), 'signing can be turned on for the passed app');
  await page.locator('#buildBtn').click();
  await page.waitForTimeout(6500);
  const last = state.builds.at(-1);
  assert(last && last.licenseKey === passKey('com.shop.app') && last.packageName === 'com.shop.app' && last.signing === true, 'the build sends the App Pass key');

  // A different app is back on Free.
  await page.locator('#steps button[data-i="1"]').click();
  await page.locator('[data-k=pkg]').fill('com.other.app');
  await page.locator('[data-k=pkg]').dispatchEvent('change');
  await page.waitForTimeout(100);
  assert((await chip()) === 'Free', 'another package ID is on Free');
  await page.locator('#planChip').click();
  assert(await card.locator('[data-buy=app]').count() === 1 && (await card.locator('.passfor').textContent()).includes('com.other.app'), 'a new pass can be bought for the other app');
  await page.locator('#plansClose').click();
  await page.locator('[data-k=pkg]').fill('com.shop.app');
  await page.locator('[data-k=pkg]').dispatchEvent('change');
  await page.waitForTimeout(100);
  assert((await chip()) === 'App Pass', 'switching back restores the pass');

  // Settings lists passes; a pasted pass key is added as a pass, not a subscription.
  await page.reload();
  await page.waitForTimeout(400);
  assert((await chip()) === 'App Pass', 'the pass persists after reload');
  await page.getByRole('button', { name: 'Settings', exact: true }).click();
  assert((await page.locator('#passList').textContent()).includes('com.shop.app'), 'Settings lists the App Pass');
  assert((await page.locator('#licStatus').textContent()).includes('com.shop.app'), 'the plan status names the passed app');
  await page.locator('#licKey').fill(passKey('com.gift.app'));
  await page.locator('#licSave').click();
  await page.waitForTimeout(300);
  assert((await page.locator('#passList').textContent()).includes('com.gift.app'), 'a pasted App Pass key is added to the passes');
  assert((await page.locator('#licKey').inputValue()) === '', 'and is not kept as the subscription license');
  page.once('dialog', d => d.accept());
  await page.locator('#passList [data-pdel]').first().click();
  await page.waitForTimeout(200);
  assert(!(await page.locator('#passList').textContent()).includes('com.gift.app'), 'a pass can be removed from the device');

  if (errors.length) throw new Error('Browser errors:\n' + errors.join('\n'));
  results.push('PASS: no browser errors');
  console.log(results.join('\n'));
  console.log(`\n${results.length} App Pass checks passed.`);
} finally {
  await browser.close();
  await new Promise(r => server.close(r));
}
