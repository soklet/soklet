import assert from 'node:assert/strict';
import {
  existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync,
  symlinkSync, unlinkSync, writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import {
  directoryIdentity, runHarness, validateCliOutput, verifyDependencyPins, workingTreeFileRows,
} from './run.mjs';
import {adjudicateTrace, projectExchange, SKILLS, UI} from './trace.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const packagePath = join(here, 'package.json');
const lockPath = join(here, 'package-lock.json');
const expectedNames = [
  'json_schema_2020_12_tool', 'test_audio_content', 'test_custom_header',
  'test_embedded_resource', 'test_error_handling', 'test_image_content',
  'test_multiple_content_types', 'test_simple_text',
  'test_tool_with_progress',
];
const output = value => `${JSON.stringify(value)}\n`;
const memoryStoreCaveat = '[mcp-inspector] Secrets are not written anywhere and are lost on exit.\n';

test('current source CLI requires explicit Apps selection with Skills enabled and retains strict historical interpretation', () => {
  for (const enabled of [true, false]) {
    const rows = ['server/discover', 'tools/list', 'tools/list'].map((method, index) => ({
      sequence: index + 1, method, responseStatus: 200,
      requestedExtensions: enabled ? 'APPS_ENABLED_SKILLS_ENABLED' : 'APPS_DISABLED_SKILLS_ENABLED',
      authorized: true, requestEnvelopeValid: true, protocolMetadataMatches: true,
      protocolHeaderMatches: true, methodHeaderMatches: true, perRequestCapabilitiesPresent: true,
      noSessionState: true, toolSelectionValid: true, responseJson: true, responseNoStore: true,
      responseCorrelated: true, resultMatchesFixture: true, extensionSelectionMatches: true,
      appsAdvertised: enabled, appsMimeMatches: enabled, skillsAdvertised: true, skillsShapeMatches: true,
    }));
    assert.equal(adjudicateTrace(rows, enabled, 'tools/list', 'apps-toggle-skills-enabled'), 'PASSED');
    assert.equal(adjudicateTrace(rows, enabled, 'tools/list'), 'FAILED');
    for (const key of ['appsAdvertised', 'appsMimeMatches', 'skillsAdvertised', 'skillsShapeMatches',
      'authorized', 'extensionSelectionMatches', 'resultMatchesFixture']) {
      const mutated = structuredClone(rows);
      mutated[1][key] = !mutated[1][key];
      assert.equal(adjudicateTrace(mutated, enabled, 'tools/list', 'apps-toggle-skills-enabled'), 'FAILED');
    }
    assert.equal(adjudicateTrace([...rows, rows[2]], enabled, 'tools/list', 'apps-toggle-skills-enabled'), 'FAILED');
    const extensions = {[SKILLS]: {}, ...(enabled ? {[UI]: {mimeTypes: ['text/html;profile=mcp-app']}} : {})};
    const row = projectExchange({request: {method: 'tools/list', params: {_meta: {
      'io.modelcontextprotocol/clientCapabilities': {extensions}}}}, response: {}, headers: {},
      responseHeaders: {}, enabled, cliProfile: 'apps-toggle-skills-enabled'});
    assert.equal(row.extensionSelectionMatches, true);
    assert.equal(row.requestedExtensions, rows[0].requestedExtensions);
  }
});

test('current source CLI recognizes only the two exact memory-store diagnostic lines', () => {
  const list = output({result: {tools: expectedNames.map(name => ({name}))}});
  const current = memoryStoreCaveat + '[mcp-inspector] How the secret store is chosen, and how to secure it: https://github.com/modelcontextprotocol/inspector/blob/main/docs/secret-storage.md\n';
  assert.equal(validateCliOutput(list, current, 'tools/list', true), true);
  assert.equal(validateCliOutput(list, current + 'unexpected\n', 'tools/list', true), false);
  assert.equal(validateCliOutput(list, memoryStoreCaveat, 'tools/list', true), false);
  assert.equal(validateCliOutput(list, current, 'tools/list'), false);
});

test('working-tree identity records tracked deletions and detects restoration without weakening symlink checks', () => {
  const scratch = mkdtempSync(join(tmpdir(), 'soklet-inspector-source-'));
  try {
    writeFileSync(join(scratch, 'present.java'), 'source');
    const paths = ['removed.java', 'present.java'];
    const deleted = workingTreeFileRows(scratch, paths);
    assert.deepEqual(deleted[1], ['removed.java', 'DELETED']);
    assert.deepEqual(workingTreeFileRows(scratch, paths.reverse()), deleted);
    writeFileSync(join(scratch, 'removed.java'), 'restored');
    assert.notDeepEqual(workingTreeFileRows(scratch, paths), deleted);
    unlinkSync(join(scratch, 'removed.java'));
    symlinkSync('missing.java', join(scratch, 'removed.java'));
    assert.throws(() => workingTreeFileRows(scratch, paths), /INPUT_FILE_INVALID/);
  } finally {
    rmSync(scratch, {recursive: true, force: true});
  }
});

test('dependency checker accepts only the exact reviewed manifest and lock', () => {
  const packageBytes = readFileSync(packagePath);
  const lockBytes = readFileSync(lockPath);
  assert.doesNotThrow(() => verifyDependencyPins(packageBytes, lockBytes));
  const manifest = JSON.parse(packageBytes);
  const lock = JSON.parse(lockBytes);
  assert.equal(manifest.dependencies['@modelcontextprotocol/inspector'],
    '2.7.0');
  assert.equal(manifest.dependencies['@modelcontextprotocol/ext-apps'],
    '2.0.0');
  assert.equal(lock.lockfileVersion, 3);
  assert.equal(lock.packages['node_modules/@modelcontextprotocol/inspector'].integrity,
    'sha512-V1SqfR+m3NWMkEe2i3v2GMm00pZtAl/JISAHQzYnOZElEuwuLLkU8vTWBYg5HMCYTgpvuMmzb+MZz98HjdcGuw==');
  const mutatedPackage = Buffer.from(packageBytes);
  mutatedPackage[0] ^= 1;
  assert.throws(() => verifyDependencyPins(mutatedPackage, lockBytes),
    { message: 'DEPENDENCY_PIN_DRIFT' });
  const mutatedLock = Buffer.from(lockBytes);
  mutatedLock[0] ^= 1;
  assert.throws(() => verifyDependencyPins(packageBytes, mutatedLock),
    { message: 'DEPENDENCY_PIN_DRIFT' });
});

test('directory identity is stable across creation order and detects mutation', () => {
  const scratch = mkdtempSync(join(tmpdir(), 'soklet-inspector-identity-'));
  try {
    const first = join(scratch, 'first');
    const second = join(scratch, 'second');
    mkdirSync(first);
    mkdirSync(second);
    mkdirSync(join(first, 'nested'));
    writeFileSync(join(first, 'z.txt'), 'last');
    writeFileSync(join(first, 'nested', 'b.txt'), 'nested');
    writeFileSync(join(first, 'a.txt'), 'first');
    writeFileSync(join(second, 'a.txt'), 'first');
    mkdirSync(join(second, 'nested'));
    writeFileSync(join(second, 'nested', 'b.txt'), 'nested');
    writeFileSync(join(second, 'z.txt'), 'last');
    const original = directoryIdentity(first);
    assert.equal(original.files, 3);
    assert.match(original.sha256, /^[a-f0-9]{64}$/u);
    assert.deepEqual(directoryIdentity(second), original);
    assert.deepEqual(directoryIdentity(first), original);
    writeFileSync(join(first, 'a.txt'), 'changed');
    assert.notEqual(directoryIdentity(first).sha256, original.sha256);
    const changed = directoryIdentity(first);
    symlinkSync('a.txt', join(first, 'alias'));
    assert.equal(directoryIdentity(first).files, changed.files + 1);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('directory identity rejects symlinks escaping its root', () => {
  const scratch = mkdtempSync(join(tmpdir(), 'soklet-inspector-symlink-'));
  try {
    const inside = join(scratch, 'inside');
    mkdirSync(inside);
    writeFileSync(join(scratch, 'outside.txt'), 'outside');
    const link = join(inside, 'escape');
    symlinkSync('../outside.txt', link);
    assert.throws(() => directoryIdentity(inside),
      { message: 'INSTALLED_SYMLINK_ESCAPE' });
    unlinkSync(link);
    symlinkSync(join(inside, 'not-present'), link);
    assert.throws(() => directoryIdentity(inside),
      { message: 'INSTALLED_SYMLINK_ESCAPE' });
    const rootLink = join(scratch, 'root-link');
    symlinkSync('inside', rootLink);
    assert.throws(() => directoryIdentity(rootLink),
      { message: 'INSTALLED_ROOT_INVALID' });
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
});

test('CLI output accepts exact fixture list and call projections', () => {
  const list = { result: { tools: expectedNames.map(name => ({ name })) } };
  const call = { result: {
    content: [{ type: 'text',
      text: 'This is a simple text response for testing.' }],
    _meta: { 'io.modelcontextprotocol/serverInfo': {
      name: 'soklet-public-conformance', version: '4.0.0',
      description: 'Soklet MCP conformance fixture',
    } },
  } };
  assert.equal(validateCliOutput(output(list), memoryStoreCaveat,
    'tools/list'), true);
  assert.equal(validateCliOutput(output(call), memoryStoreCaveat,
    'tools/call'), true);
  for (const [operation, value] of [['tools/list', list], ['tools/call', call]]) {
    assert.equal(validateCliOutput(output(value), '', operation), false);
    assert.equal(validateCliOutput(output(value), `${memoryStoreCaveat}extra\n`,
      operation), false);
    assert.equal(validateCliOutput(output(value),
      '[mcp-inspector] Secrets are kept in memory.\n', operation), false);
    assert.equal(validateCliOutput('not JSON\n', memoryStoreCaveat,
      operation), false);
    assert.equal(validateCliOutput(`${output(value)}${output(value)}`,
      memoryStoreCaveat, operation), false);
    assert.equal(validateCliOutput(output({ ...value, debug: true }),
      memoryStoreCaveat, operation), false);
  }
  assert.equal(validateCliOutput(output({ result: { ...list.result,
    extra: true } }), memoryStoreCaveat, 'tools/list'), false);
  assert.equal(validateCliOutput(output({ result: { tools: [
    ...list.result.tools.slice(0, -1), { name: 'different_tool' },
  ] } }), memoryStoreCaveat, 'tools/list'), false);
  assert.equal(validateCliOutput(output({ result: { ...call.result,
    content: [{ type: 'text', text: 'wrong response' }],
  } }), memoryStoreCaveat, 'tools/call'), false);
  assert.equal(validateCliOutput(output({ result: { ...call.result,
    isError: true } }), memoryStoreCaveat, 'tools/call'), false);
  assert.equal(validateCliOutput(output({ result: { ...call.result,
    resultType: 'complete' } }), memoryStoreCaveat, 'tools/call'), false);
  assert.equal(validateCliOutput(output(call), memoryStoreCaveat,
    'initialize'), false);
});

test('invalid work-directory input is rejected before package installation',
  async () => {
    const scratch = mkdtempSync(join(tmpdir(), 'soklet-inspector-preflight-'));
    try {
      const work = join(scratch, 'must-not-be-created');
      await assert.rejects(runHarness({
        candidateJar: packagePath,
        candidatePom: packagePath,
        java: packagePath,
        workDirectory: work,
      }), { message: 'WORK_DIRECTORY_MUST_BE_FRESH' });
      assert.equal(existsSync(work), false);
      const link = join(scratch, 'jar-link');
      symlinkSync(packagePath, link);
      await assert.rejects(runHarness({
        candidateJar: link,
        candidatePom: packagePath,
        java: packagePath,
        workDirectory: work,
      }), { message: 'INPUT_FILE_INVALID' });
      assert.equal(existsSync(work), false);
    } finally {
      rmSync(scratch, { recursive: true, force: true });
    }
  });
