import { createServer, request as httpRequest } from 'node:http';
import { isDeepStrictEqual } from 'node:util';

export const PROTOCOL = '2026-07-28';
export const UI = 'io.modelcontextprotocol/ui';
export const MIME = 'text/html;profile=mcp-app';
export const UI_URI = 'ui://soklet/catalog-v1';
const META = 'io.modelcontextprotocol/';
const SKILLS = `${META}skills`;
const MAX_REQUEST = 64 * 1024;
const MAX_RESPONSE = 1024 * 1024;
const MAX_EXCHANGES = 32;
const EXCHANGE_MS = 15000;
const METHODS = new Set(['server/discover', 'tools/list',
  'resources/list', 'resources/templates/list', 'tools/call', 'resources/read', 'subscriptions/listen']);
const ACQUISITIONS = ['resources/list', 'resources/templates/list', 'tools/list', 'tools/list'];
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const same = isDeepStrictEqual;
const keysWithin = (value, keys) => object(value) && Object.keys(value).every(key => keys.includes(key));
const serverMetadata = { [`${META}serverInfo`]: {name: 'soklet-apps-fixture', version: 'fixture-v1'} };
const schema = {type: 'object', properties: {}, additionalProperties: false};
const data = {
  locale: 'en-US', direction: 'ltr', tenant: 'alpha', title: 'Catalog view',
  refreshLabel: 'Refresh catalog', summary: 'Catalog alpha: 1 item.',
  itemLabel: 'Toy <img src=x onerror=alert(1)>', amount: 1234.5, currency: 'USD',
  updatedAt: '2026-09-19T12:00:00Z', timeZone: 'UTC',
};
const betaData = {...data, locale: 'pt-BR', tenant: 'beta', title: 'Catálogo',
  refreshLabel: 'Atualizar catálogo', summary: 'Catálogo beta: 1 item.',
  itemLabel: 'Brinquedo <img src=x onerror=alert(1)>'};
const resource = {uri: UI_URI, name: 'catalog_view', title: 'Catalog view', mimeType: MIME};
const resourceMetadata = (cspOrigin, geolocation) => ({ui: {csp: {connectDomains: cspOrigin ? [cspOrigin] : [],
  resourceDomains: cspOrigin ? [cspOrigin] : [], frameDomains: [], baseUriDomains: []},
  ...(geolocation ? {permissions: {geolocation: {}}} : {}), prefersBorder: true}});
const tools = [
  {name: 'show_catalog', title: 'Show catalog', inputSchema: schema,
    _meta: {ui: {resourceUri: UI_URI, visibility: ['model', 'app']}}},
  {name: 'refresh_catalog', title: 'Refresh catalog', inputSchema: schema,
    _meta: {ui: {visibility: ['app']}}},
];
const byName = entries => [...entries].sort((a, b) => String(a?.name).localeCompare(String(b?.name), 'en'));

// Compare fixture payloads only in memory. Never preserve raw IDs, bodies,
// headers, arguments, response text, shell bytes, or hashes of caller data.
export function validAppsResult(method, result, shell, cspOrigin, callerPhase = 'alpha', geolocation = false) {
  if (!object(result) || result.resultType !== 'complete') return false;
  if (method === 'tools/call') {
    const expected = callerPhase === 'beta' ? betaData : data;
    return keysWithin(result, ['resultType', 'content', 'structuredContent', '_meta'])
      && same(result.content, [{type: 'text', text: expected.summary}])
      && same(result.structuredContent, expected)
      && same(result._meta, {...serverMetadata, 'example/view': 'catalog-v1'});
  }
  if (!same(result._meta, serverMetadata)) return false;
  if (method === 'resources/read')
    return typeof shell === 'string' && shell.length > 0
      && result.ttlMs === 0 && result.cacheScope === 'private'
      && keysWithin(result, ['resultType', 'contents', '_meta', 'ttlMs', 'cacheScope'])
      && same(result.contents, [{uri: UI_URI, mimeType: MIME, text: shell,
        _meta: resourceMetadata(cspOrigin, geolocation)}]);
  const catalog = ['resultType', '_meta', 'ttlMs', 'cacheScope'];
  if (result.ttlMs !== 0 || result.cacheScope !== 'private') return false;
  if (method === 'server/discover')
    return keysWithin(result, [...catalog, 'supportedVersions', 'capabilities'])
      && same(result.supportedVersions, [PROTOCOL])
      && same(result.capabilities, {tools: {listChanged: true}, resources: {}, extensions: {[UI]: {mimeTypes: [MIME]}}});
  if (method === 'tools/list')
    return keysWithin(result, [...catalog, 'tools']) && Array.isArray(result.tools)
      && same(byName(result.tools), byName(tools));
  if (method === 'resources/list')
    return keysWithin(result, [...catalog, 'resources']) && same(result.resources, [resource]);
  return method === 'resources/templates/list'
    && keysWithin(result, [...catalog, 'resourceTemplates']) && same(result.resourceTemplates, []);
}

function validSelection(request) {
  const {method, params} = request ?? {};
  if (!object(params)) return false;
  if (method === 'tools/call')
    return ['show_catalog', 'refresh_catalog'].includes(params.name)
      && same(params.arguments, {}) && keysWithin(params, ['_meta', 'name', 'arguments']);
  if (method === 'resources/read')
    return params.uri === UI_URI && keysWithin(params, ['_meta', 'uri']);
  if (method === 'subscriptions/listen')
    return same(params.notifications, {toolsListChanged: true}) && keysWithin(params, ['_meta', 'notifications']);
  return METHODS.has(method) && keysWithin(params, ['_meta']);
}

export function projectAppsExchange({request, response, headers, status, responseHeaders,
  requestBytes, responseBytes, authorized, authorizationForwarded, sequence, shell, cspOrigin,
  callerPhase = 'alpha', geolocation = false}) {
  const method = METHODS.has(request?.method) ? request.method : 'UNSUPPORTED';
  const capabilities = request?.params?._meta?.[`${META}clientCapabilities`];
  const extensions = capabilities?.extensions;
  const tool = method !== 'tools/call' ? 'NONE'
    : ['show_catalog', 'refresh_catalog'].includes(request.params?.name) ? request.params.name : 'UNSUPPORTED';
  const name = method === 'tools/call' ? request.params?.name
    : method === 'resources/read' ? request.params?.uri : undefined;
  // The pinned client automatically attempts the advertised localized tools
  // list-change subscription. The fixture deliberately denies it; only this
  // exact bounded JSON denial qualifies, never a stream or a generic error.
  const subscription = method === 'subscriptions/listen';
  const deniedTool = callerPhase === 'denied' && method === 'tools/call';
  const revokedAdmission = callerPhase === 'revoked'
    && (method === 'tools/call' || method === 'subscriptions/listen');
  const deniedToolError = deniedTool && status === 400 && object(response?.error)
    && same(Object.keys(response.error).sort(), ['code', 'message'])
    && response.error.code === -32602 && response.error.message === 'Invalid params'
    && !JSON.stringify(response).includes('fixture-private-canary');
  const revokedAdmissionError = revokedAdmission && status === 401 && object(response?.error)
    && same(Object.keys(response.error).sort(), ['code', 'message'])
    && response.error.code === -31901 && response.error.message === 'Authentication required.'
    && !JSON.stringify(response).includes('fixture-private-canary');
  const subscriptionDenied = subscription && status === 403
    && same(response?.error, {code: -32603, message: 'Internal error'});
  return {
    surface: 'apps-web', sequence, method, tool,
    authorized: authorized === true,
    authorizationForwarded: authorizationForwarded === true,
    requestEnvelopeValid: same(Object.keys(request ?? {}).sort(), ['id', 'jsonrpc', 'method', 'params'])
      && request.jsonrpc === '2.0' && ['string', 'number'].includes(typeof request.id)
      && object(request.params),
    protocolMetadataMatches: request?.params?._meta?.[`${META}protocolVersion`] === PROTOCOL,
    protocolHeaderMatches: headers['mcp-protocol-version'] === PROTOCOL,
    methodHeaderMatches: headers['mcp-method'] === request?.method,
    nameHeaderMatches: headers['mcp-name'] === name,
    perRequestCapabilitiesPresent: object(capabilities),
    appsMimeMatches: same(extensions?.[UI], {mimeTypes: [MIME], elicitation: {}}),
    skillsAbsent: !Object.hasOwn(extensions ?? {}, SKILLS),
    noSessionState: headers['mcp-session-id'] === undefined && responseHeaders['mcp-session-id'] === undefined,
    requestSelectionValid: validSelection(request),
    responseStatus: status,
    responseJson: /^application\/json(?:;|$)/i.test(responseHeaders['content-type'] ?? ''),
    responseNoStore: responseHeaders['cache-control'] === 'no-store',
    responseCorrelated: response?.jsonrpc === '2.0' && response.id === request?.id,
    responseEnvelopeValid: object(response) && same(Object.keys(response).sort(),
      subscription || deniedTool || revokedAdmission ? ['error', 'id', 'jsonrpc'] : ['id', 'jsonrpc', 'result']),
    subscriptionDenied,
    resultMatchesFixture: revokedAdmission ? revokedAdmissionError
      : subscription ? subscriptionDenied : deniedTool ? deniedToolError
      : validAppsResult(method, response?.result, shell, cspOrigin, callerPhase, geolocation),
    requestBytes, responseBytes,
  };
}

export function adjudicateAppsTrace(rows) {
  // Separate from the ordinary-tools profile: exactly one initial tool call,
  // exactly one matching shell read (prefetch is permitted), and one follow-up.
  // Four catalog completions and the one exact deny-all subscription rejection
  // may interleave, but must precede the initial tool call. The shell read must
  // complete before the follow-up. No synthetic/direct call proves
  // a DOM click: the runner must independently require browser probe evidence.
  if (!Array.isArray(rows) || rows.length !== 9 || rows.some(row => !object(row))
      || rows[0].method !== 'server/discover' || rows[8].tool !== 'refresh_catalog') return 'FAILED';
  const acquisitions = rows.filter(row => ACQUISITIONS.includes(row.method));
  const show = rows.filter(row => row.tool === 'show_catalog');
  const reads = rows.filter(row => row.method === 'resources/read');
  const subscriptions = rows.filter(row => row.method === 'subscriptions/listen');
  if (!same(acquisitions.map(row => row.method).sort(), ACQUISITIONS)
      || show.length !== 1 || reads.length !== 1 || subscriptions.length !== 1
      || subscriptions[0].sequence >= show[0].sequence
      || acquisitions.some(row => row.sequence >= show[0].sequence)) return 'FAILED';
  const required = ['authorized', 'authorizationForwarded', 'requestEnvelopeValid', 'protocolMetadataMatches',
    'protocolHeaderMatches', 'methodHeaderMatches', 'nameHeaderMatches', 'perRequestCapabilitiesPresent',
    'appsMimeMatches', 'skillsAbsent', 'noSessionState', 'requestSelectionValid', 'responseJson',
    'responseNoStore', 'responseCorrelated', 'responseEnvelopeValid', 'resultMatchesFixture'];
  return rows.every((row, index) => row.surface === 'apps-web' && row.sequence === index + 1
    && METHODS.has(row.method)
    && row.responseStatus === (row.method === 'subscriptions/listen' ? 403 : 200)
    && row.subscriptionDenied === (row.method === 'subscriptions/listen')
    && required.every(key => row[key] === true)
    && (row.method === 'tools/call' ? ['show_catalog', 'refresh_catalog'].includes(row.tool) : row.tool === 'NONE')
    && Number.isSafeInteger(row.requestBytes) && row.requestBytes > 0 && row.requestBytes <= MAX_REQUEST
    && Number.isSafeInteger(row.responseBytes) && row.responseBytes > 0 && row.responseBytes <= MAX_RESPONSE)
    ? 'PASSED' : 'FAILED';
}

export const APPS_PROXY_FAILURES = Object.freeze(['APPS_EXCHANGE_BOUND', 'APPS_POST_ONLY',
  'APPS_PATH', 'APPS_AUTH_REQUIRED', 'APPS_ORIGIN', 'APPS_UNEXPECTED_METHOD', 'APPS_MODERN_PROTOCOL_REQUIRED',
  'APPS_REQUEST_SELECTION', 'APPS_METHOD_HEADER', 'APPS_NAME_HEADER', 'APPS_BODY_BOUND', 'APPS_BODY_IO',
  'APPS_REQUEST_INVALID', 'APPS_UPSTREAM_RESPONSE_INVALID', 'APPS_UPSTREAM_IO', 'APPS_EXCHANGE_TIMEOUT',
  'APPS_CONNECTION_BOUND']);

const diagnosticPaths = new Map([
  ['/mcp', 'MCP'],
  ['/.well-known/oauth-protected-resource/mcp', 'OAUTH_PROTECTED_RESOURCE_PATH'],
  ['/.well-known/oauth-protected-resource', 'OAUTH_PROTECTED_RESOURCE_ROOT'],
  ['/.well-known/oauth-authorization-server', 'OAUTH_AUTHORIZATION_SERVER_ROOT'],
  ['/.well-known/openid-configuration', 'OPENID_CONFIGURATION_ROOT'],
  ['/register', 'OAUTH_REGISTRATION_ROOT'],
]);

export function projectGateRejection(incoming, code, sequence) {
  return {sequence, method: ['GET', 'POST'].includes(incoming.method) ? incoming.method : 'OTHER',
    path: diagnosticPaths.get(incoming.url) ?? 'OTHER',
    code: APPS_PROXY_FAILURES.includes(code) ? code : 'APPS_REQUEST_INVALID',
    originAbsent: incoming.headers?.origin === undefined};
}

function collectBody(stream, maximum) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let count = 0;
    stream.on('data', chunk => {
      count += chunk.length;
      if (count > maximum) {
        reject(new Error('APPS_BODY_BOUND'));
        stream.destroy();
      } else chunks.push(chunk);
    });
    stream.once('error', () => reject(new Error('APPS_BODY_IO')));
    stream.once('aborted', () => reject(new Error('APPS_BODY_IO')));
    stream.once('end', () => resolve(Buffer.concat(chunks)));
  });
}

export async function startAppsProxy({fixturePort, token, shell, allowedOrigin, cspOrigin,
  callerTransitions = false, geolocation = false}) {
  if (!Number.isInteger(fixturePort) || fixturePort < 1 || fixturePort > 65535
      || typeof token !== 'string' || !/^[a-f0-9]{64}$/.test(token)
      || typeof shell !== 'string' || Buffer.byteLength(shell) === 0 || Buffer.byteLength(shell) > 512 * 1024
      || (cspOrigin !== undefined && (typeof cspOrigin !== 'string'
        || !/^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(cspOrigin)
        || Number(cspOrigin.split(':').at(-1)) > 65535))
      || typeof callerTransitions !== 'boolean' || typeof geolocation !== 'boolean'
      || (allowedOrigin !== undefined && (typeof allowedOrigin !== 'string'
        || !/^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(allowedOrigin)
        || Number(allowedOrigin.split(':').at(-1)) > 65535)))
    throw new Error('APPS_INVALID_PROXY_CONFIGURATION');
  const rows = [];
  const rejections = [];
  const sockets = new Set();
  const upstreams = new Set();
  let exchanges = 0;
  let failure;
  let callerPhase = 'alpha';
  const failed = code => { failure ??= APPS_PROXY_FAILURES.includes(code) ? code : 'APPS_REQUEST_INVALID'; };
  const server = createServer(async (incoming, outgoing) => {
    let upstream;
    const deadline = setTimeout(() => {
      failed('APPS_EXCHANGE_TIMEOUT');
      incoming.destroy(); outgoing.destroy(); upstream?.destroy();
    }, EXCHANGE_MS);
    outgoing.once('close', () => { clearTimeout(deadline); upstream?.destroy(); });
    try {
      if (++exchanges > MAX_EXCHANGES) throw new Error('APPS_EXCHANGE_BOUND');
      if (incoming.method !== 'POST') throw new Error('APPS_POST_ONLY');
      if (incoming.url !== '/mcp') throw new Error('APPS_PATH');
      if (incoming.headers.authorization !== `Bearer ${token}`) throw new Error('APPS_AUTH_REQUIRED');
      if (incoming.headers.origin !== undefined && incoming.headers.origin !== allowedOrigin)
        throw new Error('APPS_ORIGIN');
      const body = await collectBody(incoming, MAX_REQUEST);
      const request = JSON.parse(body.toString('utf8'));
      if (!METHODS.has(request?.method)) throw new Error('APPS_UNEXPECTED_METHOD');
      if (incoming.headers['mcp-protocol-version'] !== PROTOCOL
          || request.params?._meta?.[`${META}protocolVersion`] !== PROTOCOL)
        throw new Error('APPS_MODERN_PROTOCOL_REQUIRED');
      if (!validSelection(request)) throw new Error('APPS_REQUEST_SELECTION');
      if (incoming.headers['mcp-method'] !== request.method) throw new Error('APPS_METHOD_HEADER');
      const name = request.method === 'tools/call' ? request.params.name
        : request.method === 'resources/read' ? request.params.uri : undefined;
      if (incoming.headers['mcp-name'] !== name) throw new Error('APPS_NAME_HEADER');
      const requestCallerPhase = callerPhase;
      const headers = {host: `127.0.0.1:${fixturePort}`, 'content-length': String(body.length),
        authorization: incoming.headers.authorization};
      for (const field of ['content-type', 'accept', 'mcp-protocol-version', 'mcp-method',
        'mcp-name', 'accept-language', 'origin'])
        if (incoming.headers[field] !== undefined) headers[field] = incoming.headers[field];
      // Unlike the ordinary-tools gate, this credential reaches Soklet's own
      // admission controller. The runner separately proves invalid credentials
      // fail against that exact listener; neither credential is persisted.
      upstream = httpRequest({host: '127.0.0.1', port: fixturePort, path: '/apps',
        method: 'POST', headers, timeout: 10000}, async response => {
        try {
          const bytes = await collectBody(response, MAX_RESPONSE);
          const value = JSON.parse(bytes.toString('utf8'));
          rows.push(projectAppsExchange({request, response: value, headers: incoming.headers,
            status: response.statusCode, responseHeaders: response.headers,
            requestBytes: body.length, responseBytes: bytes.length, authorized: true,
            authorizationForwarded: headers.authorization === `Bearer ${token}`,
            sequence: rows.length + 1, shell, cspOrigin, callerPhase: requestCallerPhase, geolocation}));
          outgoing.writeHead(response.statusCode, response.headers);
          outgoing.end(bytes);
        } catch { failed('APPS_UPSTREAM_RESPONSE_INVALID'); outgoing.destroy(); }
      });
      upstreams.add(upstream);
      upstream.once('close', () => upstreams.delete(upstream));
      upstream.once('timeout', () => upstream.destroy());
      upstream.once('error', () => { failed('APPS_UPSTREAM_IO'); outgoing.destroy(); });
      upstream.end(body);
    } catch (error) {
      failed(error?.message);
      if (rejections.length < MAX_EXCHANGES)
        rejections.push(projectGateRejection(incoming, error?.message, rejections.length + 1));
      outgoing.writeHead(400, {'content-type': 'application/json', 'cache-control': 'no-store'});
      outgoing.end('{"error":"Apps fixture gate rejected request"}');
    }
  });
  server.requestTimeout = EXCHANGE_MS;
  server.headersTimeout = 10000;
  server.maxConnections = 32;
  server.on('drop', () => failed('APPS_CONNECTION_BOUND'));
  server.on('connection', socket => {
    sockets.add(socket);
    socket.setTimeout(EXCHANGE_MS, () => socket.destroy());
    socket.once('close', () => sockets.delete(socket));
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return {port: server.address().port, rows, rejections, failure: () => failure,
    setCallerPhase(next) {
      if (!callerTransitions || !((callerPhase === 'alpha' && ['beta', 'revoked'].includes(next))
          || (callerPhase === 'beta' && next === 'denied')))
        throw new Error('APPS_INVALID_CALLER_PHASE');
      callerPhase = next;
    },
    async close() {
      for (const upstream of upstreams) upstream.destroy();
      for (const socket of sockets) socket.destroy();
      await new Promise(resolve => server.close(resolve));
    }};
}
