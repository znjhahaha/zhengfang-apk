import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';
import { createDownloadWorker } from '../chunk-worker.mjs';

const bytes = Buffer.from('abcdefghijklmnopqrstuvwxyz');
const sha256 = createHash('sha256').update(bytes).digest('hex');
const path = `/releases/1.0.112/${sha256}/app-release.apk`;
const origin = 'https://dl-test.hidisiwa.xyz';
const parts = [bytes.subarray(0, 10), bytes.subarray(10, 20), bytes.subarray(20)].map((data, i) => ({
  path: `/_apk/${sha256}/${String(i).padStart(3, '0')}.bin`, size: data.length, data,
}));
const files = { [path]: { sha256, size: bytes.length, parts: parts.map(({ data, ...part }) => part) } };

function harness({ failure, ignoreRange = false, wrongLength = false, omitLength = false, truncate } = {}) {
  const calls = [];
  const pending = [];
  const ASSETS = {
    async fetch(request) {
      calls.push(request);
      const part = parts.find(part => part.path === new URL(request.url).pathname);
      if (!part || part.path === failure) return new Response('Missing asset', { status: 404 });
      const range = !ignoreRange && request.headers.get('Range');
      const headers = { 'Content-Length': String(part.size) };
      let data = part.data;
      if (range) {
        const [, first, last] = /^bytes=(\d+)-(\d+)$/.exec(range);
        data = data.subarray(Number(first), Number(last) + 1);
        headers['Content-Range'] = `bytes ${first}-${last}/${part.size}`;
        headers['Content-Length'] = String(data.length);
      }
      if (wrongLength) headers['Content-Length'] = '999';
      if (omitLength) delete headers['Content-Length'];
      if (truncate === part.path) data = data.subarray(0, 2);
      return new Response(data, { status: range ? 206 : 200, headers });
    },
  };
  const worker = createDownloadWorker(files);
  return {
    calls,
    fetch: (headers = {}, method = 'GET', target = path) => worker.fetch(new Request(origin + target, { method, headers }), { ASSETS }, { waitUntil: task => pending.push(task) }),
    done: () => Promise.all(pending),
  };
}

test('full download streams exactly the original signed APK and SHA-256', async () => {
  const h = harness();
  const response = await h.fetch();
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('Content-Length'), String(bytes.length));
  assert.equal(response.headers.get('Content-Type'), 'application/vnd.android.package-archive');
  assert.equal(h.calls.length, 1, 'later parts wait for the consumer');
  const actual = Buffer.from(await response.arrayBuffer());
  assert.deepEqual(actual, bytes);
  assert.equal(createHash('sha256').update(actual).digest('hex'), sha256);
  await h.done();
});

test('HEAD exposes complete length and ETag without fetching APK parts', async () => {
  const h = harness();
  const response = await h.fetch({ Range: 'bytes=3-7' }, 'HEAD');
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('Content-Length'), String(bytes.length));
  assert.equal(response.headers.get('ETag'), `"${sha256}"`);
  assert.equal(response.headers.get('Accept-Ranges'), 'bytes');
  assert.equal(response.body, null);
  assert.equal(h.calls.length, 0);
});

test('streaming assets without Content-Length retain the signed response length', async () => {
  const h = harness({ omitLength: true });
  const response = await h.fetch();
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('Content-Length'), String(bytes.length));
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), bytes);
  await h.done();
});

for (const [range, first, last] of [
  ['bytes=7-22', 7, 22], ['bytes=10-', 10, 25], ['bytes=-5', 21, 25],
  ['bytes=0-999', 0, 25], ['bytes=25-25', 25, 25], ['bytes=-999', 0, 25],
]) {
  test(`range ${range} preserves updater offset and full APK size`, async () => {
    const h = harness();
    const response = await h.fetch({ Range: range, 'If-Range': `"${sha256}"` });
    assert.equal(response.status, 206);
    assert.equal(response.headers.get('Content-Range'), `bytes ${first}-${last}/${bytes.length}`);
    assert.equal(response.headers.get('Content-Length'), String(last - first + 1));
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), bytes.subarray(first, last + 1));
    await h.done();
  });
}

test('stale or weak If-Range restarts the full file', async () => {
  for (const value of ['"old"', `W/"${sha256}"`]) {
    const h = harness();
    const response = await h.fetch({ Range: 'bytes=10-', 'If-Range': value });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('Content-Range'), null);
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), bytes);
    await h.done();
  }
});

test('invalid or multiple ranges are rejected before part reads', async () => {
  for (const Range of ['bytes=26-', 'bytes=9-3', 'bytes=0-1,3-4', 'bytes=-0', 'bytes=-', 'bytes=9007199254740992-', 'items=1-2']) {
    const h = harness();
    const response = await h.fetch({ Range });
    assert.equal(response.status, 416, Range);
    assert.equal(response.headers.get('Content-Range'), `bytes */${bytes.length}`);
    assert.equal(h.calls.length, 0);
  }
});

test('matching If-None-Match uses the full APK identity', async () => {
  for (const tag of [`"${sha256}"`, `W/"${sha256}"`, `"old", "${sha256}"`, '*']) {
    const h = harness();
    const response = await h.fetch({ 'If-None-Match': tag });
    assert.equal(response.status, 304);
    assert.equal(response.body, null);
    assert.equal(h.calls.length, 0);
  }
});

test('first missing or malformed part returns a noncacheable download failure', async () => {
  for (const options of [{ failure: parts[0].path }, { wrongLength: true }]) {
    const h = harness(options);
    const response = await h.fetch();
    assert.equal(response.status, 502);
    assert.equal(response.headers.get('Cache-Control'), 'no-store');
  }
});

test('missing later part fails the stream instead of reporting a truncated success', async () => {
  const h = harness({ failure: parts[1].path });
  const response = await h.fetch();
  await assert.rejects(response.arrayBuffer());
  await h.done();
});

test('an asset service ignoring Range is sliced without changing the requested bytes', async () => {
  const h = harness({ ignoreRange: true });
  const response = await h.fetch({ Range: 'bytes=3-5' });
  assert.equal(response.status, 206);
  assert.deepEqual(Buffer.from(await response.arrayBuffer()), bytes.subarray(3, 6));
  await h.done();
});

test('truncated assets fail instead of completing an incomplete range', async () => {
  const h = harness({ ignoreRange: true, truncate: parts[0].path });
  const response = await h.fetch({ Range: 'bytes=3-5' });
  await assert.rejects(response.arrayBuffer());
  await h.done();
});

test('old small APKs and unknown paths keep the static asset response', async () => {
  const h = harness();
  const response = await h.fetch({}, 'GET', '/releases/old.apk');
  assert.equal(response.status, 404);
  assert.equal(await response.text(), 'Missing asset');
  assert.equal(h.calls.length, 1);
});

test('write methods cannot access a download', async () => {
  const h = harness();
  const response = await h.fetch({}, 'POST');
  assert.equal(response.status, 405);
  assert.equal(response.headers.get('Allow'), 'GET, HEAD');
  assert.equal(h.calls.length, 0);
});
