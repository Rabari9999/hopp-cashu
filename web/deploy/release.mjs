// The build's file list (ADR 0022, 14): node release.mjs <site> [version] writes <site>/release.json —
// every file the browser loads, with its SHA-256, sorted. Its own SHA-256 is the build's hash:
// what sign.mjs signs, what Hopp shows under Settings → About, what verify.mjs compares the live
// site with. Left out: the precompressed copies (a browser gets the file they hold), version.txt
// (written for people) and release.json itself.
import crypto from 'node:crypto'; import fs from 'node:fs'; import path from 'node:path';

const site = process.argv[2];
const version = process.argv[3] || fs.readFileSync(path.join(site, 'version.txt'), 'utf8').trim();
// The service worker carries the version: the browser looks for an update only when it changes.
const worker = path.join(site, 'sw.js');
if (fs.existsSync(worker)) fs.writeFileSync(worker, fs.readFileSync(worker, 'utf8').replace(/__HOPP_VERSION__|(const VERSION = ')[^']*(')/, (m, a, b) => a ? `${a}${version}${b}` : version));
const skip = (rel) => /\.(br|gz)$/.test(rel) || rel === 'release.json' || rel === 'version.txt' || rel.endsWith('.map');
const files = {};
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name < b.name ? -1 : 1)) {
    const f = path.join(dir, e.name);
    const rel = path.relative(site, f).split(path.sep).join('/');
    if (e.isDirectory()) { walk(f); continue; }
    if (skip(rel)) continue;
    files[rel] = crypto.createHash('sha256').update(fs.readFileSync(f)).digest('hex');
  }
}
walk(site);
const sorted = Object.fromEntries(Object.keys(files).sort().map(k => [k, files[k]]));
const text = JSON.stringify({ name: 'Hopp Web', version, files: sorted }, null, 1) + '\n';
fs.writeFileSync(path.join(site, 'release.json'), text);
const hash = crypto.createHash('sha256').update(text).digest('hex');
console.log(`release.json: ${Object.keys(sorted).length} files, version ${version}, build ${hash}`);
