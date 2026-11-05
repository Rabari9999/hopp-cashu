// Signs a build of Hopp Web with Nodesignal's key (ADR 0022, 14), through a bunker (NIP-46) as
// Zapstore's releases are — Amber asks before it signs:
//   SIGN_WITH='bunker://…' node sign.mjs <build/web-release/<version>/site>
// The event is NIP-94 file metadata for release.json: `x` its SHA-256 (the build's hash),
// `version` the build's. It goes to Hopp's relays and Nodesignal's write relays; without
// SIGN_WITH it is only shown.
//
// The script keeps its own key for the bunker (~/.config/hopp-web-sign/client.key): Amber knows it
// after the first time, and a bunker:// address's secret is good for one connection only.
import crypto from 'node:crypto'; import fs from 'node:fs'; import os from 'node:os'; import path from 'node:path';
import { generateSecretKey } from 'nostr-tools/pure';
import { BunkerSigner, parseBunkerInput } from 'nostr-tools/nip46';
import { KIND, TOPIC, SIGNER, pool, relays, signatureFor } from './nostr.mjs';

const site = process.argv[2] ?? (() => { throw new Error('usage: node sign.mjs <site>'); })();
const text = fs.readFileSync(path.join(site, 'release.json'));
const release = JSON.parse(text);
const hash = crypto.createHash('sha256').update(text).digest('hex');
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

const pointer = await parseBunkerInput(process.env.SIGN_WITH);
if (!pointer) throw new Error('SIGN_WITH is no bunker:// address');
const keyFile = path.join(os.homedir(), '.config', 'hopp-web-sign', 'client.key');
let clientKey;
try { clientKey = new Uint8Array(Buffer.from(fs.readFileSync(keyFile, 'utf8').trim(), 'hex'));
  if (clientKey.length !== 32) throw new Error('no key'); } catch {
  clientKey = generateSecretKey();
  fs.mkdirSync(path.dirname(keyFile), { recursive: true, mode: 0o700 });
  fs.writeFileSync(keyFile, Buffer.from(clientKey).toString('hex'), { mode: 0o600 });
}
const bunker = BunkerSigner.fromBunker(clientKey, pointer, { onauth: (url) => console.log(`the bunker asks to be opened: ${url}`) });
// Connected before, with this key: Amber says so, and signs all the same.
await bunker.connect().catch((e) => { if (!/already connected/i.test(String(e?.message ?? e))) throw e; });
console.log('asking the bunker to sign — confirm in Amber');
const event = await Promise.race([
  bunker.signEvent(template),
  new Promise((_, no) => setTimeout(() => no(new Error('no signature from the bunker within 2 minutes: is Amber open? Its secret may belong to another app — make a new bunker connection in Amber for this script')), 120_000)),
]);
await bunker.close();
if (event.pubkey !== SIGNER) throw new Error(`signed by ${event.pubkey}, not Nodesignal's key — not published`);
const results = await Promise.allSettled(pool.publish(urls, event));
const ok = results.filter(r => r.status === 'fulfilled').length;
console.log(`build ${hash} signed: ${event.id}, on ${ok} of ${urls.length} relays`);
pool.close(urls);
process.exit(ok ? 0 : 1);
