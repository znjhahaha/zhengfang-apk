import './schema.mjs';
import { build } from 'esbuild';
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
await build({entryPoints:[new URL('./runtime.mjs',import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')],bundle:true,format:'iife',platform:'neutral',target:'es2020',minify:true,outfile:new URL('./assets/academic-plugin/host-sdk.js',import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1')});
const files=['index.d.ts','crypto-vectors.json','assets/academic-plugin/contract.schema.json','assets/academic-plugin/manifest.schema.json','assets/academic-plugin/host-sdk.js'];
writeFileSync(new URL('./api-lock.json',import.meta.url),JSON.stringify({version:'2.0.0',apiVersion:2,supportedApiVersions:[1,2],sha256:Object.fromEntries(files.map(file=>[file,createHash('sha256').update(readFileSync(new URL(file,import.meta.url))).digest('hex')]))},null,2)+'\n');
