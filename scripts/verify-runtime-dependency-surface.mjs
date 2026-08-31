#!/usr/bin/env node

import {
  closeSync,
  existsSync,
  lstatSync,
  openSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { dirname, isAbsolute, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { canonicalJson } from './import-release-harness-evidence.mjs';

const MAXIMUM_POM_BYTES = 4 * 1024 * 1024;
const XML_NAMESPACE_URI = 'http://www.w3.org/XML/1998/namespace';

export class RuntimeDependencySurfaceError extends Error {}

function fail(message) {
  throw new RuntimeDependencySurfaceError(message);
}

function decodeXmlText(text) {
  return text
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'")
    .replaceAll('&amp;', '&');
}

function xmlLocalName(name) {
  const components = name.split(':');
  if (components.length > 2
      || components.some((component) => !/^[A-Za-z_][\w.-]*$/u.test(component))
      || components[0] === 'xmlns') {
    fail(`POM contains a malformed namespace-qualified element name: ${name}.`);
  }
  return components.at(-1);
}

function xmlPrefix(name) {
  const separator = name.indexOf(':');
  return separator === -1 ? null : name.slice(0, separator);
}

function namespaceDeclarations(rawTag, elementName, selfClosing) {
  const body = rawTag.slice(1, selfClosing ? -2 : -1);
  const declarations = [];
  const names = new Set();
  let cursor = elementName.length;
  while (cursor < body.length) {
    while (cursor < body.length && /\s/u.test(body[cursor]))
      cursor++;
    if (cursor === body.length)
      break;
    const match = body.slice(cursor).match(/^([A-Za-z_][\w.:-]*)/u);
    if (match === null)
      fail('POM contains a malformed XML attribute name.');
    const name = match[1];
    const components = name.split(':');
    if (components.length > 2
        || components.some((component) => !/^[A-Za-z_][\w.-]*$/u.test(component))
        || names.has(name)) {
      fail(`POM contains a malformed or duplicate XML attribute name: ${name}.`);
    }
    names.add(name);
    cursor += name.length;
    while (cursor < body.length && /\s/u.test(body[cursor]))
      cursor++;
    if (body[cursor] !== '=')
      fail(`POM XML attribute ${name} has no value.`);
    cursor++;
    while (cursor < body.length && /\s/u.test(body[cursor]))
      cursor++;
    const quote = body[cursor];
    if (quote !== '"' && quote !== "'")
      fail(`POM XML attribute ${name} has an unquoted value.`);
    const end = body.indexOf(quote, cursor + 1);
    if (end === -1)
      fail(`POM XML attribute ${name} has an unterminated value.`);
    const value = body.slice(cursor + 1, end);
    cursor = end + 1;
    if (name === 'xmlns') {
      declarations.push({ prefix: null, value });
    } else if (components[0] === 'xmlns') {
      const prefix = components[1];
      if (prefix === 'xmlns' || value.length === 0
          || (prefix === 'xml' && value !== XML_NAMESPACE_URI)) {
        fail(`POM contains an invalid XML namespace declaration: ${name}.`);
      }
      declarations.push({ prefix, value });
    }
  }
  return declarations;
}

function tokenizeXml(text) {
  if (/<!DOCTYPE|<!ENTITY/iu.test(text))
    fail('POM may not contain DTD or entity declarations.');
  const tokens = [];
  const pattern = /<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<!\[CDATA\[([\s\S]*?)\]\]>|<\/([A-Za-z_][\w.:-]*)\s*>|<([A-Za-z_][\w.:-]*)(?:\s[^<>]*?)?\s*\/?>|([^<]+)/gu;
  let cursor = 0;
  for (const match of text.matchAll(pattern)) {
    if (match.index !== cursor)
      fail(`POM contains malformed XML near byte ${cursor}.`);
    cursor = match.index + match[0].length;
    if (match[0].startsWith('<!--') || match[0].startsWith('<?'))
      continue;
    if (match[1] !== undefined) {
      tokens.push({ kind: 'text', value: match[1] });
    } else if (match[2] !== undefined) {
      tokens.push({ kind: 'end', localName: xmlLocalName(match[2]), name: match[2] });
    } else if (match[3] !== undefined) {
      const selfClosing = /\/\s*>$/u.test(match[0]);
      tokens.push({
        kind: 'start',
        localName: xmlLocalName(match[3]),
        name: match[3],
        namespaceDeclarations: namespaceDeclarations(match[0], match[3], selfClosing),
        prefix: xmlPrefix(match[3]),
        selfClosing,
      });
    } else if (match[4] !== undefined) {
      tokens.push({ kind: 'text', value: match[4] });
    }
  }
  if (cursor !== text.length)
    fail(`POM contains malformed XML near byte ${cursor}.`);
  return tokens;
}

export function runtimeDependenciesFromPomText(text) {
  if (typeof text !== 'string' || text.length === 0)
    fail('POM text must be nonempty.');
  const stack = [];
  const dependencies = [];
  let current = null;
  let currentField = null;
  let fieldText = '';
  for (const token of tokenizeXml(text)) {
    if (token.kind === 'text') {
      if (currentField !== null)
        fieldText += token.value;
      continue;
    }
    if (token.kind === 'start') {
      const namespaces = new Map(stack.at(-1)?.namespaces ?? [['xml', XML_NAMESPACE_URI]]);
      for (const declaration of token.namespaceDeclarations) {
        if (declaration.prefix !== null)
          namespaces.set(declaration.prefix, declaration.value);
      }
      if (token.prefix !== null && !namespaces.has(token.prefix))
        fail(`POM element ${token.name} uses an undeclared XML namespace prefix.`);
      const openToken = { ...token, namespaces };
      stack.push(openToken);
      const path = stack.map(({ localName }) => localName).join('/');
      if (path === 'project/dependencies/dependency'
          || /^project\/profiles\/profile\/dependencies\/dependency$/u.test(path)) {
        if (current !== null)
          fail('POM contains nested dependency elements.');
        current = { fields: {}, profile: path.includes('/profiles/') };
      } else if (current !== null && stack.length > 0
          && ['artifactId', 'groupId', 'optional', 'scope', 'type', 'version']
            .includes(token.localName)) {
        currentField = openToken;
        fieldText = '';
      }
      if (token.selfClosing) {
        if (currentField === openToken) {
          current.fields[currentField.localName] = '';
          currentField = null;
        }
        stack.pop();
      }
      continue;
    }
    const openElement = stack.at(-1);
    if (openElement?.name !== token.name)
      fail(`POM has an unmatched closing element: ${token.name}.`);
    const path = stack.map(({ localName }) => localName).join('/');
    if (currentField === openElement) {
      current.fields[currentField.localName] = decodeXmlText(fieldText.trim());
      currentField = null;
      fieldText = '';
    }
    if (current !== null
        && (path === 'project/dependencies/dependency'
          || /^project\/profiles\/profile\/dependencies\/dependency$/u.test(path))) {
      dependencies.push(current);
      current = null;
    }
    stack.pop();
  }
  if (stack.length !== 0 || current !== null || currentField !== null)
    fail('POM XML is incomplete.');

  return dependencies.filter(({ fields }) => {
    const scope = fields.scope || 'compile';
    const type = fields.type || 'jar';
    return !['provided', 'test'].includes(scope)
      && !(scope === 'import' && type === 'pom');
  }).map(({ fields, profile }) => ({
    artifactId: fields.artifactId || '',
    groupId: fields.groupId || '',
    profile,
    scope: fields.scope || 'compile',
    type: fields.type || 'jar',
    version: fields.version || '',
  }));
}

export function verifyRuntimeDependencySurface({ outputPath, pomPath }) {
  if (typeof pomPath !== 'string' || !isAbsolute(pomPath))
    fail('POM path must be absolute.');
  if (typeof outputPath !== 'string' || !isAbsolute(outputPath))
    fail('runtime dependency surface output path must be absolute.');
  const absolutePomPath = resolve(pomPath);
  const absoluteOutputPath = resolve(outputPath);
  if (!existsSync(absolutePomPath))
    fail(`POM is missing: ${absolutePomPath}`);
  const stats = lstatSync(absolutePomPath);
  if (!stats.isFile() || stats.isSymbolicLink()
      || stats.size <= 0 || stats.size > MAXIMUM_POM_BYTES) {
    fail('POM must be a nonempty bounded regular nonsymlink file.');
  }
  if (existsSync(absoluteOutputPath))
    fail(`runtime dependency surface output already exists: ${absoluteOutputPath}`);
  if (!existsSync(dirname(absoluteOutputPath))
      || !lstatSync(dirname(absoluteOutputPath)).isDirectory()
      || lstatSync(dirname(absoluteOutputPath)).isSymbolicLink()) {
    fail('runtime dependency surface output parent must be a real nonsymlink directory.');
  }
  const bytes = readFileSync(absolutePomPath);
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes))
    fail('POM is not valid UTF-8.');
  const dependencies = runtimeDependenciesFromPomText(text);
  if (dependencies.length !== 0) {
    const names = dependencies.map(({ groupId, artifactId, scope, profile }) =>
      `${groupId}:${artifactId}:${scope}${profile ? ':profile' : ''}`);
    fail(`candidate declares external runtime dependencies: ${names.join(', ')}.`);
  }
  const descriptor = openSync(absoluteOutputPath, 'wx', 0o600);
  try {
    writeFileSync(descriptor, canonicalJson({
      externalRuntimeDependencyCount: 0,
      formatVersion: 1,
    }), 'utf8');
  } finally {
    closeSync(descriptor);
  }
  return Object.freeze({ externalRuntimeDependencyCount: 0, outputPath: absoluteOutputPath });
}

function usage() {
  return 'Usage: node scripts/verify-runtime-dependency-surface.mjs '
    + '<absolute-pom> <absolute-output-file>';
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  if (process.argv.length !== 4) {
    console.error(usage());
    process.exitCode = 64;
  } else {
    try {
      const result = verifyRuntimeDependencySurface({
        outputPath: process.argv[3],
        pomPath: process.argv[2],
      });
      console.log(`runtime dependency surface PASS count=${result.externalRuntimeDependencyCount}`);
    } catch (error) {
      console.error(error instanceof Error ? error.message : String(error));
      process.exitCode = error instanceof RuntimeDependencySurfaceError ? 1 : 70;
    }
  }
}
