import {createHash} from 'node:crypto';
import {copyFileSync, cpSync, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync,
  readlinkSync, realpathSync, writeFileSync} from 'node:fs';
import {dirname, isAbsolute, relative, resolve, sep} from 'node:path';
import {directoryIdentity, verifyDependencyPins} from '../inspector/run.mjs';

// An experiment against these exact installed bytes, not a released Inspector fix.
export const ORIGINAL_PIN = Object.freeze({files:9391,
  sha256:'8c0b1ed101c4c7e7497aa4aaba7e953b03a44bc58179308db1613a988d5a2b8d'});
export const PATCH_TARGET = 'node_modules/@modelcontextprotocol/inspector/clients/web/build/index.js';
export const ORIGINAL_SOURCE_SHA256 = 'ee5e5161243d9da6c10f682a9e84cf4a756393215285312c1d7cbd90e8622d88';
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const fail = code => {throw new Error(code);};

// The legacy parser searches unanchored Bearer text and combines parameters from
// separate schemes. Use a bounded, structural parser for the new 403 decision
// only; leave the existing 401 behavior unchanged. Ambiguous or malformed 403
// challenges remain ordinary HTTP responses rather than initiating OAuth.
const helper = String.raw`function parseForbiddenBearerChallenge(header) {
  if (!header || header.length > 16384 || /[\x00-\x08\x0a-\x1f\x7f]/.test(header)) return void 0;
  const token = /^[!#$%&'*+\-.^_\x60|~0-9A-Za-z]+$/;
  const parts = [];
  let start = 0, quoted = false, escaped = false;
  for (let i = 0; i < header.length; i++) {
    const char = header[i];
    if (escaped) { escaped = false; continue; }
    if (quoted && char === "\\") { escaped = true; continue; }
    if (char === '"') { quoted = !quoted; continue; }
    if (!quoted && char === ",") { parts.push(header.slice(start, i).trim()); start = i + 1; }
  }
  if (quoted || escaped) return void 0;
  parts.push(header.slice(start).trim());
  const challenges = [];
  let current;
  for (const part of parts) {
    if (!part) return void 0;
    let parameter = part;
    if (!/^[!#$%&'*+\-.^_\x60|~0-9A-Za-z]+[ \t]*=/.test(part)) {
      const scheme = /^([!#$%&'*+\-.^_\x60|~0-9A-Za-z]+)(?:[ \t]+(.*))?$/.exec(part);
      if (!scheme) return void 0;
      current = {scheme: scheme[1].toLowerCase(), params: Object.create(null), token68: false, bare: false};
      challenges.push(current);
      parameter = scheme[2];
      if (parameter === void 0) { current.bare = true; continue; }
      if (/^[A-Za-z0-9\-._~+/]+=*$/.test(parameter)) { current.token68 = true; continue; }
    }
    if (!current || current.token68 || current.bare) return void 0;
    const match = /^([!#$%&'*+\-.^_\x60|~0-9A-Za-z]+)[ \t]*=[ \t]*(.*)$/.exec(parameter);
    if (!match) return void 0;
    const name = match[1].toLowerCase();
    let value = match[2];
    if (Object.hasOwn(current.params, name)) return void 0;
    if (!token.test(value)) {
      if (!/^"(?:[^"\\\x00-\x08\x0a-\x1f\x7f]|\\[\t\x20-\x7e\x80-\xff])*"$/.test(value)) return void 0;
      value = value.slice(1, -1).replace(/\\(.)/g, "$1");
    }
    current.params[name] = value;
  }
  const bearer = challenges.filter(challenge => challenge.scheme === "bearer");
  if (bearer.length !== 1 || bearer[0].token68 || bearer[0].params.error !== "insufficient_scope") return void 0;
  const params = bearer[0].params;
  return {error: params.error, scope: params.scope, resourceMetadata: params.resource_metadata,
    errorDescription: params.error_description};
}
`;
const originalDecision = '  const bearer = wwwAuthenticate ? parseWwwAuthenticateBearer(wwwAuthenticate) : {};\n  const requiredScopes = parseScopeString(bearer.scope);';
const patchedDecision = '  const bearer = status === 403 ? parseForbiddenBearerChallenge(wwwAuthenticate)\n    : wwwAuthenticate ? parseWwwAuthenticateBearer(wwwAuthenticate) : {};\n  if (!bearer) return void 0;\n  const requiredScopes = parseScopeString(bearer.scope);';

export function patchSource(original) {
  if (typeof original !== 'string' || sha(original) !== ORIGINAL_SOURCE_SHA256)
    fail('AUTH_PATCH_SOURCE_PIN');
  const marker = 'function parseAuthChallengeFromResponse(response, context) {';
  if (original.split(marker).length !== 2 || original.split(originalDecision).length !== 2)
    fail('AUTH_PATCH_SOURCE_SHAPE');
  return original.replace(marker, helper + marker).replace(originalDecision, patchedDecision);
}

function regular(path) {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink()) fail('AUTH_PATCH_FILE_TYPE');
  return stat;
}

function rootDirectory(path) {
  const absolute = resolve(path);
  if (!lstatSync(absolute).isDirectory() || lstatSync(absolute).isSymbolicLink()) fail('AUTH_PATCH_ROOT');
  return realpathSync(absolute);
}

function contains(parent, child) {
  const path = relative(parent, child);
  return path === '' || (!isAbsolute(path) && path !== '..' && !path.startsWith(`..${sep}`));
}

function inventory(directory) {
  const rows = [];
  function visit(path) {
    for (const name of readdirSync(path).sort()) {
      const absolute = resolve(path, name), stat = lstatSync(absolute);
      const key = relative(directory, absolute).split(sep).join('/');
      if (stat.isDirectory()) {rows.push([key, 'directory']); visit(absolute);}
      else if (stat.isFile()) rows.push([key, 'file', sha(readFileSync(absolute))]);
      else if (stat.isSymbolicLink()) {
        const target = readlinkSync(absolute);
        if (isAbsolute(target) || !contains(directory, resolve(dirname(absolute), target)))
          fail('AUTH_PATCH_LINK_ESCAPE');
        rows.push([key, 'link', target]);
      } else fail('AUTH_PATCH_ENTRY_TYPE');
    }
  }
  visit(directory);
  return rows;
}

function checkOriginal(originalRoot) {
  const root = rootDirectory(originalRoot);
  for (const name of ['package.json', 'package-lock.json', PATCH_TARGET]) regular(resolve(root, name));
  const packageBytes = readFileSync(resolve(root, 'package.json'));
  const lockBytes = readFileSync(resolve(root, 'package-lock.json'));
  verifyDependencyPins(packageBytes, lockBytes);
  const tree = directoryIdentity(resolve(root, 'node_modules'));
  if (tree.files !== ORIGINAL_PIN.files || tree.sha256 !== ORIGINAL_PIN.sha256) fail('AUTH_PATCH_TREE_PIN');
  const source = readFileSync(resolve(root, PATCH_TARGET), 'utf8');
  const patchedSource = patchSource(source);
  return {root, tree, source, patchedSource, packageBytes, lockBytes};
}

export function verifyPatchedDependencies(originalRoot, patchedRoot) {
  const original = checkOriginal(originalRoot), patched = rootDirectory(patchedRoot);
  if (contains(original.root, patched) || contains(patched, original.root)) fail('AUTH_PATCH_ROOT_OVERLAP');
  for (const [name, bytes] of [['package.json', original.packageBytes], ['package-lock.json', original.lockBytes]]) {
    regular(resolve(patched, name));
    if (!readFileSync(resolve(patched, name)).equals(bytes)) fail('AUTH_PATCH_PACKAGE_DRIFT');
  }
  const target = resolve(patched, PATCH_TARGET);
  regular(target);
  if (readFileSync(target, 'utf8') !== original.patchedSource) fail('AUTH_PATCH_BYTES');
  const before = inventory(resolve(original.root, 'node_modules'));
  const after = inventory(resolve(patched, 'node_modules'));
  if (before.length !== after.length) fail('AUTH_PATCH_TREE_DIFF');
  const changedPaths = [];
  for (let i = 0; i < before.length; i++) {
    const [path, type, value] = before[i], [otherPath, otherType, otherValue] = after[i];
    if (path !== otherPath || type !== otherType) fail('AUTH_PATCH_TREE_DIFF');
    if (type === 'file') {
      const a = lstatSync(resolve(original.root, 'node_modules', path));
      const b = lstatSync(resolve(patched, 'node_modules', path));
      if (a.dev === b.dev && a.ino === b.ino) fail('AUTH_PATCH_SHARED_INODE');
    }
    if (value !== otherValue) changedPaths.push(`node_modules/${path}`);
  }
  if (changedPaths.length !== 1 || changedPaths[0] !== PATCH_TARGET) fail('AUTH_PATCH_TREE_DIFF');
  return {kind:'ISOLATED_INSPECTOR_AUTH_PATCH', experimental:true, releasedHostQualification:false,
    originalTree:original.tree, patchedTree:directoryIdentity(resolve(patched, 'node_modules')),
    target:PATCH_TARGET, originalFileSha256:sha(original.source), patchedFileSha256:sha(original.patchedSource),
    packageSha256:sha(original.packageBytes), lockSha256:sha(original.lockBytes), changedPaths,
    pathsTypesLinksAndBytesVerified:true, noSharedFileInodes:true, originalUnchanged:true};
}

export function prepareCopy(originalRoot, destination) {
  const original = checkOriginal(originalRoot), target = resolve(destination);
  const parent = rootDirectory(dirname(target));
  if (existsSync(target) || contains(original.root, resolve(parent, target.split(sep).at(-1))))
    fail('AUTH_PATCH_DESTINATION');
  mkdirSync(target, {recursive:false, mode:0o700});
  // Copy only the pinned runtime inputs, not npm's cache or any user state.
  for (const name of ['package.json', 'package-lock.json'])
    copyFileSync(resolve(original.root, name), resolve(target, name));
  cpSync(resolve(original.root, 'node_modules'), resolve(target, 'node_modules'),
    {recursive:true, dereference:false, verbatimSymlinks:true, force:false, errorOnExist:true});
  if (JSON.stringify(directoryIdentity(resolve(target, 'node_modules'))) !== JSON.stringify(ORIGINAL_PIN))
    fail('AUTH_PATCH_COPY_PIN');
  writeFileSync(resolve(target, PATCH_TARGET), original.patchedSource, {flag:'r+'});
  return verifyPatchedDependencies(original.root, target);
}
