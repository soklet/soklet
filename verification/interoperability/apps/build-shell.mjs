import { createHash } from 'node:crypto';
import { lstat, readFile, readdir, realpath, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Script } from 'node:vm';
import { runProcess } from '../inspector/process.mjs';

const directory = path.dirname(fileURLToPath(import.meta.url));
export const SHELL_MAX_BYTES = 512 * 1024;
export const BUILD_TIMEOUT_MS = 60_000;
export const dependencyPins = Object.freeze({
  'package.json': '8fc642c9ec63e951bf987d4ccf2c7a198a33f350f7d424ad2e95a1f48dbfe2c0',
  'package-lock.json': '37dfcdb1476f7f31c4aeea13971a729588bad7719c67bb8349f7bd8c52d29f29',
});
const packagePins = Object.freeze({
  '@modelcontextprotocol/client': '2.0.0', '@modelcontextprotocol/core': '2.0.0',
  '@modelcontextprotocol/ext-apps': '2.0.0', 'pkce-challenge': '5.0.1', zod: '4.6.5',
  rolldown: '1.2.9', '@rolldown/pluginutils': '1.0.1', '@oxc-project/types': '0.150.0',
});
const sourceNames = Object.freeze(['../inspector/process.mjs', 'assets/catalog-entry.mjs',
  'assets/catalog-shell.html', 'assets/catalog-shell.mjs', 'build-shell.mjs']);
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

export function buildArguments(args) {
  if (args.length !== 4 || args[0] !== '--dependencies' || args[2] !== '--output')
    throw new Error('APPS_BUILD_ARGUMENTS_INVALID');
  for (const value of [args[1], args[3]])
    if (typeof value !== 'string' || !path.isAbsolute(value) || path.normalize(value) !== value
        || value === path.parse(value).root || /[\u0000-\u001f\u007f]/.test(value))
      throw new Error('APPS_BUILD_PATH_INVALID');
  if (path.extname(args[3]) !== '.html') throw new Error('APPS_BUILD_OUTPUT_INVALID');
  return { dependencies: args[1], output: args[3] };
}

export function buildCliArguments(args) {
  const worker = args[0] === '--worker';
  return { worker, ...buildArguments(worker ? args.slice(1) : args) };
}

export function inlineShell(template, script) {
  const marker = '<!-- SOKLET_APPS_SCRIPT -->';
  if (typeof template !== 'string' || template.split(marker).length !== 2
      || /<script\b/i.test(template) || typeof script !== 'string' || script.length === 0)
    throw new Error('APPS_BUILD_TEMPLATE_INVALID');
  // Reject the HTML parser's alternate script states. Escape every case of the
  // closing raw-text sentinel; validate the resulting classic script syntax.
  if (/<script\b|<!--/i.test(script)) throw new Error('APPS_BUILD_SCRIPT_SENTINEL_INVALID');
  const escaped = script.replace(/<\/script/gi, match => `<\\/${match.slice(2)}`);
  new Script(escaped);
  const html = template.replace(marker, () => `<script>${escaped}</script>`);
  if (Buffer.byteLength(html, 'utf8') > SHELL_MAX_BYTES) throw new Error('APPS_BUILD_SIZE_LIMIT');
  return html;
}

export async function verifyDependencies(dependencies) {
  if (await realpath(dependencies) !== dependencies) throw new Error('APPS_BUILD_DEPENDENCIES_NONCANONICAL');
  for (const [name, expected] of Object.entries(dependencyPins)) {
    const supplied = await readFile(path.join(dependencies, name));
    const repository = await readFile(path.join(directory, '../inspector', name));
    if (digest(supplied) !== expected || digest(repository) !== expected)
      throw new Error('APPS_BUILD_DEPENDENCIES_CHANGED');
  }
  const lock = JSON.parse(await readFile(path.join(dependencies, 'package-lock.json'), 'utf8'));
  for (const [name, version] of Object.entries(packagePins)) {
    const manifest = JSON.parse(await readFile(path.join(dependencies, 'node_modules', name, 'package.json'), 'utf8'));
    if (manifest.name !== name || manifest.version !== version
        || lock.packages[`node_modules/${name}`]?.version !== version)
      throw new Error('APPS_BUILD_PACKAGE_CHANGED');
  }
}

async function fileReceipt(filename, relative) {
  const stats = await lstat(filename);
  if (!stats.isFile() || stats.size > 32 * 1024 * 1024)
    throw new Error('APPS_BUILD_FILE_INVALID');
  const bytes = await readFile(filename);
  if (bytes.length > 32 * 1024 * 1024) throw new Error('APPS_BUILD_FILE_INVALID');
  return { path: relative, bytes: bytes.length, sha256: digest(bytes) };
}

async function packageTree(dependencies, name, version) {
  const root = path.join(dependencies, 'node_modules', name);
  if (await realpath(root) !== root) throw new Error('APPS_BUILD_PACKAGE_NONCANONICAL');
  const files = [];
  let totalBytes = 0;
  async function visit(relative = '', depth = 0) {
    if (depth > 32) throw new Error('APPS_BUILD_PACKAGE_SIZE_LIMIT');
    for (const entry of await readdir(path.join(root, relative), { withFileTypes: true })) {
      const item = path.join(relative, entry.name);
      if (entry.isDirectory()) await visit(item, depth + 1);
      else if (entry.isFile()) {
        const file = await fileReceipt(path.join(root, item), item);
        totalBytes += file.bytes;
        files.push(file);
      }
      else throw new Error('APPS_BUILD_PACKAGE_NONREGULAR');
      if (files.length > 10_000 || totalBytes > 128 * 1024 * 1024)
        throw new Error('APPS_BUILD_PACKAGE_SIZE_LIMIT');
    }
  }
  await visit();
  files.sort((a, b) => a.path.localeCompare(b.path, 'en'));
  return { tree: { name, version, sha256: digest(JSON.stringify(files)), files: files.length,
    bytes: totalBytes }, files };
}

async function captureBuildInputs(dependencies) {
  await verifyDependencies(dependencies);
  const lock = JSON.parse(await readFile(path.join(dependencies, 'package-lock.json'), 'utf8'));
  const bindingEntries = (await readdir(path.join(dependencies, 'node_modules/@rolldown'), { withFileTypes: true }))
    .filter(entry => entry.name.startsWith('binding-'));
  if (bindingEntries.length === 0 || bindingEntries.length > 16 || bindingEntries.some(entry => !entry.isDirectory()))
    throw new Error('APPS_BUILD_NATIVE_BINDING_INVALID');
  const pins = [...Object.entries(packagePins), ...bindingEntries
    .map(entry => [`@rolldown/${entry.name}`, packagePins.rolldown])].sort(([a], [b]) => a.localeCompare(b, 'en'));
  const packageTrees = [];
  const packageFiles = [];
  for (const [name, version] of pins) {
    const manifest = JSON.parse(await readFile(path.join(dependencies, 'node_modules', name, 'package.json'), 'utf8'));
    if (manifest.name !== name || manifest.version !== version
        || lock.packages[`node_modules/${name}`]?.version !== version)
      throw new Error('APPS_BUILD_PACKAGE_CHANGED');
    const snapshot = await packageTree(dependencies, name, version);
    packageTrees.push(snapshot.tree);
    packageFiles.push(...snapshot.files.map(file => ({ ...file, path: `node_modules/${name}/${file.path}` })));
  }
  const sourceInputs = await Promise.all(sourceNames.map(name => fileReceipt(path.join(directory, name), name)));
  sourceInputs.sort((a, b) => a.path.localeCompare(b.path, 'en'));
  packageFiles.sort((a, b) => a.path.localeCompare(b.path, 'en'));
  return { sourceInputs, packageTrees, packageFiles };
}

export function assertInputSnapshotUnchanged(initial, current) {
  if (JSON.stringify(initial) !== JSON.stringify(current)) throw new Error('APPS_BUILD_INPUT_CHANGED');
}

async function dependencyLicenses(dependencies, modules) {
  const names = [...new Set(modules.map(module => {
    const parts = module.path.split('/');
    return parts[1].startsWith('@') ? `${parts[1]}/${parts[2]}` : parts[1];
  }))].sort();
  const files = [];
  const notices = ['Third-party source notices. Bundled for the Soklet catalog fixture; console calls removed during minification.'];
  for (const name of names) {
    const root = path.join(dependencies, 'node_modules', name);
    const entries = (await readdir(root, { withFileTypes: true })).filter(entry =>
      /^(?:LICEN[SC]E|COPYING|NOTICE)(?:[.-].*)?$/i.test(entry.name));
    if (entries.length === 0 || entries.some(entry => !entry.isFile()))
      throw new Error('APPS_BUILD_LICENSE_MISSING');
    for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
      const relative = `node_modules/${name}/${entry.name}`;
      const content = await readFile(path.join(root, entry.name), 'utf8');
      if (Buffer.byteLength(content) > 128 * 1024) throw new Error('APPS_BUILD_LICENSE_SIZE_LIMIT');
      files.push({ path: relative, bytes: Buffer.byteLength(content), sha256: digest(content) });
      notices.push(`${relative}\n${content}`);
    }
  }
  // Keep the exact shipped notices in an inert comment. Fail rather than
  // allowing a future license text to change the HTML parser's state.
  const text = notices.join('\n\n');
  if (/<!--|-->|<\/?script/i.test(text)) throw new Error('APPS_BUILD_LICENSE_SENTINEL_INVALID');
  return { files, html: `<!--\n${text}\n-->` };
}

export async function buildShell({ dependencies, output }) {
  buildArguments(['--dependencies', dependencies, '--output', output]);
  // Record the recipe and known JS/native dependency closure before importing
  // executable dependencies. Emit these initial identities, not a later read.
  const initial = await captureBuildInputs(dependencies);
  const sourceByPath = new Map(initial.sourceInputs.map(file => [file.path, file]));
  const packageByPath = new Map(initial.packageFiles.map(file => [file.path, file]));
  const require = createRequire(path.join(dependencies, 'package.json'));
  const { rolldown } = await import(pathToFileURL(require.resolve('rolldown')).href);
  const nativePaths = Object.keys(require.cache).filter(filename => filename.endsWith('.node')
    && filename.startsWith(`${dependencies}/node_modules/@rolldown/binding-`));
  if (nativePaths.length !== 1) throw new Error('APPS_BUILD_NATIVE_BINDING_INVALID');
  const nativeRelative = path.relative(dependencies, nativePaths[0]);
  const nativeName = nativeRelative.split('/').slice(1, 3).join('/');
  const nativeBinding = packageByPath.get(nativeRelative);
  if (!nativeBinding || !initial.packageTrees.some(tree => tree.name === nativeName && tree.version === packagePins.rolldown))
    throw new Error('APPS_BUILD_NATIVE_BINDING_INVALID');
  const bundle = await rolldown({
    input: path.join(directory, 'assets/catalog-entry.mjs'),
    platform: 'browser',
    resolve: { alias: { '@modelcontextprotocol/ext-apps': require.resolve('@modelcontextprotocol/ext-apps') } },
    onLog(level, log) { if (level === 'warn' || level === 'error') throw new Error('APPS_BUILD_BUNDLE_DIAGNOSTIC'); },
  });
  try {
    // The pinned SDK's transport logs whole wire messages. Drop console calls
    // in the distributed example instead of retaining tenant-bearing logs.
    const { output: chunks } = await bundle.generate({ format: 'iife',
      minify: { compress: { dropConsole: true } }, codeSplitting: false });
    if (chunks.length !== 1 || chunks[0].type !== 'chunk'
        || chunks[0].imports.length || chunks[0].dynamicImports.length)
      throw new Error('APPS_BUILD_EXTERNAL_CHUNK');
    if (/\bconsole\s*(?:\.|\[)/.test(chunks[0].code)) throw new Error('APPS_BUILD_CONSOLE_REMAINS');
    const template = await readFile(path.join(directory, 'assets/catalog-shell.html'), 'utf8');
    const bundledInputs = [];
    const virtualModules = [];
    for (const module of chunks[0].moduleIds) {
      if (module.startsWith('\0')) throw new Error('APPS_BUILD_MODULE_NOT_SNAPSHOTTED');
      if (module.startsWith(`${directory}${path.sep}`)) {
        if (!sourceByPath.has(path.relative(directory, module))) throw new Error('APPS_BUILD_MODULE_NOT_SNAPSHOTTED');
        continue;
      }
      if (!module.startsWith(`${dependencies}${path.sep}node_modules${path.sep}`))
        throw new Error('APPS_BUILD_MODULE_OUTSIDE_DEPENDENCIES');
      const file = packageByPath.get(path.relative(dependencies, module));
      if (!file) throw new Error('APPS_BUILD_MODULE_NOT_SNAPSHOTTED');
      bundledInputs.push(file);
    }
    bundledInputs.sort((a, b) => a.path.localeCompare(b.path, 'en'));
    const licenses = await dependencyLicenses(dependencies, bundledInputs);
    for (const license of licenses.files) assertInputSnapshotUnchanged(packageByPath.get(license.path), license);
    const html = inlineShell(template.replace('</body>', () => `${licenses.html}\n</body>`), chunks[0].code);
    // Detect concurrent source/dependency mutation before writing either output.
    // This is a consistency check, not malicious-filesystem race immunity.
    assertInputSnapshotUnchanged(initial, await captureBuildInputs(dependencies));
    const receipt = { schemaVersion: 1, kind: 'soklet-apps-shell-build',
      sdk: packagePins['@modelcontextprotocol/ext-apps'], bundler: packagePins.rolldown,
      lockSha256: dependencyPins['package-lock.json'], packageSha256: dependencyPins['package.json'],
      bytes: Buffer.byteLength(html), sha256: digest(html), sourceInputs: initial.sourceInputs, bundledInputs, virtualModules,
      packageTrees: initial.packageTrees, nativeBinding, licenses: licenses.files,
      consoleCallsRemoved: true, sourceRechecked: true, hostQualification: false };
    // Never overwrite a checked artifact or an existing user's file.
    await writeFile(output, html, { flag: 'wx', mode: 0o600 });
    await writeFile(`${output}.receipt.json`, `${JSON.stringify(receipt, null, 2)}\n`, { flag: 'wx', mode: 0o600 });
    return receipt;
  } finally { await bundle.close(); }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    const { worker, dependencies, output } = buildCliArguments(process.argv.slice(2));
    if (worker) {
      const { sdk, bundler, bytes, sha256, hostQualification } = await buildShell({ dependencies, output });
      console.log(JSON.stringify({ schemaVersion: 1, sdk, bundler, bytes, sha256, hostQualification }));
    } else {
      const child = await runProcess(process.execPath,
        [fileURLToPath(import.meta.url), '--worker', '--dependencies', dependencies, '--output', output], {
          cwd: directory, env: { PATH: path.dirname(process.execPath), LANG: 'C.UTF-8' },
          timeoutMs: BUILD_TIMEOUT_MS, maxOutputBytes: 16 * 1024,
        });
      if (child.code !== 0 || child.signal || child.stderr.length > 0)
        throw new Error('APPS_BUILD_WORKER_FAILED');
      const result = JSON.parse(child.stdout);
      if (result.schemaVersion !== 1 || result.sdk !== packagePins['@modelcontextprotocol/ext-apps']
          || result.bundler !== packagePins.rolldown || result.hostQualification !== false
          || !Number.isSafeInteger(result.bytes) || result.bytes < 1 || result.bytes > SHELL_MAX_BYTES
          || !/^[a-f0-9]{64}$/.test(result.sha256)) throw new Error('APPS_BUILD_WORKER_RESULT_INVALID');
      console.log(JSON.stringify(result));
    }
  }
  catch (error) {
    console.error(/^APPS_BUILD_[A-Z_]+$/.test(error?.message ?? '') ? error.message : 'APPS_BUILD_FAILED');
    process.exitCode = 1;
  }
}
