// What sign.mjs and verify.mjs share (ADR 0022, 14): who signs Hopp Web's builds, where the
// signatures are, and how one is found.
import { SimplePool } from 'nostr-tools/pool';
import { verifyEvent } from 'nostr-tools/pure';
import * as nip19 from 'nostr-tools/nip19';

/** Nodesignal's key — the one that signs Hopp's releases on Zapstore too (zapstore.yaml). */
export const SIGNER_NPUB = 'npub1n0devk3h2l3rx6vmt24a3lz4hsxp7j8rn3x44jkx6daj7j8jzc0q2u02cy';
export const SIGNER = nip19.decode(SIGNER_NPUB).data;
/** Hopp's own (IdentityRepository.DEFAULT_RELAYS): asked by the app; Nodesignal's write relays are added. */
export const RELAYS = ['wss://relay.damus.io', 'wss://nos.lol', 'wss://relay.primal.net'];
const INDEX = ['wss://purplepag.es', 'wss://user.kindpag.es'];
/** NIP-94 file metadata: the file is release.json, its `x` the build's hash. */
export const KIND = 1063;
export const TOPIC = 'hopp-web';

export const pool = new SimplePool();

/** Hopp's relays and Nodesignal's write relays (NIP-65). */
export async function relays() {
  const list = await pool.get(INDEX.concat(RELAYS), { kinds: [10002], authors: [SIGNER] }, { maxWait: 4000 }).catch(() => null);
  const own = (list?.tags ?? []).filter(t => t[0] === 'r' && t[1]?.startsWith('wss://') && t[2] !== 'read').map(t => t[1].replace(/\/$/, ''));
  return [...new Set(RELAYS.concat(own.slice(0, 4)))];
}

/** Nodesignal's signature for the build [hash], if a relay has one that verifies. */
export async function signatureFor(hash, urls) {
  const found = await pool.querySync(urls, { kinds: [KIND], authors: [SIGNER], '#x': [hash] }, { maxWait: 6000 });
  return found.find(e => verifyEvent(e) && e.pubkey === SIGNER && e.tags.some(t => t[0] === 'x' && t[1] === hash)) ?? null;
}

/** Every build of Hopp Web Nodesignal signed, newest first. */
export async function signedBuilds(urls) {
  const found = await pool.querySync(urls, { kinds: [KIND], authors: [SIGNER], '#t': [TOPIC] }, { maxWait: 6000 });
  return found.filter(e => verifyEvent(e) && e.pubkey === SIGNER).sort((a, b) => b.created_at - a.created_at);
}

export const tag = (e, name) => e.tags.find(t => t[0] === name)?.[1];
