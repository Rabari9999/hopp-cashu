// Puts Hopp's screenshots on Zapstore in zapstore.yaml's order (docs/zapstore.md):
//   [SIGN_WITH='bunker://…'] node web/deploy/zapstore-images.mjs
// zsp 2 sorts an app's image tags by their address, and Zapstore names each image after its SHA-256:
// the order it shows is chance. With `zsp publish --no-compress` the images are our own files, so each
// address names the file it came from; this signs the app event (kind 32267) again with the same tags,
// the images in the file's order, through the Amber bunker. zsp compares app events with their tags
// sorted, so its next publish keeps this one unless the listing itself changed — then run it again
// (release.sh does, after zsp). Without SIGN_WITH it only says whether the order is right.
import crypto from 'node:crypto'; import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath, pathToFileURL } from 'node:url';
import { verifyEvent } from 'nostr-tools/pure';
import { SIGNER, pool, signWithBunker } from './nostr.mjs';

/** [images] (the app event's addresses) in the order of [hashes], the SHA-256 of the files [listed]. */
export function inOrder(images, hashes, listed) {
  const ordered = hashes.map((hash, i) => {
    const match = images.filter(url => url.includes(hash));
    if (match.length !== 1) throw new Error(`${name(listed[i])} (${hash}) is ${match.length ? 'there more than once' : 'not among the app event\'s images'}: published with --no-compress, from this checkout's files?`);
    return match[0];
  });
  if (images.length !== ordered.length) throw new Error(`the app event has ${images.length} images, zapstore.yaml lists ${ordered.length}`);
  return ordered;
}
const name = (url) => url.split('/').slice(-2).join('/');
const RELAY = 'wss://relay.zapstore.dev'; // zsp's own and only relay

async function main() {
  const APP = 'space.sovereigncast.hopp';
  const RAW = 'https://gitlab.com/nodesignal/hopp/-/raw/main/';
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

  // zapstore.yaml's images: the raw files on main, which this checkout holds.
  const yaml = fs.readFileSync(path.join(root, 'zapstore.yaml'), 'utf8');
  const listed = yaml.split('\n').slice(yaml.split('\n').indexOf('images:') + 1)
    .map(line => line.match(/^\s+-\s+(\S+)/)?.[1]).filter(Boolean);
  for (const url of listed) if (!url.startsWith(RAW)) throw new Error(`${url} is no file of this repository`);
  if (!listed.length) throw new Error('zapstore.yaml lists no images');
  const hashes = listed.map(url => crypto.createHash('sha256').update(fs.readFileSync(path.join(root, url.slice(RAW.length)))).digest('hex'));

  const found = await pool.querySync([RELAY], { kinds: [32267], authors: [SIGNER], '#d': [APP] }, { maxWait: 8000 });
  const app = found.filter(e => verifyEvent(e)).sort((a, b) => b.created_at - a.created_at)[0];
  if (!app) throw new Error(`no app event of ${APP} on ${RELAY}`);
  const images = app.tags.filter(t => t[0] === 'image').map(t => t[1]);
  const ordered = inOrder(images, hashes, listed);
  if (images.every((url, i) => url === ordered[i])) { console.log(`✓ Zapstore shows the ${images.length} screenshots in zapstore.yaml's order`); pool.close([RELAY]); process.exit(0); }

  const first = app.tags.findIndex(t => t[0] === 'image');
  const rest = app.tags.filter(t => t[0] !== 'image');
  const template = {
    kind: app.kind,
    created_at: Math.max(Math.floor(Date.now() / 1000), app.created_at + 1),
    content: app.content,
    tags: [...rest.slice(0, first), ...ordered.map(url => ['image', url]), ...rest.slice(first)],
  };
  console.log('Zapstore\'s order now, and the right one:');
  images.forEach((url, i) => console.log(`  ${String(i + 1).padStart(2)}. ${name(listed[hashes.findIndex(h => url.includes(h))])}`.padEnd(40) + `→ ${name(listed[i])}`));
  if (!process.env.SIGN_WITH) { console.log('\nSIGN_WITH=\'bunker://…\' to sign the app event in this order and publish it.'); pool.close([RELAY]); process.exit(1); }

  const event = await signWithBunker(template);
  if (event.pubkey !== SIGNER) throw new Error(`signed by ${event.pubkey}, not Nodesignal's key — not published`);
  await Promise.any(pool.publish([RELAY], event));
  console.log(`✓ app event ${event.id} on ${RELAY}: the screenshots in zapstore.yaml's order`);
  pool.close([RELAY]);
  process.exit(0);
}
if (import.meta.url === pathToFileURL(process.argv[1]).href) await main();
