import { cpSync, existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const optionNames = new Set(['mode', 'java-home', 'jar', 'sha256', 'output',
  'repository', 'maven', 'gradle']);

export function parseArguments(args) {
  const options = new Map();
  for (let index = 0; index < args.length; index += 2) {
    if (!args[index]?.startsWith('--') || !args[index + 1]
        || args[index + 1].startsWith('--'))
      throw new Error('Arguments must be --name value pairs');
    const name = args[index].slice(2);
    if (!optionNames.has(name) || options.has(name))
      throw new Error(`Unknown or duplicate option: --${name}`);
    options.set(name, args[index + 1]);
  }
  return options;
}

export function javaFeatureVersion(versionOutput) {
  const match = versionOutput.match(/(?:openjdk|java) version "([0-9]+)(?:[.\-+" ])/u);
  const feature = Number(match?.[1]);
  if (!Number.isSafeInteger(feature) || feature < 17)
    throw new Error('Consumer runtime must report Java 17 or newer');
  return feature;
}

export function materializeFixture(source, fixture, feature) {
  if (!Number.isSafeInteger(feature) || feature < 17)
    throw new Error('Consumer source selection requires Java 17 or newer');
  if (existsSync(fixture))
    throw new Error('Fixture directory must not already exist');
  mkdirSync(fixture, { recursive: true });
  for (const name of ['pom.xml', 'build.gradle', 'settings.gradle'])
    cpSync(join(source, name), join(fixture, name));
  const javaSources = join(fixture, 'src/main/java');
  cpSync(join(source, 'src/main/java'), javaSources, { recursive: true });
  if (feature >= 21)
    cpSync(join(source, 'src/sse/java'), javaSources, { recursive: true });
  return feature >= 21 ? 'HTTP_MCP_SSE' : 'HTTP_MCP';
}

export function requirePositiveOutput(output, feature) {
  const expected = `Consumer HTTP/MCP${feature >= 21 ? '/SSE' : ''}`
    + ` packaged routing passed on Java ${feature}`;
  if (output.split(/\r?\n/u).filter(line => line.startsWith('Consumer ')).join('\n') !== expected)
    throw new Error(`Packaged consumer did not report the exact routing success: ${output}`);
}

export function requireNegativeOutput(output, name) {
  const expected = `PASS\t${name}\tFRAMEWORK_DISCOVERY_FAILURE`;
  if (output.split(/\r?\n/u).filter(line => line.startsWith('PASS\t')).join('\n') !== expected)
    throw new Error(`Consumer did not report the exact framework discovery failure: ${output}`);
}

export function readMutationResult(output, mode) {
  const value = JSON.parse(output);
  if (JSON.stringify(Object.keys(value).sort())
      !== JSON.stringify(['mcpIndexSha256', 'mode', 'unchangedEntryCount'])
      || value.mode !== mode || !/^[0-9a-f]{64}$/u.test(value.mcpIndexSha256)
      || !Number.isSafeInteger(value.unchangedEntryCount) || value.unchangedEntryCount < 1)
    throw new Error('Consumer mutation did not prove the expected preserved entries');
  return value;
}
