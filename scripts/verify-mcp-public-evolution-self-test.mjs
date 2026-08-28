#!/usr/bin/env node

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  declarationKey,
  declarationResolutionCountForTest,
  hasDeprecationSuppressionForTest,
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

console.log('MCP public evolution verifier self-test passed.');
