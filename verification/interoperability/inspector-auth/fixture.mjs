import { createServer } from 'node:http';
import { isDeepStrictEqual } from 'node:util';

export const PROTOCOL = '2026-07-28';
export const MODES = Object.freeze(['policy-403', 'no-subscription', 'jsonrpc-200-control']);
export const MAX_BODY = 64 * 1024;
export const MAX_REQUESTS = 64;
export const MAX_CONNECTIONS = 32;
const EXCHANGE_MS = 10000;
const META = 'io.modelcontextprotocol/';
const METHODS = new Set(['server/discover', 'tools/list', 'subscriptions/listen']);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const same = isDeepStrictEqual;
const onlyKeys = (value, keys) => object(value) && Object.keys(value).every(key => keys.includes(key));
const metadata = {[`${META}serverInfo`]: {name: 'inspector-auth-regression', version: 'fixture-v1'}};
const diagnosticPaths = new Map([
  ['/.well-known/oauth-protected-resource/mcp', 'OAUTH_PROTECTED_RESOURCE_PATH'],
  ['/.well-known/oauth-protected-resource', 'OAUTH_PROTECTED_RESOURCE_ROOT'],
  ['/.well-known/oauth-authorization-server', 'OAUTH_AUTHORIZATION_SERVER_ROOT'],
  ['/.well-known/openid-configuration', 'OPENID_CONFIGURATION_ROOT'],
  ['/register', 'OAUTH_REGISTRATION_ROOT'],
]);
export const FAILURES = Object.freeze(['AUTH_REQUEST_BOUND', 'AUTH_CONNECTION_BOUND',
  'AUTH_REQUEST_INVALID', 'AUTH_BODY_BOUND', 'AUTH_BODY_IO', 'AUTH_RESPONSE_BOUND',
  'AUTH_EXCHANGE_TIMEOUT', 'AUTH_METHOD', 'AUTH_PATH', 'AUTH_REQUIRED', 'AUTH_ORIGIN',
  'AUTH_HOST', 'AUTH_PROTOCOL', 'AUTH_SELECTION', 'AUTH_HEADERS']);

// This dependency-free responder isolates a host transport behavior. It does
// not imitate a complete MCP server or qualify Soklet. In particular, returning
// an error with HTTP 200 is a causal control, never a proposed server fix.
export function fixtureResult(method, mode) {
  const base = {resultType: 'complete', _meta: structuredClone(metadata), ttlMs: 0, cacheScope: 'private'};
  if (method === 'server/discover') return {...base, supportedVersions: [PROTOCOL],
    capabilities: {tools: {listChanged: mode !== 'no-subscription'}}};
  if (method === 'tools/list') return {...base, tools: [{name: 'auth_probe',
    title: 'Auth recovery probe', description: 'A catalog-only auth recovery fixture.',
    inputSchema: {type: 'object', properties: {}, additionalProperties: false}}]};
  throw new Error('AUTH_METHOD');
}

function selection(request, mode) {
  if (request.method === 'subscriptions/listen')
    return mode !== 'no-subscription' && onlyKeys(request.params, ['_meta', 'notifications'])
      && same(request.params.notifications, {toolsListChanged: true});
  return onlyKeys(request.params, ['_meta']);
}

function collectBody(incoming) {
  return new Promise((resolve, reject) => {
    let bytes = 0;
    const chunks = [];
    incoming.on('data', chunk => {
      bytes += chunk.length;
      if (bytes > MAX_BODY) {
        reject(new Error('AUTH_BODY_BOUND'));
        incoming.destroy();
      } else chunks.push(chunk);
    });
    incoming.once('error', () => reject(new Error('AUTH_BODY_IO')));
    incoming.once('aborted', () => reject(new Error('AUTH_BODY_IO')));
    incoming.once('end', () => resolve(Buffer.concat(chunks)));
  });
}

export function projectRejection(incoming, code, sequence, requestSequence) {
  return {sequence, requestSequence,
    method: ['GET', 'POST'].includes(incoming.method) ? incoming.method : 'OTHER',
    path: incoming.url === '/mcp' ? 'MCP' : diagnosticPaths.get(incoming.url) ?? 'OTHER',
    code: code === 'AUTH_OAUTH_REFUSED' || FAILURES.includes(code) ? code : 'AUTH_REQUEST_INVALID',
    originAbsent: incoming.headers?.origin === undefined};
}

export async function startFixture({token, mode} = {}) {
  if (typeof token !== 'string' || !/^[a-f0-9]{64}$/.test(token) || !MODES.includes(mode))
    throw new Error('AUTH_INVALID_CONFIGURATION');
  const rows = [];
  const rejections = [];
  const sockets = new Set();
  let requests = 0;
  let failure;
  let port;
  let closed = false;
  const failed = code => { failure ??= FAILURES.includes(code) ? code : 'AUTH_REQUEST_INVALID'; };
  const server = createServer(async (incoming, outgoing) => {
    const requestSequence = ++requests;
    const timer = setTimeout(() => {
      failed('AUTH_EXCHANGE_TIMEOUT');
      incoming.destroy(); outgoing.destroy();
    }, EXCHANGE_MS);
    outgoing.once('close', () => clearTimeout(timer));
    const rejectRequest = (code, status = 400) => {
      if (code !== 'AUTH_OAUTH_REFUSED') failed(code);
      if (rejections.length < MAX_REQUESTS)
        rejections.push(projectRejection(incoming, code, rejections.length + 1, requestSequence));
      outgoing.writeHead(status, {'content-type': 'application/json', 'cache-control': 'no-store'});
      outgoing.end('{"error":"Fixture request refused"}');
    };
    try {
      if (requestSequence > MAX_REQUESTS) throw new Error('AUTH_REQUEST_BOUND');
      if (incoming.headers.host !== `127.0.0.1:${port}`) throw new Error('AUTH_HOST');
      if (incoming.headers.origin !== undefined) throw new Error('AUTH_ORIGIN');
      const length = incoming.headers['content-length'];
      if (length !== undefined && (!/^\d+$/.test(length) || Number(length) > MAX_BODY))
        throw new Error('AUTH_BODY_BOUND');
      const bytes = await collectBody(incoming);
      // All discovery/registration attempts stop here, on this same loopback
      // listener. Their bodies and Authorization values are never retained.
      const oauth = diagnosticPaths.has(incoming.url)
        && incoming.method === (incoming.url === '/register' ? 'POST' : 'GET');
      if (oauth) { rejectRequest('AUTH_OAUTH_REFUSED'); return; }
      if (incoming.url !== '/mcp') throw new Error('AUTH_PATH');
      if (incoming.method !== 'POST') throw new Error('AUTH_METHOD');
      if (incoming.headers.authorization !== `Bearer ${token}`) { rejectRequest('AUTH_REQUIRED', 401); return; }
      if (!/^application\/json(?:;|$)/i.test(incoming.headers['content-type'] ?? '')
          || incoming.headers['mcp-session-id'] !== undefined || incoming.headers['mcp-name'] !== undefined)
        throw new Error('AUTH_HEADERS');
      const request = JSON.parse(bytes.toString('utf8'));
      if (!object(request) || !same(Object.keys(request).sort(), ['id', 'jsonrpc', 'method', 'params'])
          || request.jsonrpc !== '2.0' || !object(request.params)
          || !((typeof request.id === 'string' && request.id.length <= 256)
            || (typeof request.id === 'number' && Number.isSafeInteger(request.id))))
        throw new Error('AUTH_REQUEST_INVALID');
      if (!METHODS.has(request.method)) throw new Error('AUTH_METHOD');
      if (request.params._meta?.[`${META}protocolVersion`] !== PROTOCOL
          || incoming.headers['mcp-protocol-version'] !== PROTOCOL
          || !object(request.params._meta?.[`${META}clientCapabilities`]))
        throw new Error('AUTH_PROTOCOL');
      if (incoming.headers['mcp-method'] !== request.method) throw new Error('AUTH_HEADERS');
      if (!selection(request, mode)) throw new Error('AUTH_SELECTION');
      const subscriptionDenied = request.method === 'subscriptions/listen';
      const response = {jsonrpc: '2.0', id: request.id, ...(subscriptionDenied
        ? {error: {code: -32603, message: 'Internal error'}} : {result: fixtureResult(request.method, mode)})};
      const responseBytes = Buffer.from(JSON.stringify(response));
      if (responseBytes.length > MAX_BODY) throw new Error('AUTH_RESPONSE_BOUND');
      const responseStatus = subscriptionDenied && mode === 'policy-403' ? 403 : 200;
      // Only fixed labels, booleans and bounded counts leave this fixture.
      rows.push({surface: 'inspector-auth', sequence: rows.length + 1, requestSequence,
        method: request.method, authorized: true, requestEnvelopeValid: true,
        protocolMetadataMatches: true, protocolHeaderMatches: true, methodHeaderMatches: true,
        perRequestCapabilitiesPresent: true, noSessionState: true, requestSelectionValid: true,
        responseStatus, responseJson: true, responseNoStore: true, responseCorrelated: true,
        responseEnvelopeValid: true, resultMatchesFixture: true, subscriptionDenied,
        requestBytes: bytes.length, responseBytes: responseBytes.length});
      outgoing.writeHead(responseStatus, {'content-type': 'application/json', 'cache-control': 'no-store'});
      outgoing.end(responseBytes);
    } catch (error) { rejectRequest(error?.message); }
  });
  server.requestTimeout = EXCHANGE_MS;
  server.headersTimeout = EXCHANGE_MS;
  server.maxConnections = MAX_CONNECTIONS;
  server.on('drop', () => failed('AUTH_CONNECTION_BOUND'));
  server.on('clientError', (_error, socket) => { failed('AUTH_REQUEST_INVALID'); socket.destroy(); });
  server.on('connection', socket => {
    sockets.add(socket);
    socket.setTimeout(EXCHANGE_MS, () => socket.destroy());
    socket.once('close', () => sockets.delete(socket));
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  port = server.address().port;
  return {port, rows, rejections, failure: () => failure,
    async close() {
      if (closed) return;
      closed = true;
      for (const socket of sockets) socket.destroy();
      await new Promise(resolve => server.close(resolve));
    }};
}
