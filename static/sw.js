self.addEventListener('install', (event) => {
    // Force the waiting service worker to become the active service worker.
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((cacheNames) => {
            return Promise.all(
                cacheNames.map((cacheName) => {
                    // Delete all caches (this wipes the old File Browser cache)
                    return caches.delete(cacheName);
                })
            );
        }).then(() => {
            // Claim clients so the active page is immediately controlled
            return self.clients.claim();
        }).then(() => {
            // Once we've done our job, unregister so we don't interfere with anything else
            self.registration.unregister();
        })
    );
});
