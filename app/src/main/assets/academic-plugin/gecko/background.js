'use strict';
// This trusted extension contains no upstream script and exposes no web-accessible resources.
let configuration = null, current = null, registration = null;
const documents = new Map();
let serial = Promise.resolve();
const exclusive = operation => { const result = serial.then(operation); serial = result.catch(() => {}); return result; };
const port = browser.runtime.connectNative('zfBrowser');
const origin = value => { try { return new URL(value).origin; } catch (_) { return ''; } };
const route = (value, rules) => { try { const u = new URL(value); return u.protocol === 'https:' && !u.username && !u.password && !/%2f|%5c|%00/i.test(u.pathname) && rules.some(r => u.hostname === r.host && (u.pathname === r.pathPrefix || u.pathname.startsWith(r.pathPrefix.replace(/\/$/, '') + '/'))); } catch (_) { return false; } };
function resource(value, method = 'GET') {
  try {
    const u = new URL(value);
    if (!configuration || u.protocol !== 'https:' || u.username || u.password || /%2f|%5c|%00/i.test(u.pathname)) return false;
    if (current && route(value, current.declaration.exclude || [])) return false;
    return configuration.network.some(r => r.origin === u.origin && (r.methods.includes(method) || method === 'OPTIONS') &&
      (r.pathPrefix === '/' || u.pathname === r.pathPrefix || u.pathname.startsWith(r.pathPrefix.replace(/\/$/, '') + '/')));
  } catch (_) { return false; }
}
browser.webRequest.onBeforeRequest.addListener(details => {
  if (/^(moz-extension|resource|about|data|blob):/.test(details.url)) return {};
  return {cancel: !resource(details.url, details.method)};
}, {urls: ['<all_urls>']}, ['blocking']);
const failure = (code, message) => Object.assign(new Error(message), {code});
async function notify(tabId, frameId, message) {
  let timer;
  try {
    return await Promise.race([
      browser.tabs.sendMessage(tabId, message, {frameId}),
      new Promise((_, reject) => { timer = setTimeout(() => reject(failure('TIMEOUT', '原脚本页面未响应，请在 App 内处理提示核对')), 8000); })
    ]);
  } finally { clearTimeout(timer); }
}
const patterns = rules => rules.flatMap(r => r.pathPrefix === '/' ? ['https://' + r.host + '/*'] : ['https://' + r.host + r.pathPrefix, 'https://' + r.host + r.pathPrefix.replace(/\/$/, '') + '/*']);
async function register(input) {
  // Never have two registrations live: both can run in a newly navigating iframe.
  const old = registration; registration = null;
  if (old) await old.unregister();
  const excludeMatches = patterns(input.declaration.exclude || []);
  registration = await browser.userScripts.register({
    matches: patterns(input.declaration.matches),
    ...(excludeMatches.length ? {excludeMatches} : {}),
    ...(input.includeGlobs?.length ? {includeGlobs: input.includeGlobs} : {}),
    js: [{file: 'environment.js'}, {code: input.source}], allFrames: true,
    runAt: input.runAt === 'document-idle' ? 'document_idle' : 'document_end',
    scriptMetadata: {handle: input.handle, values: {...input.values}, info: input.info, declaration: input.declaration}
  });
}
async function command(action, input) {
  if (action === 'configure') { configuration = input; return {}; }
  if (!configuration) throw Error('Browser is not configured');
  if (action === 'cookies.read') {
    if (!configuration.origins.includes(origin(input.url))) throw Error('Undeclared cookie origin');
    const cookies = await browser.cookies.getAll({url: input.url});
    cookies.sort((a, b) => b.path.length - a.path.length);
    return {header: cookies.map(c => c.name + '=' + c.value).join('; ')};
  }
  if (action === 'cookies.write') {
    if (!configuration.origins.includes(origin(input.cookie.url))) throw Error('Undeclared cookie origin');
    const written = await browser.cookies.set(input.cookie);
    if (!written) throw Error('Cookie was not stored');
    return {written: true};
  }
  if (action === 'script.stop') {
    if (registration) await registration.unregister();
    registration = null; current = null; documents.clear();
    return {};
  }
  if (action === 'script.start') {
    await command('script.stop', {});
    await register(input);
    current = {...input, values:{...input.values}};
    return {registered: true};
  }
  if (action === 'script.settings') {
    if (!current || current.handle !== input.handle) throw Error('Script handle is stale');
    const previous = {...current.values};
    current.values = {...input.values};
    try { await register(current); }
    catch (error) {
      current.values = previous;
      await register(current).catch(() => {});
      throw error;
    }
    // A navigating frame must not hold up other settings, or a later stop.
    await Promise.allSettled([...documents.values()].map(d => notify(d.tabId, d.frameId, {kind:'values', document:d.document, handle:current.handle, values:current.values, apply:input.apply || {}})));
    return {};
  }
  if (action === 'script.control') {
    if (!current || current.handle !== input.handle) throw Error('Script handle is stale');
    if (input.expiresAt && input.expiresAt <= Date.now()) throw failure('TIMEOUT', '启动请求已过期，未再次点击原脚本按钮');
    const rank = d => ({running:0, ready:1, entry:2}[d.stage] ?? 3);
    const target = [...documents.values()].filter(d => d.available).sort((a,b) => rank(a) - rank(b) || a.frameId - b.frameId)[0];
    if (!target) return {accepted:false, available:false, message:'任务仍在准备，请在 App 内完成密钥与提示操作'};
    let reply;
    try {
      reply = await notify(target.tabId, target.frameId, {kind:'command', document:target.document, handle:current.handle, action:input.action, id:input.id, expiresAt:input.expiresAt});
    } catch (error) {
      if (error.code === 'TIMEOUT') throw error;
      throw failure('PAGE_CHANGED', '原脚本页面已跳转，尚未收到启动确认，请在 App 内处理提示核对');
    }
    if (!reply || typeof reply.accepted !== 'boolean') throw failure('PAGE_CHANGED', '原脚本没有返回启动确认，请在 App 内处理提示核对');
    return {...reply, documentId:target.key};
  }
  if (action === 'script.interact' || action === 'script.action') {
    if (!current || current.handle !== input.handle) throw Error('Script handle is stale');
    if (input.expiresAt && input.expiresAt <= Date.now()) throw failure('TIMEOUT', '操作已过期，请重新确认当前提示');
    const isPrompt = action === 'script.interact';
    const target = [...documents.values()].filter(d => isPrompt ? d.interaction?.id === input.interactionId : d.actions?.some(a=>a.id===input.actionId)).sort((a,b)=>a.frameId-b.frameId)[0];
    if (!target) throw failure('PAGE_CHANGED', '当前操作已失效，请刷新任务状态');
    const reply = await notify(target.tabId,target.frameId,{kind:isPrompt?'interact':'action',document:target.document,handle:current.handle,interactionId:input.interactionId,actionId:input.actionId,id:input.id,expiresAt:input.expiresAt});
    if (!reply?.accepted) throw failure('PAGE_CHANGED', '提示或任务状态已变化，未重复执行');
    return {...reply,documentId:target.key};
  }
  throw Error('Unsupported browser command');
}
port.onMessage.addListener(async message => {
  try {
    // GM requests return through the host's cookie jar. They must not wait behind
    // a page control/settings operation which can itself trigger such a request.
    const run = () => command(message.action, {...message.input, id:message.id});
    const data = await (message.action.startsWith('cookies.') ? run() : exclusive(run));
    port.postMessage({replyTo:message.id, ok:true, data});
  } catch (error) {
    const known = ['TIMEOUT','PAGE_CHANGED'].includes(error.code);
    port.postMessage({replyTo:message.id, ok:false, code:known ? error.code : 'UNSUPPORTED', message:known ? error.message : '任务引擎操作未完成，请停止后重试'});
  }
});
async function fromDocument(message, sender) {
  if (!current || !sender.tab || message.handle !== current.handle || !route(sender.url, current.declaration.matches) || route(sender.url, current.declaration.exclude || []))
    throw Error('Inactive or undeclared script document');
  if (!['hello','storage','request','abort','open','close','status','error','closed'].includes(message.kind) || !/^[a-z0-9-]{1,80}$/.test(message.document || '')) throw Error('Unsupported script message');
  const frame = sender.tab.id + ':' + sender.frameId, key = frame + ':' + message.document;
  if (message.kind === 'hello') {
    const closed = [];
    for (const [oldKey, old] of documents) if (old.frame === frame && oldKey !== key) {
      documents.delete(oldKey);
      closed.push(browser.runtime.sendNativeMessage('zfBrowser', {kind:'closed', handle:current.handle, href:sender.url, documentId:oldKey}));
    }
    documents.set(key, {key, frame, document:message.document, tabId:sender.tab.id, frameId:sender.frameId, available:false});
    await Promise.all(closed);
  } else if (!documents.has(key)) throw Error('Script document has navigated');
  if (message.kind === 'closed') documents.delete(key);
  else if (message.kind === 'status') Object.assign(documents.get(key), {available:!!message.available, stage:message.stage,interaction:message.interaction,actions:message.actions});
  const handle = current.handle;
  const result = await browser.runtime.sendNativeMessage('zfBrowser', {...message, href:sender.url, documentId:key});
  if (!current || current.handle !== handle) throw Error('Script has stopped');
  if (message.kind === 'hello') return {values:current.values};
  if (message.kind === 'storage') {
    if (result?.ok === false) throw Error('Setting was not persisted');
    current.values[message.key] = message.value;
    // Also refresh registration metadata so navigation never restores an older setting.
    await command('script.settings', {handle:current.handle, values:current.values});
  }
  return result;
}
browser.runtime.onMessage.addListener((message, sender) => message.kind === 'storage'
  ? exclusive(() => fromDocument(message, sender)) : fromDocument(message, sender));
