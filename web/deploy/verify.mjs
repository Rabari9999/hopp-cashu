// Checks what a server gives out as Hopp Web against what Nodesignal signed (ADR 0022, 14):
//   node verify.mjs [https://play.sovereigncast.space/]
// Fetches release.json, finds Nodesignal's signature for its hash on the relays, then fetches
// every file it lists and compares each one's SHA-256. Exit 0 only when all of it holds — the
// check from outside that the page itself cannot give (a page a server altered can say anything
// about itself). Run on a schedule by CI (web-verify) as a watch on the server.
import crypto from 'node:crypto';
import { pool, relays, signatureFor, signedBuilds, tag } from './nostr.mjs';

const base = (process.argv[2] ?? 'https://play.sovereigncast.space/').replace(/\/?$/, '/');
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const get = async (rel) => {
  const r = await fetch(base + rel, { headers: { 'cache-control': 'no-cache' } });
  if (!r.ok) throw new Error(`${rel}: HTTP ${r.status}`);
  return Buffer.from(await r.arrayBuffer());
};
let failed = 0;
const say = (ok, what) => { console.log(`${ok ? '✓' : '✗'} ${what}`); if (!ok) failed++; };

const text = await get('release.json').catch((e) => { console.log(`✗ no build list: ${e.message}`); process.exit(1); });
const release = JSON.parse(text);
const hash = sha256(text);
console.log(`${base}: Hopp Web ${release.version}, build ${hash}`);
const urls = await relays();
const signature = await signatureFor(hash, urls);
if (signature) say(true, `signed by Nodesignal: ${signature.id} (${new Date(signature.created_at * 1000).toISOString()})`);
else {
  const builds = await signedBuilds(urls);
  const same = builds.find(e => tag(e, 'version') === release.version);
  say(false, same ? `Nodesignal signed ${release.version} as build ${tag(same, 'x')}, not this one` : `no signature by Nodesignal for this build (${builds.length} signed builds found)`);
}
let differ = 0;
for (const [rel, want] of Object.entries(release.files)) {
  const got = sha256(await get(rel));
  if (got !== want) { differ++; say(false, `${rel}: ${got}, the list says ${want}`); }
}
say(differ === 0, `${Object.keys(release.files).length} files compared with the list`);
// The address itself serves the page the list names.
say(sha256(await get('')) === release.files['index.html'], 'the address serves index.html');
pool.close(urls);
console.log(failed ? `${failed} failed` : 'all holds');
process.exit(failed ? 1 : 0);
