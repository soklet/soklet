#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const officialRoot = resolve(dirname(fileURLToPath(import.meta.url)));
const allowedStatuses = new Set(['SUCCESS', 'FAILURE', 'WARNING', 'SKIPPED', 'INFO']);
const expectedPins = Object.freeze({
  reviewedOn: '2026-08-04',
  protocolVersion: '2026-07-28',
  suiteRepository: 'https://github.com/modelcontextprotocol/conformance.git',
  suiteCommit: '49103de6ed70804e940637bf3e9e29e4a3f54e64',
  packageName: '@modelcontextprotocol/conformance',
  packageVersion: '0.2.0-alpha.10',
  packageJsonSha256: '2fd65cda83b8af49452198944e1924a9dc1a52ed4f56aba18e1d814922150149',
  packageLockSha256: '161aef794720d2393a6a3db64e9751f2d52730b49f662e84b23363df5c1196e1',
  suiteEntryPoint: 'dist/index.js',
  suiteEntryPointBytes: 779702,
  suiteEntryPointSha256: 'b48694977974635ba1bdfa77a4423dd9cafb2419ef70840ce3cee67e8b184aa4',
  sourceTreeAlgorithm:
    "SHA-256 of bytewise-path-sorted '<file-sha256>  <relative-path>\\n' rows",
  suiteListCommandArguments: Object.freeze([
    'list', '--server', '--spec-version', '2026-07-28',
  ]),
  suiteScenarioCommandArguments: Object.freeze([
    'server', '--url', '<fixture-url>', '--scenario', '<exact-scenario-name>',
    '--spec-version', '2026-07-28', '-o', '<scenario-output-directory>', '--verbose',
  ]),
  suiteSchemaSpecificationCommit: '71e306956a4959c9655e5036be215d41986596e6',
  suiteSchemaPath: 'src/spec-types/draft.schema.json',
  suiteSchemaSha256: '9281c4890630e2d1e61792fa23b4084c4ea360cd58519610cd050545ab7b8708',
  specificationRepository: 'https://github.com/modelcontextprotocol/modelcontextprotocol.git',
  specificationTag: '2026-07-28',
  specificationCommit: '5f5440bb26a62e2cf3440b92da5a667efa03b267',
  specificationSchemaPath: 'schema/2026-07-28/schema.json',
  specificationSchemaVendoredPath: 'final-schema/schema.json',
  finalSchemaSha256: 'ef70b61f99b6d2e5e3b46863822eab08dff6a45bedc7a08914e0e5b133f40203',
  specificationLicensePath: 'LICENSE',
  specificationLicenseVendoredPath: 'final-schema/LICENSE.upstream',
  finalLicenseSha256: '0382b0057770ca05e9c350a50aa3b1c1fea84da0bc81d723bf00b9aa841be58a',
  fullCount: 40,
  runCount: 39,
  fullDigest: '3c41ddedcefd14403c891b5a518dfde19ee9f90ad18d9ca6e012de325a78821a',
  runDigest: '4979955e16de137e16d1fe1b1aa5699fe1fc879daec0033ae57629858ec3b8d5',
  nodeVersion: '26.5.0',
  npmVersion: '11.17.0',
  nodeChecksumsUrl: 'https://nodejs.org/dist/v26.5.0/SHASUMS256.txt',
  nodeChecksumsSha256: 'c293d34153b5d2357e6c1e521907dbf6bd3833a18565e3eb19839e5589a2bd9d',
  nodeLinuxX64Artifact: 'node-v26.5.0-linux-x64.tar.xz',
  nodeLinuxX64Sha256: '9f619528f1db5ddc41dccf54211066fb42228d69a156733c69cb9d6cc92e358c',
  ajvVersion: '8.20.0',
  ajvFormatsVersion: '3.0.1',
  sourceTreeFileCount: 263,
  sourceTreeSha256: '94d3b3de1266796353380122acaf7c1d02257769618d08e7dcc38cfc60fd595b',
});

export function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

export function inventoryBytes(names) {
  if (!Array.isArray(names) || names.some((name) =>
    typeof name !== 'string' || name.length === 0 || name !== name.trim()
      || name.includes('\n') || name.includes('\r'))) {
    throw new Error('Scenario inventory contains an invalid name');
  }
  return Buffer.from(`${names.join('\n')}\n`, 'utf8');
}

export function parseOfficialScenarioList(stdout) {
  if (typeof stdout !== 'string' || stdout.includes('\r') || !stdout.endsWith('\n'))
    throw new Error('Official scenario listing must be LF-only text ending in LF');
  const lines = stdout.slice(0, -1).split('\n');
  if (lines.shift() !== 'Server scenarios (test against a server):')
    throw new Error('Official scenario listing heading changed');
  if (lines.length === 0)
    throw new Error('Official scenario listing is empty');

  const names = [];
  const seen = new Set();
  for (const [index, line] of lines.entries()) {
    const match = /^  - ([a-z0-9]+(?:-[a-z0-9]+)*) \[([0-9]{4}-[0-9]{2}-[0-9]{2}(?:,[0-9]{4}-[0-9]{2}-[0-9]{2})*)\]$/.exec(line);
    if (match === null)
      throw new Error(`Official scenario listing row ${index + 1} changed format`);
    const [, name, versions] = match;
    if (!versions.split(',').includes(expectedPins.protocolVersion))
      throw new Error(`Listed scenario ${name} does not include ${expectedPins.protocolVersion}`);
    if (!seen.add(name))
      throw new Error(`Official scenario listing contains duplicate ${name}`);
    names.push(name);
  }
  return names;
}

export function readCanonicalJson(path, description = path) {
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink())
    throw new Error(`${description} must be a regular non-symbolic-link file`);
  const bytes = readFileSync(path);
  const text = bytes.toString('utf8');
  if (Buffer.from(text, 'utf8').compare(bytes) !== 0)
    throw new Error(`${description} must be UTF-8`);
  if (text.includes('\r') || !text.endsWith('\n'))
    throw new Error(`${description} must use LF and end in LF`);
  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`${description} is not valid JSON`, { cause: error });
  }
}

export function verifyManifestSet(root = officialRoot) {
  const pins = readCanonicalJson(resolve(root, 'upstream-pins.json'), 'upstream pins');
  const selection = readCanonicalJson(resolve(root, 'scenarios.json'), 'scenario manifest');
  const expectedChecks = readCanonicalJson(
    resolve(root, 'expected-checks.json'), 'expected-check manifest',
  );

  verifyPins(pins);
  verifyScenarioManifest(selection, pins);
  verifyExpectedChecks(expectedChecks, selection, pins);
  return { pins, selection, expectedChecks };
}

export function verifyOfficialSuite(suiteDirectory, pins, { requireBuilt = true } = {}) {
  const rootStats = lstatSync(suiteDirectory);
  if (!rootStats.isDirectory() || rootStats.isSymbolicLink())
    throw new Error('Official suite directory must be a real directory');

  const suite = pins.officialConformanceSuite;
  const sourceTree = sourceTreeIdentity(suiteDirectory, suite.sourceTree.excludedDirectoryNames);
  if (sourceTree.fileCount !== suite.sourceTree.fileCount
      || sourceTree.sha256 !== suite.sourceTree.sha256)
    throw new Error('Official suite source tree differs from the reviewed pin');
  const packagePath = resolve(suiteDirectory, 'package.json');
  const lockPath = resolve(suiteDirectory, 'package-lock.json');
  const suiteSchemaPath = resolve(suiteDirectory, suite.vendoredProtocolSchema.path);
  assertFileSha(packagePath, suite.package.packageJsonSha256, 'suite package.json');
  assertFileSha(lockPath, suite.package.packageLockJsonSha256, 'suite package-lock.json');
  assertFileSha(suiteSchemaPath, suite.vendoredProtocolSchema.sha256, 'suite draft schema');

  const packageJson = JSON.parse(readFileSync(packagePath, 'utf8'));
  if (packageJson.name !== expectedPins.packageName
      || packageJson.version !== expectedPins.packageVersion
      || packageJson.type !== 'module'
      || packageJson.bin?.conformance !== 'dist/index.js'
      || packageJson.scripts?.build
        !== 'tsdown src/index.ts --minify --clean --target node20 --no-fixed-extension') {
    throw new Error('Official suite package identity does not match the reviewed pin');
  }
  const lock = JSON.parse(readFileSync(lockPath, 'utf8'));
  if (lock.lockfileVersion !== 3
      || lock.packages?.['node_modules/ajv']?.version
        !== suite.lockedSchemaDependencies.ajv
      || lock.packages?.['node_modules/ajv-formats']?.version
        !== suite.lockedSchemaDependencies['ajv-formats']) {
    throw new Error('Official suite lockfile format or validator versions changed');
  }
  if (requireBuilt) {
    const entryPoint = resolve(suiteDirectory, suite.entryPoint);
    const stats = lstatSync(entryPoint);
    if (!stats.isFile() || stats.isSymbolicLink())
      throw new Error('Official suite built entry point is missing or unsafe');
    if (suite.builtEntryPoint.path !== suite.entryPoint
        || stats.size !== suite.builtEntryPoint.bytes)
      throw new Error('Official suite built entry point size differs from the reviewed pin');
    assertFileSha(
      entryPoint, suite.builtEntryPoint.sha256, 'official suite built entry point',
    );
  }
  return Object.freeze({ packagePath, lockPath, suiteSchemaPath, sourceRoot: suiteDirectory });
}

export function sourceTreeIdentity(root, excludedDirectoryNames) {
  if (!Array.isArray(excludedDirectoryNames)
      || excludedDirectoryNames.some((name) => typeof name !== 'string' || name.includes('/')))
    throw new Error('Source-tree exclusion list is invalid');
  const excluded = new Set(excludedDirectoryNames);
  const paths = [];
  visit(root);
  paths.sort(bytewiseCompare);
  const rows = paths.map((path) =>
    `${sha256(readFileSync(resolve(root, path)))}  ${path}\n`).join('');
  return Object.freeze({ fileCount: paths.length, sha256: sha256(Buffer.from(rows, 'utf8')) });

  function visit(directory) {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      if (entry.isDirectory() && excluded.has(entry.name)) continue;
      const path = resolve(directory, entry.name);
      if (entry.isSymbolicLink())
        throw new Error(`Official suite source tree contains a symbolic link: ${path}`);
      if (entry.isDirectory()) visit(path);
      else if (entry.isFile()) paths.push(relative(root, path).split(sep).join('/'));
      else throw new Error(`Official suite source tree contains an unsupported entry: ${path}`);
    }
  }
}

export function verifyListedInventory(listOutput, selection, pins) {
  const names = parseOfficialScenarioList(listOutput);
  const manifestNames = selection.scenarios.map((scenario) => scenario.name);
  if (JSON.stringify(names) !== JSON.stringify(manifestNames))
    throw new Error('Live official scenario order or names differ from scenarios.json');
  if (names.length !== pins.scenarioInventory.fullCount
      || sha256(inventoryBytes(names)) !== pins.scenarioInventory.fullInventorySha256) {
    throw new Error('Live official full-inventory count or digest differs from the reviewed pin');
  }
  const excluded = new Set(pins.scenarioInventory.excludedNames);
  const runNames = names.filter((name) => !excluded.has(name));
  if (runNames.length !== pins.scenarioInventory.selectedRunCount
      || sha256(inventoryBytes(runNames)) !== pins.scenarioInventory.selectedRunSetSha256) {
    throw new Error('Live official run-set count or digest differs from the reviewed pin');
  }
  return names;
}

export function verifyToolchain(pins, npmVersion) {
  const actualNode = process.version.startsWith('v') ? process.version.slice(1) : process.version;
  if (actualNode !== pins.toolchain.node)
    throw new Error(`Node must be exactly ${pins.toolchain.node}; found ${actualNode}`);
  if (npmVersion !== pins.toolchain.npm)
    throw new Error(`npm must be exactly ${pins.toolchain.npm}; found ${npmVersion}`);
}

export function activeScenarios(selection, phase) {
  if (!Number.isInteger(phase) || phase < 3 || phase > 7)
    throw new Error('Conformance phase must be an integer from 3 through 7');
  if (phase === 3)
    return selection.scenarios.filter((scenario) => scenario.phase3Status === 'ACTIVE_EARLY');
  return selection.scenarios.filter((scenario) =>
    scenario.selection === 'RUN' && scenario.earliestPhase <= phase);
}

export function officialScenarioArguments(pins,
  { fixtureUrl, scenarioName, outputDirectory }) {
  for (const [name, value] of Object.entries({ fixtureUrl, scenarioName, outputDirectory })) {
    if (typeof value !== 'string' || value.length === 0 || value.includes('\0'))
      throw new Error(`Official scenario ${name} replacement is invalid`);
  }
  const replacements = new Map([
    ['<fixture-url>', fixtureUrl],
    ['<exact-scenario-name>', scenarioName],
    ['<scenario-output-directory>', outputDirectory],
  ]);
  const arguments_ = pins.officialConformanceSuite.scenarioCommandArguments.map(
    (argument) => replacements.get(argument) ?? argument,
  );
  if (arguments_.some((argument) => /^<.*>$/.test(argument)))
    throw new Error('Official scenario command contains an unresolved placeholder');
  return Object.freeze(arguments_);
}

function verifyPins(pins) {
  assertExactKeys(pins, [
    'schemaVersion', 'reviewedOn', 'protocolVersion', 'officialConformanceSuite',
    'finalSpecification', 'toolchain', 'scenarioInventory', 'upstreamDriftReview',
  ], 'upstream pins');
  if (pins.schemaVersion !== 1 || pins.reviewedOn !== expectedPins.reviewedOn
      || pins.protocolVersion !== expectedPins.protocolVersion)
    throw new Error('Unsupported upstream-pins schema or protocol version');
  const suite = pins.officialConformanceSuite;
  assertExactKeys(suite, [
    'repository', 'commit', 'package', 'entryPoint', 'builtEntryPoint', 'sourceTree',
    'listCommandArguments', 'scenarioCommandArguments', 'lockedSchemaDependencies',
    'vendoredProtocolSchema',
  ], 'official suite pin');
  assertExactKeys(suite.package, [
    'name', 'version', 'versionIsDescriptiveOnly', 'packageJsonSha256',
    'packageLockJsonSha256',
  ], 'official suite package pin');
  assertExactKeys(suite.lockedSchemaDependencies, ['ajv', 'ajv-formats'],
    'locked schema dependencies');
  assertExactKeys(suite.vendoredProtocolSchema,
    ['specificationCommit', 'path', 'sha256'], 'suite schema pin');
  assertExactKeys(suite.builtEntryPoint,
    ['path', 'bytes', 'sha256'], 'suite built-entry-point pin');
  assertExactKeys(suite.sourceTree,
    ['algorithm', 'excludedDirectoryNames', 'fileCount', 'sha256'], 'suite source-tree pin');
  if (suite.repository !== expectedPins.suiteRepository
      || suite.commit !== expectedPins.suiteCommit
      || suite.package.name !== expectedPins.packageName
      || suite.package.version !== expectedPins.packageVersion
      || suite.package.versionIsDescriptiveOnly !== true
      || suite.package.packageJsonSha256 !== expectedPins.packageJsonSha256
      || suite.package.packageLockJsonSha256 !== expectedPins.packageLockSha256
      || suite.entryPoint !== expectedPins.suiteEntryPoint
      || suite.builtEntryPoint.path !== expectedPins.suiteEntryPoint
      || suite.builtEntryPoint.bytes !== expectedPins.suiteEntryPointBytes
      || suite.builtEntryPoint.sha256 !== expectedPins.suiteEntryPointSha256
      || suite.sourceTree.algorithm !== expectedPins.sourceTreeAlgorithm
      || JSON.stringify(suite.listCommandArguments)
        !== JSON.stringify(expectedPins.suiteListCommandArguments)
      || JSON.stringify(suite.scenarioCommandArguments)
        !== JSON.stringify(expectedPins.suiteScenarioCommandArguments)
      || suite.vendoredProtocolSchema.specificationCommit
        !== expectedPins.suiteSchemaSpecificationCommit
      || suite.vendoredProtocolSchema.path !== expectedPins.suiteSchemaPath
      || suite.vendoredProtocolSchema.sha256 !== expectedPins.suiteSchemaSha256
      || suite.sourceTree.fileCount !== expectedPins.sourceTreeFileCount
      || suite.sourceTree.sha256 !== expectedPins.sourceTreeSha256
      || JSON.stringify(suite.sourceTree.excludedDirectoryNames)
        !== '[".git","dist","node_modules"]'
      || suite.lockedSchemaDependencies.ajv !== expectedPins.ajvVersion
      || suite.lockedSchemaDependencies['ajv-formats'] !== expectedPins.ajvFormatsVersion) {
    throw new Error('Official conformance pin differs from the reviewed Phase 3 values');
  }
  const specification = pins.finalSpecification;
  assertExactKeys(specification,
    ['repository', 'tag', 'commit', 'schema', 'license'], 'final specification pin');
  assertExactKeys(specification.schema,
    ['path', 'sha256', 'bytes', 'definitionCount', 'vendoredPath'], 'final schema pin');
  assertExactKeys(specification.license,
    ['path', 'sha256', 'bytes', 'vendoredPath'], 'final license pin');
  if (specification.repository !== expectedPins.specificationRepository
      || specification.tag !== expectedPins.specificationTag
      || specification.commit !== expectedPins.specificationCommit
      || specification.schema.path !== expectedPins.specificationSchemaPath
      || specification.schema.vendoredPath !== expectedPins.specificationSchemaVendoredPath
      || specification.schema.sha256 !== expectedPins.finalSchemaSha256
      || specification.schema.bytes !== 181474
      || specification.schema.definitionCount !== 155
      || specification.license.path !== expectedPins.specificationLicensePath
      || specification.license.vendoredPath !== expectedPins.specificationLicenseVendoredPath
      || specification.license.sha256 !== expectedPins.finalLicenseSha256
      || specification.license.bytes !== 12227)
    throw new Error('Final specification pin differs from the reviewed Phase 3 values');
  assertExactKeys(pins.toolchain,
    ['node', 'npm', 'versionMatch', 'nodeDistribution'], 'toolchain pin');
  assertExactKeys(pins.toolchain.nodeDistribution, [
    'checksumsUrl', 'checksumsSha256', 'linuxX64Artifact', 'linuxX64Sha256',
  ], 'Node distribution pin');
  if (pins.toolchain.node !== expectedPins.nodeVersion
      || pins.toolchain.npm !== expectedPins.npmVersion
      || pins.toolchain.versionMatch !== 'EXACT'
      || pins.toolchain.nodeDistribution.checksumsUrl !== expectedPins.nodeChecksumsUrl
      || pins.toolchain.nodeDistribution.checksumsSha256
        !== expectedPins.nodeChecksumsSha256
      || pins.toolchain.nodeDistribution.linuxX64Artifact
        !== expectedPins.nodeLinuxX64Artifact
      || pins.toolchain.nodeDistribution.linuxX64Sha256
        !== expectedPins.nodeLinuxX64Sha256)
    throw new Error('Conformance toolchain pin differs from the reviewed Phase 3 values');
  const inventory = pins.scenarioInventory;
  assertExactKeys(inventory, [
    'fullCount', 'selectedRunCount', 'excludedNames', 'serialization',
    'fullInventorySha256', 'selectedRunSetSha256',
  ], 'scenario inventory pin');
  assertExactKeys(inventory.serialization, [
    'encoding', 'unicodeNormalization', 'trimWhitespace', 'lineTerminator',
    'terminatesLastLine', 'order',
  ], 'scenario serialization pin');
  if (inventory.fullCount !== expectedPins.fullCount
      || inventory.selectedRunCount !== expectedPins.runCount
      || JSON.stringify(inventory.excludedNames) !== '["completion-complete"]'
      || inventory.serialization.encoding !== 'UTF-8'
      || inventory.serialization.unicodeNormalization !== 'NONE'
      || inventory.serialization.trimWhitespace !== false
      || inventory.serialization.lineTerminator !== 'LF'
      || inventory.serialization.terminatesLastLine !== true
      || inventory.serialization.order !== 'PINNED_CLI_OUTPUT'
      || inventory.fullInventorySha256 !== expectedPins.fullDigest
      || inventory.selectedRunSetSha256 !== expectedPins.runDigest)
    throw new Error('Scenario inventory pin differs from the reviewed Phase 3 values');
  assertExactKeys(pins.upstreamDriftReview, [
    'reviewedOn', 'decision', 'suiteLabelsProtocolVersionAsDraft',
    'suiteVendoredSchemaMatchesFinalTaggedSchema', 'knownSchemaDifference',
    'requiredSupplement',
  ], 'upstream drift review');
  if (pins.upstreamDriftReview.reviewedOn !== expectedPins.reviewedOn
      || pins.upstreamDriftReview.decision !== 'RETAIN_REVIEWED_PIN'
      || pins.upstreamDriftReview.suiteLabelsProtocolVersionAsDraft !== true
      || pins.upstreamDriftReview.suiteVendoredSchemaMatchesFinalTaggedSchema !== false
      || typeof pins.upstreamDriftReview.knownSchemaDifference !== 'string'
      || pins.upstreamDriftReview.knownSchemaDifference.length < 40
      || typeof pins.upstreamDriftReview.requiredSupplement !== 'string'
      || pins.upstreamDriftReview.requiredSupplement.length < 40) {
    throw new Error('Upstream drift review differs from the reviewed Phase 3 decision');
  }
}

function verifyScenarioManifest(selection, pins) {
	assertExactKeys(selection, [
		'schemaVersion', 'protocolVersion', 'suiteCommit', 'currentImplementationPhase',
		'earliestPhaseSemantics', 'phaseExecutionPolicy', 'scenarios',
	], 'scenario manifest');
	if (selection.schemaVersion !== 1 || selection.protocolVersion !== pins.protocolVersion
			|| selection.suiteCommit !== pins.officialConformanceSuite.commit
			|| selection.currentImplementationPhase !== 5
			|| nonBlankString(selection.earliestPhaseSemantics)
			|| nonBlankString(selection.phaseExecutionPolicy)
			|| !Array.isArray(selection.scenarios))
    throw new Error('Scenario manifest identity is invalid');
  if (selection.scenarios.length !== pins.scenarioInventory.fullCount)
    throw new Error('Scenario manifest must contain the complete 40-row inventory');

  const names = [];
  const seen = new Set();
  let runCount = 0;
  let notApplicableCount = 0;
  for (const [index, scenario] of selection.scenarios.entries()) {
    assertExactKeys(scenario, [
      'ordinal', 'name', 'selection', 'earliestPhase', 'phase3Status',
      'expectedCheckProfile', 'requiredFixtureRegistrations', 'localSupplements',
      'rationale',
    ], `scenario ${index + 1}`);
    if (scenario.ordinal !== index + 1 || typeof scenario.name !== 'string'
        || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(scenario.name)
        || !seen.add(scenario.name))
      throw new Error(`Invalid scenario row at ordinal ${index + 1}`);
    if (!Array.isArray(scenario.requiredFixtureRegistrations)
        || scenario.requiredFixtureRegistrations.some(nonBlankString) === true
        || !Array.isArray(scenario.localSupplements)
        || scenario.localSupplements.some(nonBlankString) === true)
      throw new Error(`Scenario ${scenario.name} has invalid fixture or supplement inventory`);
    names.push(scenario.name);

    if (scenario.selection === 'NOT_APPLICABLE') {
      notApplicableCount++;
      if (scenario.name !== 'completion-complete' || scenario.earliestPhase !== null
          || scenario.phase3Status !== 'NOT_APPLICABLE'
          || scenario.expectedCheckProfile !== null)
        throw new Error('Completion must be the sole exact NOT_APPLICABLE disposition');
		} else if (scenario.selection === 'RUN') {
      runCount++;
      if (![4, 5].includes(scenario.earliestPhase))
        throw new Error(`RUN scenario ${scenario.name} has an invalid earliest phase`);
			const active = scenario.phase3Status === 'ACTIVE_EARLY';
			if (active !== (scenario.name === 'dns-rebinding-protection'))
				throw new Error('DNS rebinding must be the sole Phase 3 early scenario');
			const profileRequired = active
				|| scenario.earliestPhase <= selection.currentImplementationPhase;
			if (profileRequired !== (typeof scenario.expectedCheckProfile === 'string'))
				throw new Error(
					`Scenario ${scenario.name} has an invalid current-phase profile reference`,
				);
    } else {
      throw new Error(`Scenario ${scenario.name} has an unknown selection`);
    }
    if (typeof scenario.rationale !== 'string' || scenario.rationale.trim().length < 20)
      throw new Error(`Scenario ${scenario.name} needs a specific rationale`);
  }
  if (runCount !== pins.scenarioInventory.selectedRunCount || notApplicableCount !== 1)
    throw new Error('Scenario selection counts differ from the reviewed pin');
  if (sha256(inventoryBytes(names)) !== pins.scenarioInventory.fullInventorySha256)
    throw new Error('Scenario manifest order/name digest differs from the reviewed pin');
  const runNames = selection.scenarios
    .filter((scenario) => scenario.selection === 'RUN')
    .map((scenario) => scenario.name);
  if (sha256(inventoryBytes(runNames)) !== pins.scenarioInventory.selectedRunSetSha256)
    throw new Error('Scenario manifest RUN digest differs from the reviewed pin');
  if (selection.scenarios.filter((scenario) => scenario.earliestPhase === 4).length !== 23
      || selection.scenarios.filter((scenario) => scenario.earliestPhase === 5).length !== 16)
    throw new Error('Phase 4/5 scenario ownership counts changed');
}

function verifyExpectedChecks(expectedChecks, selection, pins) {
  assertExactKeys(expectedChecks, [
    'schemaVersion', 'protocolVersion', 'suiteCommit', 'profilePolicy',
    'deferredProfilePolicy', 'profiles',
  ], 'expected-check manifest');
  if (expectedChecks.schemaVersion !== 1
      || expectedChecks.protocolVersion !== pins.protocolVersion
      || expectedChecks.suiteCommit !== pins.officialConformanceSuite.commit
      || !Array.isArray(expectedChecks.profiles))
    throw new Error('Expected-check manifest identity is invalid');
	const referenced = new Set(selection.scenarios
    .map((scenario) => scenario.expectedCheckProfile)
    .filter((profile) => profile !== null));
	const seen = new Set();
	const ownersByProfile = new Map(selection.scenarios
		.filter((scenario) => scenario.expectedCheckProfile !== null)
		.map((scenario) => [scenario.expectedCheckProfile, scenario]));
	for (const profile of expectedChecks.profiles) {
    assertExactKeys(profile, [
      'id', 'scenario', 'frozenInPhase', 'suiteCommit', 'checks',
      'automaticWireChecks',
    ], `expected profile ${profile.id}`);
		const owner = ownersByProfile.get(profile.id);
		const expectedFrozenPhase = owner?.phase3Status === 'ACTIVE_EARLY'
			? 3
			: owner?.earliestPhase;
		if (typeof profile.id !== 'string' || !referenced.has(profile.id) || !seen.add(profile.id)
				|| profile.suiteCommit !== pins.officialConformanceSuite.commit
				|| owner === undefined || profile.scenario !== owner.name
				|| profile.frozenInPhase !== expectedFrozenPhase
				|| profile.frozenInPhase > selection.currentImplementationPhase
				|| !Array.isArray(profile.checks))
			throw new Error(`Invalid or orphan expected-check profile ${profile.id}`);
		const tuples = new Set();
		for (const check of profile.checks) {
			assertExactKeys(check,
				check.status === 'SKIPPED'
					? ['id', 'status', 'count', 'reason']
					: ['id', 'status', 'count'],
				`expected check ${check.id}`);
			if (typeof check.id !== 'string' || !allowedStatuses.has(check.status)
					|| !Number.isInteger(check.count) || check.count < 1
					|| (check.status === 'SKIPPED' && nonBlankString(check.reason))
					|| !tuples.add(`${check.id}\u0000${check.status}\u0000${check.reason ?? ''}`))
				throw new Error(`Invalid expected check in profile ${profile.id}`);
			if (check.status === 'FAILURE' || check.status === 'WARNING')
				throw new Error(`Expected profile ${profile.id} may not accept ${check.status}`);
			if (check.status === 'INFO'
					&& (profile.scenario !== 'server-sse-multiple-streams'
						|| check.id !== 'server-sse-streams-functional'))
				throw new Error(`Expected profile ${profile.id} contains an unreviewed INFO`);
    }
    const wire = profile.automaticWireChecks;
    assertExactKeys(wire, [
      'wire-schema-valid', 'wire-schema-harness-error', 'rationale',
    ], `wire policy ${profile.id}`);
		if (wire === null || typeof wire !== 'object'
				|| !Number.isInteger(wire['wire-schema-valid'])
				|| wire['wire-schema-valid'] < 0
				|| wire['wire-schema-harness-error'] !== 0
				|| nonBlankString(wire.rationale))
      throw new Error(`Expected profile ${profile.id} has an invalid automatic-wire policy`);
  }
  if (JSON.stringify([...seen].sort()) !== JSON.stringify([...referenced].sort()))
    throw new Error('Expected-check profile references are incomplete');
}

function assertFileSha(path, expectedSha256, description) {
  const stats = lstatSync(path);
  if (!stats.isFile() || stats.isSymbolicLink())
    throw new Error(`${description} must be a regular non-symbolic-link file`);
  if (sha256(readFileSync(path)) !== expectedSha256)
    throw new Error(`${description} checksum differs from the reviewed pin`);
}

function nonBlankString(value) {
  return typeof value !== 'string' || value.trim() !== value || value.length === 0;
}

function assertExactKeys(value, expectedKeys, description) {
  if (value === null || typeof value !== 'object' || Array.isArray(value))
    throw new Error(`${description} must be an object`);
  const actual = Object.keys(value).sort(bytewiseCompare);
  const expected = [...expectedKeys].sort(bytewiseCompare);
  if (JSON.stringify(actual) !== JSON.stringify(expected))
    throw new Error(`${description} contains missing or unexpected fields`);
}

function bytewiseCompare(left, right) {
  return Buffer.compare(Buffer.from(left), Buffer.from(right));
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length !== 2 || args[0] !== '--suite-dir') {
    console.error('Usage: node conformance/official/verify.mjs --suite-dir <built-suite>');
    process.exit(64);
  }
  const manifests = verifyManifestSet();
  verifyOfficialSuite(resolve(args[1]), manifests.pins);
  console.log('Verified official MCP conformance manifests and pinned suite inputs.');
}
