#!/usr/bin/env node
import {mkdirSync, mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {delimiter, dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {runProcess} from '../inspector/process.mjs';
import {contractSummary, regularFile} from './run.mjs';

// Fast push/PR regression checks. The inert shell intentionally requires no
// SDK installation; this runner does not produce browser or release evidence.
const directory = dirname(fileURLToPath(import.meta.url));
const arguments_ = process.argv.slice(2);
if (arguments_.length !== 2)
  throw new Error('Usage: node ci-contracts.mjs candidate.jar /absolute/jdk/bin/java');
const jar = regularFile(arguments_[0]);
const java = regularFile(arguments_[1]);
const javac = regularFile(join(dirname(java), 'javac'));
const work = mkdtempSync(join(tmpdir(), 'soklet-apps-ci-'));
const classes = join(work, 'classes');
const shell = join(work, 'catalog.html');
const env = {PATH: process.env.PATH ?? '/usr/bin:/bin', LANG: 'C.UTF-8', TMPDIR: tmpdir()};

async function command(executable, args, timeoutMs) {
  const result = await runProcess(executable, args,
    {cwd: work, env, timeoutMs, maxOutputBytes: 2 * 1024 * 1024});
  if (result.code !== 0 || result.signal !== null)
    throw new Error(`Apps CI child failed: ${result.stdout}${result.stderr}`);
  return result;
}

try {
  mkdirSync(classes);
  writeFileSync(shell, '<!doctype html><html><body>Apps contract fixture</body></html>');
  const sources = ['src/com/soklet/interop/apps/AppsFixture.java',
    'src/com/soklet/interop/apps/AppsFixtureMain.java',
    'test-src/com/soklet/interop/apps/AppsFixtureContractTest.java',
    'test-src/com/soklet/interop/apps/AppsFixtureHttpAuthorizationTest.java']
    .map(path => resolve(directory, path));
  await command(javac, ['--release', '21', '-proc:none', '-Xlint:all', '-Werror',
    '-classpath', jar, '-d', classes, ...sources], 120000);
  const classpath = classes + delimiter + jar;
  const simulator = await command(java, ['-ea', '-classpath', classpath,
    'com.soklet.interop.apps.AppsFixtureContractTest', shell], 60000);
  const contracts = contractSummary(simulator.stdout, simulator.stderr);
  const http = await command(java, ['-ea', '-classpath', classpath,
    'com.soklet.interop.apps.AppsFixtureHttpAuthorizationTest', shell], 60000);
  const authorization = JSON.parse(http.stdout);
  if (http.stderr !== '' || authorization.status !== 'PASS' || authorization.requests !== 13
      || authorization.scope !== 'live-http-apps-authorization'
      || Object.keys(authorization).sort().join(',') !== 'requests,scope,status')
    throw new Error('Apps CI authorization contract output changed');
  console.log(JSON.stringify({status: 'PASS', contracts, authorization, hostQualification: false}));
} finally {
  rmSync(work, {recursive: true, force: true});
}
