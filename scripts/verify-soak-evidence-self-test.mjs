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
import {
  verifySoakEvidence,
  verifySoakProfile,
} from './verify-soak-evidence.mjs';

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const verifierPath = resolve(scriptDirectory, 'verify-soak-evidence.mjs');
const fixtureRoot = mkdtempSync(join(tmpdir(), 'soklet-soak-evidence-'));
const projectRoot = resolve(scriptDirectory, '..');
const profileName = 'release';
const profileResource = '/com/soklet/soak-profiles/release.properties';
const profileConfiguration = readFileSync(
  resolve(projectRoot, 'soak/src/test/resources/com/soklet/soak-profiles/release.properties'),
  'utf8',
);

const scenarios = [
  'concurrent SSE churn',
  'HTTP abort churn',
  'MCP Phase 5 cross-feature churn',
  'MCP localization render and invalidation churn',
  'MCP off-network simulator churn',
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
    testCases: [
      'mcpCrossFeatureChurnReturnsResourcesToBaselineAfterCancellationAndShutdown',
      'mcpSimulatorChurnReturnsResourcesToBaselineAfterCancellationAndScopeCleanup',
    ],
  },
  {
    filename: 'TEST-com.soklet.McpLocalizationSoakTests.xml',
    name: 'com.soklet.McpLocalizationSoakTests',
    testCases: [
      'localizationRenderAndInvalidationChurnReturnsResourcesToBaseline',
    ],
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
  const scenarioSections = scenarios.map((scenario) => {
    const localizationEvidence = scenario === 'MCP localization render and invalidation churn'
      ? '- Localized catalog responses: 8\n'
        + '- Subscription terminals pre-rendered: 1\n'
        + '- Localization contexts created: 9\n'
        + '- Localization lookups completed: 9\n'
        + '- Bounded locale preferences matched: 9\n'
        + '- Catalog invalidations requested/delivered: 2/2\n'
        + '- Final active handlers/queued/streams/subscriptions: 0/0/0/0\n'
        + '- Final MCP status: TERMINATED\n'
        + '- Lifecycle core shutdown bound: PT11S\n'
      : '';

    return `## ${scenario}\n\n- Result: PASS\n${localizationEvidence}`;
  }).join('\n');

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
  for (const checkedInProfileName of ['smoke', 'nightly', 'release']) {
    const checkedInProfile = verifySoakProfile(checkedInProfileName, projectRoot);
    assert.equal(checkedInProfile.profileName, checkedInProfileName);
    assert.equal(checkedInProfile.values.size, 41);
    assert.match(checkedInProfile.profileSha256, /^[0-9a-f]{64}$/);
  }

  writeValidFixture();
  const verified = verifySoakEvidence(profileName, fixtureRoot);
  assert.equal(verified.profileName, profileName);
  assert.equal(verified.scenarios.length, 6);

  const reportPath = fixturePath('soak/target/soak-report.md');
  let restore = overwrite(reportPath, (report) => report.replace('- Profile: release', '- Profile: nightly'));
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /profile identity/);
  restore();

  const profilePath = fixturePath(
    'soak/src/test/resources/com/soklet/soak-profiles/release.properties',
  );
  restore = overwrite(
    profilePath,
    (configuration) => configuration.replace('http.abortConnectTimeoutMillis=15000\n', ''),
  );
  assert.throws(() => verifySoakProfile(profileName, fixtureRoot), /Invalid release soak profile keys/);
  restore();

  restore = overwrite(
    profilePath,
    (configuration) => configuration.replace(
      'http.abortConnectTimeoutMillis=15000',
      'http.abortConnectTimeoutMillis=0',
    ),
  );
  assert.throws(() => verifySoakProfile(profileName, fixtureRoot), /positive decimal integer/);
  restore();

  restore = overwrite(
    profilePath,
    (configuration) => configuration.replace(
      'http.abortConnectTimeoutMillis=15000\nhttp.abortIterationsPerClient=500',
      'http.abortIterationsPerClient=500\nhttp.abortConnectTimeoutMillis=15000',
    ),
  );
  assert.throws(() => verifySoakProfile(profileName, fixtureRoot), /keys must be sorted/);
  restore();

  restore = overwrite(
    profilePath,
    (configuration) => configuration.replace(
      'http.abortConnectTimeoutMillis=15000\n',
      'http.abortConnectTimeoutMillis=15000\r\n',
    ),
  );
  assert.throws(() => verifySoakProfile(profileName, fixtureRoot), /must use LF line endings/);
  restore();

  restore = overwrite(
    profilePath,
    (configuration) => configuration.replace(
      'http.concurrentClients=64',
      'http.concurrentClients=2147483648',
    ),
  );
  assert.throws(() => verifySoakProfile(profileName, fixtureRoot), /exceeds its Java numeric bound/);
  restore();

  restore = overwrite(
    profilePath,
    (configuration) => configuration.replace(
      'http.abortConnectTimeoutMillis=15000',
      'http.abortConnectTimeoutMillis=15001',
    ),
  );
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /configuration SHA-256/);
  restore();

  const savedProfileConfiguration = readFileSync(profilePath, 'utf8');
  rmSync(profilePath);
  assert.throws(() => verifySoakProfile(profileName, fixtureRoot), /Missing checked-in soak profile/);
  writeFileSync(profilePath, savedProfileConfiguration);

  restore = overwrite(
    reportPath,
    (report) => report.replace('## MCP Phase 5 cross-feature churn', '## stale scenario'),
  );
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot),
    /Unexpected scenario sections;.*missing=MCP Phase 5 cross-feature churn.*unexpected=stale scenario/);
  restore();

  restore = overwrite(
    reportPath,
    (report) => report.replace('## MCP Phase 5 cross-feature churn\n\n- Result: PASS\n', ''),
  );
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot),
    /expected=6 found=5 missing=MCP Phase 5 cross-feature churn/);
  restore();

  restore = overwrite(reportPath, (report) => report.replace('- Result: PASS', '- Result: FAIL'));
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /Expected exactly one PASS result/);
  restore();

  restore = overwrite(
    reportPath,
    (report) => report.replace('- Localization contexts created: 9', '- Localization contexts created: 8'),
  );
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /context cardinality/);
  restore();

  restore = overwrite(
    reportPath,
    (report) => report.replace(
      '- Catalog invalidations requested/delivered: 2/2',
      '- Catalog invalidations requested/delivered: 2/1',
    ),
  );
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), /positive balanced/);
  restore();

  const realtimePath = fixturePath(
    'soak/target/surefire-reports/TEST-com.soklet.RealtimeTransportSoakTests.xml',
  );
  restore = overwrite(realtimePath, (xml) => xml.replace('skipped="0"', 'skipped="1"'));
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot),
    /Surefire suite com\.soklet\.RealtimeTransportSoakTests did not pass;.*skipped=1/);
  restore();

  const mcpPath = fixturePath(
    'soak/target/surefire-reports/TEST-com.soklet.McpCrossFeatureSoakTests.xml',
  );
  restore = overwrite(
    mcpPath,
    (xml) => xml
      .replace('errors="0"', 'errors="1"')
      .replace(
        '<testcase name="mcpSimulatorChurnReturnsResourcesToBaselineAfterCancellationAndScopeCleanup" classname="com.soklet.McpCrossFeatureSoakTests"/>',
        '<testcase name="mcpSimulatorChurnReturnsResourcesToBaselineAfterCancellationAndScopeCleanup" classname="com.soklet.McpCrossFeatureSoakTests"><error message="timed out"/></testcase>',
      ),
  );
  const restorePartialReport = overwrite(
    reportPath,
    (report) => report.replace('## MCP Phase 5 cross-feature churn\n\n- Result: PASS\n', ''),
  );
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot),
    /Surefire suite com\.soklet\.McpCrossFeatureSoakTests did not pass; errors=1 failures=0 skipped=0; nonpassing=mcpSimulatorChurnReturnsResourcesToBaselineAfterCancellationAndScopeCleanup \(error: timed out\)/);
  restorePartialReport();
  restore();

  const longDiagnostic = 'x'.repeat(3_000);
  restore = overwrite(
    mcpPath,
    (xml) => xml
      .replace('failures="0"', 'failures="1"')
      .replace(
        '<testcase name="mcpCrossFeatureChurnReturnsResourcesToBaselineAfterCancellationAndShutdown" classname="com.soklet.McpCrossFeatureSoakTests"/>',
        '<testcase name="mcpCrossFeatureChurnReturnsResourcesToBaselineAfterCancellationAndShutdown" classname="com.soklet.McpCrossFeatureSoakTests"><failure message="Missing &quot;resource update&quot; &amp; details&#10;'
          + longDiagnostic + '"/></testcase>',
      ),
  );
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot), (error) => {
    assert.match(error.message,
      /mcpCrossFeatureChurnReturnsResourcesToBaselineAfterCancellationAndShutdown \(failure: Missing "resource update" & details x+/);
    assert.match(error.message, /…\)$/);
    assert.ok(error.message.length < 2_500,
      `Surefire diagnostic was not bounded: ${error.message.length}`);
    return true;
  });
  restore();

  restore = overwrite(
    mcpPath,
    (xml) => xml
      .replace('failures="0"', 'failures="1"')
      .replace(
        '<testcase name="mcpCrossFeatureChurnReturnsResourcesToBaselineAfterCancellationAndShutdown" classname="com.soklet.McpCrossFeatureSoakTests"/>',
        '<testcase name="mcpCrossFeatureChurnReturnsResourcesToBaselineAfterCancellationAndShutdown" classname="com.soklet.McpCrossFeatureSoakTests"><failure><![CDATA[Missing message attribute\nwith <angle> & raw text]]></failure></testcase>',
      ),
  );
  assert.throws(() => verifySoakEvidence(profileName, fixtureRoot),
    /mcpCrossFeatureChurnReturnsResourcesToBaselineAfterCancellationAndShutdown \(failure: Missing message attribute with <angle> & raw text\)/);
  restore();

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
    assert.match(invocation.stderr, /Usage:.*<smoke\|nightly\|release>/);
  }

  console.log('Soak evidence verifier self-test passed.');
} finally {
  rmSync(fixtureRoot, { recursive: true, force: true });
}
