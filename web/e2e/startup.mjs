// The start's time, by stage: node startup.mjs [webkit|chromium] — three loads in one browser
// context, against what scripts/web-dev.sh serves; the stages are Main.kt's performance marks.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { webkit, chromium } from 'playwright';
const engine = process.argv[2] === 'chromium' ? chromium : webkit;
const options = { viewport: { width: 375, height: 635 }, deviceScaleFactor: 3, locale: 'de-DE' };
// WebKit has no OPFS in a throwaway context (the database would live in memory): a profile on disk.
const profile = engine === webkit && fs.mkdtempSync(path.join(os.tmpdir(), 'hopp-webkit-'));
const ctx = profile
  ? await webkit.launchPersistentContext(profile, options)
  : await (await chromium.launch({ executablePath: process.env.CHROME_BIN })).newContext(options);
for (let run = 0; run < 3; run++) {
  const page = await ctx.newPage();
  page.on('console', m => { if (m.text().startsWith('[sqlite]')) console.log('  worker', m.text()); });
  const t0 = Date.now();
  await page.goto('http://127.0.0.1:8642/');
  let tour = null;
  let splash = null;
  for (let i = 0; i < 200; i++) { if (splash === null && await page.locator('#hopp-splash svg').isVisible().catch(() => false)) splash = Date.now() - t0; if (await page.getByRole('button', { name: 'Überspringen' }).count()) { tour = Date.now() - t0; break; } await page.waitForTimeout(50); }
  const perf = await page.evaluate(() => ({
    marks: performance.getEntriesByType('mark').map(m => [m.name, Math.round(m.startTime)]),
    res: performance.getEntriesByType('resource').filter(r => r.duration > 5).map(r => [r.name.split('/').pop().slice(0, 40), Math.round(r.startTime), Math.round(r.responseEnd), r.transferSize || r.encodedBodySize]),
  }));
  console.log(`run ${run}: splash after ${splash} ms, tour visible after ${tour} ms`);
  console.log(' marks', JSON.stringify(perf.marks));
  for (const r of perf.res) console.log('  ', r.join('  '));
  await page.close();
}
await ctx.close();
await ctx.browser()?.close();
if (profile) fs.rmSync(profile, { recursive: true, force: true });
