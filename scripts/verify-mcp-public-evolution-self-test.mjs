#!/usr/bin/env node

import assert from 'node:assert/strict';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import {
  declarationKey,
  declarationResolutionCountForTest,
  hasDeprecationSuppressionForTest,
  renderActiveTextAudit,
  verifyActiveText,
} from './verify-mcp-public-evolution.mjs';

const root = resolve(new URL('..', import.meta.url).pathname);
const inventory = JSON.parse(readFileSync(
  resolve(root, 'api/mcp/mcp-public-evolution-inventory.json'), 'utf8'));
const rows = inventory.suppressionBaseline.rows;

assert.equal(rows.length, 18);
assert.equal(rows.filter(({ partition }) => partition === 'candidate').length, 17);
assert.equal(rows.filter(({ partition }) => partition === 'externalSketch').length, 1);
const fingerprints = rows.map((row) =>
  `${row.partition}|${row.path}|${declarationKey(row.declaration)}`);
assert.equal(new Set(fingerprints).size, 18);

const constructors = rows.filter(({ declaration }) =>
  declaration.kind === 'constructor');
assert.equal(constructors.length, 9);
assert.equal(new Set(constructors.map(({ declaration }) =>
  declarationKey(declaration))).size, 9);

const oneParameter = constructors.find(({ declaration }) =>
  declaration.erasedParameterTypes.length === 1);
assert.ok(oneParameter);
const simpleFixture = `
  final class DefaultMcpRequestContext {
    DefaultMcpRequestContext(
        @NonNull java.util.List<@NonNull String> ignored) {}
  }
`;
const genericDeclaration = {
  kind: 'constructor',
  owner: 'com.soklet.DefaultMcpRequestContext',
  erasedParameterTypes: ['java.util.List'],
};
assert.equal(declarationResolutionCountForTest(simpleFixture,
  genericDeclaration), 1);
assert.equal(declarationResolutionCountForTest(simpleFixture.replace(
  'List<@NonNull String>', 'List<@NonNull Integer>'), genericDeclaration), 1);
assert.equal(declarationResolutionCountForTest(simpleFixture.replace(
  'java.util.List<@NonNull String>', 'java.util.Set<@NonNull String>'),
  genericDeclaration), 0);

const anonymousDeclaration = rows.find(({ declaration }) =>
  declaration.kind === 'anonymousMethod'
    && declaration.host.owner === 'com.soklet.McpPromptRegistrationTests')
  .declaration;
const anonymousFixture = `
  final class McpPromptRegistrationTests {
    private static McpRequestContext requestContext() {
      return new McpRequestContext() {
        @Override public Optional<McpLogLevel> getDeprecatedLogLevel() {
          return Optional.empty();
        }
      };
    }
  }
`;
assert.equal(declarationResolutionCountForTest(anonymousFixture,
  anonymousDeclaration), 1);
assert.equal(declarationResolutionCountForTest(`${anonymousFixture}\n${anonymousFixture}`,
  anonymousDeclaration), 0);

assert.equal(hasDeprecationSuppressionForTest(
  '@SuppressWarnings("deprecation") void method() {}'), true);
assert.equal(hasDeprecationSuppressionForTest(
  '@SuppressWarnings({"unchecked", "deprecation"}) void method() {}'), true);
assert.equal(hasDeprecationSuppressionForTest(
  '@SuppressWarnings("unchecked") void method() {}'), false);

assert.throws(() => declarationKey({ kind: 'unknown' }),
  /Unknown suppression declaration kind/);
assert.notEqual(
  declarationKey(genericDeclaration),
  declarationKey({ ...genericDeclaration, erasedParameterTypes: ['java.util.Set'] }),
);

for (const row of rows.filter(({ partition }) => partition === 'candidate')) {
  const source = readFileSync(resolve(root, row.path), 'utf8');
  assert.equal(declarationResolutionCountForTest(source, row.declaration), 1,
    `${row.path}|${declarationKey(row.declaration)}`);
}

const externalRow = rows.find(({ partition }) => partition === 'externalSketch');
assert.equal(externalRow.enforcementHost, 'R4/R7-workspace');
const externalSource = `
  package com.soklet;
  interface McpRequestContext {
    Optional<McpLogLevel> getDeprecatedLogLevel();
  }
`;
assert.equal(declarationResolutionCountForTest(externalSource,
  externalRow.declaration), 1);
assert.equal(declarationResolutionCountForTest(externalSource.replace(
  'getDeprecatedLogLevel()', 'getDeprecatedLogLevel(String changed)'),
  externalRow.declaration), 0);
assert.equal(declarationResolutionCountForTest(
  `${externalSource}\n${externalSource}`, externalRow.declaration), 2);
assert.equal(hasDeprecationSuppressionForTest(externalSource), false);

const activeTextTemporary = mkdtempSync(join(tmpdir(),
  'soklet-active-text-self-test-'));
const activeTextFixture = join(activeTextTemporary, 'candidate');
const activeTextPaths = [
  'conformance/roadmap-readiness-active-text-rules.json',
  'conformance/MCP_ROADMAP_ACTIVE_TEXT_AUDIT.md',
  'MCP.md',
  'README.md',
  'SECURITY.md',
  'api/mcp/README.md',
  'CHANGELOG.md',
  'release/README.md',
];
const activeTextOriginal = new Map(activeTextPaths.map((path) =>
  [path, readFileSync(resolve(root, path))]));

function activeTextFixturePath(path) {
  return join(activeTextFixture, path);
}

function writeActiveTextFixture(path, value) {
  const destination = activeTextFixturePath(path);
  mkdirSync(dirname(destination), { recursive: true });
  writeFileSync(destination, value);
}

function restoreActiveTextFixture() {
  rmSync(activeTextFixture, { force: true, recursive: true });
  for (const [path, bytes] of activeTextOriginal)
    writeActiveTextFixture(path, bytes);
  writeActiveTextFixture('conformance/MCP_ROADMAP_ACTIVE_TEXT_AUDIT.md',
    renderActiveTextAudit(activeTextFixture).rendered);
}

function mutateActiveTextRules(mutator) {
  const path = activeTextFixturePath(
    'conformance/roadmap-readiness-active-text-rules.json');
  const value = JSON.parse(readFileSync(path, 'utf8'));
  mutator(value);
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

function expectActiveTextRejected(label, mutator, pattern) {
  restoreActiveTextFixture();
  mutator();
  assert.throws(() => verifyActiveText(activeTextFixture), pattern, label);
}

function expectActiveTextAccepted(label, mutator) {
  restoreActiveTextFixture();
  mutator();
  assert.equal(verifyActiveText(activeTextFixture).ruleCount, 22, label);
}

try {
  restoreActiveTextFixture();
  assert.equal(verifyActiveText(activeTextFixture).ruleCount, 22);
  assert.equal(verifyActiveText(activeTextFixture).ruleCount, 22,
    'Repeated regex evaluation must not leak lastIndex state.');

  expectActiveTextAccepted(
    'inactive HTML comments must not create active-text matches', () => {
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\n<!-- Soklet automatically selects the latest profile. -->\n',
        { flag: 'a' });
    });

  expectActiveTextAccepted(
    'headings inside inactive HTML comments must not duplicate scopes', () => {
      const path = activeTextFixturePath('MCP.md');
      writeFileSync(path, readFileSync(path, 'utf8').replace(
        '### Deprecated compatibility surfaces\n',
        '<!--\n### Deprecated compatibility surfaces\n-->\n\n'
          + '### Deprecated compatibility surfaces\n'));
    });

  expectActiveTextAccepted(
    'fences inside inactive HTML comments must not duplicate scopes', () => {
      const path = activeTextFixturePath('MCP.md');
      writeFileSync(path, readFileSync(path, 'utf8').replace(
        '## Admission and identity\n',
        '<!--\n```java\nhiddenDefaultExample();\n```\n-->\n\n'
          + '## Admission and identity\n'));
    });

  expectActiveTextRejected('stale active-text rendering must fail', () => {
    writeFileSync(activeTextFixturePath(
      'conformance/MCP_ROADMAP_ACTIVE_TEXT_AUDIT.md'), '\nmanual drift\n',
    { flag: 'a' });
  }, /audit is stale/u);

  expectActiveTextRejected('allowed fingerprint mutation must fail', () => {
    mutateActiveTextRules((value) => {
      value.rules.find(({ id }) => id === 'PROFILE-001')
        .allowedMatches[0].matchedText = 'mutated match';
    });
  }, /fingerprint mismatch/u);

  expectActiveTextRejected('duplicate allowed fingerprints must fail', () => {
    mutateActiveTextRules((value) => {
      const rule = value.rules.find(({ id }) => id === 'PROFILE-001');
      rule.allowedMatches.push({ ...rule.allowedMatches[0] });
    });
  }, /fingerprint mismatch/u);

  expectActiveTextRejected(
    'classification changes must stale the complete human rendering', () => {
      mutateActiveTextRules((value) => {
        value.rules.find(({ id }) => id === 'PROFILE-002').classification +=
          '-mutated';
      });
    }, /audit is stale/u);

  expectActiveTextRejected(
    'rationale changes must stale the complete human rendering', () => {
      mutateActiveTextRules((value) => {
        value.rules.find(({ id }) => id === 'PROFILE-002').rationale +=
          ' Mutated.';
      });
    }, /audit is stale/u);

  expectActiveTextRejected(
    'matcher case-sensitivity must be explicit', () => {
      mutateActiveTextRules((value) => {
        delete value.rules.find(({ id }) => id === 'PROFILE-002')
          .matcher.caseSensitive;
      });
    }, /must have exact keys/u);

  expectActiveTextRejected(
    'case-insensitive literal matching must preserve source text', () => {
      mutateActiveTextRules((value) => {
        value.rules.find(({ id }) => id === 'PROFILE-002').matcher = {
          caseSensitive: false,
          kind: 'literal',
          pattern: 'default profile',
        };
      });
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\nDefault Profile selection is implicit.\n', { flag: 'a' });
    }, /PROFILE-002 expected zero matches/u);

  expectActiveTextRejected(
    'notice-pattern weakening must stale the human audit', () => {
      mutateActiveTextRules((value) => {
        value.rules.find(({ id }) => id === 'LIFECYCLE-004')
          .noticePattern = {
            caseSensitive: true,
            kind: 'literal',
            pattern: 'Roots',
          };
      });
    }, /audit is stale/u);

  expectActiveTextRejected(
    'unchanged compatibility text moved to the default path must fail', () => {
      const path = activeTextFixturePath('MCP.md');
      const text = readFileSync(path, 'utf8');
      const moved = 'Retained Sampling and Roots declarations remain validated\n'
        + 'and must be registered.';
      assert.ok(text.includes(moved));
      writeFileSync(path, text.replace(moved, '').replace(
        '## Multi-round-trip input and request state\n',
        `## Multi-round-trip input and request state\n\n${moved}\n`));
    }, /LIFECYCLE-002 expected zero matches/u);

  expectActiveTextRejected(
    'zero expectation cannot whitelist a forbidden match', () => {
      mutateActiveTextRules((value) => {
        const rule = value.rules.find(({ id }) => id === 'PROFILE-002');
        rule.allowedMatches.push({
          matchedText: 'automatically\nselects the latest',
          path: 'MCP.md',
          scope: { kind: 'wholeFile', role: 'factualSupport' },
        });
      });
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\nSoklet automatically\nselects the latest profile.\n', { flag: 'a' });
    }, /expectation zero and must not whitelist/u);

  expectActiveTextRejected(
    'zero-rule overclaim remains visible after Markdown reflow', () => {
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\nSoklet automatically\nselects the latest profile.\n', { flag: 'a' });
    }, /PROFILE-002 expected zero matches/u);

  expectActiveTextRejected(
    'reverse-order automatic latest-profile claim must fail', () => {
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\nThe latest MCP profile is selected automatically.\n', { flag: 'a' });
    }, /PROFILE-002 expected zero matches/u);

  expectActiveTextRejected(
    'plain exact event-variant count must remain reviewed', () => {
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\nSoklet exposes exactly 23 event variants.\n', { flag: 'a' });
    }, /COUNT-001 fingerprint mismatch/u);

  expectActiveTextRejected(
    'noun-first server-extension support claim must fail', () => {
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\nSoklet server extensions are supported.\n', { flag: 'a' });
    }, /EXTENSION-001 expected zero matches/u);

  expectActiveTextRejected(
    'visible inline-code HTML comment syntax remains governed', () => {
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\n`<!-- Soklet automatically selects the latest profile. -->`\n',
        { flag: 'a' });
    }, /PROFILE-002 expected zero matches/u);

  expectActiveTextRejected(
    'visible fenced-code HTML comments remain governed', () => {
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\n```html\n<!-- Soklet automatically selects the latest profile. -->\n```\n',
        { flag: 'a' });
    }, /PROFILE-002 expected zero matches/u);

  expectActiveTextRejected(
    'sentence-initial authorization overclaim must fail', () => {
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\nAuthorization isolation filters subscription events.\n',
        { flag: 'a' });
    }, /AUTH-001 expected zero matches/u);

  expectActiveTextRejected(
    'sentence-initial transport overclaim must fail', () => {
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\nTransport-agnostic MCP support is complete.\n', { flag: 'a' });
    }, /TRANSPORT-001 expected zero matches/u);

  expectActiveTextRejected(
    'sentence-initial DPoP overclaim must fail', () => {
      writeFileSync(activeTextFixturePath('MCP.md'),
        '\nBuilt-in DPoP support is available.\n', { flag: 'a' });
    }, /DPOP-001 expected zero matches/u);

  expectActiveTextRejected(
    'API inventory guidance is governed by profile claim rules', () => {
      writeFileSync(activeTextFixturePath('api/mcp/README.md'),
        '\nSoklet Automatically selects the latest MCP profile.\n',
        { flag: 'a' });
    }, /PROFILE-002 expected zero matches/u);

  expectActiveTextRejected(
    'migration notes are governed by extension claim rules', () => {
      writeFileSync(activeTextFixturePath('CHANGELOG.md'),
        '\nArbitrary extension methods are supported.\n', { flag: 'a' });
    }, /EXTENSION-001 expected zero matches/u);

  expectActiveTextRejected(
    'release guidance is governed by cache claim rules', () => {
      writeFileSync(activeTextFixturePath('release/README.md'),
        '\nETags can never be added to MCP responses.\n', { flag: 'a' });
    }, /CACHE-001 expected zero matches/u);

  expectActiveTextRejected('missing lifecycle notice must fail', () => {
    const path = activeTextFixturePath('MCP.md');
    writeFileSync(path, readFileSync(path, 'utf8').replace(
      'SEP-2577 marks Roots, Sampling, and Logging deprecated',
      'Upstream marks Roots, Sampling, and Logging deprecated'));
  }, /lacks its notice/u);

  expectActiveTextRejected(
    'lifecycle support and its notice hidden in an HTML comment must fail', () => {
      const path = activeTextFixturePath('MCP.md');
      const text = readFileSync(path, 'utf8');
      const start =
        'SEP-2577 marks Roots, Sampling, and Logging deprecated in MCP `2026-07-28`,';
      const end =
        'approved default-off, bounded, redacted diagnostic policy.';
      const startOffset = text.indexOf(start);
      const endOffset = text.indexOf(end, startOffset) + end.length;
      assert.ok(startOffset >= 0 && endOffset >= end.length);
      writeFileSync(path, `${text.slice(0, startOffset)}<!--\n${text.slice(
        startOffset, endOffset)}\n-->${text.slice(endOffset)}`);
    }, /LIFECYCLE-001 fingerprint mismatch/u);

  expectActiveTextRejected(
    'required governed target cannot be omitted to hide a prohibited claim', () => {
      mutateActiveTextRules((value) => {
        const rule = value.rules.find(({ id }) => id === 'PROFILE-002');
        rule.files = rule.files.filter(({ path }) => path !== 'SECURITY.md');
      });
      writeFileSync(activeTextFixturePath('SECURITY.md'),
        '\nSoklet automatically selects the latest profile.\n', { flag: 'a' });
    }, /PROFILE-002 is missing required governed scope SECURITY\.md/u);

  expectActiveTextRejected('duplicate heading scope must fail closed', () => {
    const path = activeTextFixturePath('MCP.md');
    writeFileSync(path, readFileSync(path, 'utf8').replace(
      '### Deprecated compatibility surfaces\n',
      '### Deprecated compatibility surfaces\n\n'
        + '### Deprecated compatibility surfaces\n'));
  }, /scope must resolve one heading/u);

  expectActiveTextRejected('ambiguous fenced-block scope must fail closed', () => {
    const path = activeTextFixturePath('MCP.md');
    writeFileSync(path, readFileSync(path, 'utf8').replace(
      '## Admission and identity\n',
      '```java\nextraDefaultExample();\n```\n\n## Admission and identity\n'));
  }, /scope must resolve one fenced block/u);

  expectActiveTextRejected('candidate traversal path must fail closed', () => {
    mutateActiveTextRules((value) => {
      value.rules[0].files[0].path = '../outside.md';
    });
  }, /contained POSIX root-relative path/u);

  expectActiveTextRejected('candidate symlink must fail closed', () => {
    const path = activeTextFixturePath('MCP.md');
    rmSync(path);
    symlinkSync(resolve(root, 'MCP.md'), path);
  }, /must not traverse a symbolic link/u);

  expectActiveTextRejected('fixed contract symlink must fail closed', () => {
    const path = activeTextFixturePath(
      'conformance/roadmap-readiness-active-text-rules.json');
    rmSync(path);
    symlinkSync(resolve(root,
      'conformance/roadmap-readiness-active-text-rules.json'), path);
  }, /Active-text contract must not traverse a symbolic link/u);

  expectActiveTextRejected('fixed audit symlink must fail closed', () => {
    const path = activeTextFixturePath(
      'conformance/MCP_ROADMAP_ACTIVE_TEXT_AUDIT.md');
    rmSync(path);
    symlinkSync(resolve(root,
      'conformance/MCP_ROADMAP_ACTIVE_TEXT_AUDIT.md'), path);
  }, /Active-text audit must not traverse a symbolic link/u);

  expectActiveTextRejected(
    'fixed contract and audit ancestor symlink must fail closed', () => {
      const path = activeTextFixturePath('conformance');
      rmSync(path, { recursive: true });
      symlinkSync(resolve(root, 'conformance'), path);
    }, /Active-text contract must not traverse a symbolic link/u);

  restoreActiveTextFixture();
  const activeTextFixtureLink = join(activeTextTemporary, 'candidate-link');
  symlinkSync(activeTextFixture, activeTextFixtureLink);
  assert.throws(() => verifyActiveText(activeTextFixtureLink),
    /Active-text candidate root must not be a symbolic link/u,
    'candidate root symlink must fail closed');
  rmSync(activeTextFixtureLink);

  restoreActiveTextFixture();
  assert.equal(verifyActiveText(activeTextFixture).ruleCount, 22);
} finally {
  rmSync(activeTextTemporary, { force: true, recursive: true });
}

console.log('MCP public evolution verifier self-test passed.');
