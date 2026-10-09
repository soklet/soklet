import assert from 'node:assert/strict';
import { existsSync, mkdirSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { boundedCollector, boundedLineReader } from '../run.mjs';

export const runtimeSupplementChecks = Object.freeze([
  'session-initialize-and-current-bearer-get',
  'exact-and-template-uri-delivery',
  'catalog-invalidation-and-fresh-list',
  'same-session-get-credential-refresh',
  'get-does-not-refresh-historical-uri-evidence',
  'duplicate-subscribe-replaces-uri-credentials',
  'retired-session-404-and-fresh-resubscribe-restores-uri-delivery',
  'quiet-renewal-retains-context-without-uri-replay',
  'get-gap-delivers-fresh-dirty-uri-hint',
  'unsubscribe-stops-uri-delivery',
  'uri-revocation-retires-session-and-closes-its-gets',
  'revoked-reconnect-returns-bearer-invalid-token',
  'same-owner-authorized-delete-returns-204',
  'notification-wire-has-no-modern-or-replay-fields',
  'fixture-shutdown-is-clean',
]);
const sleep = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
async function within(promise, milliseconds, description) {
  let timer;
  try {
    return await Promise.race([promise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${description} timed out`)), milliseconds);
    })]);
  } finally { clearTimeout(timer); }
}
async function waitFor(predicate, description) {
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) { if (predicate()) return; await sleep(25); }
  assert.ok(predicate(), description);
}

/** Raw HTTP supplement; it does not qualify any named SDK or host. */
export async function runRuntimeSupplement(options, supervisor) {
  const output = resolve(options.workDirectory, 'runtime');
  mkdirSync(output);
  const child = supervisor.spawn(options.javaExecutable,
    ['-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', options.classpath,
      'com.soklet.conformance.legacy.McpLegacyRuntimeFixture', output], {
      cwd: options.workDirectory, shell: false, stdio: ['ignore', 'pipe', 'pipe'],
    });
  const lines = boundedLineReader(child.stdout, 'legacy runtime stdout');
  const stderr = boundedCollector(child.stderr, 'legacy runtime stderr');
  child.once('error', error => lines.fail(error));
  const streams = [];
  const results = [];
  let failure;
  let clean = false;
  try {
    const ready = JSON.parse(await lines.next(10000));
    assert.deepEqual(Object.keys(ready), ['ready', 'port']);
    assert.equal(ready.ready, true);
    assert.ok(Number.isInteger(ready.port) && ready.port > 0 && ready.port < 65536);
    const credentialsPath = resolve(output, 'credentials.json');
    const credentials = JSON.parse(readFileSync(credentialsPath));
    unlinkSync(credentialsPath); // Short-lived credentials are never retained in the receipt.
    const events = () => {
      const file = resolve(output, 'events.ndjson');
      return existsSync(file) ? readFileSync(file, 'utf8').split('\n').filter(Boolean).map(JSON.parse) : [];
    };
    let sequence = 0;
    const command = async (revision, operation) => {
      const id = ++sequence;
      writeFileSync(resolve(output, 'command'), `${id}|${revision}|${operation}`, { flag: 'wx' });
      const ack = resolve(output, `ack-${id}`);
      await waitFor(() => existsSync(ack), `fixture command ${operation}`);
      const result = JSON.parse(readFileSync(ack)); unlinkSync(ack); return result;
    };
    for (const revision of ['2025-06-18', '2025-11-25']) {
      const url = `http://127.0.0.1:${ready.port}/mcp-${revision}`;
      const tokens = credentials[revision];
      const received = [];
      let sessionId;
      let rpcId = 0;
      const headers = label => ({ Authorization: `Bearer ${tokens[label]}`,
        'MCP-Protocol-Version': revision, Accept: 'application/json, text/event-stream',
        ...(sessionId ? { 'Mcp-Session-Id': sessionId } : {}) });
      const request = (method, label, body, signal) => fetch(url, {
        method, headers: { ...headers(label), ...(body ? { 'Content-Type': 'application/json' } : {}) },
        ...(body ? { body: JSON.stringify(body) } : {}), signal: signal ?? AbortSignal.timeout(5000),
      });
      const rpc = async (method, params = {}, label = 'B') => {
        const id = ++rpcId;
        const response = await request('POST', label, { jsonrpc: '2.0', id, method, params });
        assert.equal(response.status, 200);
        const message = await response.json(); assert.equal(message.id, id); assert.equal(message.jsonrpc, '2.0');
        assert.ok(!message.error, JSON.stringify(message.error)); return message.result;
      };
      const initializeSession = async label => {
        const previousId = sessionId;
        sessionId = undefined;
        const initialize = await request('POST', label, { jsonrpc: '2.0', id: ++rpcId, method: 'initialize',
          params: { protocolVersion: revision, capabilities: {}, clientInfo: { name: 'soklet-legacy-release-supplement', version: '1' } } });
        assert.equal(initialize.status, 200); assert.equal((await initialize.json()).result.protocolVersion, revision);
        sessionId = initialize.headers.get('mcp-session-id'); assert.match(sessionId, /^[\x21-\x7e]+$/);
        if (previousId) assert.notEqual(sessionId, previousId, 'Reinitialization must allocate a fresh session');
        const initialized = await request('POST', label, { jsonrpc: '2.0', method: 'notifications/initialized' });
        assert.equal(initialized.status, 202); await initialized.arrayBuffer();
      };
      await initializeSession('A');
      const openGet = async label => {
        const abort = new AbortController();
        let response;
        try { response = await within(request('GET', label, undefined, abort.signal), 5000, 'GET opening'); }
        catch (error) { abort.abort(); throw error; }
        assert.equal(response.status, 200); assert.ok(response.headers.get('content-type').includes('text/event-stream'));
        const stream = { abort, ended: false, failure: null };
        streams.push(stream);
        stream.done = (async () => {
          const decoder = new TextDecoder(); let pending = ''; let bytes = 0;
          try {
            for await (const chunk of response.body) {
              bytes += chunk.length; assert.ok(bytes <= 1024 * 1024, 'Bounded GET capture');
              pending += decoder.decode(chunk, { stream: true }).replaceAll('\r\n', '\n');
              assert.ok(pending.length <= 65536, 'Bounded SSE frame');
              let boundary;
              while ((boundary = pending.indexOf('\n\n')) >= 0) {
                const frame = pending.slice(0, boundary); pending = pending.slice(boundary + 2);
                assert.doesNotMatch(frame, /^(?:id|retry):/m, 'Legacy GET must not imply replay');
                const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
                if (!data) continue;
                const message = JSON.parse(data);
                assert.equal(message.jsonrpc, '2.0');
                assert.ok(['notifications/tools/list_changed', 'notifications/prompts/list_changed',
                  'notifications/resources/list_changed', 'notifications/resources/updated'].includes(message.method));
                assert.ok(Object.keys(message).every(key => ['jsonrpc', 'method', 'params'].includes(key)));
                if (message.method === 'notifications/resources/updated') assert.deepEqual(Object.keys(message.params), ['uri']);
                else assert.deepEqual(message.params ?? {}, {});
                received.push(message); assert.ok(received.length <= 64, 'Bounded notification count');
              }
            }
            assert.equal(pending, '', 'Clean GET EOF must end at a frame boundary');
            stream.ended = true;
          } catch (error) { if (!abort.signal.aborted) stream.failure = error; }
        })();
        return stream;
      };
      const exact = 'fixture:///catalog', template = 'fixture:///item/example';
      const count = (method, uri) => received.filter(message => message.method === method && (!uri || message.params.uri === uri)).length;
      const updates = uri => count('notifications/resources/updated', uri);
      const getA = await openGet('A');
      await rpc('resources/subscribe', { uri: exact }, 'A'); await rpc('resources/subscribe', { uri: template }, 'A');
      await command(revision, 'resource'); await command(revision, 'template');
      await waitFor(() => updates(exact) === 1 && updates(template) === 1, 'Initial URI hints');
      await rpc('tools/list', {}, 'A'); await rpc('prompts/list', {}, 'A'); await rpc('resources/list', {}, 'A');
      await command(revision, 'tools'); await command(revision, 'prompts'); await command(revision, 'resources');
      await waitFor(() => ['tools', 'prompts', 'resources'].every(family => count(`notifications/${family}/list_changed`) >= 1), 'Catalog hints');
      assert.ok((await rpc('tools/list', {}, 'A')).tools.some(tool => tool.name === 'catalog_after'));
      assert.ok((await rpc('prompts/list', {}, 'A')).prompts.some(prompt => prompt.name === 'prompt_after'));
      assert.equal((await rpc('resources/list', {}, 'A')).resources[0].name, 'resource_after');
      let getB = await openGet('B');
      await rpc('resources/subscribe', { uri: template });
      await command(revision, 'reconcile');
      await waitFor(() => events().some(e => e.revision === revision && e.event === 'get-renewal' && e.detail === 'B:allowed')
        && events().some(e => e.revision === revision && e.event === 'uri-renewal' && e.uri === exact && e.detail === 'A:allowed:context:credential-A')
        && events().some(e => e.revision === revision && e.event === 'uri-renewal' && e.uri === template && e.detail === 'B:allowed:context:credential-B'),
      'GET B retains historical URI A while duplicate subscribe replaces template credentials');
      await command(revision, 'revoke-a');
      await waitFor(() => getA.ended && getB.ended
        && events().some(e => e.revision === revision && e.event === 'uri-renewal' && e.uri === exact && e.detail.startsWith('A:denied')),
      'Historical URI A revocation retires its entire session');
      const exactBefore = updates(exact), templateBefore = updates(template);
      await command(revision, 'resource'); await command(revision, 'template');
      await sleep(200);
      assert.equal(updates(exact), exactBefore, 'No exact URI delivery after session retirement');
      assert.equal(updates(template), templateBefore, 'No template URI delivery after session retirement');
      const retired = await request('POST', 'B', { jsonrpc: '2.0', id: ++rpcId, method: 'ping', params: {} });
      assert.equal(retired.status, 404, 'Freshly authorized B must observe the retired session'); await retired.arrayBuffer();
      await initializeSession('B');
      getB = await openGet('B');
      await rpc('resources/subscribe', { uri: exact }); await rpc('resources/subscribe', { uri: template });
      await command(revision, 'resource'); await command(revision, 'template');
      await waitFor(() => updates(exact) > exactBefore && updates(template) > templateBefore,
        'Fresh B session and subscriptions restore both URI deliveries');
      const renewalCount = uri => events().filter(e => e.revision === revision && e.event === 'uri-renewal'
        && e.uri === uri && e.detail === 'B:allowed:context:credential-B').length;
      const beforeRenewal = new Map([exact, template].map(uri => [uri, renewalCount(uri)]));
      const beforeQuiet = count('notifications/resources/updated');
      await sleep(7000);
      for (const uri of [exact, template]) assert.ok(renewalCount(uri) - beforeRenewal.get(uri) >= 2,
        `B grant ${uri} renews twice and retains its application context`);
      assert.equal(count('notifications/resources/updated'), beforeQuiet, 'Quiet renewal must not replay a hint');
      getB.abort.abort(); await within(getB.done, 2000, 'GET disconnect'); await command(revision, 'gap');
      const beforeGap = updates(exact); await command(revision, 'resource');
      const reconnect = await openGet('B'); await waitFor(() => updates(exact) > beforeGap, 'Dirty URI hint after fresh GET');
      await rpc('resources/unsubscribe', { uri: template });
      const beforeUnsubscribe = updates(template); await command(revision, 'template'); await sleep(200);
      assert.equal(updates(template), beforeUnsubscribe, 'No hint after unsubscribe');
      await command(revision, 'revoke-b');
      await waitFor(() => reconnect.ended && events().some(e => e.revision === revision && e.event === 'uri-renewal' && e.uri === exact && e.detail.startsWith('B:denied')),
        'B URI revocation retires its session and closes GET');
      const revokedCount = received.length; await command(revision, 'resource'); await command(revision, 'tools'); await sleep(200);
      assert.equal(received.length, revokedCount, 'No delivery after revocation');
      const rejected = await request('GET', 'B'); assert.equal(rejected.status, 401);
      assert.ok(rejected.headers.get('www-authenticate').includes('invalid_token')); await rejected.arrayBuffer();
      const retiredGet = await request('GET', 'C'); assert.equal(retiredGet.status, 404); await retiredGet.arrayBuffer();
      await initializeSession('C');
      const deleted = await request('DELETE', 'C'); assert.equal(deleted.status, 204); await deleted.arrayBuffer();
      const deletedGet = await request('GET', 'C'); assert.equal(deletedGet.status, 404); await deletedGet.arrayBuffer();
      assert.equal((await command(revision, 'state')).activeGets, 0);
      for (const stream of streams) assert.equal(stream.failure, null);
      results.push({ revision, passed: true, checks: [...runtimeSupplementChecks] });
    }
  } catch (error) { failure = error; }
  finally {
    for (const stream of streams) stream.abort.abort();
    const cleanupResults = await Promise.allSettled(streams.map(stream => within(stream.done, 2000, 'GET cleanup')));
    const cleanupFailures = cleanupResults.filter(result => result.status === 'rejected').map(result => result.reason);
    if (cleanupFailures.length) failure = new AggregateError([...(failure ? [failure] : []), ...cleanupFailures], 'GET cleanup failed');
    writeFileSync(resolve(output, 'stop'), 'stop');
    try {
      const stopped = JSON.parse(await lines.next(10000));
      assert.deepEqual(stopped, { format: 1, event: 'stopped', clean: true });
      const exit = await supervisor.waitForClose(child, 10000);
      assert.deepEqual(exit, { code: 0, signal: null });
      assert.equal(lines.lineCount(), 2); lines.assertHealthy(); stderr.assertWithinLimit();
      assert.equal(stderr.text(), ''); clean = true;
    } catch (error) { failure = failure ? new AggregateError([failure, error]) : error; }
    writeFileSync(resolve(output, 'fixture.stdout.log'), lines.text());
    writeFileSync(resolve(output, 'fixture.stderr.log'), stderr.text());
    writeFileSync(resolve(output, 'fixture.cleanup.txt'), `clean=${clean}\n`);
  }
  if (failure) throw failure;
  return Object.freeze({ passed: true, results });
}
