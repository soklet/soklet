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
  ReleaseScanProducerError,
} from './produce-release-scans.mjs';

const root = realpathSync(mkdtempSync(join(tmpdir(), 'soklet-release-scans-producer-')));
const projectRoot = resolve(fileURLToPath(new URL('..', import.meta.url)));
let assertions = 0;

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
  const provenanceRoot = join(fixtureRoot, 'provenance');
  const rawReportsRoot = join(fixtureRoot, 'raw');
  mkdirSync(configRoot, { recursive: true });
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

  write(rawReportsRoot, '00-codeql-java.sarif', emptySarif('CodeQL'));
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
  const pom = readFileSync(join(projectRoot, 'pom.xml'), 'utf8');
  assert.match(
    pom,
    /<soklet\.spotbugs\.excludeFilterFile>\$\{project\.basedir\}\/config\/spotbugs-exclude\.xml<\/soklet\.spotbugs\.excludeFilterFile>/u,
  );
  assert.match(
    pom,
    /<excludeFilterFile>\$\{soklet\.spotbugs\.excludeFilterFile\}<\/excludeFilterFile>/u,
  );
  assertions += 18;

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
  assertions += 14;

  const valid = fixture('valid');
  const result = prepareReleaseScanEvidence(valid);
  assert.equal(result.reports.length, 6);
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
