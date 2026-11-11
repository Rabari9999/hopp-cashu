// Signs a build of Hopp Web with Nodesignal's key (ADR 0022, 14), through a bunker (NIP-46) as
// Zapstore's releases are — Amber asks before it signs:
//   SIGN_WITH='bunker://…' node sign.mjs <build/web-release/<version>/site>
// The event is NIP-94 file metadata for release.json: `x` its SHA-256 (the build's hash),
// `version` the build's. It goes to Hopp's relays and Nodesignal's write relays; without
// SIGN_WITH it is only shown.
import crypto from 'node:crypto'; import fs from 'node:fs'; import path from 'node:path';
import { KIND, TOPIC, SIGNER, pool, relays, signatureFor, signWithBunker } from './nostr.mjs';

const site = process.argv[2] ?? (() => { throw new Error('usage: node sign.mjs <site>'); })();
const text = fs.readFileSync(path.join(site, 'release.json'));
const release = JSON.parse(text);
const hash = crypto.createHash('sha256').update(text).digest('hex');
// verify.mjs takes any signed build as good: a signature is for releases alone (ADR 0022, 15).
if (!/^v\d+\.\d+\.\d+$/.test(release.version)) throw new Error(`${release.version} is no release: only a build of an exact tag is signed`);
const urls = await relays();
const already = await signatureFor(hash, urls);
if (already) { console.log(`build ${hash} (${release.version}) is signed already: ${already.id}`); process.exit(0); }

const template = {
  kind: KIND,
  created_at: Math.floor(Date.now() / 1000),
  content: `Hopp Web ${release.version}`,
  tags: [
    ['url', 'https://play.sovereigncast.space/release.json'],
    ['m', 'application/json'],
    ['x', hash],
    ['size', String(text.length)],
    ['version', release.version],
    ['t', TOPIC],
    ['alt', `Hopp Web ${release.version}: the build's files and their SHA-256 (https://gitlab.com/nodesignal/hopp)`],
  ],
};
console.log(JSON.stringify(template, null, 1));
if (!process.env.SIGN_WITH) { console.log('\nSIGN_WITH=\'bunker://…\' to sign and publish it.'); process.exit(0); }

const event = await signWithBunker(template);
if (event.pubkey !== SIGNER) throw new Error(`signed by ${event.pubkey}, not Nodesignal's key — not published`);
const results = await Promise.allSettled(pool.publish(urls, event));
const ok = results.filter(r => r.status === 'fulfilled').length;
console.log(`build ${hash} signed: ${event.id}, on ${ok} of ${urls.length} relays`);
pool.close(urls);
process.exit(ok ? 0 : 1);
