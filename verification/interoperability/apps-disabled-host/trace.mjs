import { createServer, request as httpRequest } from 'node:http';
import { isDeepStrictEqual } from 'node:util';
import { PROTOCOL, UI, projectAppsExchange, validAppsResult } from '../apps/host-trace.mjs';

export { PROTOCOL, UI } from '../apps/host-trace.mjs';
export const SKILLS = 'io.modelcontextprotocol/skills';
const META = 'io.modelcontextprotocol/';
const MAX_REQUEST = 64 * 1024;
const MAX_RESPONSE = 1024 * 1024;
const MAX_EXCHANGES = 32;
const EXCHANGE_MS = 15000;
const METHODS = new Set(['server/discover', 'tools/list', 'resources/list', 'resources/templates/list',
  'tools/call', 'subscriptions/listen']);
const ACQUISITIONS = ['resources/list', 'resources/templates/list', 'tools/list', 'tools/list'];
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const same = isDeepStrictEqual;
const keysWithin = (value, keys) => object(value) && Object.keys(value).every(key => keys.includes(key));
const serverMetadata = {[`${META}serverInfo`]: {name: 'soklet-apps-fixture', version: 'fixture-v1'}};
const tool = {name: 'show_catalog', title: 'Show catalog',
  inputSchema: {type: 'object', properties: {}, additionalProperties: false}};

// OFF is per-request capability negotiation, not resource authorization. The
// server's discovery and ordinary resource catalog remain unchanged. A browser
// in this profile must execute the ordinary tool without fetching its UI shell.
export function validAppsDisabledResult(method, result, shell) {
  if (method !== 'tools/list') return validAppsResult(method, result, shell);
  return keysWithin(result, ['resultType', '_meta', 'ttlMs', 'cacheScope', 'tools'])
    && result.resultType === 'complete' && same(result._meta, serverMetadata)
    && result.ttlMs === 0 && result.cacheScope === 'private' && same(result.tools, [tool]);
}

function extensionAbsent(extensions, key) {
  return extensions === undefined || (object(extensions) && !Object.hasOwn(extensions, key));
}

function selection(request) {
  const {method, params} = request ?? {};
  if (!object(params)) return false;
  if (method === 'tools/call') return params.name === 'show_catalog' && same(params.arguments, {})
    && keysWithin(params, ['_meta', 'name', 'arguments']);
  if (method === 'subscriptions/listen') return same(params.notifications, {toolsListChanged: true})
    && keysWithin(params, ['_meta', 'notifications']);
  return METHODS.has(method) && keysWithin(params, ['_meta']);
}

export function projectAppsDisabledExchange(input) {
  const row = projectAppsExchange(input);
  const extensions = input.request?.params?._meta?.[`${META}clientCapabilities`]?.extensions;
  const {appsMimeMatches, ...projected} = row;
  return {...projected, surface: 'apps-disabled-web', requestedExtensions: 'DISABLED',
    method: METHODS.has(input.request?.method) ? input.request.method : 'UNSUPPORTED',
    tool: input.request?.method !== 'tools/call' ? 'NONE'
      : input.request?.params?.name === 'show_catalog' ? 'show_catalog' : 'UNSUPPORTED',
    appsAbsent: extensionAbsent(extensions, UI), skillsAbsent: extensionAbsent(extensions, SKILLS),
    requestSelectionValid: selection(input.request),
    resultMatchesFixture: input.request?.method === 'subscriptions/listen' ? row.subscriptionDenied
      : METHODS.has(input.request?.method) && validAppsDisabledResult(input.request.method, input.response?.result, input.shell)};
}

const REQUIRED = ['authorized', 'authorizationForwarded', 'requestEnvelopeValid', 'protocolMetadataMatches',
  'protocolHeaderMatches', 'methodHeaderMatches', 'nameHeaderMatches', 'perRequestCapabilitiesPresent',
  'appsAbsent', 'skillsAbsent', 'noSessionState', 'requestSelectionValid', 'responseJson', 'responseNoStore',
  'responseCorrelated', 'responseEnvelopeValid', 'resultMatchesFixture'];
const FIELDS = new Set(['surface', 'sequence', 'requestedExtensions', 'method', 'tool', ...REQUIRED,
  'responseStatus', 'subscriptionDenied', 'requestBytes', 'responseBytes']);

export function adjudicateAppsDisabledTrace(rows) {
  if (!Array.isArray(rows) || rows.length < 7 || rows.length > 14
      || !rows.every((row, index) => object(row)
        && Object.keys(row).length === FIELDS.size && Object.keys(row).every(key => FIELDS.has(key))
        && row.sequence === index + 1 && row.surface === 'apps-disabled-web' && row.requestedExtensions === 'DISABLED'
        && METHODS.has(row.method) && REQUIRED.every(key => row[key] === true)
        && row.responseStatus === (row.method === 'subscriptions/listen' ? 403 : 200)
        && row.subscriptionDenied === (row.method === 'subscriptions/listen')
        && row.tool === (row.method === 'tools/call' ? 'show_catalog' : 'NONE')
        && Number.isSafeInteger(row.requestBytes) && row.requestBytes > 0 && row.requestBytes <= MAX_REQUEST
        && Number.isSafeInteger(row.responseBytes) && row.responseBytes > 0 && row.responseBytes <= MAX_RESPONSE)) return 'FAILED';
  const denials = rows.filter(row => row.method === 'subscriptions/listen');
  const positives = rows.filter(row => row.method !== 'subscriptions/listen');
  // Pinned web host: connect acquires each advertised catalog; selecting Tools
  // refreshes tools/list once more. Resources remain advertised with Apps OFF.
  // No flexible catalog counts, UI reads, or implicit helper calls qualify.
  return denials.length >= 1 && denials.length <= 8 && positives.length === 6
    && rows[0].method === 'server/discover' && positives[5].tool === 'show_catalog'
    && same(positives.slice(1, 5).map(row => row.method).sort(), ACQUISITIONS)
    && denials[0].sequence < positives[5].sequence ? 'PASSED' : 'FAILED';
}

export const APPS_DISABLED_PROXY_FAILURES = Object.freeze(['APPS_DISABLED_EXCHANGE_BOUND',
  'APPS_DISABLED_POST_ONLY', 'APPS_DISABLED_PATH', 'APPS_DISABLED_AUTH_REQUIRED', 'APPS_DISABLED_ORIGIN',
  'APPS_DISABLED_HOST', 'APPS_DISABLED_UNEXPECTED_METHOD', 'APPS_DISABLED_MODERN_PROTOCOL_REQUIRED',
  'APPS_DISABLED_EXTENSION_SELECTION', 'APPS_DISABLED_REQUEST_SELECTION', 'APPS_DISABLED_METHOD_HEADER',
  'APPS_DISABLED_NAME_HEADER', 'APPS_DISABLED_BODY_BOUND', 'APPS_DISABLED_BODY_IO', 'APPS_DISABLED_REQUEST_INVALID',
  'APPS_DISABLED_UPSTREAM_RESPONSE_INVALID', 'APPS_DISABLED_UPSTREAM_IO', 'APPS_DISABLED_EXCHANGE_TIMEOUT',
  'APPS_DISABLED_CONNECTION_BOUND']);
const diagnosticPaths = new Map([
  ['/mcp', 'MCP'],
  ['/.well-known/oauth-protected-resource/mcp', 'OAUTH_PROTECTED_RESOURCE_PATH'],
  ['/.well-known/oauth-protected-resource', 'OAUTH_PROTECTED_RESOURCE_ROOT'],
  ['/.well-known/oauth-authorization-server', 'OAUTH_AUTHORIZATION_SERVER_ROOT'],
  ['/.well-known/openid-configuration', 'OPENID_CONFIGURATION_ROOT'],
  ['/register', 'OAUTH_REGISTRATION_ROOT'],
]);

export function projectDisabledGateRejection(incoming, code, sequence) {
  return {sequence, method: ['GET', 'POST'].includes(incoming.method) ? incoming.method : 'OTHER',
    path: diagnosticPaths.get(incoming.url) ?? 'OTHER',
    code: APPS_DISABLED_PROXY_FAILURES.includes(code) ? code : 'APPS_DISABLED_REQUEST_INVALID',
    originAbsent: incoming.headers?.origin === undefined};
}

function collectBody(stream, maximum) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let count = 0;
    stream.on('data', chunk => {
      count += chunk.length;
      if (count > maximum) { reject(new Error('APPS_DISABLED_BODY_BOUND')); stream.destroy(); }
      else chunks.push(chunk);
    });
    stream.once('error', () => reject(new Error('APPS_DISABLED_BODY_IO')));
    stream.once('aborted', () => reject(new Error('APPS_DISABLED_BODY_IO')));
    stream.once('end', () => resolve(Buffer.concat(chunks)));
  });
}

export async function startAppsDisabledProxy({fixturePort, token, shell}) {
  if (!Number.isInteger(fixturePort) || fixturePort < 1 || fixturePort > 65535
      || typeof token !== 'string' || !/^[a-f0-9]{64}$/.test(token)
      || typeof shell !== 'string' || Buffer.byteLength(shell) === 0 || Buffer.byteLength(shell) > 512 * 1024)
    throw new Error('APPS_DISABLED_INVALID_PROXY_CONFIGURATION');
  const rows = [], rejections = [], sockets = new Set(), upstreams = new Set();
  let exchanges = 0, failure, closing;
  const failed = code => { failure ??= APPS_DISABLED_PROXY_FAILURES.includes(code) ? code : 'APPS_DISABLED_REQUEST_INVALID'; };
  const server = createServer(async (incoming, outgoing) => {
    let upstream;
    const deadline = setTimeout(() => {
      failed('APPS_DISABLED_EXCHANGE_TIMEOUT'); incoming.destroy(); outgoing.destroy(); upstream?.destroy();
    }, EXCHANGE_MS);
    outgoing.once('close', () => { clearTimeout(deadline); upstream?.destroy(); });
    try {
      if (++exchanges > MAX_EXCHANGES) throw new Error('APPS_DISABLED_EXCHANGE_BOUND');
      if (incoming.method !== 'POST') throw new Error('APPS_DISABLED_POST_ONLY');
      if (incoming.url !== '/mcp') throw new Error('APPS_DISABLED_PATH');
      if (incoming.headers.host !== `127.0.0.1:${server.address().port}`) throw new Error('APPS_DISABLED_HOST');
      if (incoming.headers.authorization !== `Bearer ${token}`) throw new Error('APPS_DISABLED_AUTH_REQUIRED');
      if (incoming.headers.origin !== undefined) throw new Error('APPS_DISABLED_ORIGIN');
      const body = await collectBody(incoming, MAX_REQUEST);
      const request = JSON.parse(body.toString('utf8'));
      if (!object(request) || !same(Object.keys(request).sort(), ['id', 'jsonrpc', 'method', 'params'])
          || request.jsonrpc !== '2.0' || !['string', 'number'].includes(typeof request.id) || !object(request.params))
        throw new Error('APPS_DISABLED_REQUEST_INVALID');
      if (!METHODS.has(request.method)) throw new Error('APPS_DISABLED_UNEXPECTED_METHOD');
      if (incoming.headers['mcp-protocol-version'] !== PROTOCOL || request.params?._meta?.[`${META}protocolVersion`] !== PROTOCOL)
        throw new Error('APPS_DISABLED_MODERN_PROTOCOL_REQUIRED');
      const capabilities = request.params?._meta?.[`${META}clientCapabilities`];
      if (!object(capabilities) || !extensionAbsent(capabilities.extensions, UI) || !extensionAbsent(capabilities.extensions, SKILLS))
        throw new Error('APPS_DISABLED_EXTENSION_SELECTION');
      if (!selection(request)) throw new Error('APPS_DISABLED_REQUEST_SELECTION');
      if (incoming.headers['mcp-method'] !== request.method) throw new Error('APPS_DISABLED_METHOD_HEADER');
      const name = request.method === 'tools/call' ? request.params.name : undefined;
      if (incoming.headers['mcp-name'] !== name) throw new Error('APPS_DISABLED_NAME_HEADER');
      const headers = {host: `127.0.0.1:${fixturePort}`, 'content-length': String(body.length),
        authorization: incoming.headers.authorization};
      for (const field of ['content-type', 'accept', 'mcp-protocol-version', 'mcp-method', 'mcp-name', 'accept-language'])
        if (incoming.headers[field] !== undefined) headers[field] = incoming.headers[field];
      upstream = httpRequest({host: '127.0.0.1', port: fixturePort, path: '/apps', method: 'POST', headers, timeout: 10000},
        async response => {
          try {
            const bytes = await collectBody(response, MAX_RESPONSE);
            const value = JSON.parse(bytes.toString('utf8'));
            rows.push(projectAppsDisabledExchange({request, response: value, headers: incoming.headers,
              status: response.statusCode, responseHeaders: response.headers, requestBytes: body.length,
              responseBytes: bytes.length, authorized: true, authorizationForwarded: headers.authorization === `Bearer ${token}`,
              sequence: rows.length + 1, shell}));
            outgoing.writeHead(response.statusCode, response.headers); outgoing.end(bytes);
          } catch { failed('APPS_DISABLED_UPSTREAM_RESPONSE_INVALID'); outgoing.destroy(); }
        });
      upstreams.add(upstream);
      upstream.once('close', () => upstreams.delete(upstream));
      upstream.once('timeout', () => upstream.destroy());
      upstream.once('error', () => { failed('APPS_DISABLED_UPSTREAM_IO'); outgoing.destroy(); });
      upstream.end(body);
    } catch (error) {
      failed(error?.message);
      if (rejections.length < MAX_EXCHANGES)
        rejections.push(projectDisabledGateRejection(incoming, error?.message, rejections.length + 1));
      outgoing.writeHead(400, {'content-type': 'application/json', 'cache-control': 'no-store'});
      outgoing.end('{"error":"Apps-disabled fixture gate rejected request"}');
    }
  });
  server.requestTimeout = EXCHANGE_MS;
  server.headersTimeout = 10000;
  server.maxConnections = 32;
  server.on('drop', () => failed('APPS_DISABLED_CONNECTION_BOUND'));
  server.on('connection', socket => {
    sockets.add(socket); socket.setTimeout(EXCHANGE_MS, () => socket.destroy());
    socket.once('close', () => sockets.delete(socket));
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  return {port: server.address().port, rows, rejections, failure: () => failure,
    close() {
      return closing ??= (async () => {
        for (const upstream of upstreams) upstream.destroy();
        for (const socket of sockets) socket.destroy();
        await new Promise(resolve => server.close(resolve));
      })();
    }};
}
