#!/usr/bin/env node

import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import {
  mkdirSync,
  mkdtempSync,
  rmSync,
  symlinkSync,
  utimesSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  SpotbugsExclusionVerificationError,
  verifySpotbugsExclusions,
} from './verify-spotbugs-exclusions.mjs';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const verifierPath = resolve(scriptDirectory, 'verify-spotbugs-exclusions.mjs');
const temporaryRoot = mkdtempSync(resolve(tmpdir(), 'soklet-spotbugs-exclusions-'));
let fixtureCounter = 0;

function u1(value) {
  const bytes = Buffer.alloc(1);
  bytes.writeUInt8(value);
  return bytes;
}

function u2(value) {
  const bytes = Buffer.alloc(2);
  bytes.writeUInt16BE(value);
  return bytes;
}

function u4(value) {
  const bytes = Buffer.alloc(4);
  bytes.writeUInt32BE(value);
  return bytes;
}

function classFile(internalName, sourceFile, methodDefinitions = []) {
  const constants = [null];
  const utf8Indices = new Map();

  function add(entry) {
    constants.push(entry);
    return constants.length - 1;
  }

  function utf8(value) {
    const existing = utf8Indices.get(value);
    if (existing !== undefined)
      return existing;
    const bytes = Buffer.from(value, 'utf8');
    const index = add(Buffer.concat([u1(1), u2(bytes.length), bytes]));
    utf8Indices.set(value, index);
    return index;
  }

  function classConstant(name) {
    return add(Buffer.concat([u1(7), u2(utf8(name))]));
  }

  function nameAndType(name, descriptor) {
    return add(Buffer.concat([u1(12), u2(utf8(name)), u2(utf8(descriptor))]));
  }

  const thisClass = classConstant(internalName);
  const objectClass = classConstant('java/lang/Object');
  const initName = utf8('<init>');
  const voidDescriptor = utf8('()V');
  const codeName = utf8('Code');
  const objectInit = add(Buffer.concat([
    u1(10),
    u2(objectClass),
    u2(nameAndType('<init>', '()V')),
  ]));
  const sourceFileName = utf8('SourceFile');
  const sourceFileValue = utf8(sourceFile);

  const constructorCode = Buffer.concat([
    u2(1),
    u2(1),
    u4(5),
    Buffer.from([0x2a, 0xb7]),
    u2(objectInit),
    Buffer.from([0xb1]),
    u2(0),
    u2(0),
  ]);
  const constructor = Buffer.concat([
    u2(0x0001),
    u2(initName),
    u2(voidDescriptor),
    u2(1),
    u2(codeName),
    u4(constructorCode.length),
    constructorCode,
  ]);
  const methods = methodDefinitions.map(({ descriptor = '()V', name }) => {
    const code = Buffer.concat([
      u2(0),
      u2(descriptor === '(I)V' ? 2 : 1),
      u4(1),
      Buffer.from([0xb1]),
      u2(0),
      u2(0),
    ]);
    return Buffer.concat([
      u2(0x0001),
      u2(utf8(name)),
      u2(utf8(descriptor)),
      u2(1),
      u2(codeName),
      u4(code.length),
      code,
    ]);
  });

  return Buffer.concat([
    u4(0xcafebabe),
    u2(0),
    u2(61),
    u2(constants.length),
    ...constants.slice(1),
    u2(0x0021),
    u2(thisClass),
    u2(objectClass),
    u2(0),
    u2(0),
    u2(methods.length + 1),
    constructor,
    ...methods,
    u2(1),
    u2(sourceFileName),
    u4(2),
    u2(sourceFileValue),
  ]);
}

function xml(body) {
  return `<?xml version="1.0" encoding="UTF-8"?>\n<FindBugsFilter>\n${body}</FindBugsFilter>\n`;
}

function write(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, value);
}

function createLayout(name, classes) {
  const root = resolve(temporaryRoot, name);
  const classesDirectory = resolve(root, 'classes');
  const sourceDirectory = resolve(root, 'src/main/java');
  mkdirSync(classesDirectory, { recursive: true });
  mkdirSync(sourceDirectory, { recursive: true });
  for (const definition of classes) {
    const slash = definition.internalName.lastIndexOf('/');
    const packagePath = slash === -1 ? '' : definition.internalName.slice(0, slash);
    write(
      resolve(sourceDirectory, packagePath, definition.sourceFile),
      definition.sourceText ?? `package ${packagePath.replaceAll('/', '.')};\n`,
    );
  }
  for (const definition of classes) {
    const bytes = definition.bytes ?? classFile(
      definition.internalName,
      definition.sourceFile,
      definition.methods,
    );
    write(resolve(classesDirectory, `${definition.pathName ?? definition.internalName}.class`), bytes);
  }
  return { classesDirectory, root, sourceDirectory };
}

function verifyFilter(layout, body, raw = false) {
  fixtureCounter += 1;
  const filterPath = resolve(temporaryRoot, `filter-${fixtureCounter}.xml`);
  writeFileSync(filterPath, raw ? body : xml(body));
  return verifySpotbugsExclusions({
    classesDirectory: layout.classesDirectory,
    filterPath,
    projectRoot: temporaryRoot,
    sourceDirectory: layout.sourceDirectory,
  });
}

function expectInvalid(layout, name, body, expected, raw = false) {
  assert.throws(
    () => verifyFilter(layout, body, raw),
    (error) => error instanceof SpotbugsExclusionVerificationError
      && expected.test(error.message),
    name,
  );
}

const broadMatches = [
  '  <Match>\n    <Package name="~com\\.soklet\\.internal\\.spring(\\..*)?"/>\n  </Match>\n',
  '  <Match>\n    <Bug pattern="EI_EXPOSE_REP"/>\n  </Match>\n',
].join('');
const targetMatch = '  <Match>\n'
  + '    <Class name="fixture.Target"/>\n'
  + '    <Or>\n'
  + '      <Method name="start"/>\n'
  + '      <Method name="stop"/>\n'
  + '    </Or>\n'
  + '    <Bug pattern="UL_UNRELEASED_LOCK_EXCEPTION_PATH"/>\n'
  + '  </Match>\n';
const nestedMatch = '  <Match>\n'
  + '    <Class name="fixture.Outer$Inner"/>\n'
  + '    <Method name="nested"/>\n'
  + '    <Bug pattern="RV_RETURN_VALUE_IGNORED_BAD_PRACTICE"/>\n'
  + '  </Match>\n';
const classOnlyMatch = '  <Match>\n'
  + '    <Class name="fixture.ClassOnly"/>\n'
  + '    <Bug pattern="SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR"/>\n'
  + '  </Match>\n';

try {
  const valid = createLayout('valid', [
    {
      internalName: 'fixture/Target',
      methods: [
        { name: 'start' },
        { descriptor: '(I)V', name: 'start' },
        { name: 'stop' },
      ],
      sourceFile: 'Target.java',
    },
    {
      internalName: 'fixture/Outer$Inner',
      methods: [{ name: 'nested' }],
      sourceFile: 'Outer.java',
    },
    {
      internalName: 'fixture/ClassOnly',
      methods: [],
      sourceFile: 'ClassOnly.java',
    },
  ]);

  const verified = verifyFilter(
    valid,
    broadMatches + targetMatch + nestedMatch + classOnlyMatch,
  );
  assert.deepEqual(verified, {
    broadBugPatternCount: 1,
    broadMatchCount: 2,
    broadPackageMatchCount: 1,
    classMatchCount: 3,
    compiledClassCount: 3,
    methodSelectorCount: 3,
    sourceFileCount: 3,
    uniqueClassCount: 3,
  });
  const tightened = verifyFilter(valid, targetMatch);
  assert.equal(tightened.broadMatchCount, 0);
  assert.equal(tightened.broadBugPatternCount, 0);

  expectInvalid(
    valid,
    'scoped exclusion may not become global',
    '  <Match>\n    <Bug pattern="UL_UNRELEASED_LOCK_EXCEPTION_PATH"/>\n  </Match>\n',
    /unapproved unscoped Bug pattern UL_UNRELEASED_LOCK_EXCEPTION_PATH/,
  );
  expectInvalid(
    valid,
    'global bug baseline may not expand',
    '  <Match>\n    <Bug pattern="EI_EXPOSE_REP,NEW_GLOBAL_PATTERN"/>\n  </Match>\n',
    /unapproved unscoped Bug pattern NEW_GLOBAL_PATTERN/,
  );
  expectInvalid(
    valid,
    'global package baseline may not expand',
    '  <Match>\n    <Package name="~fixture(\\..*)?"/>\n  </Match>\n',
    /unapproved unscoped Package selector/,
  );
  expectInvalid(
    valid,
    'global selector shape may not expand',
    '  <Match>\n    <Source name="~.*"/>\n  </Match>\n',
    /unapproved unscoped <Source> selector/,
  );

  expectInvalid(
    valid,
    'missing class',
    targetMatch.replace('fixture.Target', 'fixture.Renamed'),
    /class does not resolve.*fixture\.Renamed/,
  );
  expectInvalid(
    valid,
    'renamed method',
    targetMatch.replace('name="stop"', 'name="stopped"'),
    /method does not resolve.*fixture\.Target\.stopped/,
  );
  expectInvalid(
    valid,
    'nested class must use binary name',
    nestedMatch.replace('Outer$Inner', 'Outer.Inner'),
    /class does not resolve.*Outer\.Inner/,
  );
  expectInvalid(
    valid,
    'method without class',
    '  <Match>\n    <Method name="start"/>\n    <Bug pattern="X"/>\n  </Match>\n',
    /exactly one direct exact Class selector/,
  );
  expectInvalid(
    valid,
    'multiple classes',
    '  <Match>\n    <Class name="fixture.Target"/>\n'
      + '    <Class name="fixture.ClassOnly"/>\n    <Bug pattern="X"/>\n  </Match>\n',
    /exactly one direct exact Class selector/,
  );
  expectInvalid(
    valid,
    'duplicate method',
    targetMatch.replace('<Method name="stop"/>', '<Method name="start"/>'),
    /duplicate Method selectors/,
  );
  expectInvalid(
    valid,
    'regex class',
    classOnlyMatch.replace('fixture.ClassOnly', '~fixture\\..*'),
    /regex; exact class selectors are required/,
  );
  expectInvalid(
    valid,
    'regex method',
    nestedMatch.replace('name="nested"', 'name="~nest.*"'),
    /regex; exact method selectors are required/,
  );
  expectInvalid(
    valid,
    'signature attributes require verifier extension',
    nestedMatch.replace('name="nested"', 'name="nested" params=""'),
    /attribute params must not be empty/,
  );
  expectInvalid(
    valid,
    'nonempty signature attributes require verifier extension',
    nestedMatch.replace('name="nested"', 'name="nested" returns="void"'),
    /attributes must be exactly: name/,
  );
  expectInvalid(
    valid,
    'ambiguous compound target',
    classOnlyMatch.replace(
      '    <Bug pattern="SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR"/>',
      '    <Not>\n      <Bug pattern="X"/>\n    </Not>\n'
        + '    <Bug pattern="SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR"/>',
    ),
    /unsupported compound target condition/,
  );
  expectInvalid(
    valid,
    'target match requires bug selector',
    classOnlyMatch.replace('    <Bug pattern="SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR"/>\n', ''),
    /exactly one direct Bug selector/,
  );
  expectInvalid(
    valid,
    'malformed XML',
    '<?xml version="1.0" encoding="UTF-8"?>\n<FindBugsFilter><Match></FindBugsFilter>\n',
    /Mismatched closing tag/,
    true,
  );
  expectInvalid(
    valid,
    'DTD rejected',
    '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE FindBugsFilter []>\n<FindBugsFilter>\n</FindBugsFilter>\n',
    /may not contain DTDs/,
    true,
  );
  expectInvalid(
    valid,
    'predefined entity decoded',
    classOnlyMatch.replace('fixture.ClassOnly', 'fixture&amp;evil;ClassOnly'),
    /not an exact binary class name/,
  );
  expectInvalid(
    valid,
    'unsupported entity rejected',
    classOnlyMatch.replace('fixture.ClassOnly', 'fixture&evil;ClassOnly'),
    /unsupported XML entity/,
  );
  expectInvalid(
    valid,
    'unknown element rejected',
    '  <Match>\n    <Unknown value="x"/>\n  </Match>\n',
    /Unsupported SpotBugs filter element/,
  );
  expectInvalid(
    valid,
    'CR line ending rejected',
    xml(classOnlyMatch).replace('\n', '\r\n'),
    /must use LF line endings/,
    true,
  );
  expectInvalid(
    valid,
    'missing final LF rejected',
    xml(classOnlyMatch).slice(0, -1),
    /must end with one LF/,
    true,
  );
  expectInvalid(
    valid,
    'invalid UTF-8 rejected',
    Buffer.concat([Buffer.from('<?xml version="1.0" encoding="UTF-8"?>\n'), Buffer.from([0xff])]),
    /not valid UTF-8/,
    true,
  );

  const missingFilter = resolve(temporaryRoot, 'missing-filter.xml');
  assert.throws(
    () => verifySpotbugsExclusions({
      classesDirectory: valid.classesDirectory,
      filterPath: missingFilter,
      projectRoot: temporaryRoot,
      sourceDirectory: valid.sourceDirectory,
    }),
    /exclusion filter does not exist/,
  );
  const realFilter = resolve(temporaryRoot, 'real-filter.xml');
  const symlinkFilter = resolve(temporaryRoot, 'symlink-filter.xml');
  writeFileSync(realFilter, xml(classOnlyMatch));
  symlinkSync(realFilter, symlinkFilter);
  assert.throws(
    () => verifySpotbugsExclusions({
      classesDirectory: valid.classesDirectory,
      filterPath: symlinkFilter,
      projectRoot: temporaryRoot,
      sourceDirectory: valid.sourceDirectory,
    }),
    /exclusion filter contains a symlink path component/,
  );
  const realFilterDirectory = resolve(temporaryRoot, 'real-filter-directory');
  const linkedFilterDirectory = resolve(temporaryRoot, 'linked-filter-directory');
  write(resolve(realFilterDirectory, 'filter.xml'), xml(classOnlyMatch));
  symlinkSync(realFilterDirectory, linkedFilterDirectory);
  assert.throws(
    () => verifySpotbugsExclusions({
      classesDirectory: valid.classesDirectory,
      filterPath: resolve(linkedFilterDirectory, 'filter.xml'),
      projectRoot: temporaryRoot,
      sourceDirectory: valid.sourceDirectory,
    }),
    /exclusion filter contains a symlink path component/,
  );

  const symlinkClasses = resolve(temporaryRoot, 'symlink-classes');
  symlinkSync(valid.classesDirectory, symlinkClasses);
  assert.throws(
    () => verifySpotbugsExclusions({
      classesDirectory: symlinkClasses,
      filterPath: realFilter,
      projectRoot: temporaryRoot,
      sourceDirectory: valid.sourceDirectory,
    }),
    /Compiled-class directory contains a symlink path component/,
  );
  const linkedLayout = resolve(temporaryRoot, 'linked-layout');
  symlinkSync(valid.root, linkedLayout);
  assert.throws(
    () => verifySpotbugsExclusions({
      classesDirectory: resolve(linkedLayout, 'classes'),
      filterPath: realFilter,
      projectRoot: temporaryRoot,
      sourceDirectory: valid.sourceDirectory,
    }),
    /Compiled-class directory contains a symlink path component/,
  );
  const linkedSourceParent = resolve(temporaryRoot, 'linked-source-parent');
  symlinkSync(resolve(valid.root, 'src/main'), linkedSourceParent);
  assert.throws(
    () => verifySpotbugsExclusions({
      classesDirectory: valid.classesDirectory,
      filterPath: realFilter,
      projectRoot: temporaryRoot,
      sourceDirectory: resolve(linkedSourceParent, 'java'),
    }),
    /Main-source directory contains a symlink path component/,
  );
  assert.throws(
    () => verifySpotbugsExclusions({
      classesDirectory: resolve(temporaryRoot, 'missing-classes'),
      filterPath: realFilter,
      projectRoot: temporaryRoot,
      sourceDirectory: valid.sourceDirectory,
    }),
    /Compiled-class directory does not exist/,
  );

  const badClassCases = [
    ['bad-magic', (bytes) => {
      const result = Buffer.from(bytes);
      result.writeUInt32BE(0, 0);
      return result;
    }, /invalid JVM classfile magic/],
    ['truncated', (bytes) => bytes.subarray(0, 20), /truncated or contains an invalid length/],
    ['trailing', (bytes) => Buffer.concat([bytes, Buffer.from([0])]), /trailing byte/],
    ['unsupported-cp-tag', (bytes) => {
      const result = Buffer.from(bytes);
      result[10] = 2;
      return result;
    }, /unsupported constant-pool tag 2/],
  ];
  const targetBytes = classFile(
    'fixture/Target',
    'Target.java',
    [{ name: 'start' }, { name: 'stop' }],
  );
  for (const [name, mutate, expected] of badClassCases) {
    const layout = createLayout(name, [{
      bytes: mutate(targetBytes),
      internalName: 'fixture/Target',
      methods: [],
      sourceFile: 'Target.java',
    }]);
    expectInvalid(layout, name, targetMatch, expected);
  }

  const wrongPath = createLayout('wrong-path', [{
    bytes: classFile('fixture/Wrong', 'Target.java', [{ name: 'start' }, { name: 'stop' }]),
    internalName: 'fixture/Target',
    pathName: 'fixture/Target',
    sourceFile: 'Target.java',
  }]);
  expectInvalid(wrongPath, 'class path mismatch', targetMatch, /does not match binary name fixture\/Wrong/);

  const classSymlink = createLayout('class-symlink', [{
    internalName: 'fixture/Other',
    sourceFile: 'Other.java',
  }]);
  write(resolve(classSymlink.sourceDirectory, 'fixture/Target.java'), 'package fixture;\n');
  symlinkSync(
    resolve(classSymlink.classesDirectory, 'fixture/Other.class'),
    resolve(classSymlink.classesDirectory, 'fixture/Target.class'),
  );
  expectInvalid(classSymlink, 'classfile symlink', targetMatch, /inventory contains a symlink/);

  const missingSource = createLayout('missing-source', [{
    internalName: 'fixture/Target',
    methods: [{ name: 'start' }, { name: 'stop' }],
    sourceFile: 'Missing.java',
    sourceText: 'package fixture;\n',
  }]);
  rmSync(resolve(missingSource.sourceDirectory, 'fixture/Missing.java'));
  expectInvalid(missingSource, 'missing selected source', targetMatch, /source does not exist/);

  const staleSource = createLayout('stale-source', [{
    internalName: 'fixture/Target',
    methods: [{ name: 'start' }, { name: 'stop' }],
    sourceFile: 'Target.java',
  }]);
  const future = new Date(Date.now() + 60_000);
  utimesSync(resolve(staleSource.sourceDirectory, 'fixture/Target.java'), future, future);
  expectInvalid(staleSource, 'stale selected class', targetMatch, /older than its source/);

  const sourceSymlink = createLayout('source-symlink', [{
    internalName: 'fixture/Target',
    methods: [{ name: 'start' }, { name: 'stop' }],
    sourceFile: 'Target.java',
  }]);
  const targetSource = resolve(sourceSymlink.sourceDirectory, 'fixture/Target.java');
  const realSource = resolve(sourceSymlink.sourceDirectory, 'fixture/Real.java');
  rmSync(targetSource);
  writeFileSync(realSource, 'package fixture;\n');
  symlinkSync(realSource, targetSource);
  expectInvalid(sourceSymlink, 'selected source symlink', targetMatch, /symlink path component/);

  const usage = spawnSync(process.execPath, [verifierPath, 'unexpected'], { encoding: 'utf8' });
  assert.equal(usage.status, 64);
  assert.match(usage.stderr, /Usage: node scripts\/verify-spotbugs-exclusions\.mjs/);

  console.log('SpotBugs exclusion-target verifier self-test passed.');
} finally {
  rmSync(temporaryRoot, { force: true, recursive: true });
}
