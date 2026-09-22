import { writeFileSync, mkdirSync } from 'node:fs';
const string = (extra = {}) => ({type:'string', ...extra});
const id = string({minLength:1,maxLength:256});
const integer = (min,max) => ({type:'integer',minimum:min,maximum:max});
const bool = {type:'boolean'};
const arr = (items,maxItems=10000) => ({type:'array',items,maxItems});
const obj = (properties,required=Object.keys(properties),extra={}) => ({type:'object',properties,required,additionalProperties:false,...extra});
const ref = name => ({$ref:`#/$defs/${name}`});
const map = value => ({type:'object',additionalProperties:value});
const optional = (properties, required=[]) => obj(properties,required);
export const errors = ['UNSUPPORTED','NOT_OPEN','INVALID_CREDENTIALS','SESSION_EXPIRED','CAPTCHA_REQUIRED','WEB_LOGIN_REQUIRED','NO_CAPACITY','CONFLICT','ALREADY_SELECTED','CREDIT_LIMIT','PAGE_CHANGED','NETWORK_RETRYABLE','RESULT_UNKNOWN','UNTRUSTED_URL','VALIDATION_FAILED','TIMEOUT','CANCELLED','RUNTIME_EXITED','RESOURCE_LIMIT','BAD_SIGNATURE'];
export const methods = {
 'auth.start':'AuthState', 'auth.resume':'AuthState', 'auth.refreshCaptcha':'AuthState', 'auth.validate':'AuthState',
 'study.terms':'Terms', 'study.schedule':'Schedule','study.calendar':'Calendar','study.grades':'Grades','study.gradeDetails':'GradeDetails','study.exams':'Exams',
 'selection.catalog':'Rounds','selection.courses':'Courses','selection.sections':'Sections','selection.enrolled':'Enrollments','selection.select':'SelectionReceipt','selection.drop':'SelectionReceipt',
 'service.page':'ServicePage','service.action':'ServiceReceipt'
};
const page = name => obj({items:arr(ref(name)),nextCursor:string({maxLength:1024})},['items']);
const display = {teacher:string(),time:string(),location:string(),credits:string()};
const $defs = {
 Error: obj({code:string({enum:errors}),message:string({maxLength:2000}),details:map(string())},['code','message']),
 Term: obj({id,name:id,year:integer(1900,2300),semester:integer(1,12),order:integer(-100000,100000),startDate:string(),endDate:string(),nextId:id},['id','name']),
 Terms: obj({items:arr(ref('Term'),1000),currentId:id}),
 AuthState: {anyOf:[
   obj({status:{const:'authenticated'},studentId:string(),studentName:string()}),
   obj({status:{const:'captcha'},continuationId:id,imageBase64:string({maxLength:1400000}),mimeType:string({enum:['image/png','image/jpeg','image/gif','image/webp']})}),
   obj({status:{const:'webLogin'},continuationId:id,url:id,completionUrl:id})
 ]},
 ScheduleEntry: obj({id,name:id,teacher:string(),location:string(),day:integer(1,7),startPeriod:integer(1,30),endPeriod:integer(1,30),weeks:arr(integer(1,25),25)},['id','name','day','startPeriod','endPeriod','weeks']),
 Schedule: obj({termId:id,entries:arr(ref('ScheduleEntry')),maxWeeks:integer(1,25)}),
 Calendar: obj({termId:id,startDate:string(),periods:arr(obj({number:integer(1,30),start:string({pattern:'^[0-2][0-9]:[0-5][0-9]$'}),end:string({pattern:'^[0-2][0-9]:[0-5][0-9]$'})}),30)},['termId','periods']),
 Grade: obj({id,name:id,score:string(),credits:string(),gradePoint:string(),type:string(),termId:id,code:string(),college:string(),sectionId:string()},['id','name','score']),
 GradeDetails: obj({gradeId:id,items:arr(obj({name:string(),score:string(),weight:string()},['name','score']),100)}),
 Exam: obj({id,name:id,time:string(),location:string(),seat:string(),examName:string(),teacher:string()},['id','name','time']),
 Round: obj({id,name:id,termId:id,open:bool},['id','name','open']),
 Course: obj({id,name:id,roundId:id,...display,capacity:integer(0,100000),selected:integer(0,100000),sectionId:id,sectionName:string()},['id','name','roundId']),
 Section: obj({id,courseId:id,name:string(),teacher:string(),time:string(),location:string(),capacity:integer(0,100000),selected:integer(0,100000)},['id','courseId']),
 Enrollment: obj({id,courseId:id,sectionId:id,name:id,...display},['id','courseId','sectionId','name']),
 SelectionReceipt: obj({confirmed:bool,enrollment:ref('Enrollment'),message:string()},['confirmed']),
 Exams:page('Exam'),Rounds:page('Round'),Courses:page('Course'),Sections:page('Section'),Enrollments:page('Enrollment'),
 Grades:obj({items:arr(ref('Grade')),nextCursor:string(),gradePointAverage:string(),totalCredits:string()},['items'])
};
const label = string({minLength:1,maxLength:100});
const prose = string({maxLength:2000});
const key = string({pattern:'^[a-z][a-z0-9_-]{0,63}$'});
const params = {type:'object',additionalProperties:string({maxLength:1000})};
const tone = string({enum:['default','positive','warning']});
$defs.ServiceLink = {anyOf:[
 obj({type:{const:'page'},pageId:key,params},['type','pageId']),
 obj({type:{const:'action'},actionId:key,params},['type','actionId']),
 obj({type:{const:'url'},url:string({minLength:1,maxLength:2000})}),
 obj({type:{const:'native'},operationId:key,params},['type','operationId'])
]};
const baseBlock = {id:key,title:label};
$defs.ServiceBlock = {anyOf:[
 obj({...baseBlock,type:{const:'profile'},subtitle:prose,details:arr(label,8),badge:label},['id','type','title']),
 obj({...baseBlock,type:{const:'metrics'},columns:integer(1,3),items:{...arr(obj({id:key,label,value:label,unit:label},['id','label','value']),12),minItems:1}},['id','type','columns','items']),
 obj({...baseBlock,type:{const:'progress'},items:arr(obj({id:key,label,value:{type:'number',minimum:0,maximum:1e9},max:{type:'number',minimum:0.000001,maximum:1e9},detail:prose,displayValue:label,tone},['id','label','value','max']),40)},['id','type','title','items']),
 obj({...baseBlock,type:{const:'list'},items:arr(obj({id:key,title:label,subtitle:prose,value:label,action:ref('ServiceLink')},['id','title']),100)},['id','type','items']),
 obj({id:key,type:{const:'notice'},text:prose,tone},['id','type','text']),
 obj({...baseBlock,type:{const:'actions'},items:arr(obj({label,action:ref('ServiceLink')}),12)},['id','type','items']),
 obj({...baseBlock,type:{const:'form'},fields:arr(obj({id:key,label,type:string({enum:['text','number','select','multiline','toggle']}),required:bool,placeholder:label,value:string({maxLength:1000}),options:arr(obj({value:string({maxLength:1000}),label}),30)},['id','label','type','required']),12),submit:obj({label,actionId:key})},['id','type','fields','submit']),
 obj({...baseBlock,type:{const:'keyValue'},items:arr(obj({id:key,label,value:prose}),40)},['id','type','items']),
 obj({...baseBlock,type:{const:'table'},columns:{...arr(label,6),minItems:1},rows:arr(obj({id:key,cells:arr(prose,6)}),100)},['id','type','columns','rows']),
 obj({...baseBlock,type:{const:'timeline'},items:arr(obj({id:key,title:label,time:label,detail:prose,tone},['id','title','time']),40)},['id','type','items']),
 obj({...baseBlock,type:{const:'barChart'},unit:label,items:arr(obj({id:key,label,value:{type:'number',minimum:0,maximum:1e9},tone},['id','label','value']),24)},['id','type','items']),
 obj({...baseBlock,type:{const:'grid'},columns:integer(1,3),items:arr(obj({id:key,label,subtitle:label,action:ref('ServiceLink')},['id','label','action']),12)},['id','type','columns','items'])
]};
$defs.ServicePage = obj({pageId:key,title:label,subtitle:prose,layout:string({enum:['comfortable','compact']}),blocks:arr(ref('ServiceBlock'),40)},['pageId','title','blocks']);
$defs.ServiceReceipt = obj({actionId:key,confirmed:bool,message:prose,page:ref('ServicePage')},['actionId','confirmed']);
const success = name => obj({ok:{const:true},data:ref(name)});
const failure = obj({ok:{const:false},error:ref('Error')});
for (const [method,name] of Object.entries(methods)) $defs[method] = {anyOf:[success(name),failure]};
export const contract = {$schema:'https://json-schema.org/draft/2020-12/schema',$id:'https://zfplugin.local/api/3/contract.schema.json',apiVersion:3,methods,$defs};
const builtins = ['builtin.zf','builtin.zf_old','builtin.qz','builtin.qz_old','builtin.legacy_zf'];
export const manifest = {$schema:contract.$schema,$id:'https://zfplugin.local/api/3/manifest.schema.json',...obj({
 id:string({pattern:'^[a-z][a-z0-9.-]{2,95}$'}),name:string({minLength:1,maxLength:80}),version:string({pattern:'^[0-9]+\\.[0-9]+\\.[0-9]+$'}),apiVersion:{enum:[1,2,3]},
 kind:string({enum:['configuration','extension','independent','service']}),extends:string({enum:builtins}),entry:{const:'index.js'},
 capabilities:{...arr(string({enum:Object.keys(methods)}),18),uniqueItems:true},
 network:arr(obj({origin:string({maxLength:300}),pathPrefix:string({pattern:'^/'}),methods:arr(string({enum:['GET','POST']}),2),purposes:arr(string({enum:['query','auth','mutation']}),3),requiredQuery:map(string()),requiredForm:map(string())},['origin','pathPrefix','methods','purposes']),50),
 school:obj({id,name:id,domain:id,protocol:string({enum:['http','https']}),basePath:string(),academicSystem:string(),allowedAcademicHosts:arr(string(),30),pageCharset:string()},['id','name','domain','protocol','basePath']),
 service:obj({
  schoolIds:{...arr(id,30),minItems:1,uniqueItems:true},academicHosts:{...arr(string({pattern:'^[a-z0-9.-]+$'}),30),uniqueItems:true},
  authentication:obj({mode:string({enum:['none','password']}),usernameLabel:label,passwordLabel:label,help:prose},['mode']),
  pages:{...arr(obj({id:key,title:label}),12),minItems:1},
  entries:{...arr(obj({id:key,title:label,pageId:key,icon:string({enum:['school','book','chart','calendar','wallet','activity']}),order:integer(0,100),placements:{...arr(string({enum:['home','schedule','grades']}),3),uniqueItems:true}},['id','title','pageId','icon','order']),12),minItems:1},
  actions:arr(obj({id:key,title:label,kind:string({enum:['query','mutation']}),confirmation:prose},['id','title','kind']),40),
  nativeOperations:arr(obj({id:key,title:label,kind:string({enum:['scanCode','pickFile','notification','calendar']}),reason:string({minLength:1,maxLength:500}),resultActionId:key,mimeTypes:{...arr(string({enum:['text/plain','text/csv','application/json','application/pdf','image/png','image/jpeg']}),6),minItems:1,uniqueItems:true}},['id','title','kind','reason','resultActionId']),12)
 },['schoolIds','authentication','pages','entries','actions']),
 description:string({maxLength:2000}),files:map(string({pattern:'^[a-f0-9]{64}$'}))
},['id','name','version','apiVersion','kind','capabilities','network','school','files'])};
mkdirSync(new URL('./assets/academic-plugin/',import.meta.url),{recursive:true});
for (const [name,value] of Object.entries({'contract.schema.json':contract,'manifest.schema.json':manifest}))
 writeFileSync(new URL(`./assets/academic-plugin/${name}`,import.meta.url),JSON.stringify(value,null,2)+'\n');
