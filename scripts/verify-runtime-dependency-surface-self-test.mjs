#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import {
  runtimeDependenciesFromPomText,
  verifyRuntimeDependencySurface,
} from './verify-runtime-dependency-surface.mjs';

const root = realpathSync(mkdtempSync(join(tmpdir(), 'soklet-runtime-dependencies-')));
let assertions = 0;

function pom(dependencies, profiles = '') {
  return `<?xml version="1.0" encoding="UTF-8"?>
<project>
  <dependencies>${dependencies}</dependencies>
  ${profiles}
</project>
`;
}

function dependency(groupId, artifactId, scope = '') {
  return `<dependency><groupId>${groupId}</groupId><artifactId>${artifactId}</artifactId>${scope}</dependency>`;
}

try {
  const allowed = pom(
    dependency('example', 'provided', '<scope>provided</scope>')
      + dependency('example', 'test', '<scope>test</scope>'),
  );
  assert.deepEqual(runtimeDependenciesFromPomText(allowed), []);
  assertions++;

  const compile = runtimeDependenciesFromPomText(pom(dependency('example', 'compile')));
  assert.deepEqual(compile, [{
    artifactId: 'compile',
    groupId: 'example',
    profile: false,
    scope: 'compile',
    type: 'jar',
    version: '',
  }]);
  assertions++;

  const runtime = runtimeDependenciesFromPomText(
    pom(dependency('example', 'runtime', '<scope>runtime</scope>')),
  );
  assert.equal(runtime[0].scope, 'runtime');
  assertions++;

  const profile = runtimeDependenciesFromPomText(pom('',
    `<profiles><profile><dependencies>${dependency('example', 'profile')}</dependencies></profile></profiles>`));
  assert.equal(profile[0].profile, true);
  assertions++;

  const prefixed = runtimeDependenciesFromPomText(`<?xml version="1.0" encoding="UTF-8"?>
<m:project xmlns:m="http://maven.apache.org/POM/4.0.0">
  <m:dependencies>
    <m:dependency>
      <m:groupId>example.prefixed</m:groupId>
      <m:artifactId>runtime</m:artifactId>
      <m:scope>runtime</m:scope>
    </m:dependency>
  </m:dependencies>
</m:project>
`);
  assert.deepEqual(prefixed, [{
    artifactId: 'runtime',
    groupId: 'example.prefixed',
    profile: false,
    scope: 'runtime',
    type: 'jar',
    version: '',
  }]);
  assertions++;

  const prefixedFields = runtimeDependenciesFromPomText(`<?xml version="1.0" encoding="UTF-8"?>
<project xmlns:m="http://maven.apache.org/POM/4.0.0">
  <dependencies>
    <dependency>
      <m:groupId>example.fields</m:groupId>
      <m:artifactId>compile</m:artifactId>
      <m:version>1.2.3</m:version>
    </dependency>
  </dependencies>
</project>
`);
  assert.deepEqual(prefixedFields, [{
    artifactId: 'compile',
    groupId: 'example.fields',
    profile: false,
    scope: 'compile',
    type: 'jar',
    version: '1.2.3',
  }]);
  assertions++;

  const prefixedProfile = runtimeDependenciesFromPomText(`<?xml version="1.0" encoding="UTF-8"?>
<m:project xmlns:m="http://maven.apache.org/POM/4.0.0">
  <m:profiles><m:profile><m:dependencies>
    <m:dependency><m:groupId>example</m:groupId><m:artifactId>profile</m:artifactId></m:dependency>
  </m:dependencies></m:profile></m:profiles>
</m:project>
`);
  assert.equal(prefixedProfile[0].profile, true);
  assertions++;

  assert.throws(
    () => runtimeDependenciesFromPomText(
      '<project><dependencies><m::dependency></m::dependency></dependencies></project>',
    ),
    /malformed namespace-qualified element name/,
  );
  assert.throws(
    () => runtimeDependenciesFromPomText(
      '<m:project xmlns:m="urn:maven" xmlns:n="urn:maven"></n:project>',
    ),
    /unmatched closing element/,
  );
  assert.throws(
    () => runtimeDependenciesFromPomText(
      '<project><m:dependencies></m:dependencies></project>',
    ),
    /undeclared XML namespace prefix/,
  );
  assertions += 3;

  assert.throws(
    () => runtimeDependenciesFromPomText('<project><dependencies>'),
    /incomplete/,
  );
  assert.throws(
    () => runtimeDependenciesFromPomText('<!DOCTYPE project><project></project>'),
    /DTD or entity/,
  );
  assertions += 2;

  const fixtureRoot = join(root, 'valid');
  mkdirSync(fixtureRoot);
  const pomPath = join(fixtureRoot, 'pom.xml');
  const outputPath = join(fixtureRoot, 'surface.json');
  writeFileSync(pomPath, allowed, 'utf8');
  assert.deepEqual(
    verifyRuntimeDependencySurface({ outputPath, pomPath }),
    { externalRuntimeDependencyCount: 0, outputPath },
  );
  assert.equal(
    readFileSync(outputPath, 'utf8'),
    '{\n  "externalRuntimeDependencyCount": 0,\n  "formatVersion": 1\n}\n',
  );
  assertions += 2;

  const rejectedRoot = join(root, 'rejected');
  mkdirSync(rejectedRoot);
  const rejectedPom = join(rejectedRoot, 'pom.xml');
  writeFileSync(rejectedPom, pom(dependency('example', 'runtime')), 'utf8');
  assert.throws(
    () => verifyRuntimeDependencySurface({
      outputPath: join(rejectedRoot, 'surface.json'),
      pomPath: rejectedPom,
    }),
    /external runtime dependencies/,
  );
  assertions++;

  console.log(`Runtime dependency-surface self-test passed (${assertions} assertions).`);
} finally {
  rmSync(root, { recursive: true });
}
