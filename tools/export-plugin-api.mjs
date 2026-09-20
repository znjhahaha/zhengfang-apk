import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { createHash } from 'node:crypto';
const source=fileURLToPath(new URL('../academic-plugin-api/',import.meta.url));
const target=process.argv[2];
if(!target) throw new Error('Usage: node tools/export-plugin-api.mjs <plugin-repository>');
const lock=JSON.parse(await readFile(path.join(source,'api-lock.json'),'utf8'));
await mkdir(path.join(target,'sdk'),{recursive:true});
for(const [file,expected] of Object.entries(lock.sha256)) {
 const bytes=await readFile(path.join(source,file));
 if(createHash('sha256').update(bytes).digest('hex')!==expected) throw new Error(`Contract digest mismatch: ${file}; run npm run build in academic-plugin-api`);
 await writeFile(path.join(target,'sdk',path.basename(file)),bytes);
}
await copyFile(path.join(source,'api-lock.json'),path.join(target,'sdk','api-lock.json'));
await writeFile(path.join(target,'sdk','package.json'),JSON.stringify({name:'@zfplugin/sdk',version:lock.version,private:true,types:'index.d.ts',files:['index.d.ts','*.json','host-sdk.js']},null,2)+'\n');
console.log(`Exported Academic Plugin API ${lock.version} to ${path.resolve(target,'sdk')}`);
