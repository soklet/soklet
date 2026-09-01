#!/usr/bin/env node

import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  mkdirSync,
  mkdtempSync,
  readFileSync,
  realpathSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';
import {
  canonicalJson,
  verifyReleaseHarnessConfiguration,
} from './import-release-harness-evidence.mjs';
import {
  prepareReleaseScanEvidence,
  releaseScanFindingFingerprint,
  ReleaseScanProducerError,
} from './produce-release-scans.mjs';

const root = realpathSync(mkdtempSync(join(tmpdir(), 'soklet-release-scans-producer-')));
const projectRoot = resolve(fileURLToPath(new URL('..', import.meta.url)));
let assertions = 0;
const NOW = new Date('2026-08-31T12:00:00Z');

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function write(directory, name, bytes) {
  writeFileSync(join(directory, name), bytes);
  return sha256(Buffer.from(bytes));
}

function emptySarif(tool) {
  return canonicalJson({
    runs: [{ results: [], tool: { driver: { name: tool } } }],
    version: '2.1.0',
  });
}

function codeqlFinding() {
  return {
    locations: [{
      physicalLocation: {
        artifactLocation: {
          uri: 'src/main/java/com/soklet/Example.java',
          uriBaseId: '%SRCROOT%',
        },
        region: { endColumn: 18, endLine: 7, startColumn: 5, startLine: 7 },
      },
    }],
    ruleId: 'java/example-security-rule',
  };
}

function codeqlSarif({
  commit = 'a'.repeat(40),
  findings = [],
  securitySeverity = '6.5',
} = {}) {
  return canonicalJson({
    runs: [{
      invocations: [{
        executionSuccessful: true,
        exitCode: 0,
        toolConfigurationNotifications: [],
        toolExecutionNotifications: [],
      }],
      results: findings,
      tool: {
        driver: {
          name: 'CodeQL',
          rules: findings.length === 0 ? [] : [{
            id: findings[0].ruleId,
            properties: { 'security-severity': securitySeverity },
          }],
        },
      },
      versionControlProvenance: [{
        repositoryUri: 'https://github.com/example/soklet',
        revisionId: commit,
      }],
    }],
    version: '2.1.0',
  });
}

function codeqlApprovalFor(finding, commit = 'a'.repeat(40)) {
  const region = finding.locations[0].physicalLocation.region;
  const path = finding.locations[0].physicalLocation.artifactLocation.uri;
  return {
    approvedAt: '2026-08-30T00:00:00Z',
    approvalReference: 'SEC-5678',
    commit,
    expiresAt: '2026-09-15T00:00:00Z',
    fingerprint: releaseScanFindingFingerprint({
      commit,
      endColumn: region.endColumn,
      endLine: region.endLine,
      path,
      ruleId: finding.ruleId,
      startColumn: region.startColumn,
      startLine: region.startLine,
    }),
    owner: 'security@example.test',
    path,
    rationale: 'Synthetic CodeQL false positive used to test exact exception plumbing.',
    ruleId: finding.ruleId,
    scanner: 'codeql',
  };
}

function gitleaksFinding({ severity } = {}) {
  return {
    Commit: '1'.repeat(40),
    EndColumn: 30,
    EndLine: 12,
    File: 'src/test/resources/example.properties',
    RuleID: 'generic-api-key',
    ...(severity === undefined ? {} : { Severity: severity }),
    StartColumn: 7,
    StartLine: 12,
  };
}

function findingIdentity(finding) {
  return {
    commit: finding.Commit,
    endColumn: finding.EndColumn,
    endLine: finding.EndLine,
    path: finding.File,
    ruleId: finding.RuleID,
    startColumn: finding.StartColumn,
    startLine: finding.StartLine,
  };
}

function gitleaksSarif(finding) {
  return canonicalJson({
    runs: [{
      results: [{
        locations: [{
          physicalLocation: {
            artifactLocation: { uri: finding.File },
            region: {
              endColumn: finding.EndColumn,
              endLine: finding.EndLine,
              startColumn: finding.StartColumn,
              startLine: finding.StartLine,
            },
          },
        }],
        partialFingerprints: { commitSha: finding.Commit },
        ruleId: finding.RuleID,
      }],
      tool: { driver: { name: 'gitleaks' } },
    }],
    version: '2.1.0',
  });
}

function approvalFor(finding) {
  return {
    approvedAt: '2026-08-30T00:00:00Z',
    approvalReference: 'SEC-1234',
    commit: finding.Commit,
    expiresAt: '2026-09-15T00:00:00Z',
    fingerprint: releaseScanFindingFingerprint(findingIdentity(finding)),
    owner: 'security@example.test',
    path: finding.File,
    rationale: 'Synthetic false positive used to test exact exception plumbing.',
    ruleId: finding.RuleID,
    scanner: 'gitleaks',
  };
}

function configureFinding(value, { approvals, finding = gitleaksFinding() } = {}) {
  writeFileSync(join(value.rawReportsRoot, '02-gitleaks.sarif'), gitleaksSarif(finding));
  writeFileSync(join(value.rawReportsRoot, '03-gitleaks.json'), canonicalJson([finding]));
  writeFileSync(value.approvalsPath, canonicalJson({
    exceptions: approvals ?? [approvalFor(finding)],
    formatVersion: 1,
  }));
  value.now = NOW;
  return { approval: approvalFor(finding), finding };
}

function configureCodeqlFinding(value, {
  approval,
  finding = codeqlFinding(),
  securitySeverity = '6.5',
} = {}) {
  const selectedApproval = approval ?? codeqlApprovalFor(finding);
  writeFileSync(
    join(value.rawReportsRoot, '00-codeql-java.sarif'),
    codeqlSarif({ findings: [finding], securitySeverity }),
  );
  writeFileSync(value.approvalsPath, canonicalJson({
    exceptions: [selectedApproval],
    formatVersion: 1,
  }));
  value.now = NOW;
  return { approval: selectedApproval, finding };
}

function workflowJob(source, jobId, nextJobId) {
  const marker = `  ${jobId}:\n`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `workflow must define the ${jobId} job`);
  if (nextJobId === undefined)
    return source.slice(start);
  const end = source.indexOf(`  ${nextJobId}:\n`, start + marker.length);
  assert.notEqual(end, -1, `workflow must define the ${nextJobId} job after ${jobId}`);
  return source.slice(start, end);
}

function workflowJobPermissions(job) {
  const match = job.match(/^    permissions:\n((?:^      [a-z-]+: (?:read|write|none)\n)+)/mu);
  assert.notEqual(match, null, 'workflow job must declare an explicit permission ceiling');
  return Object.fromEntries(match[1].trim().split('\n').map((line) => {
    const [name, access] = line.trim().split(': ');
    return [name, access];
  }));
}

function fixture(label) {
  const fixtureRoot = join(root, label);
  const candidateRoot = join(fixtureRoot, 'candidate');
  const configRoot = join(candidateRoot, 'config');
  const releaseRoot = join(candidateRoot, 'release');
  const provenanceRoot = join(fixtureRoot, 'provenance');
  const rawReportsRoot = join(fixtureRoot, 'raw');
  mkdirSync(configRoot, { recursive: true });
  mkdirSync(releaseRoot);
  mkdirSync(provenanceRoot);
  mkdirSync(rawReportsRoot);

  const spotbugsFilter = '<FindBugsFilter></FindBugsFilter>\n';
  const gitleaksConfig = '[extend]\nuseDefault = true\n';

  const provenanceBytes = {
    'codeql-bundle-linux64.tar.gz': 'codeql-bundle',
    'codeql-java-queries-qlpack.yml': 'codeql-qlpack',
    'codeql-java-security-extended-selectors.yml': 'codeql-selector',
    'codeql-java-security-extended.qls': 'codeql-suite',
    'gitleaks_8.30.1_linux_x64.tar.gz': 'gitleaks-archive',
    'gitleaks.toml': gitleaksConfig,
    'spotbugs-maven-plugin.jar': 'spotbugs-plugin',
    'spotbugs-exclude.xml': spotbugsFilter,
    'spotbugs.jar': 'spotbugs-engine',
  };
  const provenanceDigests = {};
  for (const [name, bytes] of Object.entries(provenanceBytes))
    provenanceDigests[name] = write(provenanceRoot, name, bytes);

  write(rawReportsRoot, '00-codeql-java.sarif', codeqlSarif());
  write(
    rawReportsRoot,
    '01-spotbugs.xml',
    '<?xml version="1.0" encoding="UTF-8"?>\n<BugCollection></BugCollection>\n',
  );
  write(rawReportsRoot, '02-gitleaks.sarif', emptySarif('gitleaks'));
  write(rawReportsRoot, '03-gitleaks.json', canonicalJson([]));
  write(rawReportsRoot, '04-runtime-dependency-surface.json', canonicalJson({
    externalRuntimeDependencyCount: 0,
    formatVersion: 1,
  }));
  const approvalsPath = join(releaseRoot, 'release-scan-exceptions.json');
  writeFileSync(approvalsPath, canonicalJson({ exceptions: [], formatVersion: 1 }));

  const reports = [
    '00-codeql-java.sarif',
    '01-spotbugs.xml',
    '02-gitleaks.sarif',
    '03-gitleaks.json',
    '04-runtime-dependency-surface.json',
    '05-toolchain-provenance.json',
  ].map((name, ordinal) => ({ name, ordinal }));
  const contract = {
    contractVersion: 1,
    evidenceContract: 'soklet.release.release-scans.v1',
    id: 'release-scans',
    policy: {
      allowlist: {
        fields: [
          'approvedAt',
          'approvalReference',
          'commit',
          'expiresAt',
          'fingerprint',
          'owner',
          'path',
          'rationale',
          'ruleId',
          'scanner',
        ],
        maximumLifetimeDays: 30,
        wildcardSuppression: 'PROHIBITED',
      },
      codeql: {
        bundle: { linuxTarGzSha256: provenanceDigests['codeql-bundle-linux64.tar.gz'] },
        javaQueries: {
          qlpackSha256: provenanceDigests['codeql-java-queries-qlpack.yml'],
          securityExtendedSuiteSelectorSha256:
            provenanceDigests['codeql-java-security-extended-selectors.yml'],
          securityExtendedSuiteSha256:
            provenanceDigests['codeql-java-security-extended.qls'],
        },
      },
      gitleaks: {
        configSha256: provenanceDigests['gitleaks.toml'],
        linuxX64ArchiveSha256: provenanceDigests['gitleaks_8.30.1_linux_x64.tar.gz'],
      },
      reports,
      spotbugs: {
        engineJarSha256: provenanceDigests['spotbugs.jar'],
        exclusionFileSha256: provenanceDigests['spotbugs-exclude.xml'],
        mavenPluginJarSha256: provenanceDigests['spotbugs-maven-plugin.jar'],
      },
    },
    producer: '.github/workflows/codeql.yml plus release-validation scan aggregation',
    toolchains: [],
  };
  const candidate = {
    candidateCommit: 'a'.repeat(40),
    candidateMainJarSha256: 'b'.repeat(64),
    candidatePomSha256: 'c'.repeat(64),
    candidateRegistrySha256: 'd'.repeat(64),
    candidateTree: 'e'.repeat(40),
    producerWorkflowSha256: 'f'.repeat(64),
  };
  return {
    approvalsPath,
    candidate,
    candidateRoot,
    contract,
    evidenceRoot: join(fixtureRoot, 'evidence'),
    provenanceRoot,
    rawReportsRoot,
  };
}

try {
  const approved = verifyReleaseHarnessConfiguration()
    .contracts.get('release-scans');
  const workflow = readFileSync(join(projectRoot, '.github/workflows/codeql.yml'), 'utf8');
  const codeqlJob = workflowJob(workflow, 'analyze');
  assert.match(codeqlJob, /runs-on: ubuntu-24\.04/u);
  assert.doesNotMatch(codeqlJob, /ubuntu-latest/u);
  assert.match(
    codeqlJob,
    /ref: \$\{\{ inputs\.candidate_commit \|\| github\.sha \}\}\n\n      - name: Verify exact release candidate/u,
  );
  assert.match(
    codeqlJob,
    /install-pinned-maven-linux-x64\.sh[\s\S]*?codeql-maven-distribution\.txt/u,
  );
  const actionReference = `github/codeql-action/(?:init|analyze)@${approved.policy.codeql.actionCommit}`;
  assert.equal([...workflow.matchAll(new RegExp(actionReference, 'gu'))].length, 2);
  assert.doesNotMatch(workflow, /github\/codeql-action\/(?:init|analyze)@v\d/u);
  assert.match(
    workflow,
    new RegExp(`codeql-bundle-v${approved.policy.codeql.bundle.version.replaceAll('.', '\\.')}\\/codeql-bundle-linux64\\.tar\\.gz`, 'u'),
  );
  assert.match(workflow, new RegExp(approved.policy.codeql.bundle.linuxTarGzSha256, 'u'));
  assert.match(workflow, /queries: security-extended/u);
  assert.match(workflow, /install-pinned-corretto-linux-x64\.sh[\s\S]*?coreJdk21[\s\S]*?release-scans-codeql\/codeql-java-distribution\.txt/u);
  assert.match(workflow, /workflow_dispatch:[\s\S]*?candidate_commit:/u);
  assert.match(
    workflow,
    /prepare-codeql-release-report\.mjs[\s\S]*?steps\.analyze\.outputs\.sarif-output[\s\S]*?inputs\.candidate_commit/u,
  );
  assert.match(
    workflow,
    /stage-codeql-release-provenance\.mjs[\s\S]*?steps\.init\.outputs\.codeql-path/u,
  );
  assert.match(workflow, /retention-days: 90/u);
  assert.deepEqual(workflowJobPermissions(codeqlJob), {
    actions: 'read',
    contents: 'read',
    'security-events': 'write',
  });
  assertions += 15;

  const runner = readFileSync(
    join(projectRoot, 'release/scripts/produce-release-scans-linux-x64.sh'),
    'utf8',
  );
  assert.match(runner, new RegExp(approved.policy.gitleaks.commit, 'u'));
  assert.match(runner, new RegExp(approved.policy.gitleaks.configSha256, 'u'));
  assert.match(runner, new RegExp(approved.policy.gitleaks.linuxX64ArchiveSha256, 'u'));
  assert.match(
    runner,
    new RegExp(`gitleaks/releases/download/v${approved.policy.gitleaks.version.replaceAll('.', '\\.')}`, 'u'),
  );
  assert.match(runner, /--log-opts="\$candidate_commit"/u);
  assert.match(runner, new RegExp(approved.policy.spotbugs.exclusionFileSha256, 'u'));
  const spotbugsExecutionIndex = runner.indexOf('-Pspotbugs compile spotbugs:check');
  assert.notEqual(spotbugsExecutionIndex, -1);
  for (const [label, digest] of [
    ['SpotBugs Maven plugin', approved.policy.spotbugs.mavenPluginJarSha256],
    ['SpotBugs engine', approved.policy.spotbugs.engineJarSha256],
  ]) {
    const firstDigestIndex = runner.indexOf(digest);
    assert.notEqual(firstDigestIndex, -1, `${label} digest must be present`);
    assert.ok(
      firstDigestIndex < spotbugsExecutionIndex,
      `${label} must be verified before SpotBugs executes`,
    );
  }
  assert.match(runner, /repo\.maven\.apache\.org\/maven2\/com\/github\/spotbugs/u);
  assert.match(runner, /mvn -B -ntp -C -Dgpg\.skip=true/u);
  assert.match(runner, /-Dsoklet\.spotbugs\.excludeFilterFile="\$spotbugs_filter"/u);
  assert.match(runner, /verify-runtime-dependency-surface\.mjs/u);
  assert.match(runner, /produce-release-scans\.mjs/u);
  assert.match(runner, /set \+e[\s\S]*?gitleaks_exit=\$\?[\s\S]*?set -e/u);
  assert.match(runner, /run_gitleaks_report sarif[\s\S]*?run_gitleaks_report json/u);
  assert.match(runner, /--approvals "\$approvals"/u);
  assert.ok(
    runner.indexOf('run_gitleaks_report json') < runner.indexOf('produce-release-scans.mjs'),
    'both Gitleaks reports must be attempted before the producer decision',
  );
  const pom = readFileSync(join(projectRoot, 'pom.xml'), 'utf8');
  assert.match(
    pom,
    /<soklet\.spotbugs\.excludeFilterFile>\$\{project\.basedir\}\/config\/spotbugs-exclude\.xml<\/soklet\.spotbugs\.excludeFilterFile>/u,
  );
  assert.match(
    pom,
    /<excludeFilterFile>\$\{soklet\.spotbugs\.excludeFilterFile\}<\/excludeFilterFile>/u,
  );
  assertions += 22;

  const releaseWorkflow = readFileSync(
    join(projectRoot, '.github/workflows/release-validation.yml'),
    'utf8',
  );
  const codeqlCallerJob = workflowJob(
    releaseWorkflow,
    'release-scans-codeql',
    'release-scans',
  );
  const releaseScanJob = workflowJob(releaseWorkflow, 'release-scans', 'mcp-benchmarks');
  assert.match(
    codeqlCallerJob,
    /release-scans-codeql:[\s\S]*?uses: \.\/\.github\/workflows\/codeql\.yml[\s\S]*?candidate_commit: \$\{\{ inputs\.candidate_commit \}\}/u,
  );
  assert.deepEqual(workflowJobPermissions(codeqlCallerJob), {
    actions: 'read',
    contents: 'read',
    'security-events': 'write',
  });
  assert.match(
    releaseScanJob,
    /release-scans:[\s\S]*?needs: release-scans-codeql[\s\S]*?install-pinned-corretto-linux-x64\.sh[\s\S]*?coreJdk21/u,
  );
  assert.deepEqual(workflowJobPermissions(releaseScanJob), {
    actions: 'read',
    contents: 'read',
  });
  assert.match(
    releaseScanJob,
    /actions\/download-artifact@37930b1c2abaa49bbe596cd826c3c89aef350131/u,
  );
  const codeqlArtifactName = 'release-scans-codeql-${{ inputs.candidate_commit }}-'
    + '${{ github.run_id }}-${{ github.run_attempt }}';
  assert.equal((codeqlJob.match(new RegExp(codeqlArtifactName.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&'), 'gu')) ?? []).length, 1);
  assert.match(
    releaseScanJob,
    /name: release-scans-codeql-\$\{\{ inputs\.candidate_commit \}\}-\$\{\{ github\.run_id \}\}-\$\{\{ github\.run_attempt \}\}/u,
  );
  assert.doesNotMatch(releaseScanJob, /^\s+run-id:/mu);
  assert.match(releaseScanJob, /ref: \$\{\{ inputs\.candidate_commit \}\}/u);
  assert.match(codeqlJob, /ref: \$\{\{ inputs\.candidate_commit \|\| github\.sha \}\}/u);
  assert.match(codeqlJob, /prepare-codeql-release-report\.mjs[\s\S]*?inputs\.candidate_commit/u);
  assert.match(releaseScanJob, /produce-release-scans-linux-x64\.sh/u);
  assert.match(releaseScanJob, /release-scans-bundle\.json/u);
  assert.match(releaseScanJob, /retention-days: 90/u);
  const immutableBundleUpload = releaseScanJob.match(
    /      - name: Upload immutable release-scan bundle\n([\s\S]*?)(?=\n      - name:)/u,
  );
  assert.notEqual(immutableBundleUpload, null);
  assert.match(
    immutableBundleUpload[1],
    /^          name: release-scans-\$\{\{ inputs\.candidate_commit \}\}-\$\{\{ github\.run_id \}\}-\$\{\{ github\.run_attempt \}\}$/m,
  );
  assert.match(
    immutableBundleUpload[1],
    /^          path: \$\{\{ runner\.temp \}\}\/release-scans-bundle\.json$/m,
  );
  assertions += 17;

  const valid = fixture('valid');
  const result = prepareReleaseScanEvidence(valid);
  assert.equal(result.reports.length, 6);
  assertions++;

  const accepted = fixture('accepted-finding');
  const acceptedInput = configureFinding(accepted);
  prepareReleaseScanEvidence(accepted);
  const acceptedSummary = JSON.parse(
    readFileSync(join(accepted.evidenceRoot, 'release-scans.json'), 'utf8'),
  );
  assert.deepEqual(acceptedSummary.allowlist, [acceptedInput.approval]);
  assert.deepEqual(acceptedSummary.findings, [{
    accepted: true,
    commit: acceptedInput.finding.Commit,
    fingerprint: acceptedInput.approval.fingerprint,
    path: acceptedInput.finding.File,
    ruleId: acceptedInput.finding.RuleID,
    scanner: 'gitleaks',
    severity: 'UNSPECIFIED',
  }]);
  assertions += 2;

  const acceptedCodeql = fixture('accepted-codeql-finding');
  const acceptedCodeqlInput = configureCodeqlFinding(acceptedCodeql);
  prepareReleaseScanEvidence(acceptedCodeql);
  const acceptedCodeqlSummary = JSON.parse(
    readFileSync(join(acceptedCodeql.evidenceRoot, 'release-scans.json'), 'utf8'),
  );
  assert.deepEqual(acceptedCodeqlSummary.allowlist, [acceptedCodeqlInput.approval]);
  assert.deepEqual(acceptedCodeqlSummary.findings, [{
    accepted: true,
    commit: acceptedCodeqlInput.approval.commit,
    fingerprint: acceptedCodeqlInput.approval.fingerprint,
    path: acceptedCodeqlInput.approval.path,
    ruleId: acceptedCodeqlInput.approval.ruleId,
    scanner: 'codeql',
    severity: 'MEDIUM',
  }]);
  assertions += 2;

  const highCodeql = fixture('high-codeql-finding');
  configureCodeqlFinding(highCodeql, { securitySeverity: '8.1' });
  assert.throws(
    () => prepareReleaseScanEvidence(highCodeql),
    /codeql HIGH finding cannot be excepted/,
  );
  assertions++;

  const criticalCodeql = fixture('critical-codeql-finding');
  configureCodeqlFinding(criticalCodeql, { securitySeverity: '9.1' });
  assert.throws(
    () => prepareReleaseScanEvidence(criticalCodeql),
    /codeql CRITICAL finding cannot be excepted/,
  );
  assertions++;

  const wrongCodeqlRevision = fixture('wrong-codeql-revision');
  const wrongRevisionFinding = codeqlFinding();
  writeFileSync(
    join(wrongCodeqlRevision.rawReportsRoot, '00-codeql-java.sarif'),
    codeqlSarif({ commit: 'b'.repeat(40), findings: [wrongRevisionFinding] }),
  );
  writeFileSync(wrongCodeqlRevision.approvalsPath, canonicalJson({
    exceptions: [codeqlApprovalFor(wrongRevisionFinding)],
    formatVersion: 1,
  }));
  wrongCodeqlRevision.now = NOW;
  assert.throws(
    () => prepareReleaseScanEvidence(wrongCodeqlRevision),
    /does not bind the exact candidate commit/,
  );
  assertions++;

  const unmatched = fixture('unmatched-finding');
  configureFinding(unmatched, { approvals: [] });
  assert.throws(
    () => prepareReleaseScanEvidence(unmatched),
    /no exact unexpired exception/,
  );
  assertions++;

  const expired = fixture('expired-exception');
  const { approval: expiredApproval, finding: expiredFinding } = configureFinding(expired);
  writeFileSync(expired.approvalsPath, canonicalJson({
    exceptions: [{
      ...expiredApproval,
      approvedAt: '2026-07-01T00:00:00Z',
      expiresAt: '2026-07-30T00:00:00Z',
    }],
    formatVersion: 1,
  }));
  assert.equal(expiredFinding.File, expiredApproval.path);
  assert.throws(() => prepareReleaseScanEvidence(expired), /not currently effective and unexpired/);
  assertions += 2;

  const duplicate = fixture('duplicate-exception');
  const { approval: duplicateApproval } = configureFinding(duplicate);
  writeFileSync(duplicate.approvalsPath, canonicalJson({
    exceptions: [duplicateApproval, duplicateApproval],
    formatVersion: 1,
  }));
  assert.throws(() => prepareReleaseScanEvidence(duplicate), /duplicate exact exception/);
  assertions++;

  const wildcard = fixture('wildcard-exception');
  const { approval: wildcardApproval } = configureFinding(wildcard);
  writeFileSync(wildcard.approvalsPath, canonicalJson({
    exceptions: [{ ...wildcardApproval, path: 'src/**/example.properties' }],
    formatVersion: 1,
  }));
  assert.throws(() => prepareReleaseScanEvidence(wildcard), /must not contain a wildcard/);
  assertions++;

  const high = fixture('high-finding');
  configureFinding(high, { finding: gitleaksFinding({ severity: 'HIGH' }) });
  assert.throws(() => prepareReleaseScanEvidence(high), /HIGH finding cannot be excepted/);
  assertions++;

  const mismatchedReports = fixture('mismatched-reports');
  configureFinding(mismatchedReports);
  const mismatched = gitleaksFinding();
  mismatched.EndColumn++;
  writeFileSync(
    join(mismatchedReports.rawReportsRoot, '02-gitleaks.sarif'),
    gitleaksSarif(mismatched),
  );
  assert.throws(() => prepareReleaseScanEvidence(mismatchedReports), /do not describe the same/);
  assertions++;
  const summary = JSON.parse(readFileSync(join(valid.evidenceRoot, 'release-scans.json'), 'utf8'));
  assert.deepEqual(summary.candidate, valid.candidate);
  assert.deepEqual(summary.allowlist, []);
  assert.deepEqual(summary.findings, []);
  assert.equal(summary.reports.length, 6);
  assert.equal(summary.runtimeDependencySurface.externalRuntimeDependencyCount, 0);
  assertions += 5;
  const provenance = JSON.parse(readFileSync(
    join(valid.evidenceRoot, 'release-scans', '05-toolchain-provenance.json'),
    'utf8',
  ));
  assert.deepEqual(provenance.candidate, valid.candidate);
  assert.equal(provenance.producerWorkflowSha256, valid.candidate.producerWorkflowSha256);
  assertions += 2;

  assert.throws(() => prepareReleaseScanEvidence(valid), /already exists/);
  assertions++;

  const driftedFilter = fixture('drifted-filter');
  writeFileSync(join(driftedFilter.provenanceRoot, 'spotbugs-exclude.xml'), 'drift\n');
  assert.throws(
    () => prepareReleaseScanEvidence(driftedFilter),
    /SpotBugs exclusion filter SHA-256 mismatch/,
  );
  assertions++;

  const extraReport = fixture('extra-report');
  writeFileSync(join(extraReport.rawReportsRoot, 'unexpected.txt'), 'unexpected\n');
  assert.throws(
    () => prepareReleaseScanEvidence(extraReport),
    /files must be exactly/,
  );
  assertions++;

  const driftedTool = fixture('drifted-tool');
  writeFileSync(join(driftedTool.provenanceRoot, 'spotbugs.jar'), 'drift\n');
  assert.throws(
    () => prepareReleaseScanEvidence(driftedTool),
    /SpotBugs engine JAR SHA-256 mismatch/,
  );
  assertions++;

  assert.throws(
    () => prepareReleaseScanEvidence({ ...fixture('wrong-contract'), contract: { id: 'wrong' } }),
    ReleaseScanProducerError,
  );
  assertions++;

  console.log(`Release-scan producer self-test passed (${assertions} assertions).`);
} finally {
  rmSync(root, { recursive: true });
}
