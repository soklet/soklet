#!/usr/bin/env node
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { javaFeatureVersion, materializeFixture, parseArguments, readMutationResult,
  requireNegativeOutput, requirePositiveOutput } from './consumer-build-lib.mjs';

const source = dirname(fileURLToPath(import.meta.url));
const temporary = mkdtempSync(join(tmpdir(), 'soklet-consumer-self-test-'));
const binary = name => process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', name) : name;
const httpIndex = 'META-INF/soklet/resource-method-lookup-table';
const mcpIndex = 'META-INF/soklet/mcp-endpoint-descriptor-providers';
let cases = 0;
function test(name, operation) {
  operation();
  cases++;
  console.log(`PASS ${name}`);
}
function run(command, args, cwd = temporary, succeeds = true) {
  const result = spawnSync(command, args, { cwd, encoding: 'utf8', timeout: 180_000,
    maxBuffer: 16 * 1024 * 1024 });
  assert.equal(result.error, undefined, result.error?.message);
  assert.equal(result.signal, null);
  assert.equal(result.status === 0, succeeds, `${result.stdout}\n${result.stderr}`);
  return `${result.stdout}${result.stderr}`;
}
function write(path, bytes) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, bytes);
}

try {
  test('existing CLI options retain their values', () => {
    assert.deepEqual([...parseArguments(['--mode', 'gradle', '--output', '/new output'])],
      [['mode', 'gradle'], ['output', '/new output']]);
  });
  for (const args of [['--mode'], ['mode', 'gradle'], ['--mode', '--jar'],
    ['--unknown', 'value'], ['--mode', 'maven', '--mode', 'gradle']]) {
    test(`malformed or duplicate arguments fail: ${args.join(' ')}`, () =>
      assert.throws(() => parseArguments(args)));
  }
  for (const feature of [17, 21, 25]) {
    test(`consumer Java ${feature} source selection`, () => {
      assert.equal(javaFeatureVersion(`openjdk version "${feature}.0.1" 2026-01-01`), feature);
      const fixture = join(temporary, `fixture-${feature}`);
      assert.equal(materializeFixture(source, fixture, feature), feature >= 21 ? 'HTTP_MCP_SSE' : 'HTTP_MCP');
      assert.equal(existsSync(join(fixture, 'src/main/java/example/ConsumerSseEndpoints.java')), feature >= 21);
      assert.ok(readFileSync(join(fixture, 'src/main/java/example/ConsumerEndpoints.java'))
        .equals(readFileSync(join(source, 'src/main/java/example/ConsumerEndpoints.java'))));
      assert.equal(existsSync(join(fixture, 'src/sse')), false);
      assert.throws(() => materializeFixture(source, fixture, feature), /must not already exist/u);
      requirePositiveOutput(`Consumer HTTP/MCP${feature >= 21 ? '/SSE' : ''} packaged routing passed on Java ${feature}\n`, feature);
    });
  }
  test('early-access Java feature is recognized', () =>
    assert.equal(javaFeatureVersion('openjdk version "25-ea"'), 25));
  for (const version of ['openjdk version "16.0.2"', 'java version "1.8.0"', 'unknown runtime']) {
    test(`unsupported or unrecognized runtime fails: ${version}`, () =>
      assert.throws(() => javaFeatureVersion(version), /Java 17 or newer/u));
  }
  test('source materialization rejects unsupported runtimes', () =>
    assert.throws(() => materializeFixture(source, join(temporary, 'bad-runtime'), 16), /Java 17 or newer/u));
  test('routing output cannot claim unsupported SSE or the wrong Java version', () => {
    assert.throws(() => requirePositiveOutput('Consumer HTTP/MCP/SSE packaged routing passed on Java 17', 17));
    assert.throws(() => requirePositiveOutput('Consumer HTTP/MCP packaged routing passed on Java 21', 21));
  });
  for (const name of ['missing-http-index', 'corrupt-http-index', 'no-processor']) {
    test(`negative result requires exact framework rejection: ${name}`, () => {
      const marker = `PASS\t${name}\tFRAMEWORK_DISCOVERY_FAILURE`;
      requireNegativeOutput(`configuration diagnostic\n${marker}\n`, name);
      assert.throws(() => requireNegativeOutput('Missing consumer HTTP/SSE route index', name));
      assert.throws(() => requireNegativeOutput(`${marker}\n${marker}`, name));
      assert.throws(() => requireNegativeOutput('PASS\tunrelated\tFRAMEWORK_DISCOVERY_FAILURE', name));
    });
  }

  const helperClasses = join(temporary, 'helper-classes');
  mkdirSync(helperClasses);
  run(binary('javac'), ['--release', '17', '-proc:none', '-d', helperClasses,
    join(source, 'tools/ConsumerJarMutation.java')]);
  const originalTree = join(temporary, 'original');
  const mcpBytes = Buffer.from('example.Endpoint|example.Provider\n', 'utf8');
  const classBytes = Buffer.from([0xca, 0xfe, 0xba, 0xbe, 1, 2, 3]);
  write(join(originalTree, httpIndex), 'GET|L2hlbGxv|ZXhhbXBsZS5FbmRwb2ludA==|aGVsbG8=||false\n');
  write(join(originalTree, mcpIndex), mcpBytes);
  write(join(originalTree, 'example/Endpoint.class'), classBytes);
  const originalJar = join(temporary, 'original.jar');
  run(binary('jar'), ['--create', '--file', originalJar, '-C', originalTree, '.']);
  const originalBytes = readFileSync(originalJar);
  const mcpHash = createHash('sha256').update(mcpBytes).digest('hex');
  for (const name of ['missing-http-index', 'corrupt-http-index']) {
    test(`JAR mutation changes only HTTP metadata: ${name}`, () => {
      const mutated = join(temporary, `${name}.jar`);
      const mutation = readMutationResult(run(binary('java'), ['-cp', helperClasses,
        'consumerbuild.ConsumerJarMutation', originalJar, mutated, name]), name);
      assert.equal(mutation.mcpIndexSha256, mcpHash);
      const entries = run(binary('jar'), ['--list', '--file', mutated]).trim().split(/\r?\n/u);
      assert.equal(entries.includes(httpIndex), name === 'corrupt-http-index');
      assert.ok(entries.includes(mcpIndex));
      const extracted = join(temporary, `${name}-extracted`);
      mkdirSync(extracted);
      run(binary('jar'), ['--extract', '--file', mutated], extracted);
      assert.ok(readFileSync(join(extracted, mcpIndex)).equals(mcpBytes));
      assert.ok(readFileSync(join(extracted, 'example/Endpoint.class')).equals(classBytes));
      if (name === 'corrupt-http-index')
        assert.equal(readFileSync(join(extracted, httpIndex), 'utf8'), 'GET|%%%|%%%|%%%||false\n');
      assert.ok(readFileSync(originalJar).equals(originalBytes));
      run(binary('java'), ['-cp', helperClasses, 'consumerbuild.ConsumerJarMutation',
        originalJar, mutated, name], temporary, false);
    });
  }
  test('JAR mutation rejects unknown modes', () => {
    const destination = join(temporary, 'unknown.jar');
    run(binary('java'), ['-cp', helperClasses, 'consumerbuild.ConsumerJarMutation',
      originalJar, destination, 'unknown'], temporary, false);
    assert.equal(existsSync(destination), false);
  });
  for (const missing of [httpIndex, mcpIndex]) {
    test(`JAR mutation rejects already missing metadata: ${missing}`, () => {
      const name = missing === httpIndex ? 'no-http' : 'no-mcp';
      const incomplete = join(temporary, name);
      const remaining = missing === httpIndex ? mcpIndex : httpIndex;
      write(join(incomplete, remaining), 'still-present\n');
      const input = join(temporary, `${name}.jar`);
      const output = join(temporary, `${name}-mutated.jar`);
      run(binary('jar'), ['--create', '--file', input, '-C', incomplete, '.']);
      run(binary('java'), ['-cp', helperClasses, 'consumerbuild.ConsumerJarMutation',
        input, output, 'missing-http-index'], temporary, false);
      assert.equal(existsSync(output), false);
    });
  }
  test('mutation evidence rejects changed MCP hash, shape, mode, or count', () => {
    const good = { mode: 'missing-http-index', mcpIndexSha256: mcpHash, unchangedEntryCount: 2 };
    for (const value of [{ ...good, mode: 'wrong' }, { ...good, mcpIndexSha256: 'wrong' },
      { ...good, unchangedEntryCount: 0 }, { ...good, unreviewed: true }])
      assert.throws(() => readMutationResult(JSON.stringify(value), 'missing-http-index'));
  });
  console.log(`Consumer-build self-test passed (${cases} cases).`);
} finally {
  rmSync(temporary, { recursive: true, force: true });
}
