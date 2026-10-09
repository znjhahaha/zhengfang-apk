(function (config) {
  'use strict';
  const url = new URL(location.href);
  const match = rules => rules.some(r => url.hostname === r.host && (url.pathname === r.pathPrefix || url.pathname.startsWith(r.pathPrefix.replace(/\/$/, '') + '/')));
  if (url.protocol !== 'https:' || !match(config.matches) || match(config.exclude) || window.__zfOriginalScriptInstalled) return;
  const bridge = window.zfUserscript;
  if (!bridge) return;
  window.__zfOriginalScriptInstalled = true;
  const documentId = Math.random().toString(36).slice(2) + Date.now().toString(36);
  const pending = new Map();
  let sequence = 0, values = {}, initialized = false, adapter = {}, lastStatus = '';
  const post = (kind, data = {}) => bridge.postMessage(JSON.stringify({kind, documentId, href: location.href, ...data}));
  const getValue = (key, fallback) => Object.prototype.hasOwnProperty.call(values, key) ? values[key] : fallback;
  const setValue = (key, value) => { values[key] = value; post('storage', {key, value}); };
  function request(options) {
    const id = 'r' + (++sequence);
    pending.set(id, options);
    let target;
    try { target = new URL(options.url, location.href); } catch (error) { pending.delete(id); options.onerror?.(error); return {abort() {}}; }
    post('request', {id, request: {url: target.href, method: String(options.method || 'GET').toUpperCase(),
      headers: options.headers || {}, body: options.data == null ? null : String(options.data), responseType: options.responseType || 'text', timeout: options.timeout || 30000}});
    return {abort() { if (pending.delete(id)) { post('abort', {id}); options.onabort?.({}); } }};
  }
  function query(selector) { try { return selector ? document.querySelector(selector) : null; } catch (_) { return null; } }
  function command(message) {
    if (message.action === 'start') {
      const button = query(adapter.startSelector);
      if (button) button.click(); else post('adapter', {available: false});
    } else if (message.action === 'settings') {
      for (const setting of adapter.settings || []) {
        if (!Object.prototype.hasOwnProperty.call(message.values || {}, setting.key)) continue;
        const value = message.values[setting.key];
        const element = query(setting.selector);
        setValue(setting.key, value);
        if (!element) continue;
        if (setting.type === 'boolean') { if (element.checked !== value) element.click(); }
        else { element.value = String(value); element.dispatchEvent(new Event('input', {bubbles: true})); element.dispatchEvent(new Event('change', {bubbles: true})); }
      }
      query(adapter.saveSelector)?.click();
    }
  }
  function status() {
    const start = query(adapter.startSelector), running = query(adapter.runningSelector);
    const logs = query(adapter.logSelector)?.innerText?.slice(-12000) || '';
    const selected = {};
    for (const setting of adapter.settings || []) {
      const element = query(setting.selector);
      if (element) selected[setting.key] = setting.type === 'boolean' ? !!element.checked : Number(element.value);
      else if (Object.prototype.hasOwnProperty.call(values, setting.key)) selected[setting.key] = values[setting.key];
    }
    const next = JSON.stringify({available: !!(start || running), running: !!running, logs, settings: selected});
    if (next !== lastStatus) { lastStatus = next; post('status', JSON.parse(next)); }
  }
  bridge.onmessage = event => {
    let message; try { message = JSON.parse(event.data); } catch (_) { return; }
    if (message.documentId !== documentId) return;
    if (message.kind === 'init' && !initialized) {
      initialized = true; values = message.values || {}; adapter = message.adapter || {};
      const run = () => {
        try {
          const addStyle = css => { const style = document.createElement('style'); style.textContent = String(css); (document.head || document.documentElement).appendChild(style); return style; };
          const open = (target, options) => { post('open', {url: new URL(target, location.href).href, active: typeof options === 'boolean' ? !options : options?.active !== false}); return {closed: false, close() {}}; };
          // The downloaded original is unmodified; only standard userscript parameters are supplied.
          const execute = new Function('unsafeWindow', 'GM_info', 'GM_getValue', 'GM_setValue', 'GM_addStyle', 'GM_xmlhttpRequest', 'GM_openInTab', message.source);
          execute.call(window, window, {script: message.info, scriptHandler: 'Zhengfang', version: '1.0', isIncognito: false}, getValue, setValue, addStyle, request, open);
          post('installed'); status(); setInterval(status, 1500);
        } catch (_) { post('error', {message: '原脚本未能启动，请打开原页面查看或回退版本'}); }
      };
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run, {once: true}); else run();
    } else if (message.kind === 'response') {
      const options = pending.get(message.id); if (!options) return; pending.delete(message.id);
      if (!message.ok) { (message.error === 'TIMEOUT' ? options.ontimeout : options.onerror)?.({error: message.error, status: 0}); return; }
      const response = message.response;
      const result = {status: response.status, statusText: '', finalUrl: response.url, responseText: response.body,
        responseHeaders: Object.entries(response.headers || {}).map(([k, v]) => k + ': ' + v).join('\r\n'), readyState: 4};
      try {
        result.response = options.responseType === 'json' ? JSON.parse(response.body) :
          options.responseType === 'document' ? new DOMParser().parseFromString(response.body, 'text/html') :
          options.responseType === 'arraybuffer' ? Uint8Array.from(atob(response.body), c => c.charCodeAt(0)).buffer : response.body;
        options.onreadystatechange?.(result); options.onload?.(result);
      } catch (_) { options.onerror?.({error: 'PARSE_ERROR', status: response.status}); }
    } else if (message.kind === 'values') { values = message.values; }
    else if (message.kind === 'command') { command(message); status(); }
  };
  window.addEventListener('pagehide', () => post('closed'), {once: true});
  post('hello');
})(__ZF_CONFIG__);
