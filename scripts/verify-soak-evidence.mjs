#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const PROFILE_NAMES = new Set(['smoke', 'nightly']);
const EXPECTED_SCENARIOS = new Set([
  'HTTP abort churn',
  'MCP Phase 5 cross-feature churn',
  'MCP off-network simulator churn',
  'concurrent HTTP churn',
  'concurrent SSE churn',
]);
const EXPECTED_SUITES = new Map([
  ['TEST-com.soklet.HttpSoakTests.xml', {
    name: 'com.soklet.HttpSoakTests',
    tests: 2,
    testCases: new Set([
      'concurrentHttpChurnReturnsResourcesAndActiveRequestsToBaseline',
      'httpAbortChurnReturnsResourcesAndActiveRequestsToBaseline',
    ]),
  }],
  ['TEST-com.soklet.McpCrossFeatureSoakTests.xml', {
    name: 'com.soklet.McpCrossFeatureSoakTests',
    tests: 2,
    testCases: new Set([
      'mcpCrossFeatureChurnReturnsResourcesToBaselineAfterCancellationAndShutdown',
      'mcpSimulatorChurnReturnsResourcesToBaselineAfterCancellationAndScopeCleanup',
    ]),
  }],
  ['TEST-com.soklet.RealtimeTransportSoakTests.xml', {
    name: 'com.soklet.RealtimeTransportSoakTests',
    tests: 1,
    testCases: new Set([
      'concurrentSseChurnReturnsResourcesAndActiveStreamsToBaseline',
    ]),
  }],
]);

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const defaultProjectRoot = resolve(scriptDirectory, '..');

function fail(message) {
  throw new Error(message);
}

function readRequired(path, description) {
  if (!existsSync(path))
    fail(`Missing ${description}: ${path}`);

  return readFileSync(path);
}

function assertExactlyOnce(text, expected, description) {
  const first = text.indexOf(expected);

  if (first === -1)
    fail(`Missing ${description}: ${expected}`);

  if (text.indexOf(expected, first + expected.length) !== -1)
    fail(`Duplicate ${description}: ${expected}`);
}

function assertOnlyMetadataLine(text, prefix, expected, description) {
  const lines = text.split('\n').filter((line) => line.startsWith(prefix));

  if (lines.length !== 1 || lines[0] !== expected)
    fail(`Expected exactly one ${description} line '${expected}', found: ${lines.join(' | ') || '<none>'}`);
}

function equalSets(actual, expected) {
  return actual.size === expected.size && [...actual].every((value) => expected.has(value));
}

function parseAttributes(source, description) {
  const attributes = new Map();
  const pattern = /([A-Za-z_:][A-Za-z0-9_.:-]*)="([^"]*)"/g;
  let match;

  source = source.trim();

  if (source.endsWith('/'))
    source = source.slice(0, -1).trimEnd();

  while ((match = pattern.exec(source)) !== null) {
    if (attributes.has(match[1]))
      fail(`Duplicate ${description} attribute: ${match[1]}`);

    attributes.set(match[1], match[2]);
  }

  const unparsed = source.replace(pattern, '').trim();

  if (unparsed !== '')
    fail(`Malformed ${description} attributes: ${unparsed}`);

  return attributes;
}

function numericAttribute(attributes, name, description) {
  const value = attributes.get(name);

  if (value === undefined || !/^(0|[1-9][0-9]*)$/.test(value))
    fail(`Invalid ${description} ${name} attribute: ${value ?? '<missing>'}`);

  return Number(value);
}

function verifySurefireSuite(xmlPath, expected) {
  const xml = readRequired(xmlPath, 'Surefire XML report').toString('utf8');
  const suiteMatches = [...xml.matchAll(/<testsuite\b([^>]*)>/g)];

  if (suiteMatches.length !== 1)
    fail(`Expected exactly one testsuite in ${xmlPath}, found ${suiteMatches.length}`);

  if (!xml.trimEnd().endsWith('</testsuite>') || [...xml.matchAll(/<\/testsuite>/g)].length !== 1)
    fail(`Malformed or incomplete testsuite in ${xmlPath}`);

  const attributes = parseAttributes(suiteMatches[0][1], `testsuite in ${xmlPath}`);

  if (attributes.get('name') !== expected.name)
    fail(`Unexpected testsuite name in ${xmlPath}: ${attributes.get('name') ?? '<missing>'}`);

  if (numericAttribute(attributes, 'tests', xmlPath) !== expected.tests)
    fail(`Unexpected test count in ${xmlPath}; expected ${expected.tests}`);

  for (const attribute of ['errors', 'failures', 'skipped']) {
    if (numericAttribute(attributes, attribute, xmlPath) !== 0)
      fail(`Expected ${attribute}=0 in ${xmlPath}`);
  }

  if (/<(?:error|failure|skipped)\b/.test(xml))
    fail(`Failure, error, or skipped element present in ${xmlPath}`);

  const testCases = [...xml.matchAll(/<testcase\b([^>]*)>/g)].map((match) => {
    const testCaseAttributes = parseAttributes(match[1], `testcase in ${xmlPath}`);

    if (testCaseAttributes.get('classname') !== expected.name)
      fail(`Unexpected testcase classname in ${xmlPath}: ${testCaseAttributes.get('classname') ?? '<missing>'}`);

    const name = testCaseAttributes.get('name');

    if (name === undefined || name === '')
      fail(`Missing testcase name in ${xmlPath}`);

    return name;
  });

  if (testCases.length !== expected.tests)
    fail(`Expected ${expected.tests} testcase elements in ${xmlPath}, found ${testCases.length}`);

  const uniqueTestCases = new Set(testCases);

  if (uniqueTestCases.size !== testCases.length || !equalSets(uniqueTestCases, expected.testCases))
    fail(`Unexpected testcase set in ${xmlPath}: ${[...uniqueTestCases].sort().join(', ')}`);
}

export function verifySoakEvidence(profileName, projectRoot = defaultProjectRoot) {
  if (!PROFILE_NAMES.has(profileName))
    fail(`Profile must be exactly one of: ${[...PROFILE_NAMES].join(', ')}`);

  const profileResource = `/com/soklet/soak-profiles/${profileName}.properties`;
  const profilePath = resolve(
    projectRoot,
    `soak/src/test/resources/com/soklet/soak-profiles/${profileName}.properties`,
  );
  const reportPath = resolve(projectRoot, 'soak/target/soak-report.md');
  const surefireDirectory = resolve(projectRoot, 'soak/target/surefire-reports');
  const profileBytes = readRequired(profilePath, 'checked-in soak profile');
  const report = readRequired(reportPath, 'soak Markdown report').toString('utf8');
  const profileSha256 = createHash('sha256').update(profileBytes).digest('hex');

  assertOnlyMetadataLine(report, '- Profile: ', `- Profile: ${profileName}`, 'profile identity');
  assertOnlyMetadataLine(
    report,
    '- Configuration resource: ',
    `- Configuration resource: \`${profileResource}\``,
    'configuration resource identity',
  );
  assertOnlyMetadataLine(
    report,
    '- Configuration SHA-256: ',
    `- Configuration SHA-256: \`${profileSha256}\``,
    'configuration SHA-256',
  );

  const canonicalConfiguration =
    `## Canonical Configuration\n\n\`\`\`properties\n${profileBytes.toString('utf8')}\`\`\`\n`;
  assertExactlyOnce(report, canonicalConfiguration, 'canonical configuration block');

  const headings = [...report.matchAll(/^## (.+)$/gm)].map((match) => ({
    name: match[1],
    start: match.index,
  }));

  if (headings.length !== EXPECTED_SCENARIOS.size + 1)
    fail(`Expected one configuration section and exactly ${EXPECTED_SCENARIOS.size} scenario sections, found ${headings.length}`);

  if (headings[0].name !== 'Canonical Configuration')
    fail('Canonical Configuration must be the first level-two report section');

  const scenarioHeadings = headings.slice(1);
  const actualScenarios = new Set(scenarioHeadings.map(({ name }) => name));

  if (actualScenarios.size !== scenarioHeadings.length || !equalSets(actualScenarios, EXPECTED_SCENARIOS))
    fail(`Unexpected scenario set: ${[...actualScenarios].sort().join(', ')}`);

  for (let index = 0; index < scenarioHeadings.length; index++) {
    const heading = scenarioHeadings[index];
    const end = index + 1 < scenarioHeadings.length ? scenarioHeadings[index + 1].start : report.length;
    const section = report.slice(heading.start, end);
    const results = [...section.matchAll(/^- Result: (.+)$/gm)].map((match) => match[1]);

    if (results.length !== 1 || results[0] !== 'PASS')
      fail(`Expected exactly one PASS result for scenario ${heading.name}, found: ${results.join(', ') || '<none>'}`);
  }

  if (!existsSync(surefireDirectory))
    fail(`Missing Surefire report directory: ${surefireDirectory}`);

  const actualXmlReports = readdirSync(surefireDirectory)
    .filter((name) => name.startsWith('TEST-') && name.endsWith('.xml'))
    .sort();
  const expectedXmlReports = [...EXPECTED_SUITES.keys()].sort();

  if (actualXmlReports.length !== expectedXmlReports.length
      || actualXmlReports.some((name, index) => name !== expectedXmlReports[index]))
    fail(`Unexpected Surefire XML report set: ${actualXmlReports.join(', ') || '<empty>'}`);

  for (const [filename, expected] of EXPECTED_SUITES)
    verifySurefireSuite(resolve(surefireDirectory, filename), expected);

  return {
    profileName,
    profileResource,
    profileSha256,
    scenarios: [...actualScenarios].sort(),
  };
}

function isDirectExecution() {
  return process.argv[1] !== undefined
    && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
}

if (isDirectExecution()) {
  if (process.argv.length !== 3) {
    console.error('Usage: node scripts/verify-soak-evidence.mjs <smoke|nightly>');
    process.exitCode = 1;
  } else {
    try {
      const result = verifySoakEvidence(process.argv[2]);
      console.log(
        `Verified ${result.profileName} soak evidence (${result.profileSha256}; ${result.scenarios.length} scenarios).`,
      );
    } catch (error) {
      console.error(error instanceof Error ? error.message : String(error));
      process.exitCode = 1;
    }
  }
}
