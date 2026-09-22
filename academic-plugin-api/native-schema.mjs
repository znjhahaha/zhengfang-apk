const s = (extra={}) => ({type:'string',...extra});
const n = (minimum,maximum) => ({type:'number',minimum,maximum});
const i = (minimum,maximum) => ({type:'integer',minimum,maximum});
const a = (items,maxItems=1000) => ({type:'array',items,maxItems});
const o = (properties,required=Object.keys(properties)) => ({type:'object',properties,required,additionalProperties:false});
const ref = name => ({$ref:`#/$defs/${name}`});
const id = s({pattern:'^[a-zA-Z][a-zA-Z0-9_.:-]{0,95}$'}), title=s({minLength:1,maxLength:160});
const common = {id,enabled:{type:'boolean'},padding:i(0,64),weight:n(0,10),height:i(0,2000),surface:s({enum:['none','glass','tonal']}),tone:s({enum:['default','primary','positive','warning','error']})};
const node = (type,props={},required=[]) => o({...common,type:{const:type},...props},['id','type',...required]);
const layout = {children:a(ref('UiNode')),gap:i(0,64),align:s({enum:['start','center','end']})};
const shape = o({id,type:s({enum:['line','rect','circle','path']}),x:n(0,2000),y:n(0,2000),x2:n(0,2000),y2:n(0,2000),width:n(0,2000),height:n(0,2000),radius:n(0,1000),stroke:n(0,24),color:s({enum:['primary','foreground','muted','positive','warning','error']}),points:a(o({x:n(0,2000),y:n(0,2000)}),256)},['id','type']);
export const nativeDefinitions = {
  UiNode:{anyOf:[
    ...['column','row','box','scroll'].map(type=>node(type,layout,['children'])),
    node('list',{...layout,onEnd:id},['children']),
    node('text',{text:s({maxLength:8000}),style:s({enum:['title','body','label','caption']}),maxLines:i(1,100)},['text']),
    node('image',{handle:s({maxLength:128}),label:title},['handle','label']),
    node('button',{label:title,event:id},['label','event']),
    node('input',{label:title,value:s({maxLength:8000}),event:id,inputType:s({enum:['text','number','password','multiline']}),placeholder:s({maxLength:160})},['label','value','event']),
    node('toggle',{label:title,value:{type:'boolean'},event:id},['label','value','event']),
    node('select',{label:title,value:s({maxLength:200}),event:id,options:{...a(o({value:s({maxLength:200}),label:title}),100),minItems:1}},['label','value','event','options']),
    node('slider',{label:title,value:n(-1e9,1e9),min:n(-1e9,1e9),max:n(-1e9,1e9),steps:i(0,100),event:id},['label','value','min','max','event']),
    node('progress',{value:n(0,1),label:title},['value']),
    node('divider'),node('spacer'),
    node('canvas',{width:i(1,2000),height:i(1,2000),shapes:a(shape,256),label:title},['width','height','shapes','label'])
  ]},
  UiEffect:o({id,capability:s({pattern:'^[a-z][a-zA-Z0-9.]{2,80}$'}),version:i(1,100),input:{type:'object'},timeoutMs:i(1000,600000)},['id','capability','version','input']),
  UiResult:o({state:{},view:ref('UiNode'),effects:a(ref('UiEffect'),8)}),
  TaskResult:o({state:{},effects:a(ref('UiEffect'),8)}),
  DataResult:o({items:a({}),nextCursor:s({maxLength:1024})},['items'])
};
const page=o({id,title});
export const nativeManifestFields={
  permissions:{...a(s({enum:['network','storage','credentials','session','files','device.clipboard','device.haptics','tasks','notifications','navigation','auth','runtime']}),16),uniqueItems:true},
  requires:a(o({name:s({pattern:'^[a-z][a-zA-Z0-9.]{2,80}$'}),version:i(1,100)}),64,
  ),
  matches:a(o({host:s({minLength:1,maxLength:253}),port:i(1,65535),protocol:s({enum:['http','https']}),pathPrefix:s({pattern:'^/',maxLength:1024})},['host','pathPrefix']),50),
  contributes:o({
    pages:a(page,30),
    entries:a(o({id,title,pageId:id,icon:s({enum:['school','book','chart','calendar','wallet','activity']}),order:i(0,100)},['id','title','pageId']),30),
    menuActions:a(o({id,title,event:id,pageId:id},['id','title','event']),50),
    dataProviders:a(page,20),tasks:a(page,20),academic:{type:'boolean'}
  },['pages','entries']),
  authorRef:s({minLength:3,maxLength:96}),features:a(s({minLength:1,maxLength:160}),30),releaseNotes:s({maxLength:2000})
};
