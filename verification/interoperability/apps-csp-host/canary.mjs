import {createServer} from 'node:http';
import {isDeepStrictEqual as same} from 'node:util';

export const PHASES = Object.freeze(['IDLE', 'CONTROL_BEFORE', 'APP', 'CONTROL_AFTER', 'SEALED']);
export const CANARY_FAILURES = Object.freeze(['CANARY_PHASE_ORDER', 'CANARY_APP_CONTACT', 'CANARY_PHASE_CONTACT',
  'CANARY_REQUEST_BOUND', 'CANARY_CONNECTION_BOUND', 'CANARY_METHOD', 'CANARY_PATH', 'CANARY_HOST',
  'CANARY_CREDENTIAL', 'CANARY_REFERRER', 'CANARY_ORIGIN', 'CANARY_BODY', 'CANARY_BODY_IO', 'CANARY_HEADER_BOUND',
  'CANARY_HTTP_INVALID', 'CANARY_EXCHANGE_TIMEOUT', 'CANARY_SOCKET_TIMEOUT', 'CANARY_SERVER_ERROR',
  'CANARY_CLOSE_TIMEOUT', 'CANARY_CLOSE_ERROR']);
const CONNECT = Buffer.from('soklet-csp-canary', 'ascii');
const IMAGE = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=', 'base64');
const MAX_REQUESTS = 16, MAX_CONNECTIONS = 16, MAX_HEADERS = 8192, EXCHANGE_MS = 5000, CLOSE_MS = 1000;
const CREDENTIAL_HEADERS = ['authorization', 'proxy-authorization', 'cookie', 'cookie2', 'x-api-key', 'x-auth-token', 'x-mcp-remote-auth'];
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const keys = (value, expected) => object(value) && same(Object.keys(value).sort(), [...expected].sort());
const validOrigin = value => value === undefined || value === 'null'
  || typeof value === 'string' && /^http:\/\/127\.0\.0\.1:[1-9][0-9]{0,4}$/.test(value)
    && Number(value.split(':').at(-1)) <= 65535;

export function adjudicateCanary(facts) {
  if (!keys(facts, ['phase', 'closed', 'closeClean', 'failure', 'requestCount', 'connectionCount', 'phases', 'requests'])
      || facts.phase !== 'SEALED' || facts.closed !== true || facts.closeClean !== true || facts.failure !== null
      || facts.requestCount !== 4 || !Number.isSafeInteger(facts.connectionCount)
      || facts.connectionCount < 1 || facts.connectionCount > MAX_CONNECTIONS
      || !keys(facts.phases, PHASES) || !Array.isArray(facts.requests) || facts.requests.length !== 4) return 'FAILED';
  for (const phase of PHASES) {
    const control = phase === 'CONTROL_BEFORE' || phase === 'CONTROL_AFTER';
    if (!same(facts.phases[phase], {connect: control ? 1 : 0, image: control ? 1 : 0, rejected: 0})) return 'FAILED';
  }
  const expected = ['CONTROL_BEFORE', 'CONTROL_BEFORE', 'CONTROL_AFTER', 'CONTROL_AFTER'];
  const operations = [];
  for (let index = 0; index < expected.length; ++index) {
    const row = facts.requests[index];
    if (!keys(row, ['sequence', 'phase', 'operation', 'method', 'accepted', 'code'])
        || row.sequence !== index + 1 || row.phase !== expected[index]
        || !['CONNECT', 'IMAGE'].includes(row.operation) || row.method !== 'GET'
        || row.accepted !== true || row.code !== 'OK') return 'FAILED';
    operations.push(row.operation);
  }
  return same(operations.slice(0, 2).sort(), ['CONNECT', 'IMAGE'])
    && same(operations.slice(2).sort(), ['CONNECT', 'IMAGE']) ? 'PASSED' : 'FAILED';
}

/** A live local observer, never a policy-enforcement substitute. A valid App
 * request gets the same successful response as controls and fails the receipt.
 * Both exact image URLs use the same handler and bytes in every phase. The
 * fixed after-control alias prevents browser Image memory reuse from replacing
 * that final server-liveness observation; all other query strings are rejected. */
export async function startCanary() {
  let phaseIndex = 0, failure = null, requestCount = 0, connectionCount = 0;
  let closed = false, closeClean = false, closePromise;
  const phases = Object.fromEntries(PHASES.map(phase => [phase, {connect: 0, image: 0, rejected: 0}]));
  const requests = [], sockets = new Set(), pending = new Set(), socketState = new WeakMap();
  const failed = code => {failure ??= CANARY_FAILURES.includes(code) ? code : 'CANARY_SERVER_ERROR';};
  const phase = () => PHASES[phaseIndex];
  const increment = (counter, field) => {counter[field] = Math.min(MAX_REQUESTS + 1, counter[field] + 1);};
  function observed({atPhase, operation, method, accepted, code, sequence}) {
    increment(phases[atPhase], accepted ? operation === 'CONNECT' ? 'connect' : 'image' : 'rejected');
    if (sequence <= MAX_REQUESTS) requests.push({sequence, phase: atPhase, operation, method, accepted, code});
    if (code !== 'OK') failed(code);
    if (atPhase === 'APP') failed('CANARY_APP_CONTACT');
    else if (atPhase === 'IDLE' || atPhase === 'SEALED') failed('CANARY_PHASE_CONTACT');
  }
  const server = createServer({maxHeaderSize: MAX_HEADERS, requestTimeout: EXCHANGE_MS,
    headersTimeout: EXCHANGE_MS, connectionsCheckingInterval: 100}, (incoming, outgoing) => {
    const atPhase = phase();
    const connection = socketState.get(incoming.socket);
    if (connection) {connection.seenRequest = true; ++connection.activeRequests;}
    requestCount = Math.min(MAX_REQUESTS + 1, requestCount + 1);
    const sequence = requestCount;
    const operation = incoming.url === '/connect' ? 'CONNECT'
      : ['/image', '/image?control=after'].includes(incoming.url) ? 'IMAGE' : 'OTHER';
    const method = incoming.method === 'GET' ? 'GET' : 'OTHER';
    let done = false;
    const timer = setTimeout(() => finish('CANARY_EXCHANGE_TIMEOUT', true), EXCHANGE_MS);
    pending.add(timer);
    function finish(code, destroy = false) {
      if (done) return;
      done = true; clearTimeout(timer); pending.delete(timer);
      if (connection) --connection.activeRequests;
      const accepted = code === 'OK';
      observed({atPhase, operation, method, accepted, code, sequence});
      if (destroy) {incoming.destroy(); outgoing.destroy(); return;}
      const body = accepted ? operation === 'CONNECT' ? CONNECT : IMAGE : Buffer.from('canary rejected request', 'ascii');
      outgoing.writeHead(accepted ? 200 : 400, {'content-type': accepted && operation === 'IMAGE' ? 'image/png' : 'text/plain;charset=utf-8',
        'content-length': String(body.length), 'cache-control': 'no-store', 'access-control-allow-origin': '*',
        ...(!accepted ? {connection: 'close'} : {})});
      // Rejected bodies are never buffered, drained without a bound, reflected
      // or forwarded. Close the owned connection after the fixed reply flushes.
      if (!accepted) outgoing.once('finish', () => incoming.socket.destroy());
      outgoing.end(body);
    }
    outgoing.once('close', () => {
      clearTimeout(timer); pending.delete(timer);
      if (!done) finish('CANARY_BODY_IO', true);
    });
    if (requestCount > MAX_REQUESTS) return finish('CANARY_REQUEST_BOUND');
    if (method !== 'GET') return finish('CANARY_METHOD');
    if (operation === 'OTHER') return finish('CANARY_PATH');
    if (incoming.headers.host !== `127.0.0.1:${server.address()?.port}`) return finish('CANARY_HOST');
    if (CREDENTIAL_HEADERS.some(name => incoming.headers[name] !== undefined)) return finish('CANARY_CREDENTIAL');
    if (incoming.headers.referer !== undefined || incoming.headers.referrer !== undefined) return finish('CANARY_REFERRER');
    if (!validOrigin(incoming.headers.origin)) return finish('CANARY_ORIGIN');
    if (incoming.headers['transfer-encoding'] !== undefined
        || incoming.headers['content-length'] !== undefined && incoming.headers['content-length'] !== '0') return finish('CANARY_BODY');
    incoming.on('data', () => finish('CANARY_BODY', true));
    incoming.once('aborted', () => finish('CANARY_BODY_IO', true));
    incoming.once('error', () => finish('CANARY_BODY_IO', true));
    incoming.once('end', () => finish('OK'));
    incoming.resume();
  });
  server.maxConnections = MAX_CONNECTIONS;
  server.keepAliveTimeout = EXCHANGE_MS;
  server.keepAliveTimeoutBuffer = 0;
  server.on('connection', socket => {
    connectionCount = Math.min(MAX_CONNECTIONS + 1, connectionCount + 1);
    sockets.add(socket);
    const state = {seenRequest: false, activeRequests: 0}; socketState.set(socket, state);
    socket.once('close', () => sockets.delete(socket));
    if (connectionCount > MAX_CONNECTIONS) {failed('CANARY_CONNECTION_BOUND'); socket.destroy(); return;}
    socket.setTimeout(EXCHANGE_MS, () => {
      // Idle keep-alive expiration after a completed positive control is an
      // ordinary clean connection retirement, not a failed CSP observation.
      if (!state.seenRequest || state.activeRequests > 0) failed('CANARY_SOCKET_TIMEOUT');
      socket.destroy();
    });
  });
  server.on('drop', () => {connectionCount = MAX_CONNECTIONS + 1; failed('CANARY_CONNECTION_BOUND');});
  server.on('clientError', (error, socket) => {
    requestCount = Math.min(MAX_REQUESTS + 1, requestCount + 1);
    observed({atPhase: phase(), operation: 'OTHER', method: 'OTHER', accepted: false, sequence: requestCount,
      code: error?.code === 'HPE_HEADER_OVERFLOW' ? 'CANARY_HEADER_BOUND' : 'CANARY_HTTP_INVALID'});
    socket.destroy();
  });
  server.on('error', () => failed('CANARY_SERVER_ERROR'));
  await new Promise((resolve, reject) => {
    server.once('error', reject); server.listen(0, '127.0.0.1', () => {server.off('error', reject); resolve();});
  });
  const origin = `http://127.0.0.1:${server.address().port}`;
  return Object.freeze({origin, connectUrl: origin + '/connect', imageUrl: origin + '/image',
    setPhase(next) {
      if (closed || closePromise || typeof next !== 'string' || !PHASES.includes(next)
          || phaseIndex >= PHASES.length - 1 || PHASES[phaseIndex + 1] !== next) {
        failed('CANARY_PHASE_ORDER'); throw new Error('CANARY_PHASE_ORDER');
      }
      ++phaseIndex;
    },
    facts() {
      // Only known keys leave state. Both counters saturate at one beyond the
      // limits, and at most sixteen fixed-shape observations are retained.
      return {phase: phase(), closed, closeClean, failure, requestCount, connectionCount,
        phases: structuredClone(phases), requests: requests.map(row => ({...row})).sort((a, b) => a.sequence - b.sequence)};
    },
    failure: () => failure,
    close() {
      if (closePromise) return closePromise;
      closePromise = new Promise((resolve, reject) => {
        let settled = false;
        const deadline = setTimeout(() => finish('CANARY_CLOSE_TIMEOUT'), CLOSE_MS);
        const finish = code => {
          if (settled) return;
          settled = true; clearTimeout(deadline);
          for (const timer of pending) clearTimeout(timer);
          pending.clear();
          if (code) {failed(code); reject(new Error(code));}
          else {closed = true; closeClean = true; resolve();}
        };
        server.close(error => finish(error ? 'CANARY_CLOSE_ERROR' : null));
        for (const socket of sockets) socket.destroy();
      });
      return closePromise;
    }});
}
