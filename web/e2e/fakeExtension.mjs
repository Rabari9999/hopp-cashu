// A throwaway NIP-07 extension: window.nostr in the page; the key, the signatures and NIP-44 in
// Node. Nothing of it is a real listener's — a fresh key per run unless one is passed.
import { generateSecretKey, getPublicKey, finalizeEvent } from 'nostr-tools/pure';
import * as nip44 from 'nostr-tools/nip44';

export async function installFakeExtension(page, { sk = generateSecretKey(), refuse = false } = {}) {
  const pk = getPublicKey(sk);
  const calls = [];
  await page.exposeFunction('__hoppTestExtension', async (op, a, b) => {
    calls.push(op);
    if (refuse) throw new Error('User rejected');
    if (op === 'pk') return pk;
    if (op === 'sign') { const e = finalizeEvent(JSON.parse(a), sk); calls.push('kind' + e.kind); return JSON.stringify(e); }
    if (op === 'enc') return nip44.v2.encrypt(b, nip44.v2.utils.getConversationKey(sk, a));
    if (op === 'dec') return nip44.v2.decrypt(b, nip44.v2.utils.getConversationKey(sk, a));
    throw new Error('unsupported ' + op);
  });
  await page.addInitScript(() => {
    const call = (...args) => window.__hoppTestExtension(...args);
    window.nostr = {
      getPublicKey: () => call('pk'),
      signEvent: (e) => call('sign', JSON.stringify(e)).then(JSON.parse),
      nip44: { encrypt: (peer, text) => call('enc', peer, text), decrypt: (peer, text) => call('dec', peer, text) },
    };
  });
  return { pk, sk, calls };
}
