/* Helper by Entarsh : service worker de l'application (le réseau d'abord, la dernière version en secours hors ligne) */
const CACHE = "helper-app-v1";
self.addEventListener("install", e => { self.skipWaiting(); e.waitUntil(caches.open(CACHE).then(c => c.addAll(["./", "./icone-192.png", "./site.webmanifest"])).catch(() => {})); });
self.addEventListener("activate", e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener("fetch", e => {
  const r = e.request; if (r.method !== "GET") return;
  const u = new URL(r.url); if (u.origin !== location.origin) return;   // Supabase, polices… : pas touché
  e.respondWith(fetch(r).then(rep => { if (rep.ok && (r.mode === "navigate" || /\.(png|webmanifest)$/.test(u.pathname))) { const copie = rep.clone(); caches.open(CACHE).then(c => c.put(r.mode === "navigate" ? "./" : r, copie)); } return rep; })
    .catch(() => caches.match(r.mode === "navigate" ? "./" : r).then(x => x || Response.error())));
});
