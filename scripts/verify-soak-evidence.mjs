#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const PROFILE_NAMES = new Set(['smoke', 'nightly', 'release']);
const EXPECTED_PROFILE_KEYS = new Set([
  'http.abortConnectTimeoutMillis',
  'http.abortIterationsPerClient',
  'http.cleanRequestsPerClient',
  'http.concurrentClients',
  'http.resourceTolerance.maxHeapGrowthBytes',
  'http.resourceTolerance.maxLiveThreadGrowth',
  'http.resourceTolerance.maxOpenFileDescriptorGrowth',
  'http.runTimeoutMillis',
  'http.serverConcurrency',
  'http.settleTimeoutMillis',
  'http.socketPendingConnectionLimit',
  'mcp.clientSocketTimeoutMillis',
  'mcp.concurrentClients',
  'mcp.cyclesPerClient',
  'mcp.forcedShutdownMillis',
  'mcp.gracefulShutdownMillis',
  'mcp.keepAliveIntervalMillis',
  'mcp.maximumSubscriptionDurationMillis',
  'mcp.maximumSubscriptionsPerPrincipal',
  'mcp.metricDeliveryTimeoutMillis',
  'mcp.requestHandlerConcurrency',
  'mcp.requestHandlerQueueCapacity',
  'mcp.requestTimeoutMillis',
  'mcp.resourceTolerance.maxHeapGrowthBytes',
  'mcp.resourceTolerance.maxLiveThreadGrowth',
  'mcp.resourceTolerance.maxOpenFileDescriptorGrowth',
  'mcp.runTimeoutMillis',
  'mcp.settleTimeoutMillis',
  'mcp.shutdownCycles',
  'mcp.streamQueueCapacity',
  'mcp.writeTimeoutMillis',
  'realtime.clientSocketTimeoutMillis',
  'realtime.concurrentClients',
  'realtime.resourceTolerance.maxHeapGrowthBytes',
  'realtime.resourceTolerance.maxLiveThreadGrowth',
  'realtime.resourceTolerance.maxOpenFileDescriptorGrowth',
  'realtime.runTimeoutMillis',
  'realtime.settleTimeoutMillis',
  'realtime.sseConcurrentConnectionLimit',
  'realtime.sseInterStreamPauseMillis',
  'realtime.sseStreamsPerClient',
]);
const INTEGER_PROFILE_KEYS = new Set([
  'http.abortConnectTimeoutMillis',
  'http.abortIterationsPerClient',
  'http.cleanRequestsPerClient',
  'http.concurrentClients',
  'http.resourceTolerance.maxLiveThreadGrowth',
  'http.serverConcurrency',
  'http.socketPendingConnectionLimit',
  'mcp.concurrentClients',
  'mcp.cyclesPerClient',
  'mcp.maximumSubscriptionsPerPrincipal',
  'mcp.requestHandlerConcurrency',
  'mcp.requestHandlerQueueCapacity',
  'mcp.resourceTolerance.maxLiveThreadGrowth',
  'mcp.shutdownCycles',
  'mcp.streamQueueCapacity',
  'realtime.concurrentClients',
  'realtime.resourceTolerance.maxLiveThreadGrowth',
  'realtime.sseConcurrentConnectionLimit',
  'realtime.sseStreamsPerClient',
]);
const MAXIMUM_JAVA_INTEGER = 2_147_483_647n;
const MAXIMUM_JAVA_LONG = 9_223_372_036_854_775_807n;
const MAXIMUM_SUREFIRE_DIAGNOSTIC_CHARACTERS = 2_048;
const EXPECTED_SCENARIOS = new Set([
  'HTTP abort churn',
  'MCP Phase 5 cross-feature churn',
  'MCP localization render and invalidation churn',
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
  ['TEST-com.soklet.McpLocalizationSoakTests.xml', {
    name: 'com.soklet.McpLocalizationSoakTests',
    tests: 1,
    testCases: new Set([
      'localizationRenderAndInvalidationChurnReturnsResourcesToBaseline',
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

function requireProfileName(profileName) {
  if (!PROFILE_NAMES.has(profileName))
    fail(`Profile must be exactly one of: ${[...PROFILE_NAMES].join(', ')}`);
}

function parseProfileConfiguration(profileName, profilePath, profileBytes) {
  const configuration = profileBytes.toString('utf8');

  if (!configuration.endsWith('\n'))
    fail(`Soak profile must end with LF: ${profilePath}`);

  if (configuration.includes('\r'))
    fail(`Soak profile must use LF line endings: ${profilePath}`);

  const lines = configuration.slice(0, -1).split('\n');
  const sortedLines = [...lines].sort();

  if (lines.length === 0 || lines.some((line) => line === ''))
    fail(`Soak profile must contain only non-empty property lines: ${profilePath}`);

  if (lines.some((line, index) => line !== sortedLines[index]))
    fail(`Soak profile keys must be sorted: ${profilePath}`);

  const values = new Map();

  for (const line of lines) {
    const equals = line.indexOf('=');

    if (equals <= 0 || equals === line.length - 1 || line.indexOf('=', equals + 1) !== -1)
      fail(`Malformed soak profile line in ${profilePath}: ${line}`);

    const key = line.slice(0, equals);
    const value = line.slice(equals + 1);

    if (values.has(key))
      fail(`Duplicate soak profile key in ${profilePath}: ${key}`);

    if (!/^[1-9][0-9]*$/.test(value))
      fail(`Soak profile value must be a positive decimal integer: ${key}=${value}`);

    const numericValue = BigInt(value);
    const maximum = INTEGER_PROFILE_KEYS.has(key) ? MAXIMUM_JAVA_INTEGER : MAXIMUM_JAVA_LONG;

    if (numericValue > maximum)
      fail(`Soak profile value exceeds its Java numeric bound: ${key}=${value}`);

    values.set(key, value);
  }

  const actualKeys = new Set(values.keys());

  if (!equalSets(actualKeys, EXPECTED_PROFILE_KEYS)) {
    const missing = [...EXPECTED_PROFILE_KEYS].filter((key) => !actualKeys.has(key)).sort();
    const unexpected = [...actualKeys].filter((key) => !EXPECTED_PROFILE_KEYS.has(key)).sort();
    fail(`Invalid ${profileName} soak profile keys; missing=${missing.join(',') || '<none>'} unexpected=${unexpected.join(',') || '<none>'}`);
  }

  return { configuration, values };
}

export function verifySoakProfile(profileName, projectRoot = defaultProjectRoot) {
  requireProfileName(profileName);

  const profileResource = `/com/soklet/soak-profiles/${profileName}.properties`;
  const profilePath = resolve(
    projectRoot,
    `soak/src/test/resources/com/soklet/soak-profiles/${profileName}.properties`,
  );
  const profileBytes = readRequired(profilePath, 'checked-in soak profile');
  const { configuration, values } = parseProfileConfiguration(
    profileName,
    profilePath,
    profileBytes,
  );

  return {
    profileName,
    profileResource,
    profilePath,
    profileBytes,
    configuration,
    values,
    profileSha256: createHash('sha256').update(profileBytes).digest('hex'),
  };
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

function decodeXmlAttribute(value, description) {
  return value.replace(/&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos);/g,
    (reference, entity) => {
      switch (entity) {
        case 'amp': return '&';
        case 'lt': return '<';
        case 'gt': return '>';
        case 'quot': return '"';
        case 'apos': return "'";
        default: {
          const hexadecimal = entity.startsWith('#x');
          const codePoint = Number.parseInt(entity.slice(hexadecimal ? 2 : 1),
            hexadecimal ? 16 : 10);
          if (!Number.isSafeInteger(codePoint) || codePoint > 0x10FFFF
              || (codePoint >= 0xD800 && codePoint <= 0xDFFF))
            fail(`Invalid XML character reference in ${description}: ${reference}`);
          return String.fromCodePoint(codePoint);
        }
      }
    });
}

function boundedSurefireDiagnostic(value, description, xmlEncoded) {
  const decoded = xmlEncoded ? decodeXmlAttribute(value, description) : value;
  const normalized = decoded.replace(/\s+/g, ' ').trim();

  if (normalized === '')
    return '';

  if (normalized.length <= MAXIMUM_SUREFIRE_DIAGNOSTIC_CHARACTERS)
    return normalized;

  return `${normalized.slice(0,
    MAXIMUM_SUREFIRE_DIAGNOSTIC_CHARACTERS - 1)}…`;
}

function surefireOutcome(testcaseBody, xmlPath) {
  const outcome = testcaseBody.match(
    /<(error|failure|skipped)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/\1>)/,
  );

  if (outcome === null)
    return null;

  const outcomeType = outcome[1];
  const description = `${outcomeType} in ${xmlPath}`;
  const attributes = parseAttributes(outcome[2], description);
  const attributeMessage = attributes.get('message');
  let diagnostic = '';

  if (attributeMessage !== undefined) {
    diagnostic = boundedSurefireDiagnostic(attributeMessage, description, true);
  } else {
    const body = outcome[3] ?? '';
    const cdata = body.match(/<!\[CDATA\[([\s\S]*?)\]\]>/);
    const bodyText = cdata === null
      ? body.replace(/<[^>]*>/g, ' ')
      : cdata[1];
    diagnostic = boundedSurefireDiagnostic(bodyText, description,
      cdata === null);
  }

  return diagnostic === '' ? outcomeType : `${outcomeType}: ${diagnostic}`;
}

function observation(section, name) {
  const prefix = `- ${name}: `;
  const values = section.split('\n')
    .filter((line) => line.startsWith(prefix))
    .map((line) => line.slice(prefix.length));

  if (values.length !== 1 || values[0] === '')
    fail(`Expected exactly one non-empty '${name}' observation, found: ${values.join(' | ') || '<none>'}`);

  return values[0];
}

function positiveIntegerObservation(section, name) {
  const value = observation(section, name);

  if (!/^[1-9][0-9]*$/.test(value))
    fail(`Expected '${name}' to be a positive integer, found: ${value}`);

  return Number(value);
}

function verifyLocalizationScenario(section, profileValues) {
  const localizedResponses = positiveIntegerObservation(
    section,
    'Localized catalog responses',
  );
  const subscriptionTerminals = positiveIntegerObservation(
    section,
    'Subscription terminals pre-rendered',
  );
  const contexts = positiveIntegerObservation(
    section,
    'Localization contexts created',
  );
  const lookups = positiveIntegerObservation(
    section,
    'Localization lookups completed',
  );
  const preferenceMatches = positiveIntegerObservation(
    section,
    'Bounded locale preferences matched',
  );

  if (subscriptionTerminals !== 1)
    fail(`Expected exactly one pre-rendered subscription terminal, found: ${subscriptionTerminals}`);

  if (contexts !== localizedResponses + subscriptionTerminals)
    fail('Localization context cardinality does not match rendered responses plus the subscription terminal');

  if (lookups !== contexts)
    fail('Localization lookup cardinality does not match context cardinality');

  if (preferenceMatches !== contexts)
    fail('Bounded locale-preference evidence does not match context cardinality');

  const invalidations = observation(
    section,
    'Catalog invalidations requested/delivered',
  ).match(/^([1-9][0-9]*)\/([1-9][0-9]*)$/);

  if (invalidations === null || invalidations[1] !== invalidations[2])
    fail('Catalog invalidation evidence must be a positive balanced requested/delivered pair');

  if (observation(
    section,
    'Final active handlers/queued/streams/subscriptions',
  ) !== '0/0/0/0')
    fail('Localization runtime resources did not return to zero');

  if (observation(section, 'Final MCP status') !== 'TERMINATED')
    fail('Localization MCP server did not finish terminated');

  const lifecycleCoreBoundMilliseconds =
    BigInt(profileValues.get('mcp.gracefulShutdownMillis'))
    + BigInt(profileValues.get('mcp.forcedShutdownMillis'));

  if (lifecycleCoreBoundMilliseconds % 1000n !== 0n)
    fail('Localization lifecycle core shutdown bound must use whole seconds');

  const expectedLifecycleCoreBound =
    `PT${lifecycleCoreBoundMilliseconds / 1000n}S`;

  if (observation(section, 'Lifecycle core shutdown bound')
      !== expectedLifecycleCoreBound) {
    fail(`Localization lifecycle core shutdown bound did not match the profile: expected=${expectedLifecycleCoreBound}`);
  }
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

  const errors = numericAttribute(attributes, 'errors', xmlPath);
  const failures = numericAttribute(attributes, 'failures', xmlPath);
  const skipped = numericAttribute(attributes, 'skipped', xmlPath);

  if (errors !== 0 || failures !== 0 || skipped !== 0) {
    const nonpassing = [...xml.matchAll(
      /<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g,
    )].flatMap((match) => {
      const outcome = surefireOutcome(match[2] ?? '', xmlPath);

      if (outcome === null)
        return [];

      const testcase = parseAttributes(match[1], `testcase in ${xmlPath}`);
      return [`${testcase.get('name') ?? '<unnamed>'} (${outcome})`];
    });
    fail(`Surefire suite ${expected.name} did not pass; errors=${errors} failures=${failures} skipped=${skipped}; nonpassing=${nonpassing.join(', ') || '<unidentified>'}`);
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

function verifySurefireEvidence(surefireDirectory) {
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
}

export function verifySoakEvidence(profileName, projectRoot = defaultProjectRoot) {
  const profile = verifySoakProfile(profileName, projectRoot);
  const { profileBytes, profileResource, profileSha256 } = profile;
  const reportPath = resolve(projectRoot, 'soak/target/soak-report.md');
  const surefireDirectory = resolve(projectRoot, 'soak/target/surefire-reports');
  // A failed Maven test often leaves a partial Markdown report. Validate the
  // authoritative Surefire outcome first so the follow-up `if: always()` CI
  // step reports the failed suite and testcase instead of only a missing section.
  verifySurefireEvidence(surefireDirectory);
  const report = readRequired(reportPath, 'soak Markdown report').toString('utf8');

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

  if (headings.length === 0 || headings[0].name !== 'Canonical Configuration')
    fail('Canonical Configuration must be the first level-two report section');

  const scenarioHeadings = headings.slice(1);
  const actualScenarios = new Set(scenarioHeadings.map(({ name }) => name));

  if (actualScenarios.size !== scenarioHeadings.length
      || !equalSets(actualScenarios, EXPECTED_SCENARIOS)) {
    const missing = [...EXPECTED_SCENARIOS]
      .filter((name) => !actualScenarios.has(name)).sort();
    const unexpected = [...actualScenarios]
      .filter((name) => !EXPECTED_SCENARIOS.has(name)).sort();
    fail(`Unexpected scenario sections; expected=${EXPECTED_SCENARIOS.size} found=${scenarioHeadings.length} missing=${missing.join(', ') || '<none>'} unexpected=${unexpected.join(', ') || '<none>'}`);
  }

  for (let index = 0; index < scenarioHeadings.length; index++) {
    const heading = scenarioHeadings[index];
    const end = index + 1 < scenarioHeadings.length ? scenarioHeadings[index + 1].start : report.length;
    const section = report.slice(heading.start, end);
    const results = [...section.matchAll(/^- Result: (.+)$/gm)].map((match) => match[1]);

    if (results.length !== 1 || results[0] !== 'PASS')
      fail(`Expected exactly one PASS result for scenario ${heading.name}, found: ${results.join(', ') || '<none>'}`);

    if (heading.name === 'MCP localization render and invalidation churn')
      verifyLocalizationScenario(section, profile.values);
  }

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
    console.error('Usage: node scripts/verify-soak-evidence.mjs <smoke|nightly|release>');
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
