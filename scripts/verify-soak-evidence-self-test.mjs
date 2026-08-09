#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { verifySoakEvidence } from './verify-soak-evidence.mjs';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const verifierPath = resolve(scriptDirectory, 'verify-soak-evidence.mjs');
const fixtureRoot = mkdtempSync(join(tmpdir(), 'soklet-soak-evidence-'));
const profileName = 'smoke';
const profileResource = '/com/soklet/soak-profiles/smoke.properties';
const profileConfiguration = 'fixture.iterations=1\n';

const scenarios = [
  'concurrent SSE churn',
  'HTTP abort churn',
  'MCP Phase 5 cross-feature churn',
  'concurrent HTTP churn',
];

const suites = [
  {
    filename: 'TEST-com.soklet.HttpSoakTests.xml',
    name: 'com.soklet.HttpSoakTests',
    testCases: [
      'concurrentHttpChurnReturnsResourcesAndActiveRequestsToBaseline',
      'httpAbortChurnReturnsResourcesAndActiveRequestsToBaseline',
    ],
  },
  {
    filename: 'TEST-com.soklet.McpCrossFeatureSoakTests.xml',
    name: 'com.soklet.McpCrossFeatureSoakTests',
    testCases: ['mcpCrossFeatureChurnReturnsResourcesToBaselineAfterCancellationAndShutdown'],
  },
  {
    filename: 'TEST-com.soklet.RealtimeTransportSoakTests.xml',
    name: 'com.soklet.RealtimeTransportSoakTests',
    testCases: ['concurrentSseChurnReturnsResourcesAndActiveStreamsToBaseline'],
  },
];

function fixturePath(...parts) {
  return resolve(fixtureRoot, ...parts);
}

function reportText() {
  const sha256 = createHash('sha256').update(profileConfiguration).digest('hex');
  const scenarioSections = scenarios.map((scenario) => `## ${scenario}\n\n- Result: PASS\n`).join('\n');

  return `# Soklet Soak Report\n\n`
    + `- Profile: ${profileName}\n`
    + `- Configuration resource: \`${profileResource}\`\n`
    + `- Configuration SHA-256: \`${sha256}\`\n\n`
    + `## Canonical Configuration\n\n\`\`\`properties\n${profileConfiguration}\`\`\`\n\n`
    + scenarioSections;
}

function suiteXml(name, testCases, { skipped = 0 } = {}) {
  const cases = testCases
    .map((testCase) => `  <testcase name="${testCase}" classname="${name}"/>`)
    .join('\n');

  return `<?xml version="1.0" encoding="UTF-8"?>\n`
    + `<testsuite name="${name}" tests="${testCases.length}" errors="0" skipped="${skipped}" failures="0">\n`
    + `${cases}\n`
    + '</testsuite>\n';
}

function writeValidFixture() {
  const profileDirectory = fixturePath('soak/src/test/resources/com/soklet/soak-profiles');
  const targetDirectory = fixturePath('soak/target');
  const surefireDirectory = fixturePath('soak/target/surefire-reports');
  mkdirSync(profileDirectory, { recursive: true });
  mkdirSync(surefireDirectory, { recursive: true });
  writeFileSync(resolve(profileDirectory, `${profileName}.properties`), profileConfiguration);
  writeFileSync(resolve(targetDirectory, 'soak-report.md'), reportText());

  for (const suite of suites)
    writeFileSync(resolve(surefireDirectory, suite.filename), suiteXml(suite.name, suite.testCases));
}

function overwrite(path, transform) {
  const original = readFileSync(path, 'utf8');
  writeFileSync(path, transform(original));
  return () => writeFileSync(path, original);
}

try {
  writeValidFixture();
  const verified = verifySoakEvidence(profileName, fixtureRoot);
  assert.equal(verified.profileName, profileName);
  assert.equal(verified.scenarios.length, 4);

  const reportPath = fixturePath('soak/target/soak-report.md');
  let restore = overwrite(reportPath, (report) => report.replace('- Profile: smoke', '- Profile: nightly'));
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /profile identity/);
  restore();

  restore = overwrite(
    reportPath,
    (report) => report.replace('## MCP Phase 5 cross-feature churn', '## stale scenario'),
  );
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /Unexpected scenario set/);
  restore();

  restore = overwrite(reportPath, (report) => report.replace('- Result: PASS', '- Result: FAIL'));
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /Expected exactly one PASS result/);
  restore();

  const realtimePath = fixturePath(
    'soak/target/surefire-reports/TEST-com.soklet.RealtimeTransportSoakTests.xml',
  );
  restore = overwrite(realtimePath, (xml) => xml.replace('skipped="0"', 'skipped="1"'));
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /Expected skipped=0/);
  restore();

  const mcpPath = fixturePath(
    'soak/target/surefire-reports/TEST-com.soklet.McpCrossFeatureSoakTests.xml',
  );
  restore = overwrite(
    mcpPath,
    (xml) => xml.replace(
      'mcpCrossFeatureChurnReturnsResourcesToBaselineAfterCancellationAndShutdown',
      'staleMcpCrossFeatureTest',
    ),
  );
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /Unexpected testcase set/);
  restore();

  rmSync(mcpPath);
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /Unexpected Surefire XML report set/);
  const mcpSuite = suites.find(({ filename }) => filename === 'TEST-com.soklet.McpCrossFeatureSoakTests.xml');
  assert.notEqual(mcpSuite, undefined);
  writeFileSync(mcpPath, suiteXml(mcpSuite.name, mcpSuite.testCases));

  const stalePath = fixturePath('soak/target/surefire-reports/TEST-com.soklet.StaleSoakTests.xml');
  writeFileSync(stalePath, suiteXml('com.soklet.StaleSoakTests', ['stale']));
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /Unexpected Surefire XML report set/);
  rmSync(stalePath);

  assert.throws(() => verifySoakEvidence('weekly', fixtureRoot), /Profile must be exactly one of/);

  for (const args of [[], ['smoke', 'extra']]) {
    const invocation = spawnSync(process.execPath, [verifierPath, ...args], { encoding: 'utf8' });
    assert.notEqual(invocation.status, 0);
    assert.match(invocation.stderr, /Usage:/);
  }

  console.log('Soak evidence verifier self-test passed.');
} finally {
  rmSync(fixtureRoot, { recursive: true, force: true });
}
