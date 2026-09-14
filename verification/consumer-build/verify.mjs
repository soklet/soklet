#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { javaFeatureVersion, materializeFixture, parseArguments, readMutationResult,
  requireNegativeOutput, requirePositiveOutput } from './consumer-build-lib.mjs';

const options = parseArguments(process.argv.slice(2));
function required(name) {
  if (!options.has(name))
    throw new Error(`Missing --${name}`);
  return options.get(name);
}
const mode = required('mode');
if (!['maven', 'gradle', 'javac'].includes(mode))
  throw new Error('Mode must be maven, gradle, or javac');
const javaHome = resolve(required('java-home'));
const jar = resolve(required('jar'));
const expectedHash = required('sha256');
if (!/^[0-9a-f]{64}$/u.test(expectedHash))
  throw new Error('Expected SHA-256 must contain 64 lowercase hexadecimal characters');
const output = resolve(required('output'));
if (existsSync(output))
  throw new Error('Output must not already exist; use a fresh directory');
const hash = path => createHash('sha256').update(readFileSync(path)).digest('hex');
function verifyJar(path) {
  if (hash(path) !== expectedHash)
    throw new Error(`Candidate JAR identity mismatch: ${path}`);
}
verifyJar(jar);
const source = dirname(fileURLToPath(import.meta.url));
mkdirSync(output, { recursive: true });
const fixture = join(output, 'fixture');
const env = { ...process.env, JAVA_HOME: javaHome, PATH: `${join(javaHome, 'bin')}:${process.env.PATH}` };
let commandIndex = 0;
function run(command, args) {
  const result = spawnSync(command, args, { cwd: existsSync(fixture) ? fixture : output, env, encoding: 'utf8',
    timeout: 180_000, maxBuffer: 16 * 1024 * 1024 });
  const log = `${result.stdout ?? ''}${result.stderr ?? ''}`;
  writeFileSync(join(output, `${++commandIndex}.log`), log);
  if (result.error || result.signal || result.status !== 0)
    throw new Error(`${command} failed the expected outcome: ${result.error ?? result.signal ?? result.status}\n${log}`);
  return log;
}
const java = join(javaHome, 'bin', 'java');
const version = run(java, ['-version']).trim();
const javaFeature = javaFeatureVersion(version);
const sourceVariant = materializeFixture(source, fixture, javaFeature);
let consumerJar;
let unprocessed;
if (mode === 'maven' || mode === 'gradle') {
  const repository = resolve(required('repository'));
  const installedJar = join(repository, 'com/soklet/soklet/4.0.0/soklet-4.0.0.jar');
  verifyJar(installedJar);
  if (mode === 'maven') {
    run(resolve(required('maven')), ['-o', '-B', '-ntp', `-Dmaven.repo.local=${repository}`, 'clean', 'package']);
    consumerJar = join(fixture, 'target/consumer-build-1.0.0.jar');
  } else {
    env.GRADLE_USER_HOME = join(output, 'gradle-cache');
    run(resolve(required('gradle')), ['--offline', '--no-daemon', '--console', 'plain',
      `-Dsoklet.consumer.repository=${repository}`, 'clean', 'jar']);
    consumerJar = join(fixture, 'build/libs/consumer-build-1.0.0.jar');
  }
  verifyJar(installedJar);
} else {
  const sources = readdirSync(join(fixture, 'src/main/java/example'))
    .filter(name => name.endsWith('.java')).sort()
    .map(name => join(fixture, 'src/main/java/example', name));
  const classes = join(output, 'classes');
  mkdirSync(classes);
  run(join(javaHome, 'bin', 'javac'), ['--release', '17', '-parameters',
    '-processor', 'com.soklet.SokletProcessor', '-cp', jar, '-d', classes, ...sources]);
  consumerJar = join(output, 'consumer.jar');
  run(join(javaHome, 'bin', 'jar'), ['--create', '--file', consumerJar, '-C', classes, '.']);
  unprocessed = join(output, 'unprocessed');
  mkdirSync(unprocessed);
  run(join(javaHome, 'bin', 'javac'), ['--release', '17', '-parameters', '-proc:none',
    '-cp', jar, '-d', unprocessed, ...sources]);
}
const result = run(java, ['-cp', `${consumerJar}:${jar}`, 'example.ConsumerSmoke']);
requirePositiveOutput(result, javaFeature);
const consumerHash = hash(consumerJar);
const helperClasses = join(output, 'helper-classes');
mkdirSync(helperClasses);
run(join(javaHome, 'bin', 'javac'), ['--release', '17', '-proc:none', '-d', helperClasses,
  join(source, 'tools/ConsumerJarMutation.java')]);
const negativeControls = [];
for (const name of ['missing-http-index', 'corrupt-http-index']) {
  const mutatedJar = join(output, `${name}.jar`);
  const mutation = readMutationResult(run(java, ['-cp', helperClasses,
    'consumerbuild.ConsumerJarMutation', consumerJar, mutatedJar, name]), name);
  const negative = run(java, ['-cp', `${mutatedJar}:${jar}`, 'example.ConsumerSmoke', name]);
  requireNegativeOutput(negative, name);
  negativeControls.push({ name, status: 'PASS', log: `${commandIndex}.log`,
    consumerJarSha256: hash(mutatedJar), mcpIndexSha256: mutation.mcpIndexSha256,
    unchangedEntryCount: mutation.unchangedEntryCount });
}
if (negativeControls[0].mcpIndexSha256 !== negativeControls[1].mcpIndexSha256)
  throw new Error('Negative fixtures do not preserve the same MCP descriptor index');
if (unprocessed) {
  const name = 'no-processor';
  requireNegativeOutput(run(java, ['-cp', `${unprocessed}:${jar}`, 'example.ConsumerSmoke', name]), name);
  negativeControls.push({ name, status: 'PASS', log: `${commandIndex}.log` });
}
if (hash(consumerJar) !== consumerHash)
  throw new Error('Positive consumer JAR changed while creating negative fixtures');
verifyJar(jar);
writeFileSync(join(output, 'result.json'), `${JSON.stringify({
  formatVersion: 1, kind: 'DEVELOPMENT_CONSUMER_SMOKE', mode, java: version, javaFeature,
  sourceVariant, candidateJarSha256: expectedHash, consumerJarSha256: consumerHash,
  negativeControls, status: 'PASS', output: result.trim(),
}, null, 2)}\n`);
process.stdout.write(`${mode} packaged consumer passed; evidence: ${output}\n`);
