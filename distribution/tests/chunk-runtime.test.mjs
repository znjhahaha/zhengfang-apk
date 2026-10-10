import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve, sep } from 'node:path';
import test from 'node:test';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

test('Cloudflare runtime streams a file above 25 MiB with real asset ranges', { timeout: 60000 }, async () => {
  const root = await mkdtemp(join(tmpdir(), 'academic-apk-stream-'));
  let runtime;
  try {
    const directory = join(root, 'assets');
    await mkdir(directory);
    const data = Buffer.alloc(26 * 1024 * 1024 + 173);
    for (let i = 0; i < data.length; i++) data[i] = i % 251;
    const sha256 = createHash('sha256').update(data).digest('hex');
    const path = `/releases/1.0.112/${sha256}/app-release.apk`;
    const chunkSize = 20 * 1024 * 1024;
    const parts = [];
    for (let start = 0; start < data.length; start += chunkSize) {
      const part = { path: `/_apk/${sha256}/${String(parts.length).padStart(3, '0')}.bin`, size: Math.min(chunkSize, data.length - start) };
      const file = join(directory, part.path.slice(1));
      await mkdir(dirname(file), { recursive: true });
      await writeFile(file, data.subarray(start, start + part.size));
      parts.push(part);
    }
    await writeFile(join(directory, '_headers'), '/_apk/*\n  Content-Type: application/octet-stream\n  Cache-Control: public, max-age=31536000, immutable, no-transform\n');
    await mkdir(join(directory, 'releases/old'), { recursive: true });
    await writeFile(join(directory, 'releases/old/app-release.apk'), 'old');
    const source = await readFile(new URL('../chunk-worker.mjs', import.meta.url), 'utf8');
    const files = { [path]: { sha256, size: data.length, parts } };
    runtime = new Miniflare(convertV4MiniflareOptions({
      modules: true,
      script: `${source}\nexport default createDownloadWorker(${JSON.stringify(files)});`,
      compatibilityDate: '2026-09-25',
      assets: { directory, binding: 'ASSETS', run_worker_first: ['/releases/*'], routerConfig: { has_user_worker: true } },
    }));
    const url = `https://dl-test.hidisiwa.xyz${path}`;
    const full = await runtime.dispatchFetch(url);
    assert.equal(full.status, 200, full.status === 200 ? undefined : await full.text());
    assert.equal(full.headers.get('Content-Length'), String(data.length));
    let count = 0;
    const hash = createHash('sha256');
    for await (const chunk of full.body) { hash.update(chunk); count += chunk.length; }
    assert.equal(count, data.length);
    assert.equal(hash.digest('hex'), sha256);

    const first = chunkSize - 123;
    const last = chunkSize + 256;
    const partial = await runtime.dispatchFetch(url, { headers: { Range: `bytes=${first}-${last}`, 'If-Range': `"${sha256}"` } });
    assert.equal(partial.status, 206);
    assert.equal(partial.headers.get('Content-Length'), String(last - first + 1));
    assert.equal(partial.headers.get('Content-Range'), `bytes ${first}-${last}/${data.length}`);
    assert.deepEqual(Buffer.from(await partial.arrayBuffer()), data.subarray(first, last + 1));

    const head = await runtime.dispatchFetch(url, { method: 'HEAD' });
    assert.equal(head.status, 200);
    assert.equal(head.headers.get('Content-Length'), String(data.length));
    assert.equal(head.headers.get('ETag'), `"${sha256}"`);
    const old = await runtime.dispatchFetch('https://dl-test.hidisiwa.xyz/releases/old/app-release.apk');
    assert.equal(old.status, 200);
    assert.equal(await old.text(), 'old');
    assert.equal((await runtime.dispatchFetch('https://dl-test.hidisiwa.xyz/releases/missing.apk')).status, 404);
  } finally {
    await runtime?.dispose();
    if (!resolve(root).startsWith(resolve(tmpdir()) + sep)) throw new Error('Unexpected test directory');
    await rm(root, { recursive: true, force: true });
  }
});
