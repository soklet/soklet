import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import test from 'node:test';
import {runInNewContext} from 'node:vm';
import {ORIGINAL_PIN, ORIGINAL_SOURCE_SHA256, PATCH_TARGET, patchSource, prepareCopy,
  verifyPatchedDependencies} from './patch.mjs';

// Explicitly supplied local dependency bytes; never install or download in a test.
const dependencies = process.env.SOKLET_INSPECTOR_AUTH_DEPENDENCIES;
assert.ok(dependencies, 'SOKLET_INSPECTOR_AUTH_DEPENDENCIES must identify the pinned original installation');
const original = readFileSync(resolve(dependencies, PATCH_TARGET), 'utf8');
const patched = patchSource(original);
const sha = value => createHash('sha256').update(value).digest('hex');

function functions(source) {
  const start = source.indexOf('var AuthChallengeError = class extends Error {');
  const end = source.indexOf('// ../../core/mcp/node/proxyFetch.ts', start);
  assert.ok(start > 0 && end > start);
  return runInNewContext(`${source.slice(start, end)}\n({AuthChallengeError, parseAuthChallengeFromResponse,
    createAuthChallengeInterceptFetch, createAuthChallengeObserverFetch})`);
}
const before = functions(original), after = functions(patched);
const normal = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
function response(status, header) {
  return new Response('policy body stays intact', {status, headers:header === undefined ? {} : {'WWW-Authenticate':header}});
}
function challenge(implementation, status, header, context) {
  return normal(implementation.parseAuthChallengeFromResponse(response(status, header), context));
}

test('patch accepts only the exact pinned original and rejects reapplication', () => {
  assert.equal(sha(original), ORIGINAL_SOURCE_SHA256);
  assert.notEqual(sha(patched), ORIGINAL_SOURCE_SHA256);
  assert.equal(patchSource(original), patched);
  for (const value of [undefined, Buffer.from(original), original + '\n', patched])
    assert.throws(() => patchSource(value), /AUTH_PATCH_SOURCE_PIN/);
  assert.equal(patched.split('function parseForbiddenBearerChallenge(header)').length, 2);
});

test('plain policy 403 is no longer synthesized as an auth challenge', async () => {
  assert.equal(challenge(before, 403).reason, 'unauthorized');
  assert.equal(challenge(after, 403), undefined);
  const originalResponse = response(403);
  await assert.rejects(before.createAuthChallengeInterceptFetch(async () => originalResponse)('unused'),
    error => error.name === 'AuthChallengeError' && error.status === 403);
  assert.equal(originalResponse.bodyUsed, true);
  const patchedResponse = response(403);
  assert.equal(await after.createAuthChallengeInterceptFetch(async () => patchedResponse)('unused'), patchedResponse);
  assert.equal(patchedResponse.bodyUsed, false);
  assert.equal(await patchedResponse.text(), 'policy body stays intact');
});

const notChallenges = [
  '', 'Basic realm="policy"', 'Bearer', 'Bearer realm="policy"', 'Bearer abc==',
  'Bearer scope="read"', 'Bearer error="invalid_token"', 'Bearer error="unauthorized"',
  'Bearer error="INSUFFICIENT_SCOPE"', 'NotBearer error="insufficient_scope"',
  'Basic realm="Bearer error=insufficient_scope"',
  'Basic realm="Bearer error=\\"insufficient_scope\\""',
  'Basic realm="policy", error="insufficient_scope"',
  'Bearer error="invalid_token", Basic error="insufficient_scope"',
  'Basic error="insufficient_scope", Bearer realm="policy"',
  'Bearer, error="insufficient_scope"', 'Bearer abc==, error="insufficient_scope"',
  'Bearer error="insufficient_scope', 'Bearer error="insufficient_scope" trailing',
  'Bearer error="insufficient_scope",', ', Bearer error="insufficient_scope"',
  'Bearer error="insufficient_scope", ERROR="insufficient_scope"',
  'Bearer error="insufficient_scope", error="invalid_token"',
  'Bearer error="insufficient_scope", Bearer error="invalid_token"',
  'Bearer error="insufficient_scope", Bearer error="insufficient_scope"',
  'Bearer error="insufficient_scope", scope="read", SCOPE="write"',
  `Bearer error="insufficient_scope", realm="${'x'.repeat(16384)}"`,
];
for (const [i, header] of notChallenges.entries()) test(`403 non-auth or malformed challenge ${i + 1} leaves body intact`, async () => {
  assert.equal(challenge(after, 403, header), undefined);
  const result = response(403, header), observed = [];
  assert.equal(await after.createAuthChallengeObserverFetch(async () => result, value => observed.push(value))('unused'), result);
  assert.deepEqual(observed, []);
  assert.equal(await after.createAuthChallengeInterceptFetch(async () => result)('unused'), result);
  assert.equal(result.bodyUsed, false);
  assert.equal(await result.text(), 'policy body stays intact');
});

const positiveHeaders = [
  'Bearer error="insufficient_scope", scope="read write", resource_metadata="https://resource.example/.well-known/oauth-protected-resource", error_description="Need more permission"',
  'bEaReR ERROR=insufficient_scope, ScOpE = "read write", RESOURCE_METADATA = "https://resource.example/.well-known/oauth-protected-resource", ERROR_DESCRIPTION="Need more permission"',
  'Basic realm="policy, Bearer error=invalid_token", Bearer error="insufficient_scope", scope="read write", resource_metadata="https://resource.example/.well-known/oauth-protected-resource", error_description="Need more permission"',
  'Bearer error="insufficient_scope", scope="read write", resource_metadata="https://resource.example/.well-known/oauth-protected-resource", error_description="Need more permission", Basic realm="policy", error="invalid_token"',
  'Negotiate abc==, Bearer error="insufficient_scope", scope="read write", resource_metadata="https://resource.example/.well-known/oauth-protected-resource", error_description="Need more permission"',
];
for (const [i, header] of positiveHeaders.entries()) test(`explicit 403 Bearer insufficient_scope ${i + 1} preserves challenge fields`, async () => {
  const context = {method:'tools/call'};
  const expected = {reason:'insufficient_scope', requiredScopes:['read','write'],
    resourceMetadataUrl:'https://resource.example/.well-known/oauth-protected-resource',
    message:'Need more permission', context, raw:{httpStatus:403, wwwAuthenticate:header}};
  assert.deepEqual(challenge(after, 403, header, context), expected);
  const result = response(403, header);
  await assert.rejects(after.createAuthChallengeInterceptFetch(async () => result)('unused'), error => {
    assert.equal(error.name, 'AuthChallengeError');
    assert.equal(error.status, 403);
    assert.equal(error.message, 'MCP auth challenge (403)');
    const intercepted = {...expected}; delete intercepted.context;
    assert.deepEqual(normal(error.authChallenge), intercepted);
    return true;
  });
  assert.equal(result.bodyUsed, true);
  const observable = response(403, header), observed = [];
  assert.equal(await after.createAuthChallengeObserverFetch(async () => observable, value => observed.push(normal(value)))('unused'), observable);
  assert.equal(observed.length, 1);
  assert.equal(observed[0].reason, 'insufficient_scope');
  assert.equal(observable.bodyUsed, false);
});

test('quoted commas, escaped quotes/backslashes, and scope decoding stay with the real Bearer scheme', () => {
  const header = 'Basic realm="Bearer error=insufficient_scope", Bearer error="insufficient_scope", scope="read write", error_description="Need \\"read\\" permission, path \\\\ok"';
  const parsed = challenge(after, 403, header);
  assert.equal(parsed.message, 'Need "read" permission, path \\ok');
  assert.deepEqual(parsed.requiredScopes, ['read', 'write']);
  assert.equal(parsed.raw.wwwAuthenticate, header);
});

test('all existing 401 decisions and body semantics are unchanged, including absent and malformed headers', async () => {
  for (const header of [undefined, ...notChallenges, ...positiveHeaders, 'Bearer error="invalid_token"']) {
    assert.deepEqual(challenge(after, 401, header, {method:'tools/list'}),
      challenge(before, 401, header, {method:'tools/list'}));
    const result = response(401, header);
    await assert.rejects(after.createAuthChallengeInterceptFetch(async () => result)('unused'), error => {
      assert.equal(error.name, 'AuthChallengeError');
      assert.equal(error.status, 401);
      assert.deepEqual(normal(error.authChallenge), challenge(before, 401, header));
      return true;
    });
    assert.equal(result.bodyUsed, true);
  }
  assert.equal(challenge(after, 401).reason, 'token_expired');
  assert.equal(challenge(after, 401, 'Bearer error="invalid_token"').reason, 'invalid_token');
});

test('401 observer still reports auth without consuming the response body', async () => {
  const result = response(401, 'Bearer error="invalid_token"'), observed = [];
  assert.equal(await after.createAuthChallengeObserverFetch(async () => result, value => observed.push(normal(value)))('unused'), result);
  assert.equal(observed[0].reason, 'invalid_token');
  assert.equal(await result.text(), 'policy body stays intact');
});

test('non-auth status codes ignore even explicit challenge headers and retain the body', async () => {
  for (const status of [200, 400, 404, 429, 500]) {
    const result = response(status, positiveHeaders[0]), observed = [];
    assert.equal(challenge(after, status, positiveHeaders[0]), undefined);
    assert.equal(await after.createAuthChallengeObserverFetch(async () => result, value => observed.push(value))('unused'), result);
    assert.equal(await after.createAuthChallengeInterceptFetch(async () => result)('unused'), result);
    assert.deepEqual(observed, []);
    assert.equal(result.bodyUsed, false);
  }
});

test('underlying fetch inputs, response failures and cancellation failures preserve prior behavior', async () => {
  const input = {url:'unused'}, init = {method:'POST'}, failure = new Error('fixed test failure');
  for (const implementation of [before, after]) {
    const calls = [];
    const wrapped = implementation.createAuthChallengeInterceptFetch(async (...args) => {calls.push(args); throw failure;});
    await assert.rejects(wrapped(input, init), error => error === failure);
    assert.deepEqual(calls, [[input, init]]);
    let canceled = 0;
    const custom = {status:401, headers:new Headers(), body:{cancel:() => {canceled++; return Promise.reject(failure);}}};
    await assert.rejects(implementation.createAuthChallengeInterceptFetch(async () => custom)(input, init),
      error => error.name === 'AuthChallengeError');
    assert.equal(canceled, 1);
  }
});

test('isolated copy verifies one-file provenance and refuses additional file/directory/link drift', () => {
  const temporary = mkdtempSync(join(tmpdir(), 'soklet-inspector-auth-patch-test-'));
  const destination = join(temporary, 'copy');
  try {
    const provenance = prepareCopy(dependencies, destination);
    assert.deepEqual(provenance.originalTree, ORIGINAL_PIN);
    assert.notEqual(provenance.patchedTree.sha256, ORIGINAL_PIN.sha256);
    assert.equal(provenance.patchedTree.files, ORIGINAL_PIN.files);
    assert.deepEqual(provenance.changedPaths, [PATCH_TARGET]);
    assert.equal(provenance.originalFileSha256, ORIGINAL_SOURCE_SHA256);
    assert.equal(provenance.patchedFileSha256, sha(patched));
    assert.equal(provenance.pathsTypesLinksAndBytesVerified, true);
    assert.equal(provenance.noSharedFileInodes, true);
    assert.equal(provenance.originalUnchanged, true);
    assert.equal(provenance.releasedHostQualification, false);
    assert.equal(provenance.experimental, true);
    assert.throws(() => prepareCopy(dependencies, destination), /AUTH_PATCH_DESTINATION/);
    assert.throws(() => verifyPatchedDependencies(dependencies, dependencies), /AUTH_PATCH_ROOT_OVERLAP/);
    const extra = join(destination, 'node_modules', 'auth-patch-extra');
    writeFileSync(extra, 'additional file');
    assert.throws(() => verifyPatchedDependencies(dependencies, destination), /AUTH_PATCH_TREE_DIFF/);
    rmSync(extra);
    mkdirSync(extra);
    assert.throws(() => verifyPatchedDependencies(dependencies, destination), /AUTH_PATCH_TREE_DIFF/);
    rmSync(extra, {recursive:true});
    symlinkSync('../package.json', extra);
    assert.throws(() => verifyPatchedDependencies(dependencies, destination), /AUTH_PATCH_LINK_ESCAPE/);
    rmSync(extra);
    writeFileSync(join(destination, 'package.json'), '{}');
    assert.throws(() => verifyPatchedDependencies(dependencies, destination), /AUTH_PATCH_PACKAGE_DRIFT/);
    assert.equal(sha(readFileSync(resolve(dependencies, PATCH_TARGET))), ORIGINAL_SOURCE_SHA256);
  } finally {
    // Only remove the exact fresh, test-owned directory returned by mkdtemp.
    if (existsSync(temporary)) rmSync(temporary, {recursive:true});
  }
});
