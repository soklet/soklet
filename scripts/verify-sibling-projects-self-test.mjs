#!/usr/bin/env node

import assert from 'node:assert/strict';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { createDeterministicZip } from './release-promotion.mjs';
import { parseSiblingArguments, verifySiblingProjects } from './verify-sibling-projects.mjs';

const scratch = mkdtempSync(join(tmpdir(), 'soklet-sibling-self-test-'));
try {
  const candidateJar = join(scratch, 'built.jar');
  writeFileSync(candidateJar, createDeterministicZip([
    { path: 'META-INF/MANIFEST.MF', bytes: Buffer.from('Manifest-Version: 1.0\n\n') },
    { path: 'com/soklet/Soklet.class', bytes: Buffer.from('fixture') },
  ]));
  const projects = ['soklet-servlet-javax', 'toystore-app', 'barebones-app'];
  for (const project of projects) {
    mkdirSync(join(scratch, project, 'src'), { recursive: true });
    writeFileSync(join(scratch, project, 'pom.xml'), '<project/>');
    writeFileSync(join(scratch, project, 'src/current.txt'), 'local edits');
    mkdirSync(join(scratch, project, 'target'));
    writeFileSync(join(scratch, project, 'target/stale.txt'), 'must not copy');
  }
  const options = { candidateJar, workspace: scratch, projects, jdk21Home: 'jdk21', jdk25Home: 'jdk25', offline: true };
  let installedJar;
  const calls = [];
  const environmentForJava = (home, major) => ({ fixtureJdk: String(major), fixtureHome: home });
  function run(command, args, { cwd, env }) {
    calls.push({ command, args, cwd, env });
    const property = name => args.find(value => value.startsWith(`-D${name}=`))?.slice(name.length + 3);
    const repository = property('maven.repo.local');
    if (args.some(value => value.endsWith(':install-file'))) {
      const version = property('version');
      assert.match(version, /^4\.0\.0-validation-[a-f0-9]{12}$/);
      installedJar = join(repository, 'com/soklet/soklet', version, `soklet-${version}.jar`);
      mkdirSync(dirname(installedJar), { recursive: true });
      copyFileSync(property('file'), installedJar);
    } else {
      assert.equal(existsSync(join(cwd, 'target/stale.txt')), false);
      assert.equal(readFileSync(join(cwd, 'src/current.txt'), 'utf8'), 'local edits');
      assert.ok(args.includes('-DskipTests=false') && args.includes('-Dmaven.test.skip=false'));
      assert.ok(args.some(value => value.startsWith('-Dsoklet.version=4.0.0-validation-')));
      const reports = join(cwd, 'target/surefire-reports');
      mkdirSync(reports, { recursive: true });
      writeFileSync(join(reports, 'TEST-Fixture.xml'), `<testsuite tests="1" errors="0" failures="0" skipped="0"><properties><property name="java.class.path" value="${installedJar}"/></properties></testsuite>`);
    }
  }
  let smokeCalls = 0;
  const smoke = async (checkout, jar, env) => {
    smokeCalls++;
    assert.equal(env.fixtureJdk, '21');
    assert.deepEqual(readFileSync(jar), readFileSync(candidateJar));
    assert.equal(existsSync(join(checkout, '.git')), false);
    assert.equal(existsSync(join(checkout, 'target')), false);
  };
  await verifySiblingProjects(options, { run, environmentForJava, smoke });
  assert.equal(calls.length, 3);
  assert.ok(calls.every(call => call.args.includes('-o')));
  assert.equal(calls[1].env.fixtureJdk, '21');
  assert.equal(calls[2].env.fixtureJdk, '25');
  assert.equal(smokeCalls, 1);
  assert.equal(existsSync(calls[0].cwd), false, 'temporary workspace must be removed');
  assert.equal(readFileSync(join(scratch, projects[0], 'target/stale.txt'), 'utf8'), 'must not copy');

  let attempted = 0;
  await assert.rejects(verifySiblingProjects({ ...options, projects: [projects[0]] }, {
    environmentForJava, smoke,
    run: (...args) => { attempted++; if (attempted === 2) throw new Error('actual build failed'); run(...args); },
  }), /actual build failed/);
  assert.equal(attempted, 2);
  assert.equal(existsSync(calls.at(-1).cwd), false);

  await assert.rejects(verifySiblingProjects({ ...options, projects: [projects[0]] }, {
    environmentForJava, smoke,
    run: (command, args, context) => {
      run(command, args, context);
      if (args.includes('verify')) {
        const report = join(context.cwd, 'target/surefire-reports/TEST-Fixture.xml');
        writeFileSync(report, readFileSync(report, 'utf8').replace(installedJar, candidateJar));
      }
    },
  }), /classpath must contain the expected Soklet core JAR/);

  await assert.rejects(verifySiblingProjects({ ...options, projects: ['missing-project'] }, { run, environmentForJava, smoke }), /Choose distinct projects/);
  assert.deepEqual(parseSiblingArguments(['built.jar', '--workspace', '/tmp/work', '--project', 'toystore-app', '--offline']), {
    candidateJar: 'built.jar', workspace: '/tmp/work', projects: ['toystore-app'], offline: true,
  });
  assert.throws(() => parseSiblingArguments(['built.jar', '--project']), /Missing value/);
  assert.throws(() => parseSiblingArguments(['built.jar', '--unknown']), /Unknown option/);
  console.log('Sibling runner self-test passed: isolated selected-JAR resolution, JDK selection, source snapshots, failures, cleanup, and arguments.');
} finally { rmSync(scratch, { recursive: true, force: true }); }
