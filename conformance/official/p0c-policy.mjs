import { createHash, randomUUID } from 'node:crypto';
import { lstatSync, readFileSync } from 'node:fs';
import { dirname, isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import {
  assessP0CFiles,
  fixtureClassesTreeSha256,
} from './proposals/p0c-disposition.mjs';

export const acceptedP0CPolicy = 'accepted-2026-09-22';
export const acceptedP0CStatus = 'PASSED_WITH_REVIEWED_EXCEPTION';
const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const decisionPath = resolve(projectRoot,
  'conformance/official/P0C_CHECK_DISPOSITION_2026-09-22.md');
const decisionSha256 = 'cb132c45219f0bd905a96940a66f23bfd30104e7389f4b0bc0e5b8be2eb791b8';
const scenarioName = 'server-stateless';
const scenarioDirectoryName = '001-server-stateless';
const expectedProfile = 'server-stateless.phase5.v1';
const acceptedFailureIds = Object.freeze([
  'sep-2575-server-rejects-undeclared-capability',
  'sep-2575-missing-capability-http-400',
]);

function fail(message) { throw new Error(`Accepted P0-C policy: ${message}`); }
function sha256(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
function exactKeys(value, names, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)
      || !isDeepStrictEqual(Object.keys(value).sort(), [...names].sort()))
    fail(`${label} fields changed`);
}
function boundedJson(path, label) {
  const stat = lstatSync(path);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size === 0
      || stat.size > 64 * 1024) fail(`${label} must be a bounded regular file`);
  return JSON.parse(readFileSync(path, 'utf8'));
}
function verifyOwnerDecision() {
  if (sha256(readFileSync(decisionPath)) !== decisionSha256)
    fail('owner decision document changed');
}
function capturePaths(directory) {
  return Object.freeze({
    classes: resolve(directory, 'fixture/fixture-build/classes'),
    checks: resolve(directory, 'official/checks.json'),
    stdout: resolve(directory, 'official/official.stdout.log'),
    stderr: resolve(directory, 'official/official.stderr.log'),
    officialReceipt: resolve(directory, 'official/official-receipt.json'),
    controlReceipt: resolve(directory, 'control/control-receipt.json'),
  });
}
function assessmentArguments({ suiteDirectory, candidateJarPath, directory }) {
  const paths = capturePaths(directory);
  return [
    '--suite-dir', suiteDirectory,
    '--candidate-jar', candidateJarPath,
    '--fixture-classes-dir', paths.classes,
    '--checks', paths.checks,
    '--official-stdout', paths.stdout,
    '--official-stderr', paths.stderr,
    '--official-receipt', paths.officialReceipt,
    '--control', paths.controlReceipt,
  ];
}

export function verifyAcceptedP0CRow({ row, evidencePath, suiteDirectory,
  candidateJarPath, fixtureClassesPath, expectedProjectRoot = projectRoot }) {
  verifyOwnerDecision();
  if (resolve(expectedProjectRoot) !== projectRoot)
    fail('candidate project root differs from the policy source tree');
  if (!isAbsolute(suiteDirectory) || !isAbsolute(candidateJarPath)
      || !isAbsolute(fixtureClassesPath) || !isAbsolute(evidencePath))
    fail('suite, candidate, fixture, and evidence paths must be absolute');
  exactKeys(row, ['name', 'passed', 'checkCount', 'expectedCheckProfile',
    'observedProfileDraft', 'p0cDisposition'], 'scenario row');
  if (row.name !== scenarioName || row.passed !== false
      || row.checkCount !== 30 || row.expectedCheckProfile !== expectedProfile
      || row.observedProfileDraft !== null)
    fail('scenario row changed or hid the raw official failure');
  exactKeys(row.p0cDisposition,
    ['policy', 'decisionSha256', 'suiteDirectory', 'assessment'], 'disposition');
  if (row.p0cDisposition.policy !== acceptedP0CPolicy
      || row.p0cDisposition.decisionSha256 !== decisionSha256
      || row.p0cDisposition.suiteDirectory !== suiteDirectory)
    fail('owner decision, policy, or suite path changed');
  const directory = resolve(dirname(evidencePath), scenarioDirectoryName);
  const directoryStat = lstatSync(directory);
  if (!directoryStat.isDirectory() || directoryStat.isSymbolicLink())
    fail('capture directory is unsafe');
  const status = boundedJson(resolve(directory, 'capture-status.json'),
    'capture status');
  exactKeys(status, ['formatVersion', 'kind', 'runId', 'stage', 'status',
    'officialReceiptPresent', 'controlReceiptPresent', 'failure',
    'releaseCandidateEvidence'], 'capture status');
  if (status.formatVersion !== 1 || status.kind !== 'p0c-proposal-capture'
      || status.status !== 'CAPTURED' || status.officialReceiptPresent !== true
      || status.controlReceiptPresent !== true || status.failure !== null
      || status.releaseCandidateEvidence !== false)
    fail('raw capture did not complete cleanly');
  const assessment = assessP0CFiles(assessmentArguments({
    suiteDirectory, candidateJarPath, directory,
  }));
  if (!isDeepStrictEqual(row.p0cDisposition.assessment, assessment)
      || assessment.runId !== status.runId
      || assessment.assessment !== 'PROPOSAL_REVIEWABLE'
      || assessment.adopted !== false
      || assessment.releaseGate !== 'UNCHANGED'
      || assessment.officialVerdict !== 'FAILURE'
      || assessment.officialExitCode !== 1
      || assessment.rawCheckCount !== 30
      || assessment.unaffectedCheckCount !== 28
      || !isDeepStrictEqual(assessment.upstreamFailureIds, acceptedFailureIds)
      || assessment.fixtureClassesSha256
        !== fixtureClassesTreeSha256(fixtureClassesPath))
    fail('capture assessment, raw failure, or public fixture binding changed');
  return assessment;
}

export async function runAcceptedP0CScenario({ suiteDirectory,
  candidateJarPath, fixtureClassesPath, javaExecutable, evidencePath,
  runBoundedCapture, expectedProjectRoot = projectRoot }) {
  if (!isAbsolute(javaExecutable) || !javaExecutable.endsWith('/bin/java'))
    fail('accepted policy requires an explicit absolute JDK java executable');
  if (typeof runBoundedCapture !== 'function')
    fail('accepted policy requires a bounded supervised capture command');
  verifyOwnerDecision();
  if (resolve(expectedProjectRoot) !== projectRoot)
    fail('candidate project root differs from the policy source tree');
  const directory = resolve(dirname(evidencePath), scenarioDirectoryName);
  const result = await runBoundedCapture(fileURLToPath(new URL(
    './proposals/capture-p0c-official.mjs', import.meta.url)), [
    '--suite-dir', suiteDirectory,
    '--candidate-jar', candidateJarPath,
    '--java-home', dirname(dirname(javaExecutable)),
    '--output-dir', directory,
    '--run-id', `p0c-${randomUUID()}`,
  ]);
  if (result.status !== 0 || result.signal !== null || result.timedOut
      || result.outputFailure !== null)
    fail(`bounded paired capture failed: ${result.outputFailure ?? result.stderr}`);
  const assessment = assessP0CFiles(assessmentArguments({
    suiteDirectory, candidateJarPath, directory,
  }));
  const row = Object.freeze({
    name: scenarioName,
    passed: false,
    checkCount: assessment.rawCheckCount,
    expectedCheckProfile: expectedProfile,
    observedProfileDraft: null,
    p0cDisposition: Object.freeze({
      policy: acceptedP0CPolicy,
      decisionSha256,
      suiteDirectory,
      assessment,
    }),
  });
  verifyAcceptedP0CRow({ row, evidencePath, suiteDirectory,
    candidateJarPath, fixtureClassesPath, expectedProjectRoot });
  return row;
}
