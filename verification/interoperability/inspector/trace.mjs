import { createServer, request as httpRequest } from 'node:http';
import { isDeepStrictEqual } from 'node:util';

export const PROTOCOL = '2026-07-28';
export const UI = 'io.modelcontextprotocol/ui';
export const SKILLS = 'io.modelcontextprotocol/skills';
const META = 'io.modelcontextprotocol/';
const MAX_BODY = 1024 * 1024;
const MAX_EXCHANGES = 32;
const METHODS = new Set(['server/discover', 'tools/list', 'tools/call']);
const WEB_LIST_METHODS = ['prompts/list', 'resources/list', 'resources/templates/list', 'tools/list'];
const WEB_METHODS = new Set([...METHODS, ...WEB_LIST_METHODS]);
const WEB_ACQUISITION_METHODS = [...WEB_LIST_METHODS, 'tools/list'];
const TOOL_NAMES = [
  'json_schema_2020_12_tool', 'test_audio_content', 'test_custom_header',
  'test_embedded_resource', 'test_error_handling', 'test_image_content',
  'test_multiple_content_types', 'test_simple_text', 'test_tool_with_progress',
];
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
// Exact public tools-list fixture descriptors, not arbitrary host data. These
// remain in memory for comparison; the trace never retains descriptor values.
const WEB_LISTS = {
  'prompts/list': { key: 'prompts', entries: [
    { name: 'test_simple_prompt', description: 'Returns a deterministic simple prompt.' },
    { name: 'test_prompt_with_arguments', description: 'Substitutes two required string arguments.',
      arguments: [{ name: 'arg1', description: 'First test argument', required: true },
        { name: 'arg2', description: 'Second test argument', required: true }] },
    { name: 'test_prompt_with_embedded_resource', description: 'Embeds the requested text resource.',
      arguments: [{ name: 'resourceUri', description: 'URI of the resource to embed', required: true }] },
    { name: 'test_prompt_with_image', description: 'Returns deterministic image prompt content.' },
  ] },
  'resources/list': { key: 'resources', entries: [
    { uri: 'test://static-text', name: 'Static text resource',
      description: 'A deterministic UTF-8 text resource.', mimeType: 'text/plain' },
    { uri: 'test://static-binary', name: 'Static binary resource',
      description: 'A deterministic PNG resource.', mimeType: 'image/png' },
  ] },
  'resources/templates/list': { key: 'resourceTemplates', entries: [
    { uriTemplate: 'test://template/{id}/data', name: 'Template data resource',
      description: 'A deterministic RFC 6570 Level 1 template.', mimeType: 'application/json' },
  ] },
};
const sortedByName = entries => [...entries].sort((left, right) =>
  String(left?.name).localeCompare(String(right?.name)));

export function validResult(method, result, surface = 'cli') {
  if (surface !== 'cli' && surface !== 'web') return false;
  if (!object(result) || result.resultType !== 'complete') return false;
  if (method === 'server/discover') {
    return JSON.stringify(result.supportedVersions) === JSON.stringify([PROTOCOL])
      && object(result.capabilities?.tools)
      && (surface !== 'web' || (object(result.capabilities?.prompts)
        && object(result.capabilities?.resources)))
      && !Object.hasOwn(result.capabilities?.extensions ?? {}, UI)
      && !Object.hasOwn(result.capabilities?.extensions ?? {}, SKILLS);
  }
  if (method === 'tools/list') {
    return Array.isArray(result.tools) && result.nextCursor === undefined
      && JSON.stringify(result.tools.map(tool => tool?.name).sort()) === JSON.stringify(TOOL_NAMES);
  }
  if (surface === 'web' && Object.hasOwn(WEB_LISTS, method)) {
    const { key, entries } = WEB_LISTS[method];
    return Array.isArray(result[key]) && result.nextCursor === undefined
      && isDeepStrictEqual(sortedByName(result[key]), sortedByName(entries));
  }
  return method === 'tools/call' && result.isError === undefined
    && result.content?.length === 1 && result.content[0]?.type === 'text'
    && result.content[0].text === 'This is a simple text response for testing.';
}

// Deliberately project booleans/enums/counts, not raw IDs, metadata, arguments,
// headers, response bodies, or hashes of potentially sensitive body bytes.
export function projectExchange({ request, response, headers, status, responseHeaders,
  requestBytes, responseBytes, authorized, enabled, sequence, surface = 'cli' }) {
  if (surface !== 'cli' && surface !== 'web') throw new Error('INVALID_TRACE_SURFACE');
  const methods = surface === 'web' ? WEB_METHODS : METHODS;
  const method = methods.has(request?.method) ? request.method : 'UNSUPPORTED';
  const metadata = request?.params?._meta;
  const capabilities = metadata?.[`${META}clientCapabilities`];
  const extensions = capabilities?.extensions;
  const ui = object(extensions) && Object.hasOwn(extensions, UI);
  const skills = object(extensions) && Object.hasOwn(extensions, SKILLS);
  const uiShape = ui && (surface === 'web'
    ? isDeepStrictEqual(extensions[UI], { mimeTypes: ['text/html;profile=mcp-app'], elicitation: {} })
    : JSON.stringify(extensions[UI]) === JSON.stringify({ mimeTypes: ['text/html;profile=mcp-app'] }));
  const skillsShape = skills && object(extensions[SKILLS])
    && Object.keys(extensions[SKILLS]).length === 0;
  const extensionSelectionMatches = enabled
    ? uiShape && skillsShape : !ui && !skills;
  const row = {
    ...(surface === 'web' ? { surface: 'web' } : {}),
    sequence,
    requestedExtensions: enabled ? 'ENABLED' : 'DISABLED',
    method,
    authorized,
    requestEnvelopeValid: request?.jsonrpc === '2.0' && object(request.params)
      && ['string', 'number'].includes(typeof request.id),
    protocolMetadataMatches: metadata?.[`${META}protocolVersion`] === PROTOCOL,
    protocolHeaderMatches: headers['mcp-protocol-version'] === PROTOCOL,
    methodHeaderMatches: headers['mcp-method'] === request?.method,
    perRequestCapabilitiesPresent: object(capabilities),
    noSessionState: headers['mcp-session-id'] === undefined
      && responseHeaders['mcp-session-id'] === undefined,
    appsAdvertised: Boolean(ui),
    appsMimeMatches: Boolean(uiShape),
    skillsAdvertised: Boolean(skills),
    skillsShapeMatches: Boolean(skillsShape),
    extensionSelectionMatches: Boolean(extensionSelectionMatches),
    toolSelectionValid: method !== 'tools/call' || (request.params?.name === 'test_simple_text'
      && object(request.params.arguments) && Object.keys(request.params.arguments).length === 0),
    responseStatus: status,
    responseJson: /^application\/json(?:;|$)/i.test(responseHeaders['content-type'] ?? ''),
    responseNoStore: responseHeaders['cache-control'] === 'no-store',
    responseCorrelated: response?.jsonrpc === '2.0' && response.id === request?.id,
    resultMatchesFixture: validResult(method, response?.result, surface),
    ...(surface === 'web' ? { responseEnvelopeValid: object(response)
      && isDeepStrictEqual(Object.keys(response).sort(), ['id', 'jsonrpc', 'result']) } : {}),
    requestBytes,
    responseBytes,
  };
  return row;
}

export function adjudicateTrace(rows, enabled, operation) {
  // Exact unmodified 2.7.0 CLI acquisition observed against the candidate:
  // managed tools perform their initial listing and the CLI refreshes again.
  const expectedMethods = operation === 'tools/list' ? ['server/discover', 'tools/list', 'tools/list']
    : operation === 'tools/call' ? ['server/discover', 'tools/list', 'tools/list', 'tools/call'] : null;
  if (!Array.isArray(rows) || expectedMethods === null
      || JSON.stringify(rows.map(row => row.method)) !== JSON.stringify(expectedMethods)) return 'FAILED';
  const required = ['authorized', 'requestEnvelopeValid', 'protocolMetadataMatches',
    'protocolHeaderMatches', 'methodHeaderMatches', 'perRequestCapabilitiesPresent', 'noSessionState',
    'toolSelectionValid', 'responseJson', 'responseNoStore', 'responseCorrelated',
    'resultMatchesFixture'];
  if (rows.some((row, index) => row.sequence !== index + 1 || !METHODS.has(row.method)
      || row.responseStatus !== 200 || required.some(key => row[key] !== true)
      || row.requestedExtensions !== (enabled ? 'ENABLED' : 'DISABLED'))) return 'FAILED';
  if (rows.every(row => row.extensionSelectionMatches)) return 'PASSED';
  // An observed host limitation stays a non-passing row, not an accepted skip.
  if (!enabled && rows.every(row => row.appsAdvertised && row.appsMimeMatches
      && row.skillsAdvertised && row.skillsShapeMatches)) return 'BLOCKED_HOST_EXTENSION_TOGGLE';
  return 'FAILED';
}

export function adjudicateWebTrace(rows, enabled) {
  // Observed with unmodified 2.7.0 web: four managed catalog stores refresh on
  // connect, then selecting Tools refreshes its list again. Their completions
  // may interleave, but discovery must precede them and the one explicit
  // plain-text tool call must follow all five. This profile does not
  // admit subscriptions, Apps resources, Skills retrieval, or extra requests.
  if (typeof enabled !== 'boolean' || !Array.isArray(rows) || rows.length !== 7
      || rows[0]?.method !== 'server/discover' || rows[6]?.method !== 'tools/call'
      || !isDeepStrictEqual(rows.slice(1, 6).map(row => row?.method).sort(), WEB_ACQUISITION_METHODS))
    return 'FAILED';
  const required = ['authorized', 'requestEnvelopeValid', 'protocolMetadataMatches',
    'protocolHeaderMatches', 'methodHeaderMatches', 'perRequestCapabilitiesPresent', 'noSessionState',
    'toolSelectionValid', 'responseJson', 'responseNoStore', 'responseCorrelated',
    'resultMatchesFixture', 'responseEnvelopeValid', 'extensionSelectionMatches'];
  return rows.every((row, index) => row?.surface === 'web' && row.sequence === index + 1
    && WEB_METHODS.has(row.method) && row.responseStatus === 200
    && required.every(key => row[key] === true)
    && row.requestedExtensions === (enabled ? 'ENABLED' : 'DISABLED')
    && row.appsAdvertised === enabled && row.appsMimeMatches === enabled
    && row.skillsAdvertised === enabled && row.skillsShapeMatches === enabled
    && Number.isSafeInteger(row.requestBytes) && row.requestBytes > 0 && row.requestBytes <= MAX_BODY
    && Number.isSafeInteger(row.responseBytes) && row.responseBytes > 0 && row.responseBytes <= MAX_BODY)
    ? 'PASSED' : 'FAILED';
}

function collectBody(stream) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let bytes = 0;
    stream.on('data', chunk => {
      bytes += chunk.length;
      if (bytes > MAX_BODY) {
        reject(new Error('BODY_BOUND'));
        stream.destroy();
      } else chunks.push(chunk);
    });
    stream.once('error', () => reject(new Error('BODY_IO')));
    stream.once('end', () => resolve(Buffer.concat(chunks)));
  });
}

export async function startProxy({ fixturePort, token, enabled, surface = 'cli' }) {
  if (!Number.isInteger(fixturePort) || fixturePort < 1 || fixturePort > 65535
      || !/^[a-f0-9]{64}$/.test(token) || typeof enabled !== 'boolean'
      || (surface !== 'cli' && surface !== 'web'))
    throw new Error('INVALID_PROXY_CONFIGURATION');
  const methods = surface === 'web' ? WEB_METHODS : METHODS;
  const rows = [];
  const sockets = new Set();
  let exchanges = 0;
  let failure;
  const server = createServer(async (incoming, outgoing) => {
    try {
      if (++exchanges > MAX_EXCHANGES) throw new Error('EXCHANGE_BOUND');
      if (incoming.method !== 'POST' || incoming.url !== '/mcp') throw new Error('POST_ONLY');
      const authorized = incoming.headers.authorization === `Bearer ${token}`;
      if (!authorized) throw new Error('DISPOSABLE_AUTH_REQUIRED');
      const body = await collectBody(incoming);
      const request = JSON.parse(body.toString('utf8'));
      // Unknown/legacy methods are never substituted or forwarded as modern.
      if (!methods.has(request?.method)) throw new Error('UNEXPECTED_METHOD');
      if (incoming.headers['mcp-protocol-version'] !== PROTOCOL
          || request.params?._meta?.[`${META}protocolVersion`] !== PROTOCOL)
        throw new Error('MODERN_PROTOCOL_REQUIRED');
      const headers = { host: `127.0.0.1:${fixturePort}`, 'content-length': String(body.length) };
      for (const name of ['content-type', 'accept', 'mcp-protocol-version',
        'mcp-method', 'mcp-name', 'accept-language', 'origin']) {
        if (incoming.headers[name] !== undefined) headers[name] = incoming.headers[name];
      }
      // Authorization terminates at this disposable gate, not at Soklet. This
      // is credential isolation evidence, not a core authentication test.
      const upstream = httpRequest({ host: '127.0.0.1', port: fixturePort,
        path: '/mcp', method: 'POST', headers, timeout: 10000 }, async response => {
        try {
          const bytes = await collectBody(response);
          const value = JSON.parse(bytes.toString('utf8'));
          rows.push(projectExchange({ request, response: value,
            headers: incoming.headers, status: response.statusCode,
            responseHeaders: response.headers, requestBytes: body.length,
            responseBytes: bytes.length, authorized, enabled, sequence: rows.length + 1, surface }));
          outgoing.writeHead(response.statusCode, response.headers);
          outgoing.end(bytes);
        } catch {
          failure ??= 'UPSTREAM_RESPONSE_INVALID';
          outgoing.destroy();
        }
      });
      upstream.once('timeout', () => upstream.destroy());
      upstream.once('error', () => { failure ??= 'UPSTREAM_IO'; outgoing.destroy(); });
      outgoing.once('close', () => upstream.destroy());
      upstream.end(body);
    } catch (error) {
      const known = new Set(['EXCHANGE_BOUND', 'POST_ONLY', 'DISPOSABLE_AUTH_REQUIRED',
        'UNEXPECTED_METHOD', 'MODERN_PROTOCOL_REQUIRED', 'BODY_BOUND', 'BODY_IO']);
      failure ??= known.has(error?.message) ? error.message : 'REQUEST_INVALID';
      outgoing.writeHead(400, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      outgoing.end('{"error":"fixture gate rejected request"}');
    }
  });
  server.requestTimeout = 15000;
  server.headersTimeout = 10000;
  server.on('connection', socket => {
    sockets.add(socket);
    socket.setTimeout(15000, () => socket.destroy());
    socket.once('close', () => sockets.delete(socket));
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return { port: server.address().port, rows, failure: () => failure,
    async close() {
      for (const socket of sockets) socket.destroy();
      await new Promise(resolve => server.close(resolve));
    } };
}
