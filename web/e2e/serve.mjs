// Serves the built web client for local work: node serve.mjs <dir> [port] [--lan]
// --lan: on every interface, over HTTPS with the mkcert certificate in web/e2e/certs
// (cert.pem, key.pem), and the feed proxy (127.0.0.1:8643) passed through under /feed-proxy/ —
// an iPhone reaches neither the Mac's 127.0.0.1 nor plain HTTP from an HTTPS page. Local work
// only: none of the headers of ADR 0022, 7, and the proxy shares the page's origin here.
import http from 'node:http'; import https from 'node:https'; import fs from 'node:fs'; import path from 'node:path';
const args = process.argv.slice(2);
const lan = args.includes('--lan');
const [dir, port] = [args[0], +args[1] || 8642];
const types = { '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm', '.json': 'application/json',
  '.map': 'application/json', '.xml': 'application/xml', '.ttf': 'font/ttf', '.png': 'image/png', '.svg': 'image/svg+xml', '.txt': 'text/plain', '.md': 'text/plain' };

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
    res.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream', 'cache-control': 'no-store' });
    res.end(b);
  });
}

if (lan) {
  const certs = new URL('./certs/', import.meta.url);
  const server = https.createServer({ cert: fs.readFileSync(new URL('cert.pem', certs)), key: fs.readFileSync(new URL('key.pem', certs)) }, handler);
  server.listen(port, '0.0.0.0', () => console.log('serving', dir, 'over HTTPS on port', port, '(all interfaces)'));
} else {
  http.createServer(handler).listen(port, '127.0.0.1', () => console.log('serving', dir, 'on', port));
}
