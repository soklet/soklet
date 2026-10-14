#!/usr/bin/env node
import {createHash} from 'node:crypto';
import {existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, readlinkSync,
  realpathSync, writeFileSync} from 'node:fs';
import {dirname, isAbsolute, relative, resolve, sep} from 'node:path';
import {fileURLToPath} from 'node:url';
import {runProcess} from './process.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const json = value => JSON.stringify(value) + '\n';
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const hash = path => digest(readFileSync(path));
const fail = code => {throw new Error(code);};
const pinPath = resolve(here, 'upstream-source-pin.json');
export const INSPECTOR_SOURCE_PIN = Object.freeze(JSON.parse(readFileSync(pinPath, 'utf8')));
const pin = INSPECTOR_SOURCE_PIN;

// Execute reviewed build tools directly: no npm build/prepack/postinstall hooks.
export const BUILD_COMMANDS = Object.freeze([
  {cwd: 'clients/web', tool: 'node_modules/typescript/bin/tsc', args: ['-b']},
  {cwd: 'clients/web', tool: 'clients/web/node_modules/vite/bin/vite.js', args: ['build']},
  {cwd: 'clients/web', tool: 'clients/web/node_modules/tsup/dist/cli-default.js', args: ['--config', 'tsup.runner.config.ts']},
  {cwd: 'clients/cli', tool: 'clients/cli/node_modules/tsup/dist/cli-default.js', args: []},
  {cwd: 'clients/launcher', tool: 'node_modules/typescript/bin/tsc', args: []},
  {cwd: 'clients/web', tool: 'clients/web/node_modules/vitest/vitest.mjs', args: ['run', '--project=unit',
    'clients/web/src/test/core/auth/challenge.test.ts',
    'clients/web/src/test/core/mcp/node/authChallengeFetch.test.ts']},
]);

function regular(path) {
  if (!lstatSync(path).isFile() || lstatSync(path).isSymbolicLink()) fail('UPSTREAM_FILE_INVALID');
  return path;
}

function generated(path) {
  return path.split('/').includes('node_modules')
    || /^(clients\/(web|cli|launcher)\/build|clients\/web\/dist)(\/|$)/.test(path)
    || /^clients\/web\/tsconfig\.[a-z]+\.tsbuildinfo$/.test(path);
}

export function upstreamSourceIdentity(source) {
  const rows = [];
  function visit(directory) {
    for (const name of readdirSync(directory).sort()) {
      const path = resolve(directory, name), key = relative(source, path).split(sep).join('/');
      if (generated(key)) continue;
      const entry = lstatSync(path);
      if (entry.isSymbolicLink()) fail('UPSTREAM_SOURCE_SYMLINK');
      if (entry.isDirectory()) visit(path);
      else if (entry.isFile()) rows.push([key, hash(path)]);
      else fail('UPSTREAM_SOURCE_ENTRY');
    }
  }
  visit(source);
  rows.sort((a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0);
  return {files: rows.length, sha256: digest(json(rows))};
}

export function upstreamTreeIdentity(directory) {
  if (!lstatSync(directory).isDirectory() || lstatSync(directory).isSymbolicLink())
    fail('UPSTREAM_ROOT_INVALID');
  const rows = [];
  function visit(path) {
    for (const name of readdirSync(path).sort()) {
      const absolute = resolve(path, name), key = relative(directory, absolute).split(sep).join('/');
      const entry = lstatSync(absolute);
      if (entry.isDirectory()) visit(absolute);
      else if (entry.isFile()) rows.push([key, 'file', hash(absolute)]);
      else if (entry.isSymbolicLink()) {
        const target = readlinkSync(absolute);
        const resolved = relative(directory, realpathSync(absolute));
        if (isAbsolute(target) || resolved === '..' || resolved.startsWith(`..${sep}`))
          fail('UPSTREAM_SYMLINK_ESCAPE');
        rows.push([key, 'link', target]);
      } else fail('UPSTREAM_TREE_ENTRY');
    }
  }
  visit(directory);
  return {files: rows.length, sha256: digest(json(rows))};
}

export function verifyUpstreamSources(source) {
  const identity = upstreamSourceIdentity(source);
  if (identity.files !== pin.sourceFiles || identity.sha256 !== pin.sourceSha256)
    fail('UPSTREAM_SOURCE_DRIFT');
  const pkg = JSON.parse(readFileSync(resolve(source, 'package.json'), 'utf8'));
  if (pkg.name !== '@modelcontextprotocol/inspector' || pkg.version !== pin.packageVersion)
    fail('UPSTREAM_PACKAGE_DRIFT');
  for (const [path, expected] of Object.entries(pin.locks)) {
    const bytes = readFileSync(regular(resolve(source, path)));
    if (digest(bytes) !== expected) fail('UPSTREAM_LOCK_DRIFT');
    const lock = JSON.parse(bytes);
    if (lock.lockfileVersion !== 3) fail('UPSTREAM_LOCK_FORMAT');
    for (const [name, value] of Object.entries(lock.packages)) {
      if (name && (!value.resolved?.startsWith('https://registry.npmjs.org/')
          || !/^sha512-[A-Za-z0-9+/]+=*$/.test(value.integrity))) fail('UPSTREAM_DEPENDENCY_SOURCE');
    }
  }
  return identity;
}

function recipeIdentity() {
  return {pinSha256: hash(pinPath), builderSha256: hash(fileURLToPath(import.meta.url)),
    supervisorSha256: hash(resolve(here, 'process.mjs'))};
}

export function verifyUpstreamBuild(buildRoot) {
  const build = resolve(buildRoot);
  if (!lstatSync(build).isDirectory() || lstatSync(build).isSymbolicLink()) fail('UPSTREAM_ROOT_INVALID');
  const receiptPath = regular(resolve(build, 'receipt.json'));
  if (lstatSync(receiptPath).size > 65536) fail('UPSTREAM_RECEIPT_SIZE');
  const receipt = JSON.parse(readFileSync(receiptPath, 'utf8'));
  if (receipt.kind !== 'soklet.inspector.upstream-source-build.v1' || receipt.status !== 'PASS'
      || JSON.stringify(receipt.pin) !== JSON.stringify(pin)
      || JSON.stringify(receipt.recipe) !== JSON.stringify(recipeIdentity())
      || JSON.stringify(receipt.commands) !== JSON.stringify(BUILD_COMMANDS)
      || receipt.lifecycleScriptsExecuted !== false || receipt.upstreamModified !== false
      || receipt.node !== pin.node || receipt.npm !== pin.npm
      || receipt.platform !== process.platform || receipt.architecture !== process.arch)
    fail('UPSTREAM_RECEIPT_INVALID');
  if (hash(regular(resolve(build, 'upstream.tar.gz'))) !== pin.archiveSha256) fail('UPSTREAM_ARCHIVE_DRIFT');
  const inspector = resolve(build, 'source');
  verifyUpstreamSources(inspector);
  const identity = upstreamTreeIdentity(inspector);
  if (JSON.stringify(identity) !== JSON.stringify(receipt.tree)) fail('UPSTREAM_BUILD_DRIFT');
  const entry = regular(resolve(inspector, 'clients/launcher/build/index.js'));
  regular(resolve(inspector, 'clients/web/dist/index.html'));
  regular(resolve(inspector, 'clients/web/build/index.js'));
  regular(resolve(inspector, 'clients/cli/build/index.js'));
  return {entry, inspector, identity, receiptSha256: hash(receiptPath),
    target: {name: '@modelcontextprotocol/inspector', version: pin.packageVersion,
      commit: pin.commit, distribution: pin.distribution},
    provenance: {archiveSha256: pin.archiveSha256, sourceSha256: pin.sourceSha256,
      locks: pin.locks, recipe: receipt.recipe, tree: identity}};
}

export async function buildUpstream({workDirectory, archivePath}) {
  if (process.versions.node !== pin.node) fail('UPSTREAM_NODE_VERSION');
  const work = resolve(workDirectory);
  if (existsSync(work)) fail('UPSTREAM_WORK_MUST_BE_FRESH');
  for (let parent = dirname(work); parent !== dirname(parent); parent = dirname(parent)) {
    if (existsSync(parent) && (!lstatSync(parent).isDirectory() || lstatSync(parent).isSymbolicLink()))
      fail('UPSTREAM_WORK_ANCESTOR');
  }
  mkdirSync(work, {recursive: true, mode: 0o700});
  const source = resolve(work, 'source'), isolation = resolve(work, 'isolation');
  for (const path of [source, isolation, resolve(isolation, 'home'), resolve(isolation, 'tmp'), resolve(isolation, 'cache')])
    mkdirSync(path, {mode: 0o700});
  for (const name of ['user.npmrc', 'global.npmrc']) writeFileSync(resolve(isolation, name), '', {flag: 'wx'});
  const env = {PATH: `${dirname(process.execPath)}:/usr/bin:/bin`, HOME: resolve(isolation, 'home'),
    TMPDIR: resolve(isolation, 'tmp'), XDG_CONFIG_HOME: resolve(isolation, 'home'),
    XDG_CACHE_HOME: resolve(isolation, 'cache'), LANG: 'C.UTF-8', CI: 'true',
    npm_config_userconfig: resolve(isolation, 'user.npmrc'), npm_config_globalconfig: resolve(isolation, 'global.npmrc'),
    npm_config_cache: resolve(isolation, 'cache'), npm_config_registry: 'https://registry.npmjs.org/',
    npm_config_ignore_scripts: 'true', INSPECTOR_SKIP_CLIENT_INSTALL: '1',
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: '1', MCP_INSPECTOR_SECRET_STORE: 'memory'};
  const receipt = {kind: 'soklet.inspector.upstream-source-build.v1', status: 'FAIL', pin,
    recipe: recipeIdentity(), node: process.versions.node, npm: null, platform: process.platform,
    architecture: process.arch, lifecycleScriptsExecuted: false, upstreamModified: false,
    commands: BUILD_COMMANDS, stages: []};
  async function command(label, executable, args, cwd, timeoutMs) {
    const result = await runProcess(executable, args, {cwd, env, timeoutMs, maxOutputBytes: 8 * 1024 * 1024});
    writeFileSync(resolve(work, `${label}.log`), result.stdout + result.stderr, {flag: 'wx'});
    if (result.code !== 0 || result.signal !== null) fail('UPSTREAM_COMMAND_FAILED');
    receipt.stages.push(label);
    return result.stdout;
  }
  try {
    receipt.npm = (await command('npm-version', 'npm', ['--version'], work, 10000)).trim();
    if (receipt.npm !== pin.npm) fail('UPSTREAM_NPM_VERSION');
    let archive;
    if (archivePath) archive = readFileSync(regular(resolve(archivePath)));
    else {
      const response = await fetch(pin.archiveUrl, {signal: AbortSignal.timeout(60000), redirect: 'error'});
      if (!response.ok) fail('UPSTREAM_DOWNLOAD_FAILED');
      const chunks = []; let size = 0;
      for await (const chunk of response.body) {
        if ((size += chunk.length) > 16 * 1024 * 1024) fail('UPSTREAM_ARCHIVE_SIZE');
        chunks.push(chunk);
      }
      archive = Buffer.concat(chunks);
    }
    if (archive.length > 16 * 1024 * 1024 || digest(archive) !== pin.archiveSha256)
      fail('UPSTREAM_ARCHIVE_DRIFT');
    writeFileSync(resolve(work, 'upstream.tar.gz'), archive, {flag: 'wx'});
    await command('extract', '/usr/bin/tar', ['-xf', resolve(work, 'upstream.tar.gz'), '--strip-components=1', '-C', source], work, 30000);
    verifyUpstreamSources(source);
    for (const directory of ['', 'clients/web', 'clients/cli', 'clients/launcher'])
      await command(`install-${directory.split('/').pop() || 'root'}`, 'npm',
        ['ci', '--ignore-scripts', '--no-audit', '--no-fund'], resolve(source, directory), 300000);
    verifyUpstreamSources(source);
    for (const [index, step] of BUILD_COMMANDS.entries())
      await command(`build-${index + 1}`, process.execPath, [regular(resolve(source, step.tool)), ...step.args],
        resolve(source, step.cwd), 300000);
    verifyUpstreamSources(source);
    receipt.tree = upstreamTreeIdentity(source);
    receipt.status = 'PASS';
  } catch (error) {
    receipt.failure = /^UPSTREAM_[A-Z_]+$/.test(error.message) ? error.message : 'UPSTREAM_EXECUTION_FAILED';
  } finally {
    writeFileSync(resolve(work, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n', {flag: 'wx'});
  }
  if (receipt.status !== 'PASS') fail(receipt.failure);
  return verifyUpstreamBuild(work);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  try {
    if (![2, 4].includes(args.length) || args[0] !== '--work-dir'
        || (args.length === 4 && args[2] !== '--archive')) fail('UPSTREAM_ARGUMENTS');
    const result = await buildUpstream({workDirectory: args[1], archivePath: args[3]});
    console.log(JSON.stringify({status: 'PASS', ...result}));
  } catch (error) {
    console.error(/^UPSTREAM_[A-Z_]+$/.test(error.message) ? error.message : 'UPSTREAM_EXECUTION_FAILED');
    process.exitCode = 1;
  }
}
