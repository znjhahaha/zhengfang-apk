import {mkdir,copyFile,access} from 'node:fs/promises';
import {resolve} from 'node:path';
const repo=process.argv[2];
if(!repo)throw new Error('Usage: node tools/sync-plugin-test-assets.mjs <plugin-repository>');
const output=new URL('../app/src/androidTest/assets/academic-plugin/',import.meta.url);
await mkdir(output,{recursive:true});
// Retain legacy fixture names to keep verifying compatibility with existing imports.
for(const file of ['local.test-config-1.0.0.zfplugin','local.test-extend-1.0.0.zfplugin','local.test-mock-1.0.0.zfplugin','local.test-mock-1.0.0-signed.zfplugin']) {
 let source=resolve(repo,'dist',file.replace('.zfplugin','.eduplugin'));
 try { await access(source); } catch { source=resolve(repo,'dist',file); }
 await copyFile(source,new URL(file,output));
}
await copyFile(resolve(repo,'.local/signing/public.json'),new URL('test-public-key.json',output));
await copyFile(new URL('../academic-plugin-api/crypto-vectors.json',import.meta.url),new URL('crypto-vectors.json',output));
console.log('Copied test-only packages and public key. No private key is copied.');
