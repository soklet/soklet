#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

const reviewedLockSha256 =
  '4bbf44df937f30f99f56dcb359ec5ca67c8200241b279f49f25d4b646e38fa1f';
const reviewedAdvisory = 'https://github.com/advisories/GHSA-g7r4-m6w7-qqqr';

function fail(message) {
  throw new Error(`Conformance dependency audit: ${message}`);
}

function requireCounts(report, expected, label) {
  if (report?.auditReportVersion !== 2
      || report.metadata?.vulnerabilities === undefined)
    fail(`${label} is not an npm audit v2 report`);
  const counts = report.metadata.vulnerabilities;
  for (const [severity, count] of Object.entries(expected)) {
    if (counts[severity] !== count)
      fail(`${label} ${severity} count changed: ${counts[severity]}`);
  }
}

export function verifyDependencyRepinAudit(lockBytes, runtimeAudit, fullAudit) {
  const lockSha256 = createHash('sha256').update(lockBytes).digest('hex');
  if (lockSha256 !== reviewedLockSha256)
    fail('lockfile differs from the reviewed repin');
  const lock = JSON.parse(lockBytes.toString('utf8'));
  if (lock.packages?.['node_modules/esbuild']?.version !== '0.27.4'
      || lock.packages['node_modules/esbuild'].dev !== true)
    fail('reviewed esbuild dependency is no longer development-only');

  requireCounts(runtimeAudit,
    { info: 0, low: 0, moderate: 0, high: 0, critical: 0, total: 0 },
    'runtime audit');
  if (Object.keys(runtimeAudit.vulnerabilities ?? {}).length !== 0)
    fail('runtime audit contains an affected package');

  requireCounts(fullAudit,
    { info: 0, low: 1, moderate: 0, high: 0, critical: 0, total: 1 },
    'full audit');
  if (Object.keys(fullAudit.vulnerabilities ?? {}).join(',') !== 'esbuild')
    fail('full audit differs from the single reviewed esbuild finding');
  const esbuild = fullAudit.vulnerabilities.esbuild;
  if (esbuild.name !== 'esbuild' || esbuild.severity !== 'low'
      || esbuild.isDirect !== false
      || esbuild.nodes?.length !== 1
      || esbuild.nodes[0] !== 'node_modules/esbuild'
      || esbuild.via?.length !== 1
      || esbuild.via[0]?.url !== reviewedAdvisory
      || esbuild.via[0]?.severity !== 'low')
    fail('reviewed esbuild finding changed');
  return { lockSha256, acceptedLowAdvisory: reviewedAdvisory };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  if (process.argv.length !== 5) {
    console.error('Usage: node verify-dependency-repin-audit.mjs <lock> <runtime-audit.json> <full-audit.json>');
    process.exit(64);
  }
  try {
    const [, , lockPath, runtimePath, fullPath] = process.argv;
    const result = verifyDependencyRepinAudit(
      readFileSync(lockPath),
      JSON.parse(readFileSync(runtimePath, 'utf8')),
      JSON.parse(readFileSync(fullPath, 'utf8')),
    );
    console.log(`Verified conformance repin audit for ${result.lockSha256}; only ${result.acceptedLowAdvisory} remains in development dependencies`);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
