// SoundWave PWA Service Worker - Offline Cache & Streamed Audio Persistence
const STATIC_CACHE_NAME = 'soundwave-static-v2';
const AUDIO_CACHE_NAME = 'soundwave-audio-v1';
const MAX_AUDIO_ITEMS = 30; // Limited offline cache for recently played tracks

const STATIC_ASSETS = [
  './',
  './index.html',
  './manifest.json',
  './icon-192.png',
  './icon-512.png'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(STATIC_CACHE_NAME).then((cache) => {
      return cache.addAll(STATIC_ASSETS);
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys
          .filter((key) => key !== STATIC_CACHE_NAME && key !== AUDIO_CACHE_NAME)
          .map((key) => caches.delete(key))
      );
    }).then(() => self.clients.claim())
  );
});

// Helper: Trim audio cache to limit stored tracks (LRU)
async function trimAudioCache(maxItems) {
  try {
    const cache = await caches.open(AUDIO_CACHE_NAME);
    const keys = await cache.keys();
    if (keys.length > maxItems) {
      const toDelete = keys.slice(0, keys.length - maxItems);
      await Promise.all(toDelete.map((req) => cache.delete(req)));
    }
  } catch (e) {
    console.warn('Error trimming audio cache:', e);
  }
}

// Helper: Check if request is an audio stream
function isAudioStreamRequest(request, url) {
  if (request.method !== 'GET') return false;
  if (request.destination === 'audio') return true;
  const path = url.pathname.toLowerCase();
  if (/\.(mp3|m4a|wav|flac|ogg|aac|opus)$/i.test(path)) return true;
  if (
    (url.hostname.includes('archive.org') && path.includes('/download/')) ||
    (url.hostname.includes('audius.co') && path.includes('/stream')) ||
    url.hostname.includes('hearthis.at') ||
    url.hostname.includes('incompetech.com') ||
    url.hostname.includes('audio-ssl.itunes.apple.com') ||
    (url.hostname.includes('apple.com') && path.includes('AudioPreview'))
  ) {
    return true;
  }
  return false;
}

// Helper: Serve cached audio response with HTTP 206 Partial Content support for <audio> Range requests
async function handleAudioRangeRequest(request, cachedResponse) {
  const rangeHeader = request.headers.get('Range');
  if (!rangeHeader) {
    return cachedResponse;
  }

  const arrayBuffer = await cachedResponse.arrayBuffer();
  const totalLength = arrayBuffer.byteLength;
  const rangeMatch = rangeHeader.match(/bytes=(\d+)-(\d+)?/);

  if (!rangeMatch) {
    return cachedResponse;
  }

  const start = parseInt(rangeMatch[1], 10);
  const end = rangeMatch[2] ? parseInt(rangeMatch[2], 10) : totalLength - 1;

  if (start >= totalLength || end >= totalLength) {
    return new Response('', {
      status: 416,
      statusText: 'Range Not Satisfiable',
      headers: { 'Content-Range': `bytes */${totalLength}` }
    });
  }

  const slicedBuffer = arrayBuffer.slice(start, end + 1);
  const contentType = cachedResponse.headers.get('Content-Type') || 'audio/mpeg';

  return new Response(slicedBuffer, {
    status: 206,
    statusText: 'Partial Content',
    headers: {
      'Content-Type': contentType,
      'Content-Range': `bytes ${start}-${end}/${totalLength}`,
      'Content-Length': String(slicedBuffer.byteLength),
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'public, max-age=86400'
    }
  });
}

// Audio Fetch Handler: Cache streamed audio automatically and serve offline when played once
async function handleAudioFetch(request) {
  const url = new URL(request.url);
  const cleanUrl = url.origin + url.pathname + url.search;
  const audioCache = await caches.open(AUDIO_CACHE_NAME);

  // If explicitly offline, immediately serve from cache
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    const cachedOffline = await audioCache.match(cleanUrl) || await audioCache.match(request);
    if (cachedOffline) {
      console.log('Serving offline cached audio track:', cleanUrl);
      return handleAudioRangeRequest(request, cachedOffline.clone());
    }
    return new Response('Audio not available offline. Play it once online to cache.', {
      status: 503,
      statusText: 'Service Unavailable',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' }
    });
  }

  // Try network first to get stream and cache it for offline listening
  try {
    const networkResponse = await fetch(request);

    // If full stream response (200), automatically save copy to audio cache
    if (networkResponse && networkResponse.status === 200) {
      const responseToCache = networkResponse.clone();
      audioCache.put(cleanUrl, responseToCache).then(() => {
        trimAudioCache(MAX_AUDIO_ITEMS);
      }).catch((e) => console.warn('Could not cache audio:', e));
    } else if (networkResponse && networkResponse.status === 206) {
      // Audio server answered range request with HTTP 206. Fetch full stream in background for offline cache
      audioCache.match(cleanUrl).then((alreadyCached) => {
        if (!alreadyCached) {
          fetch(cleanUrl, { headers: { 'Accept': 'audio/*' } })
            .then((fullResp) => {
              if (fullResp && fullResp.status === 200) {
                return audioCache.put(cleanUrl, fullResp).then(() => trimAudioCache(MAX_AUDIO_ITEMS));
              }
            })
            .catch(() => {});
        }
      });
    }

    return networkResponse;
  } catch (networkErr) {
    // Network failed (device went offline or connectivity drop):
    // Check if we have this streamed track cached from a previous listen
    const cachedResponse = await audioCache.match(cleanUrl) || await audioCache.match(request);
    if (cachedResponse) {
      console.log('Serving offline cached audio track after network error:', cleanUrl);
      return handleAudioRangeRequest(request, cachedResponse.clone());
    }

    return new Response('Audio not available offline. Play it once online to cache.', {
      status: 503,
      statusText: 'Service Unavailable',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' }
    });
  }
}

self.addEventListener('fetch', (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // 1. YouTube embed frames and magnet links should bypass Service Worker
  if (
    url.hostname.includes('youtube.com') ||
    url.hostname.includes('ytimg.com') ||
    url.pathname.includes('/yt_embed') ||
    request.url.startsWith('magnet:')
  ) {
    return;
  }

  // 2. Streamed Audio Requests -> Dedicated Audio Caching & Offline Playback
  if (isAudioStreamRequest(request, url)) {
    event.respondWith(handleAudioFetch(request));
    return;
  }

  // 3. Static Assets & App Shell -> Cache First with Network Fallback
  event.respondWith(
    caches.match(request).then((cachedResponse) => {
      if (cachedResponse) {
        return cachedResponse;
      }
      return fetch(request).then((networkResponse) => {
        if (!networkResponse || networkResponse.status !== 200 || networkResponse.type !== 'basic') {
          return networkResponse;
        }
        const responseToCache = networkResponse.clone();
        caches.open(STATIC_CACHE_NAME).then((cache) => {
          cache.put(request, responseToCache);
        });
        return networkResponse;
      }).catch(() => {
        if (request.mode === 'navigate') {
          return caches.match('./index.html');
        }
      });
    })
  );
});
