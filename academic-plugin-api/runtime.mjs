import { parseDocument } from 'htmlparser2';
import { selectAll, selectOne } from 'css-select';
import { textContent, getOuterHTML } from 'domutils';

function document(html) {
  if (typeof html !== 'string' || html.length > 5 * 1024 * 1024) throw new Error('HTML exceeds 5 MiB');
  return parseDocument(html, {decodeEntities:true});
}
function text(node) { return textContent(node).replace(/\s+/g,' ').trim(); }
const html = Object.freeze({
  select(source, selector) {
    return selectAll(selector,document(source)).slice(0,10000).map(node=>({text:text(node),html:getOuterHTML(node),attributes:{...node.attribs}}));
  },
  text(source) { return text(document(source)); },
  form(source, selector='form') {
    const doc=document(source), form=selectOne(selector,doc); if(!form) return {};
    const fields={};
    for (const node of selectAll('input[name],select[name],textarea[name]',form)) {
      const a=node.attribs;
      if ('disabled' in a || ['submit','button','file','reset'].includes(a.type) || (['checkbox','radio'].includes(a.type) && !('checked' in a))) continue;
      let value=a.value??'';
      if(node.name==='textarea') value=textContent(node);
      if(node.name==='select') { const option=selectOne('option[selected]',node)??selectOne('option',node); value=option?.attribs.value??(option?text(option):''); }
      Object.defineProperty(fields,a.name,{value,writable:true,enumerable:true,configurable:true});
    }
    return fields;
  },
  table(source, selector='table') {
    const table=selectOne(selector,document(source)); if(!table) return [];
    return selectAll('tr',table).map(row=>selectAll(':scope > td,:scope > th',row).map(text));
  }
});

// The native binding is captured once. Every host call still validates the operation
// context in the host process, even when untrusted code bypasses this convenience API.
const bridge=globalThis.__zfHost;
async function host(method,payload={}) {
  const result=JSON.parse(await bridge(method,JSON.stringify(payload)));
  if(!result.ok) { const error=new Error(result.error.message); error.code=result.error.code; throw error; }
  return result.data;
}
const store=area=>Object.freeze({get:key=>host(`${area}.get`,{key}),set:(key,value)=>host(`${area}.set`,{key,value}),remove:key=>host(`${area}.remove`,{key})});
const sdk=Object.freeze({html,http:request=>host('http',request),state:store('state'),storage:store('storage'),
 crypto:Object.freeze({digest:(algorithm,text)=>host('crypto.digest',{algorithm,text}),hmacSha256:(key,text)=>host('crypto.hmacSha256',{key,text}),aesCbcEncrypt:(keyBase64,ivBase64,text)=>host('crypto.aesCbcEncrypt',{keyBase64,ivBase64,text}),rsaEncrypt:(publicKeySpkiBase64,text)=>host('crypto.rsaEncrypt',{publicKeySpkiBase64,text}),base64:text=>host('crypto.base64',{text})}),
 log:(level,message,fields={})=>host('log',{level,message,fields})
});
Object.defineProperty(globalThis,'__zfInvoke',{value:async function(request) {
  try {
    const plugin=globalThis.plugin;
    if(request.operation==='__inspect') {
      const capabilities=[];
      for(const group of ['auth','study','selection']) if(plugin?.[group]) for(const method of Object.keys(plugin[group])) {
        if(typeof plugin[group][method]!=='function') throw new Error('Capability must be a function');
        capabilities.push(`${group}.${method}`);
      }
      return JSON.stringify({ok:true,data:capabilities.sort()});
    }
    const [group,method]=request.operation.split('.');
    const handler=plugin?.[group]?.[method];
    if(typeof handler!=='function') return JSON.stringify({ok:false,error:{code:'UNSUPPORTED',message:'该学校尚未适配此功能'}});
    const result=await handler(request.args??{},Object.freeze(request.context),sdk);
    return JSON.stringify(result);
  } catch(error) {
    return JSON.stringify({ok:false,error:{code:error?.code??'PAGE_CHANGED',message:String(error?.message??error).slice(0,2000)}});
  }
},writable:false,configurable:false});
