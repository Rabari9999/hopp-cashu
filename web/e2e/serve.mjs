// Serves the built web client for local work: node serve.mjs <dir> [port] [--lan] [--strict]
// --strict: with the headers of ADR 0022, 7 as the server sends them — read from
// web/deploy/Caddyfile, the local proxy added to connect-src — so a check fails where the CSP would.
// --lan: on every interface, over HTTPS with the mkcert certificate in web/e2e/certs
// (cert.pem, key.pem), and the feed proxy (127.0.0.1:8643) passed through under /feed-proxy/ —
// an iPhone reaches neither the Mac's 127.0.0.1 nor plain HTTP from an HTTPS page. Local work
// only: none of the headers of ADR 0022, 7, and the proxy shares the page's origin here.
// Compressed and cached as a real server would (maintainer 2026-10-29: 20 MB sent uncompressed
// at every start made the iPhone's measurement one of the Wi-Fi): Brotli or gzip, the hashed
// files kept for good, the others asked again with their ETag.
import http from 'node:http'; import https from 'node:https'; import fs from 'node:fs'; import path from 'node:path'; import zlib from 'node:zlib';
const args = process.argv.slice(2);
const lan = args.includes('--lan');
const [dir, port] = [args[0], +args[1] || 8642];
const strict = args.includes('--strict') ? serverHeaders() : {};

function serverHeaders() {
  const caddy = fs.readFileSync(new URL('../deploy/Caddyfile', import.meta.url), 'utf8');
  const block = caddy.slice(caddy.indexOf('(hopp_web_headers)'), caddy.indexOf('\nplay.'));
  const h = {};
  for (const [, k, v] of block.matchAll(/^\s+([A-Z][A-Za-z-]+) "(.*)"$/gm)) if (k !== 'Strict-Transport-Security') h[k.toLowerCase()] = v;
  h['content-security-policy'] = h['content-security-policy'].replace("connect-src 'self'", "connect-src 'self' http://127.0.0.1:8643");
  return h;
}
const types = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json', '.webmanifest': 'application/manifest+json',
  '.map': 'application/json', '.xml': 'application/xml', '.ttf': 'font/ttf', '.png': 'image/png', '.svg': 'image/svg+xml', '.txt': 'text/plain', '.md': 'text/plain' };

const packed = new Map(); // path → { tag, br, gz }, made once per build
const hashed = /\/[0-9a-f]{20}\.(wasm|js)$/;

function send(req, res, f, b) {
  const st = fs.statSync(f);
  const tag = `"${st.size.toString(16)}-${st.mtimeMs.toString(16)}-${b.length.toString(16)}"`;
  const headers = { ...strict, 'content-type': types[path.extname(f)] || 'application/octet-stream', etag: tag, vary: 'accept-encoding',
    'cache-control': hashed.test(f) ? 'public, max-age=31536000, immutable' : 'no-cache' };
  if (req.headers['if-none-match'] === tag) { res.writeHead(304, headers); return res.end(); }
  const accept = String(req.headers['accept-encoding'] || '');
  if (b.length > 1024 && /\.(wasm|js|css|html|json|xml|ttf|svg|txt|map)$/.test(f)) {
    let p = packed.get(f);
    if (!p || p.tag !== tag) packed.set(f, p = { tag });
    if (/\bbr\b/.test(accept)) {
      p.br ??= zlib.brotliCompressSync(b, { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 9 } });
      res.writeHead(200, { ...headers, 'content-encoding': 'br' }); return res.end(p.br);
    }
    if (/\bgzip\b/.test(accept)) {
      p.gz ??= zlib.gzipSync(b, { level: 9 });
      res.writeHead(200, { ...headers, 'content-encoding': 'gzip' }); return res.end(p.gz);
    }
  }
  res.writeHead(200, headers); res.end(b);
}

function handler(req, res) {
  const p0 = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  if (lan && p0.startsWith('/feed-proxy/')) {
    const up = http.request({ host: '127.0.0.1', port: 8643, path: req.url.replace('/feed-proxy', ''), method: req.method,
      headers: { accept: req.headers.accept || '*/*', 'if-none-match': req.headers['if-none-match'] || '' } }, r => { res.writeHead(r.statusCode, r.headers); r.pipe(res); });
    up.on('error', () => { res.writeHead(502); res.end(); });
    return up.end();
  }
  let p = p0.endsWith('/') ? p0 + 'index.html' : p0;
  const f = path.join(dir, p);
  if (!f.startsWith(dir)) { res.writeHead(403); return res.end(); }
  fs.readFile(f, (e, b) => {
    if (e) { res.writeHead(404); return res.end('404'); }
    // On the LAN the proxy is this origin's /feed-proxy/.
    if (lan && p.endsWith('index.html')) b = Buffer.from(String(b).replace(/(name="hopp-feed-proxy" content=")[^"]*"/, '$1feed-proxy/"'));
    send(req, res, f, b);
  });
}

if (lan) {
  const certs = new URL('./certs/', import.meta.url);
  const server = https.createServer({ cert: fs.readFileSync(new URL('cert.pem', certs)), key: fs.readFileSync(new URL('key.pem', certs)) }, handler);
  server.listen(port, '0.0.0.0', () => console.log('serving', dir, 'over HTTPS on port', port, '(all interfaces)'));
} else {
  http.createServer(handler).listen(port, '127.0.0.1', () => console.log('serving', dir, 'on', port));
}
