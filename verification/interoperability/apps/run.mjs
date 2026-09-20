#!/usr/bin/env node
import { createHash } from 'node:crypto';
import { existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, realpathSync, writeFileSync } from 'node:fs';
import { dirname, delimiter, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { runProcess } from '../inspector/process.mjs';
import { dependencyPins } from './build-shell.mjs';

const directory = dirname(fileURLToPath(import.meta.url));
const root = resolve(directory, '../../..');
const maximumShellBytes = 512 * 1024;
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const fileDigest = file => digest(readFileSync(file));

export function parseArguments(args) {
  const keys = ['--candidate-jar', '--candidate-pom', '--java', '--shell', '--work-dir'];
  if (args.length !== keys.length * 2) throw new Error('APPS_ARGUMENTS');
  const options = {};
  for (let i = 0; i < args.length; i += 2) {
    if (!keys.includes(args[i]) || options[args[i]] !== undefined || !args[i + 1])
      throw new Error('APPS_ARGUMENTS');
    options[args[i]] = args[i + 1];
  }
  return options;
}

export function regularFile(file) {
  const resolved = resolve(file);
  if (!existsSync(resolved) || !lstatSync(resolved).isFile() || lstatSync(resolved).isSymbolicLink())
    throw new Error('APPS_INPUT_FILE');
  return realpathSync(resolved);
}

export function contractSummary(stdout, stderr) {
  let result;
  try { result = JSON.parse(stdout); } catch { throw new Error('APPS_UNEXPECTED_CONTRACT_OUTPUT'); }
  if (stderr !== '' || result.status !== 'PASS' || result.cases !== 12 || result.requests !== 34
      || result.scope !== 'candidate-public-api-simulator'
      || Object.keys(result).sort().join(',') !== 'cases,requests,scope,status')
    throw new Error('APPS_UNEXPECTED_CONTRACT_OUTPUT');
  return result;
}

export function validateShellBuild(build, shellBytes, expectedInputs) {
  const ordered = value => [...value].sort((a, b) => a.path.localeCompare(b.path, 'en'));
  if (!build || build.schemaVersion !== 1 || build.kind !== 'soklet-apps-shell-build'
      || build.sdk !== '2.0.0' || build.bundler !== '1.2.9'
      || build.lockSha256 !== dependencyPins['package-lock.json']
      || build.packageSha256 !== dependencyPins['package.json']
      || build.hostQualification !== false || build.consoleCallsRemoved !== true || build.sourceRechecked !== true
      || build.bytes !== shellBytes.length || build.sha256 !== digest(shellBytes)
      || !Array.isArray(build.sourceInputs) || build.sourceInputs.length !== expectedInputs.length
      || ordered(build.sourceInputs).some((input, index) =>
        input.path !== ordered(expectedInputs)[index].path || input.sha256 !== ordered(expectedInputs)[index].sha256
        || input.bytes !== ordered(expectedInputs)[index].bytes))
    throw new Error('APPS_SHELL_BUILD_MISMATCH');
  return build;
}

function shellSourceInputs() {
  return ['../inspector/process.mjs', 'assets/catalog-entry.mjs', 'assets/catalog-shell.html',
    'assets/catalog-shell.mjs', 'build-shell.mjs']
    .map(path => ({path, bytes: lstatSync(join(directory, path)).size, sha256: fileDigest(join(directory, path))}));
}

function inputs() {
  const files = [];
  function visit(path) {
    for (const entry of readdirSync(path, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      if (entry.isSymbolicLink()) throw new Error('APPS_SOURCE_SYMLINK');
      const file = join(path, entry.name);
      if (entry.isDirectory()) visit(file);
      else if (entry.isFile()) files.push({path: file, sha256: fileDigest(file)});
    }
  }
  visit(directory);
  const processFile = resolve(directory, '../inspector/process.mjs');
  files.push({path: processFile, sha256: fileDigest(processFile)});
  return files;
}

export async function run(options) {
  const jar = regularFile(options['--candidate-jar']);
  const pom = regularFile(options['--candidate-pom']);
  const java = regularFile(options['--java']);
  const javac = regularFile(join(dirname(java), 'javac'));
  const jdeps = regularFile(join(dirname(java), 'jdeps'));
  const shell = regularFile(options['--shell']);
  if (lstatSync(shell).size > maximumShellBytes || lstatSync(shell).size === 0)
    throw new Error('APPS_SHELL_SIZE');
  const shellReceipt = regularFile(shell + '.receipt.json');
  if (lstatSync(shellReceipt).size > 2 * 1024 * 1024) throw new Error('APPS_SHELL_RECEIPT_SIZE');
  const shellBuild = validateShellBuild(JSON.parse(readFileSync(shellReceipt, 'utf8')),
    readFileSync(shell), shellSourceInputs());
  const work = resolve(options['--work-dir']);
  if (existsSync(work) || work === directory || work.startsWith(directory + '/'))
    throw new Error('APPS_WORK_DIRECTORY_MUST_BE_NEW_AND_OUTSIDE_SOURCES');
  mkdirSync(work, {recursive: true});
  const classes = join(work, 'classes');
  mkdirSync(classes);
  const receipt = {
    kind: 'soklet.apps.candidate-fixture.local.v1',
    verdict: 'FAIL', hostQualification: false,
    candidate: {jar, jarSha256: fileDigest(jar), pom, pomSha256: fileDigest(pom)},
    shell: {path: shell, sha256: fileDigest(shell), bytes: lstatSync(shell).size,
      receiptPath: shellReceipt, receiptSha256: fileDigest(shellReceipt), build: shellBuild},
    inputs: inputs(),
    bounds: {compileMs: 120000, contractsMs: 60000, dependencyAuditMs: 60000, maximumChildOutputBytes: 2 * 1024 * 1024},
    checks: [],
    limitations: ['Simulator contracts only; no actual browser/host rendering or bridge qualification',
      'CSP/permissions metadata is not proof of host enforcement',
      'Disposable fixture authentication is not production OAuth qualification',
      'Dirty-tree local evidence, not immutable release conformance']
  };
  let stage = 'inputs';
  const env = {PATH: process.env.PATH ?? '/usr/bin:/bin', LANG: 'C.UTF-8', TMPDIR: tmpdir()};
  async function command(name, executable, args, timeoutMs) {
    stage = name;
    const result = await runProcess(executable, args, {cwd: work, env, timeoutMs, maxOutputBytes: 2 * 1024 * 1024});
    if (result.code !== 0 || result.signal !== null) throw new Error('APPS_CHILD_FAILED');
    receipt.checks.push(name);
    return result;
  }
  try {
    receipt.java = (await command('java-identity', java, ['-version'], 10000)).stderr.trim();
    receipt.node = process.version;
    receipt.coreCommit = (await command('core-identity', '/usr/bin/git',
      ['-c', `safe.directory=${root}`, '-C', root, 'rev-parse', 'HEAD'], 10000)).stdout.trim();
    const embedded = await command('candidate-pom', '/usr/bin/unzip', ['-p', jar, 'META-INF/maven/com.soklet/soklet/pom.xml'], 10000);
    if (digest(embedded.stdout) !== receipt.candidate.pomSha256) throw new Error('APPS_POM_MISMATCH');
    const source = join(directory, 'src/com/soklet/interop/apps/AppsFixture.java');
    const tests = join(directory, 'test-src/com/soklet/interop/apps/AppsFixtureContractTest.java');
    if ([source, tests].some(file => readFileSync(file, 'utf8').includes('com.soklet.internal')))
      throw new Error('APPS_INTERNAL_SOURCE_REFERENCE');
    await command('compile-release-17', javac, ['--release', '17', '-proc:none', '-Xlint:all', '-Werror',
      '-classpath', jar, '-d', classes, source, tests], 120000);
    const contracts = await command('server-contracts', java, ['-ea', '-classpath', classes + delimiter + jar,
      'com.soklet.interop.apps.AppsFixtureContractTest', shell], 60000);
    receipt.contracts = contractSummary(contracts.stdout, contracts.stderr);
    const dependencies = await command('public-api-dependencies', jdeps, ['-q', '--multi-release', '17',
      '-verbose:class', '-classpath', jar, classes], 60000);
    if (dependencies.stdout.includes('com.soklet.internal') || dependencies.stdout.includes('not found'))
      throw new Error('APPS_NONPUBLIC_DEPENDENCY');
    writeFileSync(join(work, 'dependencies.txt'), dependencies.stdout, {flag: 'wx'});
    receipt.dependenciesSha256 = digest(dependencies.stdout);
    stage = 'input-recheck';
    if (fileDigest(jar) !== receipt.candidate.jarSha256 || fileDigest(pom) !== receipt.candidate.pomSha256
        || fileDigest(shell) !== receipt.shell.sha256 || fileDigest(shellReceipt) !== receipt.shell.receiptSha256
        || JSON.stringify(inputs()) !== JSON.stringify(receipt.inputs))
      throw new Error('APPS_INPUT_CHANGED');
    receipt.checks.push(stage);
    receipt.verdict = 'PASS';
  } catch (error) {
    receipt.failure = {stage, code: /^APPS_[A-Z_]+$/.test(error.message) ? error.message : 'APPS_EXECUTION_FAILED'};
  } finally {
    writeFileSync(join(work, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n', {flag: 'wx'});
  }
  return receipt;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const receipt = await run(parseArguments(process.argv.slice(2)));
    console.log(JSON.stringify({verdict: receipt.verdict, contracts: receipt.contracts, failure: receipt.failure,
      hostQualification: false}));
    if (receipt.verdict !== 'PASS') process.exitCode = 1;
  } catch {
    console.error('Apps fixture runner rejected its inputs. See README for the exact invocation.');
    process.exitCode = 1;
  }
}
