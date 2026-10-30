// Compresses a built site once, beside each file (x.br, x.gz), for Caddy's precompressed file
// server: node pack.mjs <dir>. Only what compresses — fonts and wasm do, images do not.
import fs from 'node:fs'; import path from 'node:path'; import zlib from 'node:zlib';
const packable = /\.(wasm|js|mjs|css|html|json|xml|ttf|otf|svg|txt)$/;
let raw = 0, br = 0;
function walk(dir) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const f = path.join(dir, e.name);
    if (e.isDirectory()) { walk(f); continue; }
    if (!packable.test(f)) continue;
    const b = fs.readFileSync(f);
    if (b.length < 1024) continue;
    const z = zlib.brotliCompressSync(b, { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 11, [zlib.constants.BROTLI_PARAM_SIZE_HINT]: b.length } });
    fs.writeFileSync(f + '.br', z);
    fs.writeFileSync(f + '.gz', zlib.gzipSync(b, { level: 9 }));
    raw += b.length; br += z.length;
  }
}
walk(process.argv[2]);
console.log(`packed ${(raw / 2 ** 20).toFixed(1)} MB to ${(br / 2 ** 20).toFixed(1)} MB (Brotli)`);
