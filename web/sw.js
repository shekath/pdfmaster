// Offline support: app shell is precached; libraries, fonts and models are cached on first use.
const VERSION = 'pdfmaster-v2';
const SHELL = ['./', 'index.html', 'css/app.css', 'manifest.webmanifest', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png',
  'js/app.js', 'js/tools.js', 'js/ui.js', 'js/lib.js', 'js/pdfops.js', 'js/render.js', 'js/office.js', 'js/ocr.js', 'js/smart.js', 'js/runner.js', 'js/editor.js', 'js/organize.js'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== VERSION && k !== 'pdfmaster-runtime').map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  const sameOrigin = url.origin === self.location.origin;
  const isLangData = url.hostname === 'cdn.jsdelivr.net' && url.pathname.includes('tesseract');
  if (!sameOrigin && !isLangData) return;
  const isShell = sameOrigin && (req.mode === 'navigate' || SHELL.some((s) => url.pathname.endsWith(s.replace('./', '/'))));
  if (isShell) {
    // network first so updates arrive; fall back to cache offline
    e.respondWith(fetch(req).then((r) => { const copy = r.clone(); caches.open(VERSION).then((c) => c.put(req, copy)); return r; })
      .catch(() => caches.match(req).then((r) => r || caches.match('index.html'))));
    return;
  }
  // cache first for heavy, versioned assets (vendor libraries, fonts, OCR data)
  e.respondWith(caches.open('pdfmaster-runtime').then((c) => c.match(req).then((hit) => hit || fetch(req).then((r) => { if (r.ok) c.put(req, r.clone()); return r; }))));
});
