#!/usr/bin/env node

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { verifyManifestSet } from './verify.mjs';

const statuses = new Set(['SUCCESS', 'FAILURE', 'WARNING', 'SKIPPED', 'INFO']);

export function adjudicateChecks(scenarioName, checks, profile) {
  if (!Array.isArray(checks))
    throw new Error(`Official checks for ${scenarioName} must be an array`);
  if (profile.scenario !== scenarioName)
    throw new Error(`Expected profile ${profile.id} belongs to ${profile.scenario}`);

  const actual = new Map();
  for (const [index, check] of checks.entries()) {
    if (check === null || typeof check !== 'object' || typeof check.id !== 'string'
        || check.id.length === 0 || !statuses.has(check.status))
      throw new Error(`Malformed official check ${index + 1} for ${scenarioName}`);
    if (check.status === 'FAILURE' || check.status === 'WARNING')
      throw new Error(`${scenarioName} emitted forbidden ${check.status} check ${check.id}`);
    if (check.id === 'wire-schema-harness-error')
      throw new Error(`${scenarioName} emitted wire-schema-harness-error`);
    if (check.id === 'wire-schema-valid') {
      if (check.status !== 'SUCCESS' || check.details === null
          || typeof check.details !== 'object'
          || !Number.isInteger(check.details.messagesValidated)
          || check.details.messagesValidated < 1) {
        throw new Error(`${scenarioName} emitted an invalid wire-schema-valid result`);
      }
    }
    increment(actual, tuple(check.id, check.status), 1);
  }

  const expected = new Map();
  for (const check of profile.checks)
    increment(expected, tuple(check.id, check.status), check.count);
  const expectedWireSuccesses = profile.automaticWireChecks['wire-schema-valid'];
  if (expectedWireSuccesses > 0)
    increment(expected, tuple('wire-schema-valid', 'SUCCESS'), expectedWireSuccesses);

  const actualObject = canonicalMultiset(actual);
  const expectedObject = canonicalMultiset(expected);
  if (JSON.stringify(actualObject) !== JSON.stringify(expectedObject)) {
    throw new Error(
      `${scenarioName} check multiset mismatch\nexpected: ${JSON.stringify(expectedObject)}`
        + `\nactual:   ${JSON.stringify(actualObject)}`,
    );
  }
  return actualObject;
}

function tuple(id, status) {
  return `${id}\u0000${status}`;
}

function increment(multiset, key, count) {
  multiset.set(key, (multiset.get(key) ?? 0) + count);
}

function canonicalMultiset(multiset) {
  return [...multiset.entries()]
    .sort(([left], [right]) => Buffer.compare(Buffer.from(left), Buffer.from(right)))
    .map(([key, count]) => {
      const [id, status] = key.split('\u0000');
      return { id, status, count };
    });
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  if (args.length !== 4 || args[0] !== '--scenario' || args[2] !== '--checks') {
    console.error(
      'Usage: node conformance/official/adjudicate.mjs '
        + '--scenario <exact-name> --checks <checks.json>',
    );
    process.exit(64);
  }
  const [, scenarioName, , checksPath] = args;
  const { selection, expectedChecks } = verifyManifestSet(
    resolve(dirname(fileURLToPath(import.meta.url))),
  );
  const scenario = selection.scenarios.find((candidate) => candidate.name === scenarioName);
  if (scenario === undefined || scenario.expectedCheckProfile === null)
    throw new Error(`Scenario ${scenarioName} has no frozen expected-check profile`);
  const profile = expectedChecks.profiles.find(
    (candidate) => candidate.id === scenario.expectedCheckProfile,
  );
  const checks = JSON.parse(readFileSync(resolve(checksPath), 'utf8'));
  const multiset = adjudicateChecks(scenarioName, checks, profile);
  console.log(`Adjudicated ${scenarioName}: ${JSON.stringify(multiset)}`);
}
