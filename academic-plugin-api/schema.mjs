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
 'selection.catalog':'Rounds','selection.courses':'Courses','selection.sections':'Sections','selection.enrolled':'Enrollments','selection.select':'SelectionReceipt','selection.drop':'SelectionReceipt'
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
const success = name => obj({ok:{const:true},data:ref(name)});
const failure = obj({ok:{const:false},error:ref('Error')});
for (const [method,name] of Object.entries(methods)) $defs[method] = {anyOf:[success(name),failure]};
export const contract = {$schema:'https://json-schema.org/draft/2020-12/schema',$id:'https://zfplugin.local/api/1/contract.schema.json',apiVersion:1,methods,$defs};
const builtins = ['builtin.zf','builtin.zf_old','builtin.qz','builtin.qz_old','builtin.legacy_zf'];
export const manifest = {$schema:contract.$schema,$id:'https://zfplugin.local/api/1/manifest.schema.json',...obj({
 id:string({pattern:'^[a-z][a-z0-9.-]{2,95}$'}),name:string({minLength:1,maxLength:80}),version:string({pattern:'^[0-9]+\\.[0-9]+\\.[0-9]+$'}),apiVersion:{const:1},
 kind:string({enum:['configuration','extension','independent']}),extends:string({enum:builtins}),entry:{const:'index.js'},
 capabilities:{...arr(string({enum:Object.keys(methods)}),16),uniqueItems:true},
 network:arr(obj({origin:string({maxLength:300}),pathPrefix:string({pattern:'^/'}),methods:arr(string({enum:['GET','POST']}),2),purposes:arr(string({enum:['query','auth','mutation']}),3),requiredQuery:map(string()),requiredForm:map(string())},['origin','pathPrefix','methods','purposes']),50),
 school:obj({id,name:id,domain:id,protocol:string({enum:['http','https']}),basePath:string(),academicSystem:string(),allowedAcademicHosts:arr(string(),30),pageCharset:string()},['id','name','domain','protocol','basePath']),
 description:string({maxLength:2000}),files:map(string({pattern:'^[a-f0-9]{64}$'}))
},['id','name','version','apiVersion','kind','capabilities','network','school','files'])};
mkdirSync(new URL('./assets/academic-plugin/',import.meta.url),{recursive:true});
for (const [name,value] of Object.entries({'contract.schema.json':contract,'manifest.schema.json':manifest}))
 writeFileSync(new URL(`./assets/academic-plugin/${name}`,import.meta.url),JSON.stringify(value,null,2)+'\n');
