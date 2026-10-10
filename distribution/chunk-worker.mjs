const APK_TYPE = 'application/vnd.android.package-archive';

function selectedRange(value, size) {
  if (!value) return [0, size - 1];
  const match = /^bytes=(\d*)-(\d*)$/.exec(value);
  if (!match || (!match[1] && !match[2])) return null;
  const first = match[1] ? Number(match[1]) : null;
  const last = match[2] ? Number(match[2]) : null;
  if ([first, last].some(n => n !== null && !Number.isSafeInteger(n))) return null;
  if (first === null) return last > 0 ? [Math.max(0, size - last), size - 1] : null;
  if (first >= size || (last !== null && last < first)) return null;
  return [first, Math.min(last ?? size - 1, size - 1)];
}

function requestedParts(file, start, end) {
  const selected = [];
  let offset = 0;
  for (const part of file.parts) {
    if (offset <= end && offset + part.size > start) {
      selected.push({ ...part, first: Math.max(0, start - offset), last: Math.min(part.size - 1, end - offset) });
    }
    offset += part.size;
  }
  return selected;
}

function sliceStream(body, first, last) {
  const reader = body.getReader();
  let offset = 0;
  let finished = false;
  return new ReadableStream({
    async pull(controller) {
      try {
        while (!finished) {
          const { value, done } = await reader.read();
          if (finished) return;
          if (done) throw new Error('Download part ended before the requested range');
          const begin = Math.max(0, first - offset);
          const end = Math.min(value.byteLength, last + 1 - offset);
          offset += value.byteLength;
          if (end > begin) controller.enqueue(value.subarray(begin, end));
          if (offset > last) {
            finished = true;
            controller.close();
            await reader.cancel();
          }
          if (end > begin) return;
        }
      } catch (error) {
        if (!finished) controller.error(error);
        finished = true;
        await reader.cancel(error).catch(() => {});
      }
    },
    cancel(reason) { finished = true; return reader.cancel(reason); },
  });
}

async function openPart(part, request, assets) {
  const headers = new Headers({ 'Accept-Encoding': 'identity' });
  const partial = part.first !== 0 || part.last !== part.size - 1;
  if (partial) headers.set('Range', `bytes=${part.first}-${part.last}`);
  const response = await assets.fetch(new Request(new URL(part.path, request.url), { headers }));
  const expected = part.last - part.first + 1;
  const ranged = partial && response.status === 206;
  const actualLength = response.headers.get('Content-Length');
  const encoding = response.headers.get('Content-Encoding');
  if ((!ranged && response.status !== 200) || !response.body ||
      (encoding && encoding !== 'identity') ||
      (actualLength !== null && Number(actualLength) !== (ranged ? expected : part.size)) ||
      (ranged && response.headers.get('Content-Range') !== `bytes ${part.first}-${part.last}/${part.size}`)) {
    await response.body?.cancel();
    throw new Error('Download part unavailable or inconsistent');
  }
  // Asset bindings may ignore Range. Slice just this bounded part as it streams.
  return partial && !ranged ? sliceStream(response.body, part.first, part.last) : response.body;
}

function failedDownload() {
  return new Response('Download temporarily unavailable', { status: 502, headers: { 'Cache-Control': 'no-store' } });
}

export function createDownloadWorker(files) {
  return {
    async fetch(request, env, ctx) {
      const file = files[new URL(request.url).pathname];
      if (!file) return env.ASSETS.fetch(request);
      if (request.method !== 'GET' && request.method !== 'HEAD') {
        return new Response(null, { status: 405, headers: { Allow: 'GET, HEAD' } });
      }
      const etag = `"${file.sha256}"`;
      const headers = new Headers({
        'Content-Type': APK_TYPE,
        'Content-Disposition': 'attachment; filename="app-release.apk"',
        'X-Content-Type-Options': 'nosniff',
        'Accept-Ranges': 'bytes',
        'Cache-Control': 'public, max-age=31536000, immutable, no-transform',
        ETag: etag,
      });
      const cached = request.headers.get('If-None-Match');
      if (cached?.split(',').some(value => value.trim() === '*' || value.trim().replace(/^W\//, '') === etag)) {
        return new Response(null, { status: 304, headers });
      }
      const ifRange = request.headers.get('If-Range');
      const rangeHeader = request.method === 'GET' && (!ifRange || ifRange === etag) ? request.headers.get('Range') : null;
      const range = selectedRange(rangeHeader, file.size);
      if (!range) {
        headers.set('Content-Range', `bytes */${file.size}`);
        return new Response(null, { status: 416, headers });
      }
      const [start, end] = range;
      const length = end - start + 1;
      headers.set('Content-Length', String(length));
      if (rangeHeader) headers.set('Content-Range', `bytes ${start}-${end}/${file.size}`);
      const status = rangeHeader ? 206 : 200;
      if (request.method === 'HEAD') return new Response(null, { status, headers });

      const parts = requestedParts(file, start, end);
      let first;
      try {
        first = await openPart(parts[0], request, env.ASSETS);
      } catch {
        return failedDownload();
      }
      // Cloudflare pipes streams natively and enforces the exact signed length.
      // The standard stream fallback keeps the same module testable in Node.
      const stream = typeof FixedLengthStream === 'function' ? new FixedLengthStream(length) : new TransformStream();
      const pump = async () => {
        try {
          await first.pipeTo(stream.writable, { preventClose: true });
          for (const part of parts.slice(1)) {
            const body = await openPart(part, request, env.ASSETS);
            await body.pipeTo(stream.writable, { preventClose: true });
          }
          await stream.writable.getWriter().close();
        } catch (error) {
          // An incomplete stream stays a failed download; never substitute HTML.
          await stream.writable.abort(error).catch(() => {});
        }
      };
      ctx.waitUntil(pump());
      return new Response(stream.readable, { status, headers });
    },
  };
}
