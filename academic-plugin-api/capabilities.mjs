const s = (maxLength=8192) => ({type:'string',maxLength});
const id = {type:'string',minLength:1,maxLength:128};
const integer=(minimum,maximum)=>({type:'integer',minimum,maximum});
const obj=(properties={},required=Object.keys(properties))=>({type:'object',properties,required,additionalProperties:false});
const map=()=>({type:'object',additionalProperties:s()});
const handle=obj({handle:id});
const http=obj({url:s(2048),method:{enum:['GET','POST']},purpose:{enum:['query','auth','mutation']},headers:map(),form:map(),body:s(262144),charset:{enum:['UTF-8','GBK','GB2312','GB18030']},responseType:{enum:['text','base64']},credential:id,bindings:obj({form:map(),headers:map()},[])},['url','purpose']);
const response=obj({status:integer(100,599),url:s(2048),headers:map(),body:s(7*1024*1024)});
const fileInfo=obj({handle:id,name:s(160),mime:s(100),size:integer(0,16*1024*1024)});
const items={type:'array',items:id,maxItems:32};
function capability(name,permission,input,output={},userGesture=false,limits={}) {return {name,version:1,permission,input,output,cancellable:true,userGesture,limits:{maxPerFlow:32,...limits}};}
export const hostCapabilities=[
  capability('network.request','network',http,response,false,{responseBytes:5*1024*1024}),
  capability('storage.get','storage',obj({key:id}),{},false,{bytes:262144}),
  capability('storage.set','storage',obj({key:id,value:{}}),{},false,{bytes:262144}),
  capability('storage.remove','storage',obj({key:id})),
  capability('auth.prompt','auth',obj({title:s(160),key:id,remember:{type:'boolean'},imageHandle:id,fields:{type:'array',maxItems:12,minItems:1,items:obj({id,label:s(160),type:{enum:['text','password','otp']},required:{type:'boolean'}},['id','label','type'])}},['title','key','fields']),obj({credential:id}),true),
  capability('credentials.find','credentials',obj({key:id}),{},false),
  capability('credentials.remove','credentials',handle),
  capability('session.save','session',obj({key:id})),
  capability('session.restore','session',obj({key:id}),obj({restored:{type:'boolean'}})),
  capability('session.clear','session',obj({key:id})),
  capability('files.pick','files',obj({mimeTypes:{type:'array',items:s(100),minItems:1,maxItems:8}}),fileInfo,true,{bytes:16*1024*1024}),
  capability('files.create','files',obj({name:s(160),mime:s(100)}),fileInfo),
  capability('files.read','files',obj({handle:id,offset:integer(0,16*1024*1024),length:integer(1,262144)},['handle','length']),obj({base64:s(350000),size:integer(0,16*1024*1024),eof:{type:'boolean'}})),
  capability('files.write','files',obj({handle:id,offset:integer(0,16*1024*1024),base64:s(350000)},['handle','base64']),fileInfo),
  capability('files.remove','files',handle),
  capability('files.download','files',obj({request:http,name:s(160),mime:s(100)}),fileInfo),
  capability('files.upload','files',obj({handle:id,request:http,field:s(100)}),response,true),
  capability('files.open','files',handle,{},true),
  capability('files.share','files',handle,{},true),
  capability('device.clipboard.read','device.clipboard',obj(),obj({text:s(8000)}),true),
  capability('device.clipboard.write','device.clipboard',obj({text:s(8000)}),{},true),
  capability('device.haptic','device.haptics',obj(),{},true),
  capability('tasks.schedule','tasks',obj({taskId:id,delaySeconds:integer(60,2592000),input:{},state:{}},['taskId','delaySeconds','input','state']),handle,true,{pending:16}),
  capability('tasks.cancel','tasks',handle),
  capability('tasks.list','tasks',obj(),{type:'array',maxItems:16,items:obj({handle:id,taskId:id,status:s(40),scheduledAt:integer(0,Number.MAX_SAFE_INTEGER)})}),
  capability('notifications.post','notifications',obj({id:integer(1,1000000),title:s(160),body:s(2000)})),
  capability('navigation.page','navigation',obj({pageId:id,params:{type:'object'}},['pageId'])),
  capability('navigation.back','navigation',obj()),
  capability('navigation.url','navigation',obj({url:s(2048)}),{},true),
  capability('runtime.cancel','runtime',obj({effectIds:items}))
  ,capability('data.query','runtime',obj({providerId:id,input:{},cursor:s(1024)},['providerId','input']),obj({items:{type:'array',items:{},maxItems:1000},nextCursor:s(1024)},['items']))
];
