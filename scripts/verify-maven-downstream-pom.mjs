#!/usr/bin/env node

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const MAVEN_VERSION_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._+-]*$/;
const STABLE_MAVEN_VERSION_PATTERN = /^[0-9]+(?:\.[0-9]+){2}(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?$/;

function fail(message) {
  throw new Error(message);
}

function localName(name) {
  return name.includes(':') ? name.slice(name.lastIndexOf(':') + 1) : name;
}

function parseXml(text, description) {
  if (/<!DOCTYPE\b|<!ENTITY\b/i.test(text))
    fail(`${description} must not contain a document type or entity declaration`);

  const document = { children: [], name: '#document', text: '' };
  const stack = [document];
  const tokens = /<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<!\[CDATA\[[\s\S]*?\]\]>|<\/[A-Za-z_][A-Za-z0-9_.:-]*\s*>|<[A-Za-z_][^<>]*?>/g;
  let cursor = 0;
  let match;

  while ((match = tokens.exec(text)) !== null) {
    stack.at(-1).text += text.slice(cursor, match.index);
    const token = match[0];
    cursor = tokens.lastIndex;

    if (token.startsWith('<!--') || token.startsWith('<?'))
      continue;
    if (token.startsWith('<![CDATA[')) {
      stack.at(-1).text += token.slice(9, -3);
      continue;
    }
    if (token.startsWith('</')) {
      const closingName = token.slice(2, -1).trim();
      if (stack.length === 1 || stack.at(-1).qualifiedName !== closingName)
        fail(`${description} has mismatched XML element ${closingName}`);
      stack.pop();
      continue;
    }

    const opening = token.match(/^<([A-Za-z_][A-Za-z0-9_.:-]*)\b/);
    if (opening === null)
      fail(`${description} contains an unsupported XML construct`);
    const node = {
      children: [],
      name: localName(opening[1]),
      qualifiedName: opening[1],
      text: '',
    };
    stack.at(-1).children.push(node);
    if (!token.endsWith('/>'))
      stack.push(node);
  }

  stack.at(-1).text += text.slice(cursor);
  if (stack.length !== 1)
    fail(`${description} has unclosed XML elements`);
  if (document.text.trim() !== '' || document.children.length !== 1)
    fail(`${description} must contain exactly one XML document element`);
  return document.children[0];
}

function children(node, name) {
  return node.children.filter((child) => child.name === name);
}

function onlyChild(node, name, description) {
  const matches = children(node, name);
  if (matches.length !== 1)
    fail(`${description} must declare exactly one ${name}`);
  return matches[0];
}

function leafText(node, description) {
  if (node.children.length !== 0)
    fail(`${description} must contain plain text only`);
  const value = node.text.trim();
  if (value === '')
    fail(`${description} must not be empty`);
  return value;
}

function coordinate(node, name, description) {
  return leafText(onlyChild(node, name, description), `${description} ${name}`);
}

export function verifyMavenDownstreamPom(
  pomPath,
  expectedArtifactIdentity,
  expectedVersionProperty = 'soklet.version',
  expectedDefaultArtifactIdentity = null,
) {
  const absolutePomPath = resolve(pomPath);
  const bytes = readFileSync(absolutePomPath);
  const text = bytes.toString('utf8');
  if (Buffer.from(text, 'utf8').compare(bytes) !== 0)
    fail(`Downstream POM is not UTF-8: ${absolutePomPath}`);

  const expectedCoordinates = expectedArtifactIdentity.split(':');
  if (expectedCoordinates.length !== 3 || expectedCoordinates.some((value) => value === ''))
    fail(`Downstream artifact identity must be groupId:artifactId:version: ${expectedArtifactIdentity}`);
  const [expectedGroupId, expectedArtifactId, expectedVersion] = expectedCoordinates;

  const project = parseXml(text, `Downstream POM ${absolutePomPath}`);
  if (project.name !== 'project')
    fail(`Downstream POM root must be project: ${absolutePomPath}`);
  const actualIdentity = [
    coordinate(project, 'groupId', 'Downstream project'),
    coordinate(project, 'artifactId', 'Downstream project'),
    coordinate(project, 'version', 'Downstream project'),
  ].join(':');
  if (actualIdentity !== expectedArtifactIdentity) {
    fail(`Downstream project identity is ${actualIdentity}; expected ${expectedArtifactIdentity}`);
  }

  const properties = onlyChild(project, 'properties', 'Downstream project');
  const versionPropertyNode = onlyChild(
    properties,
    expectedVersionProperty,
    'Downstream project properties',
  );
  const defaultSokletVersion = leafText(
    versionPropertyNode,
    `Downstream ${expectedVersionProperty}`,
  );
  if (!MAVEN_VERSION_PATTERN.test(defaultSokletVersion)) {
    fail(`Downstream ${expectedVersionProperty} must be a concrete Maven version`);
  }

  if (expectedDefaultArtifactIdentity !== null) {
    const defaultCoordinates = expectedDefaultArtifactIdentity.split(':');
    if (defaultCoordinates.length !== 3
        || defaultCoordinates[0] !== 'com.soklet'
        || defaultCoordinates[1] !== 'soklet'
        || defaultCoordinates[2] === '') {
      fail(`Default artifact identity must be com.soklet:soklet:<version>: ${expectedDefaultArtifactIdentity}`);
    }
    const expectedDefaultVersion = defaultCoordinates[2];
    if (!STABLE_MAVEN_VERSION_PATTERN.test(expectedDefaultVersion)
        || /(?:^|[-.])(SNAPSHOT|LATEST|RELEASE)(?:$|[-.])/i.test(expectedDefaultVersion)
        || defaultSokletVersion !== expectedDefaultVersion) {
      fail(
        `Downstream ${expectedVersionProperty} is ${defaultSokletVersion}; expected exact stable version ${expectedDefaultVersion}`,
      );
    }
  }

  const dependencies = onlyChild(project, 'dependencies', 'Downstream project');
  const sokletDependencies = children(dependencies, 'dependency').filter((dependency) => {
    const groupIds = children(dependency, 'groupId');
    const artifactIds = children(dependency, 'artifactId');
    return groupIds.length === 1 && artifactIds.length === 1
      && leafText(groupIds[0], 'Dependency groupId') === 'com.soklet'
      && leafText(artifactIds[0], 'Dependency artifactId') === 'soklet';
  });
  if (sokletDependencies.length !== 1)
    fail('Downstream project must declare exactly one direct com.soklet:soklet dependency');
  const dependencyVersion = coordinate(
    sokletDependencies[0],
    'version',
    'Downstream com.soklet:soklet dependency',
  );
  const expectedDependencyVersion = `\${${expectedVersionProperty}}`;
  if (dependencyVersion !== expectedDependencyVersion) {
    fail(`Downstream com.soklet:soklet dependency version is ${dependencyVersion}; expected ${expectedDependencyVersion}`);
  }

  return Object.freeze({
    artifactId: expectedArtifactId,
    defaultArtifactIdentity: expectedDefaultArtifactIdentity,
    defaultSokletVersion,
    groupId: expectedGroupId,
    version: expectedVersion,
  });
}

function main(args) {
  if (args.length !== 4) {
    console.error('Usage: node scripts/verify-maven-downstream-pom.mjs <pom> <artifact-identity> <version-property> <default-artifact-identity-or-empty>');
    process.exitCode = 64;
    return;
  }
  const result = verifyMavenDownstreamPom(
    args[0],
    args[1],
    args[2],
    args[3] === '' ? null : args[3],
  );
  console.log(result.defaultSokletVersion);
}

if (process.argv[1] !== undefined
    && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}
