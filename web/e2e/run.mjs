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
  const mirrored = (selector) => page.evaluate((sel) => [...document.querySelectorAll('*')]
    .flatMap(n => n.shadowRoot ? [...n.shadowRoot.querySelectorAll(sel)] : []).map(b => b.textContent.trim()).filter(Boolean), selector);
  const buttons = () => mirrored('[role=button]');
  const has = async (text) => (await buttons()).some(b => b.includes(text));
  const press = async (name, wait = 3000) => {
    await page.getByRole('button', { name, exact: false }).first().dispatchEvent('click', {}, { timeout: 10_000 }).catch(() => {});
    await page.waitForTimeout(wait);
  };
  const shot = (n) => page.screenshot({ path: `out/${name}-${n}.png` });
  await page.goto(url);
  await page.waitForTimeout(8000);
  return { ctx, page, extension, errors, mirrored, buttons, has, press, shot };
}

/** A show found in Discover and opened — its preview, or its page when subscribed. */
async function openShow(d, show) {
  await d.press('Discover', 3000);
  // The tab keeps the page it showed last: back to its start with the arrow at the top left.
  for (let i = 0; i < 5 && !(await d.mirrored('*')).some(t => t.includes('Search or feed address')); i++) {
    await d.page.mouse.click(28, 32); await d.page.waitForTimeout(1500);
  }
  await d.page.mouse.click(195, 110); await d.page.waitForTimeout(500);
  await d.page.keyboard.press('ControlOrMeta+A');
  await d.page.keyboard.type(show, { delay: 25 }); await d.page.keyboard.press('Enter'); await d.page.waitForTimeout(7000);
  await d.page.mouse.click(195, 330); await d.page.waitForTimeout(7000);
}

/** The show's own search for [query], then that episode's page. */
async function openEpisode(d, query, title) {
  await d.press('Search episodes', 800);
  await d.page.keyboard.type(query, { delay: 25 }); await d.page.keyboard.press('Enter'); await d.page.waitForTimeout(3000);
  await d.press(title, 5000);
}

// 1. Start, the tour, a search through the proxy, a subscription, playback.
const a = await device('a');
check(await a.has('Skip'), 'the tour opens');
await a.press('Skip', 4000);
await openShow(a, 'Nodesignal');
await a.press('Subscribe', 8000);
check(await a.has('Subscribed'), 'a show found and subscribed (feed through the proxy)');
check(!(await a.has('Download')), 'no download offered in the browser');
await a.press('Top episodes', 4000);
check(await a.has('Library'), "a Sovereigncast show's tabs open (Listener API, CORS)");
await a.press('Episodes', 2000);
await a.shot('show');
await openEpisode(a, 'E298', 'Nodesignal-Talk - E298');
await a.press('Play', 12_000);
check(await a.has('Pause'), 'an episode plays');
await a.shot('playing');
await a.press('Pause', 2000);

// 1b. The episode menu saves the audio file instead of downloading it.
const opened = new Promise(ok => { a.ctx.once('page', p => ok(p.url())); setTimeout(() => ok(null), 15_000); });
await a.press('More', 1500);
await a.press('Save audio file', 1000);
check(!!(await opened), "Save audio file hands the episode's audio to the browser");

// 1c. Hopp's own language, apart from the browser's.
await a.press('Home', 2000); await a.press('Settings', 2000);
await a.press('Language', 1500);
await a.page.getByText('Deutsch').first().dispatchEvent('click').catch(() => {});
await a.page.waitForTimeout(10_000);
check(await a.has('Bibliothek'), 'Deutsch chosen in Hopp, the browser English');
await a.press('Einstellungen', 2000);
await a.press('Sprache', 1500);
await a.page.getByText('Sprache des Browsers').first().dispatchEvent('click').catch(() => {});
await a.page.waitForTimeout(10_000);
check(await a.has('Library'), "back to the browser's language");

// 2. Signing in: the connect code as a QR code; the extension; the identity survives a reload.
await a.press('Settings', 2000);
await a.press('Nostr identity', 3000);
check(await a.has('Sign in with your browser extension'), 'the extension is offered');
await a.press('Show a code for your signer', 3000);
check((await a.mirrored('*')).some(t => t === 'QR code for your signer') || await a.page.evaluate(() =>
  [...document.querySelectorAll('*')].some(n => n.shadowRoot && n.shadowRoot.innerHTML.includes('QR code for your signer'))), 'the connect code is shown as a QR code');
await a.press('Cancel', 1500);
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

// 4b. "This is not my device": the library so far moves into memory, nothing stays stored, and a
// reload starts afresh.
const c = await device('c');
const stored = () => c.page.evaluate(async () => {
  const names = []; for await (const [n] of (await navigator.storage.getDirectory()).entries()) names.push(n);
  return names.concat((await indexedDB.databases()).map(d => d.name));
});
const text = async (t) => (await c.mirrored('*')).some(s => s.includes(t));
await c.press('Skip', 4000);
await openShow(c, 'Nodesignal');
await c.press('Subscribe', 8000);
await c.press('Home', 2000); await c.press('Settings', 2000); await c.press('Nostr identity', 3000);
const tabOnly = await c.page.getByText('This is not my device').first().boundingBox().catch(() => null);
check(!!tabOnly, '"This is not my device" is offered at sign-in');
if (tabOnly) await c.page.mouse.click(tabOnly.x + 20, tabOnly.y + tabOnly.height / 2);
await c.page.waitForTimeout(4000);
check(await text('Closing or reloading the tab'), 'it is on');
await c.press('Sign in with your browser extension', 6000);
check(await c.has('Sign out of Hopp'), 'signed in, keeping nothing');
check((await stored()).length === 0, 'nothing stored in the browser: no database file, no secrets');
await c.press('Library', 3000); await c.press('Library', 3000);
check(await text('Nodesignal'), 'the library carried over into memory');
await c.page.reload(); await c.page.waitForTimeout(8000);
check(await c.has('Skip'), 'a reload starts afresh');
check(c.errors.length === 0, `no uncaught errors there${c.errors.length ? ': ' + c.errors[0].slice(0, 200) : ''}`);
await c.ctx.close();

// 5. Two browsers, one key: sync (public relays; throwaway key).
if (withSync) {
  const sk = generateSecretKey();
  const p = await device('sync-a', { sk });
  await p.press('Skip', 4000);
  await openShow(p, 'Nodesignal');
  await p.press('Subscribe', 8000);
  await p.press('Home', 2000); await p.press('Settings', 2000); await p.press('Nostr identity', 2000);
  await p.press('Sign in with your browser extension', 6000);
  await p.press('Sync across devices', 25_000);
  const q = await device('sync-b', { sk });
  await q.press('Skip', 4000); await q.press('Settings', 2000); await q.press('Nostr identity', 2000);
  await q.press('Sign in with your browser extension', 6000);
  await q.press('Sync across devices', 30_000);
  await q.press('Library', 4000);
  await q.shot('library');
  check((await q.mirrored('*')).some(t => t.includes('Nodesignal')), 'the second browser restored the subscription');
  check(q.extension.calls.filter(c => c === 'dec').length === 1, 'its signer was asked to decrypt once');
}

check(a.errors.length === 0, `no uncaught errors${a.errors.length ? ': ' + a.errors[0].slice(0, 200) : ''}`);
await browser.close();
console.log(failed ? `${failed} failed` : 'all passed');
process.exit(failed ? 1 : 0);
