// Refresh the committed, synthetic device-test fixture from the developer template.
import {writeFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL,fileURLToPath} from 'node:url';
const repository=process.argv[2];
if(!repository)throw new Error('Usage: node tools/export-service-test-fixture.mjs <plugin-repository>');
const {bundle,testPlugin}=await import(pathToFileURL(path.resolve(repository,'cli/index.mjs')).href);
const template=process.argv[3]??'campus-service';
if(!['campus-service','native-extension'].includes(template))throw new Error('Unknown synthetic fixture template');
const folder=path.resolve(repository,'templates',template);
const built=await bundle(folder);await testPlugin(folder,built);
const destination=fileURLToPath(new URL('../app/src/androidTest/assets/academic-plugin/',import.meta.url));
await mkdir(destination,{recursive:true});
await writeFile(path.join(destination,`${template}-manifest.json`),JSON.stringify(built.manifest,null,2)+'\n');
await writeFile(path.join(destination,`${template}.js`),built.source);
console.log(`Exported synthetic ${template} test fixture (no real network or accounts).`);
