// Browser checks of Hopp's web client (ADR 0022, stage W2), against what scripts/web-dev.sh serves:
//   node run.mjs [--sync] [--url http://127.0.0.1:8642/]
// Compose draws to a canvas; its accessibility layer mirrors buttons into the DOM, and the checks
// read and press those, as a screen reader does. --sync also syncs two browsers over public relays,
// with a throwaway key. Screenshots in ./out.
import fs from 'node:fs';
import { chromium } from 'playwright';
import { generateSecretKey } from 'nostr-tools/pure';
import { installFakeExtension } from './fakeExtension.mjs';

const args = process.argv.slice(2);
const url = args.includes('--url') ? args[args.indexOf('--url') + 1] : 'http://127.0.0.1:8642/';
const withSync = args.includes('--sync');
fs.mkdirSync('out', { recursive: true });

const browser = await chromium.launch({
  executablePath: process.env.CHROME_BIN || undefined,
  args: ['--autoplay-policy=no-user-gesture-required'],
});
let failed = 0;
const check = (ok, what) => { console.log(`${ok ? '✓' : '✗'} ${what}`); if (!ok) failed++; };

async function device(name, ext = {}) {
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, locale: 'en-US' });
  const page = await ctx.newPage();
  const extension = await installFakeExtension(page, ext);
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  const buttons = () => page.evaluate(() => [...document.querySelectorAll('*')]
    .flatMap(n => n.shadowRoot ? [...n.shadowRoot.querySelectorAll('[role=button]')] : []).map(b => b.textContent.trim()));
  const has = async (text) => (await buttons()).some(b => b.includes(text));
  const press = async (text, wait = 3000) => {
    await page.getByRole('button', { name: text, exact: false }).first().dispatchEvent('click', {}, { timeout: 10_000 }).catch(() => {});
    await page.waitForTimeout(wait);
  };
  const shot = (n) => page.screenshot({ path: `out/${name}-${n}.png` });
  await page.goto(url);
  await page.waitForTimeout(8000);
  return { ctx, page, extension, errors, has, press, shot };
}

async function subscribe(d, show) {
  await d.press('Discover', 3000);
  await d.page.mouse.click(195, 110); await d.page.waitForTimeout(600);
  await d.page.keyboard.type(show, { delay: 25 }); await d.page.keyboard.press('Enter'); await d.page.waitForTimeout(7000);
  await d.page.mouse.click(195, 330); await d.page.waitForTimeout(8000);
  await d.press('Subscribe', 8000);
}

// 1. Start, the tour, a search through the proxy, a subscription, playback.
const a = await device('a');
check(await a.has('Skip'), 'the tour opens');
await a.press('Skip', 4000);
await subscribe(a, 'Nodesignal');
check(await a.has('Subscribed'), 'a show found and subscribed (feed through the proxy)');
check(!(await a.has('Download')), 'no download offered in the browser');
await a.shot('show');
await a.page.mouse.click(160, 560); await a.page.waitForTimeout(6000);
await a.press('Play', 12_000);
check(await a.has('Pause'), 'an episode plays');
await a.shot('playing');
await a.press('Pause', 2000);

// 2. Signing in with an extension; the identity survives a reload; no key field.
await a.press('Home', 2000);
await a.press('Settings', 2000);
await a.press('Nostr identity', 3000);
check(await a.has('Sign in with your browser extension'), 'the extension is offered');
await a.press('Sign in with your browser extension', 6000);
check(await a.has('Sign out of Hopp'), 'signed in with the extension');
await a.page.reload(); await a.page.waitForTimeout(8000);
await a.press('Settings', 2000); await a.press('Nostr identity', 3000);
check(await a.has('Sign out of Hopp'), 'still signed in after a reload');
check(a.extension.calls.filter(c => c === 'pk').length === 1, 'the extension was asked once');

// 3. A second tab gets a note, not a second database.
const second = await a.ctx.newPage();
await second.goto(url); await second.waitForTimeout(4000);
check((await second.textContent('body')).includes('open in another tab'), 'a second tab is turned away');
await second.close();

// 4. Signing out takes everything Hopp kept in this browser: the tour again, no secrets left.
await a.press('Home', 2000); await a.press('Settings', 2000); await a.press('Nostr identity', 3000);
await a.press('Sign out of Hopp', 2000);
await a.press('Sign out', 12_000);
check(await a.has('Skip'), 'after signing out Hopp starts afresh');
const left = await a.page.evaluate(() => new Promise(ok => { const r = indexedDB.open('hopp-secrets'); r.onsuccess = () => { const s = r.result.transaction('kv').objectStore('kv').getAllKeys(); s.onsuccess = () => ok(s.result.filter(k => k !== '__key').length); }; }));
check(left === 0, 'no secrets left after signing out');

// 5. Two browsers, one key: sync (public relays; throwaway key).
if (withSync) {
  const sk = generateSecretKey();
  const p = await device('sync-a', { sk });
  await p.press('Skip', 4000);
  await subscribe(p, 'Nodesignal');
  await p.press('Home', 2000); await p.press('Settings', 2000); await p.press('Nostr identity', 2000);
  await p.press('Sign in with your browser extension', 6000);
  await p.press('Sync across devices', 25_000);
  const q = await device('sync-b', { sk });
  await q.press('Skip', 4000); await q.press('Settings', 2000); await q.press('Nostr identity', 2000);
  await q.press('Sign in with your browser extension', 6000);
  await q.press('Sync across devices', 30_000);
  await q.press('Library', 4000);
  await q.shot('library');
  check(await q.has('Nodesignal'), 'the second browser restored the subscription');
  check(q.extension.calls.filter(c => c === 'dec').length === 1, 'its signer was asked to decrypt once');
}

check(a.errors.length === 0, `no uncaught errors${a.errors.length ? ': ' + a.errors[0] : ''}`);
await browser.close();
console.log(failed ? `${failed} failed` : 'all passed');
process.exit(failed ? 1 : 0);
