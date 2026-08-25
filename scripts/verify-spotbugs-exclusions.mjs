#!/usr/bin/env node

import {
  existsSync,
  lstatSync,
  readFileSync,
  readdirSync,
} from 'node:fs';
import {
  dirname,
  isAbsolute,
  relative,
  resolve,
  sep,
} from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const MAXIMUM_FILTER_BYTES = 1024 * 1024;
const MAXIMUM_CLASS_BYTES = 64 * 1024 * 1024;
const MAXIMUM_CLASS_COUNT = 100_000;
const MAXIMUM_XML_DEPTH = 64;
const MAXIMUM_XML_NODES = 100_000;
const XML_DECLARATION = '<?xml version="1.0" encoding="UTF-8"?>';
const APPROVED_UNSCOPED_PACKAGE = '~com\\.soklet\\.internal\\.spring(\\..*)?';
const APPROVED_UNSCOPED_BUG_PATTERNS = new Set([
  'BX_UNBOXING_IMMEDIATELY_REBOXED',
  'CT_CONSTRUCTOR_THROW',
  'EI_EXPOSE_REP',
  'EI_EXPOSE_REP2',
  'MS_EXPOSE_REP',
  'MS_PKGPROTECT',
  'SIC_INNER_SHOULD_BE_STATIC',
  'VA_FORMAT_STRING_USES_NEWLINE',
]);

const COMPOUND_ELEMENTS = new Set(['And', 'Match', 'Not', 'Or']);
const LEAF_ATTRIBUTES = Object.freeze({
  Annotation: ['name'],
  Bug: ['category', 'code', 'pattern'],
  BugCode: ['name'],
  BugPattern: ['name'],
  Class: ['name', 'role'],
  Confidence: ['value'],
  Field: ['name', 'role', 'type'],
  Local: ['name'],
  Method: ['name', 'params', 'returns', 'role'],
  Package: ['name'],
  Priority: ['value'],
  Rank: ['value'],
  Source: ['name'],
});

export class SpotbugsExclusionVerificationError extends Error {}

function fail(message) {
  throw new SpotbugsExclusionVerificationError(message);
}

function compareAscii(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function readRegularFile(path, label, maximumBytes) {
  if (!existsSync(path))
    fail(`${label} does not exist: ${path}`);
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink())
    fail(`${label} must be a regular nonsymlink file: ${path}`);
  if (stats.size <= 0 || stats.size > maximumBytes)
    fail(`${label} has an invalid size (${stats.size} bytes): ${path}`);
  return { bytes: readFileSync(path), stats };
}

function requireDirectory(path, label) {
  if (!existsSync(path))
    fail(`${label} does not exist: ${path}`);
  const stats = lstatSync(path);
  if (!stats.isDirectory() || stats.isSymbolicLink())
    fail(`${label} must be a regular nonsymlink directory: ${path}`);
}

function requireContainedNonsymlinkPath(root, path, label) {
  const contained = relative(root, path);
  if (contained === '..' || contained.startsWith(`..${sep}`) || isAbsolute(contained))
    fail(`${label} escapes its declared root: ${path}`);
  let component = root;
  for (const segment of contained.split(sep).filter((value) => value.length !== 0)) {
    component = resolve(component, segment);
    if (!existsSync(component))
      return;
    if (lstatSync(component).isSymbolicLink())
      fail(`${label} contains a symlink path component: ${component}`);
  }
}

function isXmlCharacter(codePoint) {
  return codePoint === 0x9
    || codePoint === 0xa
    || codePoint === 0xd
    || (codePoint >= 0x20 && codePoint <= 0xd7ff)
    || (codePoint >= 0xe000 && codePoint <= 0xfffd)
    || (codePoint >= 0x10000 && codePoint <= 0x10ffff);
}

function decodeXmlAttribute(raw, label) {
  let result = '';
  for (let index = 0; index < raw.length;) {
    if (raw[index] !== '&') {
      result += raw[index];
      index += 1;
      continue;
    }
    const end = raw.indexOf(';', index + 1);
    if (end === -1)
      fail(`${label} contains an unterminated XML entity.`);
    const entity = raw.slice(index + 1, end);
    const predefined = {
      amp: '&',
      apos: "'",
      gt: '>',
      lt: '<',
      quot: '"',
    }[entity];
    if (predefined !== undefined) {
      result += predefined;
    } else {
      const match = entity.match(/^#(?:x([0-9A-Fa-f]+)|([0-9]+))$/);
      if (match === null)
        fail(`${label} contains an unsupported XML entity: &${entity};`);
      const codePoint = Number.parseInt(match[1] ?? match[2], match[1] === undefined ? 10 : 16);
      if (!Number.isSafeInteger(codePoint) || !isXmlCharacter(codePoint))
        fail(`${label} contains an invalid XML character reference: &${entity};`);
      result += String.fromCodePoint(codePoint);
    }
    index = end + 1;
  }
  return result;
}

class XmlParser {
  constructor(text) {
    this.text = text;
    this.offset = 0;
    this.nodeCount = 0;
  }

  parse() {
    if (!this.text.startsWith(XML_DECLARATION))
      fail(`SpotBugs exclusion filter must start with ${XML_DECLARATION}.`);
    this.offset = XML_DECLARATION.length;
    this.skipTrivia();
    const root = this.parseElement(0);
    this.skipTrivia();
    if (this.offset !== this.text.length)
      fail(`Unexpected XML content at byte ${this.offset}.`);
    return root;
  }

  skipWhitespace() {
    while (this.offset < this.text.length && /[\t\n ]/.test(this.text[this.offset]))
      this.offset += 1;
  }

  skipTrivia() {
    for (;;) {
      this.skipWhitespace();
      if (!this.text.startsWith('<!--', this.offset))
        return;
      const end = this.text.indexOf('-->', this.offset + 4);
      if (end === -1)
        fail(`Unterminated XML comment at byte ${this.offset}.`);
      const body = this.text.slice(this.offset + 4, end);
      if (body.includes('--'))
        fail(`Invalid -- sequence in XML comment at byte ${this.offset}.`);
      this.offset = end + 3;
    }
  }

  parseName(label) {
    const match = this.text.slice(this.offset).match(/^[A-Za-z_][A-Za-z0-9_.:-]*/);
    if (match === null)
      fail(`Expected ${label} at byte ${this.offset}.`);
    this.offset += match[0].length;
    return match[0];
  }

  parseAttributeValue(elementName, attributeName) {
    const quote = this.text[this.offset];
    if (quote !== '"' && quote !== "'")
      fail(`Expected a quoted value for ${elementName}.${attributeName}.`);
    this.offset += 1;
    const start = this.offset;
    const end = this.text.indexOf(quote, start);
    if (end === -1)
      fail(`Unterminated value for ${elementName}.${attributeName}.`);
    const raw = this.text.slice(start, end);
    if (raw.includes('<'))
      fail(`${elementName}.${attributeName} contains a raw < character.`);
    this.offset = end + 1;
    return decodeXmlAttribute(raw, `${elementName}.${attributeName}`);
  }

  parseElement(depth) {
    if (depth > MAXIMUM_XML_DEPTH)
      fail(`SpotBugs exclusion filter exceeds XML depth ${MAXIMUM_XML_DEPTH}.`);
    if (!this.text.startsWith('<', this.offset)
        || this.text.startsWith('</', this.offset)
        || this.text.startsWith('<!', this.offset)
        || this.text.startsWith('<?', this.offset)) {
      fail(`Expected an XML element at byte ${this.offset}.`);
    }
    this.offset += 1;
    const name = this.parseName('an element name');
    const attributes = {};
    let selfClosing = false;
    for (;;) {
      const beforeWhitespace = this.offset;
      this.skipWhitespace();
      if (this.text.startsWith('/>', this.offset)) {
        selfClosing = true;
        this.offset += 2;
        break;
      }
      if (this.text[this.offset] === '>') {
        this.offset += 1;
        break;
      }
      if (this.offset === beforeWhitespace)
        fail(`Expected whitespace before an attribute on <${name}>.`);
      const attributeName = this.parseName(`an attribute name on <${name}>`);
      if (Object.hasOwn(attributes, attributeName))
        fail(`<${name}> contains duplicate attribute ${attributeName}.`);
      this.skipWhitespace();
      if (this.text[this.offset] !== '=')
        fail(`Expected = after ${name}.${attributeName}.`);
      this.offset += 1;
      this.skipWhitespace();
      attributes[attributeName] = this.parseAttributeValue(name, attributeName);
    }

    this.nodeCount += 1;
    if (this.nodeCount > MAXIMUM_XML_NODES)
      fail(`SpotBugs exclusion filter exceeds ${MAXIMUM_XML_NODES} XML elements.`);
    const node = { attributes, children: [], name, selfClosing };
    if (selfClosing)
      return node;

    for (;;) {
      this.skipTrivia();
      if (this.text.startsWith('</', this.offset)) {
        this.offset += 2;
        const closingName = this.parseName('a closing element name');
        this.skipWhitespace();
        if (this.text[this.offset] !== '>')
          fail(`Malformed closing tag for </${closingName}>.`);
        this.offset += 1;
        if (closingName !== name)
          fail(`Mismatched closing tag </${closingName}> for <${name}>.`);
        return node;
      }
      if (this.offset >= this.text.length)
        fail(`Unterminated <${name}> element.`);
      if (this.text[this.offset] !== '<')
        fail(`Non-whitespace text is not allowed inside <${name}>.`);
      node.children.push(this.parseElement(depth + 1));
    }
  }
}

function exactAttributeNames(node, names, label) {
  const actual = Object.keys(node.attributes).sort(compareAscii);
  const expected = [...names].sort(compareAscii);
  if (actual.length !== expected.length
      || actual.some((value, index) => value !== expected[index])) {
    fail(`${label} attributes must be exactly: ${expected.join(', ')}.`);
  }
}

function validateLeaf(node) {
  const allowed = LEAF_ATTRIBUTES[node.name];
  if (allowed === undefined)
    fail(`Unsupported SpotBugs filter element <${node.name}>.`);
  if (!node.selfClosing || node.children.length !== 0)
    fail(`<${node.name}> must be self-closing.`);
  const actual = Object.keys(node.attributes);
  for (const name of actual) {
    if (!allowed.includes(name))
      fail(`<${node.name}> contains unsupported attribute ${name}.`);
    if (node.attributes[name].length === 0)
      fail(`<${node.name}> attribute ${name} must not be empty.`);
  }
  if (actual.length === 0)
    fail(`<${node.name}> must contain at least one selector attribute.`);
}

function validateXmlTree(root) {
  if (root.name !== 'FindBugsFilter')
    fail('SpotBugs exclusion filter root must be <FindBugsFilter>.');
  if (root.selfClosing)
    fail('<FindBugsFilter> must not be self-closing.');
  exactAttributeNames(root, [], '<FindBugsFilter>');
  for (const child of root.children) {
    if (child.name !== 'Match')
      fail(`<FindBugsFilter> may contain only <Match>, found <${child.name}>.`);
    validateCondition(child);
  }
}

function validateCondition(node) {
  if (!COMPOUND_ELEMENTS.has(node.name)) {
    validateLeaf(node);
    return;
  }
  if (node.selfClosing)
    fail(`<${node.name}> must not be self-closing.`);
  exactAttributeNames(node, [], `<${node.name}>`);
  const minimumChildren = node.name === 'And' || node.name === 'Or' ? 2 : 1;
  if (node.children.length < minimumChildren)
    fail(`<${node.name}> must contain at least ${minimumChildren} condition(s).`);
  for (const child of node.children) {
    if (child.name === 'Match' || child.name === 'FindBugsFilter')
      fail(`<${node.name}> may not contain <${child.name}>.`);
    validateCondition(child);
  }
}

function descendants(node, name, result = []) {
  for (const child of node.children) {
    if (child.name === name)
      result.push(child);
    descendants(child, name, result);
  }
  return result;
}

function assertExactClassName(value, label) {
  if (value.startsWith('~'))
    fail(`${label} uses a regex; exact class selectors are required.`);
  if (/[\s/\\;\[]/.test(value) || value === '.' || value.startsWith('.') || value.endsWith('.'))
    fail(`${label} is not an exact binary class name: ${value}`);
}

function assertExactMethodName(value, label) {
  if (value.startsWith('~'))
    fail(`${label} uses a regex; exact method selectors are required.`);
  if (/[\s./\\;\[]/.test(value))
    fail(`${label} is not an exact JVM method name: ${value}`);
}

function targetsFromMatch(match, matchIndex) {
  const classNodes = descendants(match, 'Class');
  const methodNodes = descendants(match, 'Method');
  if (classNodes.length === 0 && methodNodes.length === 0)
    return null;
  const label = `SpotBugs Match ${matchIndex + 1}`;
  if (classNodes.length !== 1 || !match.children.includes(classNodes[0]))
    fail(`${label} must contain exactly one direct exact Class selector.`);
  const classNode = classNodes[0];
  exactAttributeNames(classNode, ['name'], `${label} Class`);
  const className = classNode.attributes.name;
  assertExactClassName(className, `${label} Class`);

  const bugNodes = match.children.filter((child) => child.name === 'Bug');
  if (bugNodes.length !== 1)
    fail(`${label} must contain exactly one direct Bug selector.`);
  let selectedMethodNodes = [];
  const directMethods = match.children.filter((child) => child.name === 'Method');
  const compounds = match.children.filter((child) => COMPOUND_ELEMENTS.has(child.name));
  if (methodNodes.length !== 0) {
    if (directMethods.length === 1 && methodNodes.length === 1 && compounds.length === 0) {
      selectedMethodNodes = directMethods;
    } else {
      const methodGroups = match.children.filter((child) => child.name === 'Or'
        && child.children.length >= 2
        && child.children.every((grandchild) => grandchild.name === 'Method'));
      if (methodGroups.length !== 1
          || compounds.length !== 1
          || methodGroups[0].children.length !== methodNodes.length) {
        fail(`${label} methods must be one direct Method or one direct Or containing only Methods.`);
      }
      selectedMethodNodes = methodGroups[0].children;
    }
  } else if (compounds.length !== 0) {
    fail(`${label} contains an unsupported compound target condition.`);
  }

  const allowedDirect = new Set([classNode, bugNodes[0], ...directMethods, ...compounds]);
  if (match.children.some((child) => !allowedDirect.has(child)))
    fail(`${label} contains an unsupported target condition.`);
  const methodNames = selectedMethodNodes.map((node, methodIndex) => {
    exactAttributeNames(node, ['name'], `${label} Method ${methodIndex + 1}`);
    const name = node.attributes.name;
    assertExactMethodName(name, `${label} Method ${methodIndex + 1}`);
    return name;
  });
  if (new Set(methodNames).size !== methodNames.length)
    fail(`${label} contains duplicate Method selectors.`);
  return Object.freeze({ className, matchIndex: matchIndex + 1, methodNames });
}

function validateApprovedUnscopedMatch(match, matchIndex, state) {
  const label = `SpotBugs Match ${matchIndex + 1}`;
  if (match.children.length !== 1)
    fail(`${label} is unscoped and must match one approved Package or Bug selector.`);
  const selector = match.children[0];
  if (selector.name === 'Package') {
    exactAttributeNames(selector, ['name'], `${label} Package`);
    if (selector.attributes.name !== APPROVED_UNSCOPED_PACKAGE)
      fail(`${label} contains an unapproved unscoped Package selector.`);
    if (state.packageSeen)
      fail(`${label} duplicates the approved unscoped Package selector.`);
    state.packageSeen = true;
    state.broadMatchCount += 1;
    return;
  }
  if (selector.name !== 'Bug')
    fail(`${label} contains an unapproved unscoped <${selector.name}> selector.`);
  exactAttributeNames(selector, ['pattern'], `${label} Bug`);
  const patterns = selector.attributes.pattern.split(',');
  if (patterns.length === 0 || patterns.some((pattern) => pattern.length === 0))
    fail(`${label} contains an empty unscoped Bug pattern.`);
  if (new Set(patterns).size !== patterns.length)
    fail(`${label} contains duplicate unscoped Bug patterns.`);
  for (const pattern of patterns) {
    if (!APPROVED_UNSCOPED_BUG_PATTERNS.has(pattern))
      fail(`${label} contains unapproved unscoped Bug pattern ${pattern}.`);
    if (state.bugPatterns.has(pattern))
      fail(`${label} repeats unscoped Bug pattern ${pattern}.`);
    state.bugPatterns.add(pattern);
  }
  state.broadMatchCount += 1;
}

function parseFilter(bytes) {
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes))
    fail('SpotBugs exclusion filter is not valid UTF-8.');
  if (text.includes('\r'))
    fail('SpotBugs exclusion filter must use LF line endings.');
  if (!text.endsWith('\n'))
    fail('SpotBugs exclusion filter must end with one LF.');
  if (/<!DOCTYPE|<!ENTITY|<\?[^x]/i.test(text))
    fail('SpotBugs exclusion filter may not contain DTDs, entities, or processing instructions.');
  const root = new XmlParser(text).parse();
  validateXmlTree(root);
  const targets = [];
  const unscopedState = {
    broadMatchCount: 0,
    bugPatterns: new Set(),
    packageSeen: false,
  };
  root.children.forEach((match, index) => {
    const target = targetsFromMatch(match, index);
    if (target === null)
      validateApprovedUnscopedMatch(match, index, unscopedState);
    else
      targets.push(target);
  });
  return {
    broadBugPatternCount: unscopedState.bugPatterns.size,
    broadMatchCount: unscopedState.broadMatchCount,
    broadPackageMatchCount: unscopedState.packageSeen ? 1 : 0,
    targets,
  };
}

class ClassReader {
  constructor(bytes, label) {
    this.bytes = bytes;
    this.label = label;
    this.offset = 0;
  }

  require(count) {
    if (!Number.isSafeInteger(count) || count < 0 || this.offset + count > this.bytes.length)
      fail(`${this.label} is truncated or contains an invalid length at byte ${this.offset}.`);
  }

  u1() {
    this.require(1);
    const value = this.bytes.readUInt8(this.offset);
    this.offset += 1;
    return value;
  }

  u2() {
    this.require(2);
    const value = this.bytes.readUInt16BE(this.offset);
    this.offset += 2;
    return value;
  }

  u4() {
    this.require(4);
    const value = this.bytes.readUInt32BE(this.offset);
    this.offset += 4;
    return value;
  }

  take(count) {
    this.require(count);
    const value = this.bytes.subarray(this.offset, this.offset + count);
    this.offset += count;
    return value;
  }

  skip(count) {
    this.take(count);
  }
}

function decodeModifiedUtf8(bytes, label) {
  const units = [];
  for (let index = 0; index < bytes.length;) {
    const first = bytes[index];
    if (first >= 0x01 && first <= 0x7f) {
      units.push(first);
      index += 1;
      continue;
    }
    if ((first & 0xe0) === 0xc0) {
      if (index + 1 >= bytes.length || (bytes[index + 1] & 0xc0) !== 0x80)
        fail(`${label} contains malformed modified UTF-8.`);
      const value = ((first & 0x1f) << 6) | (bytes[index + 1] & 0x3f);
      if (value < 0x80 && !(first === 0xc0 && bytes[index + 1] === 0x80))
        fail(`${label} contains noncanonical modified UTF-8.`);
      units.push(value);
      index += 2;
      continue;
    }
    if ((first & 0xf0) === 0xe0) {
      if (index + 2 >= bytes.length
          || (bytes[index + 1] & 0xc0) !== 0x80
          || (bytes[index + 2] & 0xc0) !== 0x80) {
        fail(`${label} contains malformed modified UTF-8.`);
      }
      const value = ((first & 0x0f) << 12)
        | ((bytes[index + 1] & 0x3f) << 6)
        | (bytes[index + 2] & 0x3f);
      if (value < 0x800)
        fail(`${label} contains noncanonical modified UTF-8.`);
      units.push(value);
      index += 3;
      continue;
    }
    fail(`${label} contains malformed modified UTF-8.`);
  }
  let result = '';
  for (let index = 0; index < units.length; index += 8192)
    result += String.fromCharCode(...units.slice(index, index + 8192));
  return result;
}

function utf8Entry(constantPool, index, label) {
  const entry = constantPool[index];
  if (entry?.tag !== 1)
    fail(`${label} references invalid CONSTANT_Utf8 index ${index}.`);
  return entry.value;
}

function skipAttributes(reader, constantPool) {
  const count = reader.u2();
  for (let index = 0; index < count; index += 1) {
    utf8Entry(constantPool, reader.u2(), `${reader.label} attribute ${index + 1}`);
    reader.skip(reader.u4());
  }
}

function parseClassFile(bytes, label) {
  const reader = new ClassReader(bytes, label);
  if (reader.u4() !== 0xcafebabe)
    fail(`${label} has an invalid JVM classfile magic value.`);
  reader.u2();
  reader.u2();
  const constantPoolCount = reader.u2();
  if (constantPoolCount === 0)
    fail(`${label} has an invalid constant-pool count.`);
  const constantPool = new Array(constantPoolCount);
  for (let index = 1; index < constantPoolCount; index += 1) {
    const tag = reader.u1();
    if (tag === 1) {
      const value = decodeModifiedUtf8(reader.take(reader.u2()), `${label} constant ${index}`);
      constantPool[index] = { tag, value };
    } else if (tag === 7) {
      constantPool[index] = { nameIndex: reader.u2(), tag };
    } else if (tag === 3 || tag === 4) {
      reader.skip(4);
      constantPool[index] = { tag };
    } else if (tag === 5 || tag === 6) {
      reader.skip(8);
      constantPool[index] = { tag };
      index += 1;
      if (index >= constantPoolCount)
        fail(`${label} contains an invalid two-slot constant at the end of its pool.`);
    } else if (tag === 8 || tag === 16 || tag === 19 || tag === 20) {
      reader.skip(2);
      constantPool[index] = { tag };
    } else if (tag === 9 || tag === 10 || tag === 11 || tag === 12
        || tag === 17 || tag === 18) {
      reader.skip(4);
      constantPool[index] = { tag };
    } else if (tag === 15) {
      reader.skip(3);
      constantPool[index] = { tag };
    } else {
      fail(`${label} contains unsupported constant-pool tag ${tag} at index ${index}.`);
    }
  }

  reader.u2();
  const thisClassIndex = reader.u2();
  reader.u2();
  const classEntry = constantPool[thisClassIndex];
  if (classEntry?.tag !== 7)
    fail(`${label} references invalid this_class index ${thisClassIndex}.`);
  const internalName = utf8Entry(
    constantPool,
    classEntry.nameIndex,
    `${label} this_class`,
  );
  const interfaceCount = reader.u2();
  reader.skip(interfaceCount * 2);
  const fieldCount = reader.u2();
  for (let index = 0; index < fieldCount; index += 1) {
    reader.u2();
    utf8Entry(constantPool, reader.u2(), `${label} field ${index + 1} name`);
    utf8Entry(constantPool, reader.u2(), `${label} field ${index + 1} descriptor`);
    skipAttributes(reader, constantPool);
  }
  const methodCount = reader.u2();
  const methods = new Map();
  for (let index = 0; index < methodCount; index += 1) {
    reader.u2();
    const name = utf8Entry(constantPool, reader.u2(), `${label} method ${index + 1} name`);
    const descriptor = utf8Entry(
      constantPool,
      reader.u2(),
      `${label} method ${index + 1} descriptor`,
    );
    let descriptors = methods.get(name);
    if (descriptors === undefined) {
      descriptors = new Set();
      methods.set(name, descriptors);
    }
    if (descriptors.has(descriptor))
      fail(`${label} contains duplicate method ${name}${descriptor}.`);
    descriptors.add(descriptor);
    skipAttributes(reader, constantPool);
  }

  let sourceFile = null;
  const attributeCount = reader.u2();
  for (let index = 0; index < attributeCount; index += 1) {
    const name = utf8Entry(constantPool, reader.u2(), `${label} class attribute ${index + 1}`);
    const length = reader.u4();
    if (name === 'SourceFile') {
      if (sourceFile !== null || length !== 2)
        fail(`${label} contains an invalid SourceFile attribute.`);
      sourceFile = utf8Entry(constantPool, reader.u2(), `${label} SourceFile`);
    } else {
      reader.skip(length);
    }
  }
  if (reader.offset !== bytes.length)
    fail(`${label} contains ${bytes.length - reader.offset} trailing byte(s).`);
  return { internalName, methods, sourceFile };
}

function inventoryClasses(classesDirectory) {
  requireDirectory(classesDirectory, 'Compiled-class directory');
  const inventory = new Map();
  let classCount = 0;

  function visit(directory, relativeDirectory) {
    const entries = readdirSync(directory).sort(compareAscii);
    for (const name of entries) {
      const path = resolve(directory, name);
      const relativePath = relativeDirectory.length === 0 ? name : `${relativeDirectory}/${name}`;
      const stats = lstatSync(path);
      if (stats.isSymbolicLink())
        fail(`Compiled-class inventory contains a symlink: ${relativePath}`);
      if (stats.isDirectory()) {
        visit(path, relativePath);
      } else if (stats.isFile()) {
        if (!name.endsWith('.class'))
          continue;
        classCount += 1;
        if (classCount > MAXIMUM_CLASS_COUNT)
          fail(`Compiled-class inventory exceeds ${MAXIMUM_CLASS_COUNT} classfiles.`);
        if (stats.size <= 0 || stats.size > MAXIMUM_CLASS_BYTES)
          fail(`Compiled class has an invalid size (${stats.size} bytes): ${relativePath}`);
        const parsed = parseClassFile(readFileSync(path), `Compiled class ${relativePath}`);
        const expectedPath = `${parsed.internalName}.class`;
        if (relativePath !== expectedPath)
          fail(`Compiled class path ${relativePath} does not match binary name ${parsed.internalName}.`);
        const binaryName = parsed.internalName.replaceAll('/', '.');
        if (inventory.has(binaryName))
          fail(`Compiled-class inventory contains duplicate binary class ${binaryName}.`);
        inventory.set(binaryName, {
          ...parsed,
          binaryName,
          classPath: path,
          classStats: stats,
          relativePath,
        });
      } else {
        fail(`Compiled-class inventory contains a non-regular entry: ${relativePath}`);
      }
    }
  }

  visit(classesDirectory, '');
  if (classCount === 0)
    fail(`Compiled-class directory contains no .class files: ${classesDirectory}`);
  return inventory;
}

function sourceForClass(entry, sourceDirectory) {
  if (entry.sourceFile === null)
    fail(`Selected class ${entry.binaryName} has no SourceFile attribute.`);
  if (entry.sourceFile === '.' || entry.sourceFile === '..'
      || entry.sourceFile.includes('/') || entry.sourceFile.includes('\\')) {
    fail(`Selected class ${entry.binaryName} has an invalid SourceFile name: ${entry.sourceFile}`);
  }
  const slash = entry.internalName.lastIndexOf('/');
  const packagePath = slash === -1 ? '' : entry.internalName.slice(0, slash);
  const sourcePath = resolve(sourceDirectory, packagePath, entry.sourceFile);
  const relativePath = relative(sourceDirectory, sourcePath);
  if (relativePath === '..' || relativePath.startsWith(`..${sep}`) || isAbsolute(relativePath))
    fail(`Selected class ${entry.binaryName} source escapes the source root.`);
  requireContainedNonsymlinkPath(
    sourceDirectory,
    sourcePath,
    `Selected class ${entry.binaryName} source`,
  );
  if (!existsSync(sourcePath))
    fail(`Selected class ${entry.binaryName} source does not exist: ${sourcePath}`);
  const stats = lstatSync(sourcePath);
  if (!stats.isFile() || stats.isSymbolicLink())
    fail(`Selected class ${entry.binaryName} source must be a regular nonsymlink file: ${sourcePath}`);
  if (entry.classStats.mtimeMs < stats.mtimeMs)
    fail(`Selected class ${entry.binaryName} is older than its source; run a clean compile.`);
  return sourcePath;
}

export function verifySpotbugsExclusions(options = {}) {
  const defaultRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const projectRoot = resolve(options.projectRoot ?? defaultRoot);
  const filterPath = resolve(
    options.filterPath ?? resolve(projectRoot, 'config/spotbugs-exclude.xml'),
  );
  const classesDirectory = resolve(
    options.classesDirectory ?? resolve(projectRoot, 'target/classes'),
  );
  const sourceDirectory = resolve(
    options.sourceDirectory ?? resolve(projectRoot, 'src/main/java'),
  );
  requireDirectory(projectRoot, 'Project root');
  requireContainedNonsymlinkPath(projectRoot, filterPath, 'SpotBugs exclusion filter');
  requireContainedNonsymlinkPath(projectRoot, classesDirectory, 'Compiled-class directory');
  requireContainedNonsymlinkPath(projectRoot, sourceDirectory, 'Main-source directory');
  requireDirectory(sourceDirectory, 'Main-source directory');
  const { bytes } = readRegularFile(
    filterPath,
    'SpotBugs exclusion filter',
    MAXIMUM_FILTER_BYTES,
  );
  const parsedFilter = parseFilter(bytes);
  const { targets } = parsedFilter;
  const inventory = inventoryClasses(classesDirectory);
  const selectedSources = new Set();
  for (const target of targets) {
    const entry = inventory.get(target.className);
    if (entry === undefined) {
      fail(`SpotBugs Match ${target.matchIndex} class does not resolve in compiled inventory: ${target.className}`);
    }
    selectedSources.add(sourceForClass(entry, sourceDirectory));
    for (const methodName of target.methodNames) {
      if (!entry.methods.has(methodName)) {
        fail(`SpotBugs Match ${target.matchIndex} method does not resolve: ${target.className}.${methodName}`);
      }
    }
  }
  return Object.freeze({
    broadBugPatternCount: parsedFilter.broadBugPatternCount,
    broadMatchCount: parsedFilter.broadMatchCount,
    broadPackageMatchCount: parsedFilter.broadPackageMatchCount,
    classMatchCount: targets.length,
    compiledClassCount: inventory.size,
    methodSelectorCount: targets.reduce((count, target) => count + target.methodNames.length, 0),
    sourceFileCount: selectedSources.size,
    uniqueClassCount: new Set(targets.map(({ className }) => className)).size,
  });
}

function isMainModule() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isMainModule()) {
  if (process.argv.length !== 2) {
    console.error('Usage: node scripts/verify-spotbugs-exclusions.mjs');
    process.exitCode = 64;
  } else {
    try {
      const result = verifySpotbugsExclusions();
      console.log(
        `Verified ${result.classMatchCount} SpotBugs class-scoped exclusion match(es) `
        + `(${result.uniqueClassCount} unique class(es), ${result.methodSelectorCount} method selector(s), `
        + `${result.sourceFileCount} source file(s)) and ${result.broadMatchCount} approved unscoped match(es) `
        + `(${result.broadBugPatternCount} bug pattern(s), ${result.broadPackageMatchCount} package selector(s)) `
        + `against ${result.compiledClassCount} compiled class(es).`,
      );
    } catch (error) {
      console.error(error instanceof Error ? error.message : String(error));
      process.exitCode = 1;
    }
  }
}
