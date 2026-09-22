import {writeFile,mkdir} from 'node:fs/promises';
import path from 'node:path';
import {pathToFileURL,fileURLToPath} from 'node:url';
const repository=process.argv[2];
if(!repository)throw new Error('Usage: node tools/export-native-test-fixtures.mjs <plugin-repository>');
const {bundle,testPlugin}=await import(pathToFileURL(path.resolve(repository,'cli/index.mjs')).href);
const destination=fileURLToPath(new URL('../app/src/androidTest/assets/academic-plugin/',import.meta.url));
await mkdir(destination,{recursive:true});
for(const name of ['native-components','native-capabilities']){
  const folder=path.resolve(repository,'templates',name),built=await bundle(folder);await testPlugin(folder,built);
  await writeFile(path.join(destination,name+'-manifest.json'),JSON.stringify(built.manifest,null,2)+'\n');
  await writeFile(path.join(destination,name+'.js'),built.source);
}
console.log('Exported API v3 synthetic device fixtures; no school network or credentials.');
