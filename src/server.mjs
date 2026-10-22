import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PmdpClient } from './pmdpClient.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.join(__dirname, '..', 'public');
const PORT = Number(process.env.PORT || 3333);

const pmdp = new PmdpClient();

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.mjs': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

function sendJson(res, statusCode, data) {
  const body = JSON.stringify(data);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, OPTIONS',
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type',
      });
      res.end();
      return;
    }

    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

    // 1. Endpoint: /api/stops (hledání podle GPS nebo názvu)
    if (url.pathname === '/api/stops' && req.method === 'GET') {
      const latRaw = url.searchParams.get('lat');
      const lonRaw = url.searchParams.get('lon');
      const q = url.searchParams.get('q');
      const limit = Math.min(350, Math.max(1, Number(url.searchParams.get('limit') || 12)));

      if (latRaw != null && lonRaw != null && latRaw !== '' && lonRaw !== '') {
        const lat = Number(latRaw);
        const lon = Number(lonRaw);
        if (Number.isFinite(lat) && Number.isFinite(lon)) {
          const stops = await pmdp.findNearestStops(lat, lon, limit);
          sendJson(res, 200, { stops });
          return;
        }
      }

      const stops = await pmdp.searchStopsByName(q || '', limit);
      sendJson(res, 200, { stops });
      return;
    }

    // 2. Endpoint: /api/departures (živé odjezdy z nejbližší, zvolené nebo okolního uzlu zastávek)
    if (url.pathname === '/api/departures' && req.method === 'GET') {
      const cis = url.searchParams.get('cis');
      const stopId = url.searchParams.get('stopId');
      const latRaw = url.searchParams.get('lat');
      const lonRaw = url.searchParams.get('lon');
      const prevLatRaw = url.searchParams.get('prevLat');
      const prevLonRaw = url.searchParams.get('prevLon');
      const multiStop = url.searchParams.get('multi') === '1' || url.searchParams.get('multi') === 'true';
      const radiusMeters = Math.min(500, Number(url.searchParams.get('radius') || 240));
      const limit = Math.min(40, Number(url.searchParams.get('limit') || 16));

      const lat = latRaw != null && latRaw !== '' ? Number(latRaw) : null;
      const lon = lonRaw != null && lonRaw !== '' ? Number(lonRaw) : null;
      const prevLat = prevLatRaw != null && prevLatRaw !== '' ? Number(prevLatRaw) : null;
      const prevLon = prevLonRaw != null && prevLonRaw !== '' ? Number(prevLonRaw) : null;

      if (!cis && !stopId && (lat == null || lon == null || !Number.isFinite(lat) || !Number.isFinite(lon))) {
        sendJson(res, 400, {
          error: 'Zadejte prosím platné GPS souřadnice (lat, lon) nebo číslo zastávky (cis).',
        });
        return;
      }

      const result = await pmdp.getLiveDepartures({
        cisNumber: cis ? Number(cis) : null,
        stopId: stopId ? Number(stopId) : null,
        lat: Number.isFinite(lat) ? lat : null,
        lon: Number.isFinite(lon) ? lon : null,
        prevLat: Number.isFinite(prevLat) ? prevLat : null,
        prevLon: Number.isFinite(prevLon) ? prevLon : null,
        multiStop,
        radiusMeters,
        maxResults: limit,
      });

      sendJson(res, 200, result);
      return;
    }

    // 3. Statické soubory z /public (se streamováním pro velké soubory jako APK)
    let reqPath = url.pathname === '/' ? '/index.html' : url.pathname;
    const safePath = path.normalize(reqPath).replace(/^(\.\.[/\\])+/, '');
    const filePath = path.join(PUBLIC_DIR, safePath);

    if (!filePath.startsWith(PUBLIC_DIR) || !fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
      sendJson(res, 404, { error: 'Nenalezeno' });
      return;
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';
    const stat = fs.statSync(filePath);

    res.writeHead(200, {
      'Content-Type': contentType,
      'Content-Length': stat.size,
      'Cache-Control': ext === '.apk' ? 'no-cache' : 'public, max-age=3600',
    });
    fs.createReadStream(filePath).pipe(res);
  } catch (err) {
    console.error('Server error:', err);
    sendJson(res, 500, {
      error: err.message || 'Interní chyba serveru při komunikaci s PMDP.',
    });
  }
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`🚀 Stíhám to? (PMDP Live Server) běží na http://localhost:${PORT} (v síti: http://0.0.0.0:${PORT})`);
});
