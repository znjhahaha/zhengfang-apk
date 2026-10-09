(function (config) {
  'use strict';
  const origins = new Set(config.origins);
  const allowed = value => {
    try {
      const url = new URL(String(value), location.href);
      if (url.username || url.password) return false;
      if (url.protocol === 'wss:') url.protocol = 'https:';
      return url.protocol === 'https:' && origins.has(url.origin) && !config.exclude.some(r => url.hostname === r.host && (url.pathname === r.pathPrefix || url.pathname.startsWith(r.pathPrefix.replace(/\/$/, '') + '/')));
    } catch (_) { return false; }
  };
  const check = value => { if (!allowed(value)) throw new TypeError('Network destination is outside this script subscription'); };
  function lock(object, key, value) { try { Object.defineProperty(object, key, {value, writable:false, configurable:false}); } catch (_) {} }
  if (globalThis.fetch) {
    const fetch = globalThis.fetch.bind(globalThis);
    lock(globalThis, 'fetch', (input, init) => { check(typeof input === 'string' || input instanceof URL ? input : input.url); return fetch(input, init); });
  }
  if (globalThis.XMLHttpRequest) {
    const open = XMLHttpRequest.prototype.open;
    lock(XMLHttpRequest.prototype, 'open', function(method, url, ...args) { check(url); return open.call(this, method, url, ...args); });
  }
  for (const key of ['WebSocket','EventSource']) {
    const Original = globalThis[key]; if (!Original) continue;
    const Guarded = function(url, ...args) { check(url); return Reflect.construct(Original, [url, ...args], Original); };
    Guarded.prototype = Original.prototype;
    for (const name of Object.getOwnPropertyNames(Original)) if (/^[A-Z_]+$/.test(name)) lock(Guarded, name, Original[name]);
    lock(Original.prototype, 'constructor', Guarded); lock(globalThis, key, Guarded);
  }
  if (navigator.sendBeacon) {
    const send = navigator.sendBeacon.bind(navigator), guarded = (url, data) => allowed(url) && send(url, data);
    lock(navigator, 'sendBeacon', guarded);
    const prototype = Object.getPrototypeOf(navigator);
    if (prototype && typeof prototype.sendBeacon === 'function') lock(prototype, 'sendBeacon', guarded);
  }
  const unsupported = () => { throw new TypeError('Workers are not supported in this bounded userscript session'); };
  for (const key of ['Worker','SharedWorker']) if (globalThis[key]) {
    lock(globalThis[key].prototype, 'constructor', unsupported); lock(globalThis, key, unsupported);
  }
  if (navigator.serviceWorker) {
    lock(navigator.serviceWorker, 'register', unsupported);
    const prototype = Object.getPrototypeOf(navigator.serviceWorker);
    if (prototype && typeof prototype.register === 'function') lock(prototype, 'register', unsupported);
    navigator.serviceWorker.getRegistrations?.().then(list => list.forEach(registration => registration.unregister())).catch(() => {});
  }
})(__ZF_NETWORK_CONFIG__);
