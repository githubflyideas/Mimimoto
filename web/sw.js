// Offline shell.
//
// The point is not offline browsing — it is that the app opens instantly and
// keeps working on a train, in a basement, on a prepaid SIM that has run out.
// Those are the conditions the target families actually record in.
//
// Cache-first for the shell, and a new deploy takes over on the next launch:
// the version string below is the only thing that has to change for that.

const VERSION = 'mimimoto-v1';
const SHELL = [
  './',
  './index.html',
  './audioqc.js',
  './recorder.js',
  './capture-worklet.js',
  './manifest.webmanifest',
  './icon-180.png',
  './icon-192.png',
  './icon-512.png',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(VERSION)
      // A single failing entry would reject the whole addAll and leave the app
      // uncached, so each one is allowed to fail on its own.
      .then((cache) => Promise.allSettled(SHELL.map((url) => cache.add(url))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  event.respondWith(
    caches.match(request).then((hit) => {
      if (hit) return hit;
      return fetch(request)
        .then((response) => {
          // Only same-origin, fully successful responses are worth keeping; an
          // opaque or partial response cached here would be served forever.
          if (response && response.ok && response.type === 'basic') {
            const copy = response.clone();
            caches.open(VERSION).then((cache) => cache.put(request, copy)).catch(() => {});
          }
          return response;
        })
        .catch(() => caches.match('./index.html'));
    })
  );
});
