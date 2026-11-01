// Browser checks of Hopp's web client (ADR 0022, stage W2), against what scripts/web-dev.sh serves:
//   node run.mjs [--sync] [--webkit] [--url http://127.0.0.1:8642/]
// Compose draws to a canvas; its accessibility layer mirrors buttons into the DOM, and the checks
// read and press those, as a screen reader does. --sync also syncs two browsers over public relays,
// with a throwaway key. --webkit runs them in Playwright's WebKit, Safari's engine. Screenshots in ./out.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { chromium, webkit } from 'playwright';
import { generateSecretKey } from 'nostr-tools/pure';
import { installFakeExtension } from './fakeExtension.mjs';

const args = process.argv.slice(2);
const url = args.includes('--url') ? args[args.indexOf('--url') + 1] : 'http://127.0.0.1:8642/';
const withSync = args.includes('--sync');
const inWebKit = args.includes('--webkit');
fs.mkdirSync('out', { recursive: true });

const browser = inWebKit ? null : await chromium.launch({
  executablePath: process.env.CHROME_BIN || undefined,
  args: ['--autoplay-policy=no-user-gesture-required'],
});
// WebKit gives a page no OPFS in Playwright's throwaway contexts, and Hopp would fall back to a
// database in memory there: each device gets a profile of its own on disk instead.
const contexts = [];
const profiles = [];
async function newContext(options) {
  const profile = inWebKit && fs.mkdtempSync(path.join(os.tmpdir(), 'hopp-webkit-'));
  if (profile) profiles.push(profile);
  const ctx = profile ? await webkit.launchPersistentContext(profile, options) : await browser.newContext(options);
  contexts.push(ctx);
  return ctx;
}
let failed = 0;
// The last check passed or failed, so an error or a refusal says where it happened.
let after = 'the start';
let pressed = '';
const check = (ok, what) => { console.log(`${ok ? '✓' : '✗'} ${what}`); if (!ok) failed++; after = what; pressed = ''; };
const where = () => `(after "${after}"${pressed ? `, pressing "${pressed}"` : ''})`;

async function device(name, ext = {}) {
  const ctx = await newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, locale: 'en-US' });
  const page = ctx.pages()[0] ?? await ctx.newPage();
  const extension = await installFakeExtension(page, ext);
  const errors = [];
  page.on('pageerror', e => errors.push({ at: Date.now(), text: `${e} ${where()}` }));
  // WebKit fails the old page's loads still under way when it reloads (signing out, a language
  // chosen), and they surface there as errors just before the new page comes: not the listener's.
  if (inWebKit) page.on('framenavigated', f => {
    if (f !== page.mainFrame()) return;
    for (let i = errors.length - 1; i >= 0 && errors[i].at > Date.now() - 1000; i--) errors.splice(i, 1);
  });
  // Under scripts/web-dev.sh --strict the page has the server's CSP; what it refuses shows here.
  const refused = [];
  // The browser's own message names no culprit; the violation's sample shows what was refused.
  await page.addInitScript(() => document.addEventListener('securitypolicyviolation', e => console.log(
    `Content Security Policy refused ${e.violatedDirective}: "${e.sample}" from ${e.sourceFile || e.blockedURI}:${e.lineNumber}`), true));
  // A screenshot in WebKit puts a <style> of Playwright's into the page, which the CSP refuses.
  let shooting = false;
  page.on('console', m => { if (/Content Security Policy/.test(m.text()) && !shooting) refused.push(`${m.text()} ${where()}`); });
  const mirrored = (selector) => page.evaluate((sel) => [...document.querySelectorAll('*')]
    .flatMap(n => n.shadowRoot ? [...n.shadowRoot.querySelectorAll(sel)] : []).map(b => b.textContent.trim()).filter(Boolean), selector);
  const buttons = () => mirrored('[role=button]');
  const has = async (text) => (await buttons()).some(b => b.includes(text));
  // The button of that name, else the first whose name holds it: "Home" is the tab, not "Customize Home".
  const press = async (name, wait = 3000) => {
    pressed = name;
    const exact = page.getByRole('button', { name, exact: true });
    const button = (await exact.count()) > 0 ? exact : page.getByRole('button', { name, exact: false });
    await button.first().dispatchEvent('click', {}, { timeout: 10_000 }).catch(() => {});
    await page.waitForTimeout(wait);
  };
  const shot = async (n) => {
    shooting = true;
    await page.screenshot({ path: `out/${inWebKit ? 'webkit-' : ''}${name}-${n}.png` });
    await page.waitForTimeout(500);
    shooting = false;
  };
  // A menu or dialog closed leaves Compose's mirror for screen readers frozen on it until the page
  // is reloaded (Compose Multiplatform 1.11/1.12, fixed in 1.13 — docs/tasks/web-client.md): the
  // checks reload after each one, as a screen reader's user would have to.
  const reload = async () => { await page.reload(); await page.waitForTimeout(8000); };
  await page.goto(url);
  // Until the first screen is mirrored (a cold WebKit takes longer than a warm Chromium), then a moment more.
  for (let i = 0; i < 40 && (await buttons()).length === 0; i++) await page.waitForTimeout(500);
  await page.waitForTimeout(3000);
  return { ctx, page, extension, errors, refused, mirrored, buttons, has, press, shot, reload };
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
// No wallet in the browser yet (ADR 0022, 12): the episode of a show with a value block offers no boost.
check(!(await a.buttons()).some(b => b.trim() === 'Boost'), 'no boost offered in the browser');
await a.shot('playing');
await a.press('Pause', 2000);

// 1b. The episode menu saves the audio file instead of downloading it.
const opened = new Promise(ok => { a.ctx.once('page', p => ok(p.url())); setTimeout(() => ok(null), 15_000); });
await a.press('More', 1500);
await a.press('Save audio file', 1000);
check(!!(await opened), "Save audio file hands the episode's audio to the browser");
await a.reload();

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
await a.reload();
await a.press('Settings', 2000);
await a.press('Nostr identity', 3000);
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
// The library went too: the database opened afresh, nothing of the show subscribed before.
await a.press('Skip', 4000); await a.press('Library', 3000);
check(!(await a.mirrored('*')).some(t => t.includes('Nodesignal')), 'no library left after signing out');

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
check(c.errors.length === 0, `no uncaught errors there${c.errors.length ? ': ' + c.errors[0].text.slice(0, 200) : ''}`);
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

check(a.errors.length === 0, `no uncaught errors${a.errors.length ? ':\n  ' + a.errors.map(e => e.text.slice(0, 300)).join('\n  ') : ''}`);
check(a.refused.length === 0, `nothing refused by the CSP${a.refused.length ? ':\n  ' + a.refused.map(e => e.slice(0, 300)).join('\n  ') : ''}`);
for (const ctx of contexts) await ctx.close().catch(() => {});
await browser?.close();
for (const profile of profiles) fs.rmSync(profile, { recursive: true, force: true });
console.log(failed ? `${failed} failed` : 'all passed');
process.exit(failed ? 1 : 0);
