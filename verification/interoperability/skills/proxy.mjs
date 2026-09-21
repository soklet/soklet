import { createServer, request as httpRequest } from 'node:http';

const methods = new Set(['server/discover', 'skills/list', 'skills/get', 'resources/list',
  'resources/templates/list', 'resources/read']);
const prefix = 'io.modelcontextprotocol/';
const protocol = '2026-07-28';
const targets = new Map([
  ['skill://soklet.example/toy-catalog-guide/SKILL.md', 'ROOT'],
  ['skill://soklet.example/toy-catalog-guide/references/catalog.csv', 'CSV'],
  ['skill://soklet.example/toy-catalog-guide/assets/sample.bin', 'BINARY'],
]);
const failure = () => new Error('Skills example probe failed.');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const utf8 = new TextDecoder('utf-8', { fatal: true });

function send(response, status, headers, body) {
  return new Promise((resolve, reject) => {
    const finish = () => settle();
    const closed = () => settle(failure());
    const settle = error => {
      response.off('finish', finish);
      response.off('close', closed);
      response.off('error', closed);
      error ? reject(error) : resolve();
    };
    response.once('finish', finish);
    response.once('close', closed);
    response.once('error', closed);
    try {
      if (response.destroyed) throw failure();
      response.writeHead(status, headers);
      response.end(body);
    } catch { settle(failure()); }
  });
}

// A transparent loopback observer, except for the explicitly selected negative
// control. It never injects protocol metadata or changes client capabilities.
export async function startProxy(targetPort) {
  if (!Number.isInteger(targetPort) || targetPort < 1 || targetPort > 65535)
    throw new Error('Invalid example listener port');
  const rows = [];
  const jobs = new Set();
  const aborters = new Set();
  let tamper = false;
  let received = 0;
  let closing;
  async function handle(request, response) {
    // Retain at most 64 exchanges plus one fixed invalid overflow marker.
    // Later requests never buffer a body or open an upstream socket.
    if (++received > 64) {
      if (received === 65) rows.push({ method: 'UNSUPPORTED', valid: false, tampered: false, limitExceeded: true });
      response.on('error', () => {});
      response.writeHead(502, { 'content-type': 'text/plain', connection: 'close' });
      response.end('Skills example probe failed.');
      return;
    }
    const row = { method: 'UNSUPPORTED', valid: false, tampered: false };
    rows.push(row);
    const tamperThisRequest = tamper;
    let upstream;
    let aborted = false;
    const abort = () => {
      aborted = true;
      row.valid = false;
      upstream?.destroy(failure());
      request.destroy();
      response.destroy();
    };
    aborters.add(abort);
    const downstreamClosed = () => { if (!response.writableFinished) abort(); };
    response.on('close', downstreamClosed);
    response.on('error', abort);
    request.on('error', abort);
    const deadline = setTimeout(abort, 5000);
    try {
      if (request.method !== 'POST' || request.url !== '/mcp')
        throw new Error('Unsupported request');
      const chunks = [];
      let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        if (size > 1024 * 1024) throw new Error('Request too large');
        chunks.push(chunk);
      }
      if (aborted) throw failure();
      const body = Buffer.concat(chunks);
      const message = JSON.parse(utf8.decode(body));
      if (!object(message) || !object(message.params)) throw failure();
      const meta = message.params?._meta;
      const capabilities = meta?.[`${prefix}clientCapabilities`];
      row.method = methods.has(message.method) ? message.method : 'UNSUPPORTED';
      row.target = message.params.uri === undefined ? 'NONE'
        : targets.get(message.params.uri) ?? 'UNEXPECTED';
      row.modernProtocol = request.headers['mcp-protocol-version'] === protocol
        && meta?.[`${prefix}protocolVersion`] === protocol;
      row.methodMirrored = request.headers['mcp-method'] === message.method;
      row.nameMirrored = row.method === 'resources/read'
        ? typeof message.params.uri === 'string' && request.headers['mcp-name'] === message.params.uri
        : request.headers['mcp-name'] === undefined;
      row.skillsAdvertised = object(capabilities?.extensions)
        && object(capabilities.extensions[`${prefix}skills`]);
      row.noSession = request.headers['mcp-session-id'] === undefined;
      if (row.method === 'UNSUPPORTED' || message.jsonrpc !== '2.0'
          || !(typeof message.id === 'string' || Number.isSafeInteger(message.id))
          || !row.modernProtocol || !row.methodMirrored || !row.nameMirrored || !row.skillsAdvertised || !row.noSession)
        throw new Error('Unexpected client protocol');
      const result = await new Promise((resolve, reject) => {
        upstream = httpRequest({ hostname: '127.0.0.1', port: targetPort, path: '/mcp', method: 'POST',
          headers: { ...request.headers, host: `127.0.0.1:${targetPort}`, connection: 'close' } }, reply => {
          const parts = [];
          let count = 0;
          reply.on('data', chunk => {
            count += chunk.length;
            if (count > 4 * 1024 * 1024) reply.destroy(new Error('Response too large'));
            else parts.push(chunk);
          });
          reply.on('error', reject);
          reply.once('aborted', () => reject(failure()));
          reply.on('end', () => resolve({ status: reply.statusCode, headers: reply.headers, body: Buffer.concat(parts) }));
        });
        upstream.on('error', reject);
        upstream.end(body);
      });
      if (aborted) throw failure();
      const envelope = JSON.parse(utf8.decode(result.body));
      row.status = result.status;
      row.correlated = envelope?.jsonrpc === '2.0' && envelope.id === message.id;
      row.complete = envelope?.result?.resultType === 'complete' && !Object.hasOwn(envelope, 'error');
      row.noStore = result.headers['cache-control'] === 'no-store';
      row.noSession &&= result.headers['mcp-session-id'] === undefined;
      if (!(row.status === 200 && row.correlated && row.complete && row.noStore && row.noSession
          && /^application\/json(?:;|$)/i.test(result.headers['content-type'] ?? ''))) throw failure();
      if (tamperThisRequest && row.method === 'resources/read') {
        const binary = envelope.result.contents?.find(content => typeof content.blob === 'string');
        if (binary) {
          const bytes = Buffer.from(binary.blob, 'base64');
          if (!bytes.length) throw new Error('Missing binary negative control');
          bytes[0] ^= 1;
          binary.blob = bytes.toString('base64');
          result.body = Buffer.from(JSON.stringify(envelope));
          row.tampered = true;
        }
      }
      const headers = { ...result.headers, 'content-length': String(result.body.length), connection: 'close' };
      delete headers['transfer-encoding'];
      await send(response, result.status, headers, result.body);
      // A successful upstream read alone is not a successful client exchange.
      if (!aborted) row.valid = true;
    } catch {
      row.valid = false;
      upstream?.destroy();
      if (!response.destroyed && !response.headersSent) {
        try { await send(response, 502, { 'content-type': 'text/plain', connection: 'close' }, 'Skills example probe failed.'); }
        catch { response.destroy(); }
      } else if (!response.writableFinished) response.destroy();
    } finally {
      clearTimeout(deadline);
      aborters.delete(abort);
      response.off('close', downstreamClosed);
      response.off('error', abort);
      request.off('error', abort);
    }
  }
  const server = createServer((request, response) => {
    const job = handle(request, response);
    jobs.add(job);
    void job.then(() => jobs.delete(job), () => jobs.delete(job));
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return { port: server.address().port, rows,
    setTampered(value) { if (typeof value !== 'boolean') throw new Error('Invalid control'); tamper = value; },
    close() {
      closing ??= (async () => {
        const stopped = new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
        for (const abort of aborters) abort();
        server.closeAllConnections();
        await Promise.all([stopped, ...jobs]);
      })();
      return closing;
    },
  };
}
