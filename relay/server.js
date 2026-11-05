// Petbot relay - zero dependencies. Run with:  node server.js   (PORT env var, default 8787)
//
// It only stores each player's pet settings for 90 seconds and hands them out to other Petbot users.
// Put it anywhere that runs Node.js (Render, Railway, Replit, a VPS, your own PC with a port forward).
// Use an https:// address if you can, then in Minecraft:  /petrelay https://your-relay.example.com

const http = require('http');

const PORT = process.env.PORT || 8787;
const TTL_MS = 90 * 1000;
const MAX_BODY = 20000;
const MAX_PETS = 50;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

const store = new Map(); // uuid -> { data, exp }
let hits = new Map();    // ip -> timestamps (very small rate limit)

setInterval(() => {
  const now = Date.now();
  for (const [k, v] of store) if (v.exp < now) store.delete(k);
  hits = new Map();
}, 30 * 1000);

function limited(ip) {
  const n = (hits.get(ip) || 0) + 1;
  hits.set(ip, n);
  return n > 200; // per 30 seconds
}

function send(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

http.createServer((req, res) => {
  const ip = req.headers['x-forwarded-for'] || req.socket.remoteAddress || '';
  if (limited(ip)) return send(res, 429, { error: 'slow down' });

  const url = new URL(req.url, 'http://x');
  const m = url.pathname.match(/^\/pets\/([0-9a-f-]{36})$/i);

  if (req.method === 'POST' && m && UUID_RE.test(m[1])) {
    let body = '';
    req.on('data', (c) => {
      body += c;
      if (body.length > MAX_BODY) req.destroy();
    });
    req.on('end', () => {
      try {
        const data = JSON.parse(body);
        if (!data || !Array.isArray(data.pets) || data.pets.length > MAX_PETS) throw new Error('bad');
        store.set(m[1].toLowerCase(), { data, exp: Date.now() + TTL_MS });
        send(res, 200, { ok: true });
      } catch (e) {
        send(res, 400, { error: 'bad data' });
      }
    });
    return;
  }

  if (req.method === 'GET' && url.pathname === '/pets') {
    const ids = (url.searchParams.get('ids') || '').split(',').slice(0, 200);
    const out = {};
    const now = Date.now();
    for (const id of ids) {
      if (!UUID_RE.test(id)) continue;
      const e = store.get(id.toLowerCase());
      if (e && e.exp > now) out[id.toLowerCase()] = e.data;
    }
    return send(res, 200, out);
  }

  if (req.method === 'GET' && url.pathname === '/') return send(res, 200, { petbot: 'relay ok' });
  send(res, 404, { error: 'not found' });
}).listen(PORT, () => console.log('Petbot relay listening on ' + PORT));
