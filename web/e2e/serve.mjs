// Static server for the built web client: node serve.mjs <dir> [port] — local work only (no headers of ADR 0022, 7).
import http from 'node:http'; import fs from 'node:fs'; import path from 'node:path';
const [dir, port] = [process.argv[2], +process.argv[3] || 8642];
const types = { '.html':'text/html', '.js':'text/javascript', '.mjs':'text/javascript', '.wasm':'application/wasm', '.json':'application/json', '.map':'application/json', '.xml':'application/xml', '.ttf':'font/ttf', '.png':'image/png', '.svg':'image/svg+xml', '.cvr':'application/octet-stream' };
http.createServer((req, res) => {
  let p = decodeURIComponent(new URL(req.url, 'http://x').pathname); if (p.endsWith('/')) p += 'index.html';
  const f = path.join(dir, p); if (!f.startsWith(dir)) { res.writeHead(403); return res.end(); }
  fs.readFile(f, (e, b) => { if (e) { res.writeHead(404); return res.end('404'); } res.writeHead(200, { 'content-type': types[path.extname(f)] || 'application/octet-stream', 'cache-control': 'no-store' }); res.end(b); });
}).listen(port, '127.0.0.1', () => console.log('serving', dir, 'on', port));
