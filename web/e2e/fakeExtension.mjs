// A throwaway NIP-07 extension: window.nostr in the page; the key, the signatures and NIP-44 in
// Node. Nothing of it is a real listener's — a fresh key per run unless one is passed.
// With webln, a WebLN wallet too (ADR 0022, 4, stage W3) that pays nothing: it records what it
// was asked and answers keysend with a made-up preimage (an invoice's it cannot know: refused).
import { generateSecretKey, getPublicKey, finalizeEvent } from 'nostr-tools/pure';
import * as nip44 from 'nostr-tools/nip44';
import { randomBytes } from 'node:crypto';

export async function installFakeExtension(page, { sk = generateSecretKey(), refuse = false, webln = false } = {}) {
  const pk = getPublicKey(sk);
  const calls = [];
  const payments = [];
  if (webln) await page.exposeFunction('__hoppTestWebln', async (op, a) => {
    payments.push({ op, ...JSON.parse(a || '{}') });
    if (op === 'enable' || op === 'balance') return '';
    if (op === 'keysend') return randomBytes(32).toString('hex');
    throw new Error('Payment refused by the test wallet');
  });
  await page.exposeFunction('__hoppTestExtension', async (op, a, b) => {
    calls.push(op);
    if (refuse) throw new Error('User rejected');
    if (op === 'pk') return pk;
    if (op === 'sign') { const e = finalizeEvent(JSON.parse(a), sk); calls.push('kind' + e.kind); return JSON.stringify(e); }
    if (op === 'enc') return nip44.v2.encrypt(b, nip44.v2.utils.getConversationKey(sk, a));
    if (op === 'dec') return nip44.v2.decrypt(b, nip44.v2.utils.getConversationKey(sk, a));
    throw new Error('unsupported ' + op);
  });
  await page.addInitScript((withWebln) => {
    const call = (...args) => window.__hoppTestExtension(...args);
    window.nostr = {
      getPublicKey: () => call('pk'),
      signEvent: (e) => call('sign', JSON.stringify(e)).then(JSON.parse),
      nip44: { encrypt: (peer, text) => call('enc', peer, text), decrypt: (peer, text) => call('dec', peer, text) },
    };
    if (withWebln) {
      const pay = (op, a) => window.__hoppTestWebln(op, JSON.stringify(a || {}));
      window.webln = {
        enable: () => pay('enable').then(() => undefined),
        getInfo: async () => ({ node: { alias: 'Test wallet', pubkey: '02' + '11'.repeat(32) }, methods: ['getInfo', 'sendPayment', 'keysend', 'getBalance'] }),
        sendPayment: (paymentRequest) => pay('sendPayment', { paymentRequest }).then(preimage => ({ preimage })),
        keysend: (args) => pay('keysend', args).then(preimage => ({ preimage })),
        getBalance: () => pay('balance').then(() => ({ balance: 50000, currency: 'sats' })),
      };
    }
  }, webln);
  return { pk, sk, calls, payments };
}
