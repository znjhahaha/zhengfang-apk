'use strict';
browser.userScripts.onBeforeScript.addListener(script => {
  const metadata = script.metadata;
  if (!metadata?.handle) return;
  const {handle, declaration} = metadata;
  let values = {...metadata.values}, lastStatus = '', sequence = 0, opening = false;
  const controls = new Map();
  let interaction = null, interactionSequence = 0;
  const documentId = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
  const requests = new Set();
  const requestErrors = new Map();
  const writes = new Map(), tabs = new Set();
  const send = (kind, data = {}) => browser.runtime.sendMessage({handle, document:documentId, kind, ...data});
  const read = (key, fallback) => script.export(Object.prototype.hasOwnProperty.call(values, key) ? values[key] : fallback);
  const merge = incoming => { values = {...incoming}; for (const [key, pending] of writes) values[key] = pending.value; };
  const write = (key, value) => {
    key = String(key); const pending = {value}; writes.set(key, pending); values[key] = value;
    send('storage', {key, value}).then(() => { if (writes.get(key) === pending) writes.delete(key); })
      .catch(() => { if (writes.get(key) === pending) writes.delete(key); send('error', {reason:'storage'}).catch(() => {}); });
  };
  const query = selector => { try { return selector ? document.querySelector(selector) : null; } catch (_) { return null; } };
  const visible = selector => {
    try {
      return selector ? [...document.querySelectorAll(selector)].find(node =>
        (!node.getClientRects || node.getClientRects().length > 0) && getComputedStyle(node).visibility !== 'hidden') || null : null;
    } catch (_) { return null; }
  };
  const controlNodes = new Map();
  const settledControl = selector => {
    const node = visible(selector);
    if (!node || node.disabled || node.getAttribute?.('aria-disabled') === 'true') {
      controlNodes.delete(selector); return null;
    }
    const previous = controlNodes.get(selector);
    if (previous?.node !== node) {
      controlNodes.set(selector, {node, since:Date.now()}); return null;
    }
    // Upstream inserts controls before attaching its delayed click handlers.
    // Require the same enabled control to survive initialization, including on
    // the next task page; an early click cannot safely be retried afterwards.
    return Date.now() - previous.since >= 1000 ? node : null;
  };
  const checked = (node, setting) => setting.enabledClass ? node.classList.contains(setting.enabledClass) : !!node.checked;
  function currentInteraction() {
    for (const rule of declaration.adapter.dialogs || []) {
      const node = visible(rule.selector); if (!node) continue;
      const title = (rule.titleSelector ? node.querySelector(rule.titleSelector)?.textContent : '')?.trim().slice(0,160) || rule.title;
      const message = (rule.messageSelector ? node.querySelector(rule.messageSelector)?.textContent : node.textContent)?.trim().slice(0,6000) || '';
      const actions = rule.actions.filter(action => {
        const button = node.querySelector(action.selector);
        return button && !button.disabled && button.getClientRects().length > 0;
      });
      const signature = JSON.stringify([rule.id,title,message,actions.map(a=>a.id)]);
      if (!interaction || interaction.node !== node || interaction.signature !== signature) {
        interaction = {node, rule, signature, consumed:false, id:documentId+':p'+(++interactionSequence)};
      }
      return {id:interaction.id,ruleId:rule.id,kind:rule.kind,title,message,actions:actions.map(({id,label})=>({id,label}))};
    }
    interaction = null;
    return null;
  }
  function apply(changes) {
    let save = false;
    for (const setting of declaration.adapter.settings || []) {
      if (!Object.prototype.hasOwnProperty.call(changes, setting.key)) continue;
      const node = query(setting.selector); if (!node) continue;
      if (setting.type === 'boolean') { if (checked(node, setting) !== (changes[setting.key] === true || changes[setting.key] === 1)) node.click(); }
      else if (Number(node.value) !== Number(changes[setting.key])) { node.value = String(changes[setting.key]); node.dispatchEvent(new Event('input', {bubbles:true})); node.dispatchEvent(new Event('change', {bubbles:true})); save = true; }
    }
    if (save) query(declaration.adapter.saveSelector)?.click();
  }
  function status() {
    const adapter = declaration.adapter;
    const start = settledControl(adapter.startSelector), running = query(adapter.runningSelector);
    const entry = settledControl(adapter.entrySelector), prompt = currentInteraction(), needsInput = prompt || visible(adapter.promptSelector);
    const settings = {};
    for (const s of adapter.settings) {
      const node = query(s.selector);
      if (node) settings[s.key] = s.type === 'boolean' ? checked(node, s) : Number(node.value);
      else if (Object.prototype.hasOwnProperty.call(values, s.key)) settings[s.key] = values[s.key];
    }
    const stage = needsInput ? 'interaction' : requestErrors.size ? 'error' : running ? 'running' : start ? 'ready' : opening ? 'opening' : entry ? 'entry' : 'loading';
    const actions = needsInput ? [] : (adapter.actions || []).filter(action=>visible(action.selector)).map(({id,label})=>({id,label}));
    const diagnostics = [...new Set(requestErrors.values())].map(code=>'任务请求未完成（'+code+'），请停止后重试').join('\n');
    const state = {stage, entry:!!entry, available:['entry','ready','running'].includes(stage), running:stage==='running', settings, actions, interaction:prompt, logs:((query(adapter.logSelector)?.innerText || '')+(diagnostics?'\n'+diagnostics:'')).slice(-12000)};
    if (adapter.progress) {
      const total = Number(query(adapter.progress.totalSelector)?.textContent?.trim()), remaining = Number(query(adapter.progress.remainingSelector)?.textContent?.trim());
      if (Number.isInteger(total) && Number.isInteger(remaining) && total > 0 && total <= 100000 && remaining >= 0 && remaining <= total) state.progress = {total,remaining};
    }
    const json = JSON.stringify(state);
    if (json !== lastStatus) { lastStatus = json; send('status', state).catch(() => {}); }
    return state;
  }
  const request = input => {
    // Xray wrappers hide callable properties on the userScript's options object.
    // Unwrap this input only; requests still pass the background and native gates.
    const options = input.wrappedJSObject || input;
    const id = 'r' + (++sequence);
    let target;
    try { target = new URL(String(options.url), location.href).href; } catch (_) { options.onerror?.(script.export({status:0,error:'URL'})); return script.export({abort(){}}); }
    const requestKey = String(options.method || 'GET').toUpperCase() + ' ' + target;
    const failed = code => {
      if (requestErrors.size >= 16) requestErrors.delete(requestErrors.keys().next().value);
      requestErrors.set(requestKey, /^[A-Z_0-9]{1,40}$/.test(code) ? code : 'NETWORK');
      status();
    };
    requests.add(id);
    send('request', {id, request:{url:target, method:String(options.method || 'GET').toUpperCase(), headers:options.headers || {}, body:options.data == null ? null : String(options.data), responseType:options.responseType || 'text', timeout:options.timeout || 30000}}).then(response => {
      if (!requests.delete(id)) return;
      if (!response.ok) { failed(response.error); (response.error==='TIMEOUT' ? options.ontimeout : options.onerror)?.(script.export({status:0,error:response.error})); return; }
      const r=response.response;
      if (r.status >= 400) failed('HTTP_'+r.status);
      else { requestErrors.delete(requestKey); status(); }
      let content=r.body;
      try {
        if (options.responseType==='json') content=JSON.parse(r.body);
        else if (options.responseType==='document') content=new window.wrappedJSObject.DOMParser().parseFromString(r.body, 'text/html');
        else if (options.responseType==='arraybuffer') {
          const bytes=atob(r.body); content=new window.wrappedJSObject.ArrayBuffer(bytes.length);
          const array=new window.wrappedJSObject.Uint8Array(content); for(let i=0;i<bytes.length;i++)array[i]=bytes.charCodeAt(i);
        }
      } catch (_) { failed('RESPONSE_FORMAT'); options.onerror?.(script.export({status:r.status,error:'RESPONSE_FORMAT'})); return; }
      const result=script.export({status:r.status,statusText:'',finalUrl:r.url,responseText:options.responseType==='arraybuffer'?'':r.body,
        response:content,responseHeaders:Object.entries(r.headers || {}).map(([k,v])=>k+': '+v).join('\r\n'),readyState:4});
      options.onreadystatechange?.(result); options.onload?.(result);
    }).catch(() => { if (requests.delete(id)) { failed('NETWORK'); options.onerror?.(script.export({status:0,error:'NETWORK'})); } });
    return script.export({abort(){if (requests.delete(id)){send('abort',{id}).catch(()=>{}); options.onabort?.(script.export({status:0}));}}});
  };
  const openTab = (target, options) => {
    const id = 'tab' + (++sequence); let closing = false, remote = '';
    const result = script.export({closed:false,onclose:null,close(){
      closing = true; if(remote)send('close',{pageHandle:remote}).catch(()=>{});
      Object.defineProperty(result,'closed',{value:true,writable:true,configurable:true});
      if(typeof result.onclose==='function')result.onclose();
    }});
    tabs.add(result);
    send('open',{id,url:new URL(String(target),location.href).href,active:typeof options==='boolean'?!options:options?.active!==false}).then(page=>{
      remote=page.handle || '';if(closing&&remote)send('close',{pageHandle:remote}).catch(()=>{});
    }).catch(()=>{Object.defineProperty(result,'closed',{value:true,writable:true,configurable:true});});
    return result;
  };
  script.defineGlobals({
    GM_info:{script:metadata.info,scriptHandler:'Zhengfang',version:'3.4.0',isIncognito:false},
    GM_getValue:read, GM_setValue:write,
    GM_addStyle(css){const node=document.createElement('style');node.textContent=String(css);(document.head || document.documentElement).appendChild(node);return script.export(node);},
    GM_xmlhttpRequest:request,
    GM_openInTab:openTab
  });
  const listener=message=>{
    if(message.handle!==handle || message.document!==documentId)return;
    if(message.kind==='values'){merge(message.values);apply(message.apply || {});status();return Promise.resolve({accepted:true});}
    if(message.kind==='interact'||message.kind==='action') {
      if(controls.has(message.id))return Promise.resolve(controls.get(message.id));
      const state=status(); let target;
      if(!message.expiresAt || message.expiresAt>Date.now()) {
        if(message.kind==='interact' && interaction && !interaction.consumed && state.interaction?.id===message.interactionId) {
          const action=interaction.rule.actions.find(a=>a.id===message.actionId);
          if(state.interaction.actions.some(a=>a.id===message.actionId))target=action && interaction.node.querySelector(action.selector);
          if(target)interaction.consumed=true;
        } else if(message.kind==='action' && !state.interaction) {
          const action=declaration.adapter.actions?.find(a=>a.id===message.actionId);
          if(action)target=visible(action.selector);
        }
      }
      const result={accepted:!!target};
      if(target)target.click();
      if(controls.size>=32)controls.delete(controls.keys().next().value);
      controls.set(message.id,result);status();return Promise.resolve(result);
    }
    if(message.kind!=='command'||message.action!=='start')return;
    if(controls.has(message.id))return Promise.resolve(controls.get(message.id));
    let state=status(), result;
    if(state.stage==='ready'){apply(values);state=status();}
    if(message.expiresAt && message.expiresAt<=Date.now())result={accepted:false,stage:state.stage,message:'启动请求已过期，未执行点击'};
    else if(state.stage==='running')result={accepted:true,stage:'running'};
    else if(state.stage==='interaction')result={accepted:false,stage:'interaction',message:'原脚本需要填写密钥或确认提示，请在 App 内处理提示'};
    else {
      const target=state.stage==='ready' ? settledControl(declaration.adapter.startSelector)
        : state.stage==='entry' && !opening ? settledControl(declaration.adapter.entrySelector) : null;
      if(!target)result={accepted:false,stage:state.stage,message:opening?'正在进入原脚本任务页，请稍候':'原脚本控件尚未就绪，请检查任务设置后重试'};
      else {
        const entry=state.stage==='entry';
        opening=entry;
        target.click();
        state=status();
        result={accepted:true,stage:entry && state.stage!=='interaction'?'opening':state.stage};
      }
    }
    if(controls.size>=32)controls.delete(controls.keys().next().value);
    controls.set(message.id,result);
    return Promise.resolve(result);
  };
  browser.runtime.onMessage.addListener(listener);
  send('hello').then(result=>{merge(result?.values || values);status();}).catch(()=>{});
  const timer=setInterval(status,1500);
  window.addEventListener('pagehide',()=>{clearInterval(timer);browser.runtime.onMessage.removeListener(listener);requests.clear();tabs.clear();send('closed').catch(()=>{});},{once:true});
});
