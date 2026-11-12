#!/usr/bin/env node

import { spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { constants, copyFileSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, delimiter, dirname, join, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { verifySokletCoreJar, verifySurefireReports } from './verify-surefire-reports.mjs';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const siblingProjects = Object.freeze(['soklet-servlet-javax', 'soklet-servlet-jakarta', 'soklet-otel', 'toystore-app', 'barebones-app']);
const excludedDirectories = new Set(['.git', 'target', 'node_modules', 'dist']);

function execute(command, args, options = {}) {
  const runner = join(projectRoot, 'verification/interoperability/run-command.mjs');
  const result = spawnSync(process.execPath, [runner, '900', options.cwd ?? projectRoot, command, ...args], { stdio: 'inherit', ...options });
  if (result.error || result.status !== 0)
    throw new Error(`${command} failed (${result.error?.message ?? result.status ?? result.signal})`);
}

function javaEnvironment(home, major) {
  const env = { ...process.env };
  const selectedHome = home ?? env.JAVA_HOME;
  if (selectedHome) {
    env.JAVA_HOME = resolve(selectedHome);
    env.PATH = `${join(env.JAVA_HOME, 'bin')}${delimiter}${env.PATH ?? ''}`;
  }
  const result = spawnSync(selectedHome ? join(resolve(selectedHome), 'bin/java') : 'java', ['-version'], { env, encoding: 'utf8', timeout: 10_000 });
  if (result.error || result.status !== 0 || !new RegExp(`version "${major}(?:[."]|$)`).test(`${result.stdout}${result.stderr}`))
    throw new Error(`JDK ${major} is required; set SOKLET_JDK${major}_HOME or --jdk${major}-home.`);
  return env;
}

function sourcesBelow(root) {
  return readdirSync(root, { withFileTypes: true }).flatMap(entry => {
    const path = join(root, entry.name);
    return entry.isDirectory() ? sourcesBelow(path) : entry.isFile() && entry.name.endsWith('.java') ? [path] : [];
  }).sort();
}

async function barebonesSmoke(checkout, candidateJar, env, run) {
  const classes = join(checkout, 'validation-classes');
  mkdirSync(classes);
  const sources = sourcesBelow(join(checkout, 'src'));
  if (!sources.length)
    throw new Error('barebones-app has no Java sources');
  run('javac', ['--release', '21', '-parameters', '-processor', 'com.soklet.SokletProcessor', '-classpath', candidateJar, '-d', classes, ...sources], { cwd: checkout, env });
  const launcher = join(checkout, 'BarebonesSmoke.java');
  writeFileSync(launcher, `import com.soklet.*;
import com.soklet.barebones.App;
import java.net.*;
import java.util.Set;
public class BarebonesSmoke {
  public static void main(String[] args) throws Exception {
    int port;
    try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
    var config = SokletConfig.withHttpServer(HttpServer.withPort(port).host("127.0.0.1").build())
        .resourceMethodResolver(ResourceMethodResolver.fromClasses(Set.of(App.class)))
        .jvmShutdownHookEnabled(false).build();
    Soklet.run(config, () -> {
      System.out.println("SOKLET_BAREBONES_READY " + port);
      try { System.in.read(); } catch (java.io.IOException e) { throw new RuntimeException(e); }
    });
  }
}
`);
  const classpath = `${candidateJar}${delimiter}${classes}`;
  run('javac', ['--release', '21', '-proc:none', '-classpath', classpath, '-d', classes, launcher], { cwd: checkout, env });
  const child = spawn('java', ['-classpath', classpath, 'BarebonesSmoke'], { cwd: checkout, env, stdio: ['pipe', 'pipe', 'pipe'] });
  let stderr = '';
  child.stderr.setEncoding('utf8').on('data', data => { stderr = `${stderr}${data}`.slice(-64 * 1024); });
  const exit = new Promise((resolveExit, rejectExit) => {
    child.once('error', rejectExit);
    child.once('close', (code, signal) => resolveExit({ code, signal }));
  });
  const reader = createInterface({ input: child.stdout });
  let timer;
  try {
    const port = await Promise.race([
      new Promise(resolvePort => reader.on('line', line => {
        const match = /^SOKLET_BAREBONES_READY ([0-9]+)$/.exec(line);
        if (match) resolvePort(Number(match[1]));
      })),
      exit.then(result => { throw new Error(`barebones-app exited before readiness (${result.code ?? result.signal}): ${stderr}`); }),
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`barebones-app startup timed out: ${stderr}`)), 30_000); }),
    ]);
    clearTimeout(timer);
    for (const [path, expected] of [['/', 'Hello, world!'], ['/test-input?input=123', '{"input": 123}']]) {
      const response = await fetch(`http://127.0.0.1:${port}${path}`, { signal: AbortSignal.timeout(5000) });
      if (response.status !== 200 || await response.text() !== expected)
        throw new Error(`barebones-app returned an unexpected response for ${path}`);
    }
    child.stdin.end();
    const result = await Promise.race([exit, new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('barebones-app shutdown timed out')), 15_000); })]);
    if (result.code !== 0 || result.signal)
      throw new Error(`barebones-app failed (${result.code ?? result.signal}): ${stderr}`);
    console.log('barebones-app: compiled with the selected JAR; HTTP and shutdown checks passed.');
  } finally {
    clearTimeout(timer);
    reader.close();
    if (child.exitCode === null && child.signalCode === null) {
      child.kill('SIGTERM');
      const killTimer = setTimeout(() => child.kill('SIGKILL'), 2000);
      await exit.catch(() => {});
      clearTimeout(killTimer);
    }
  }
}

export async function verifySiblingProjects({ candidateJar, workspace = resolve(projectRoot, '..'), projects = siblingProjects, jdk21Home = process.env.SOKLET_JDK21_HOME, jdk25Home = process.env.SOKLET_JDK25_HOME, mavenCache, offline = false }, { run = execute, environmentForJava = javaEnvironment, smoke = barebonesSmoke } = {}) {
  const selectedJar = realpathSync(resolve(candidateJar));
  const selectedHash = createHash('sha256').update(readFileSync(selectedJar)).digest('hex');
  verifySokletCoreJar(selectedJar, selectedHash);
  if (!projects.length || new Set(projects).size !== projects.length || projects.some(name => !siblingProjects.includes(name)))
    throw new Error(`Choose distinct projects from: ${siblingProjects.join(', ')}`);
  const selectedWorkspace = resolve(workspace);
  for (const name of projects)
    if (!existsSync(join(selectedWorkspace, name, name === 'barebones-app' ? 'src' : 'pom.xml')))
      throw new Error(`Missing sibling project: ${join(selectedWorkspace, name)}`);
  const env21 = projects.some(name => name !== 'toystore-app') ? environmentForJava(jdk21Home, 21) : undefined;
  const env25 = projects.includes('toystore-app') ? environmentForJava(jdk25Home, 25) : undefined;
  const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'soklet-siblings-')));
  try {
    const stagedJar = join(scratch, 'selected-soklet.jar');
    copyFileSync(selectedJar, stagedJar);
    const repository = join(scratch, 'repository');
    if (mavenCache) cpSync(resolve(mavenCache), repository, { recursive: true, mode: constants.COPYFILE_FICLONE });
    else mkdirSync(repository);
    const version = `4.0.0-validation-${selectedHash.slice(0, 12)}`;
    const maven = process.env.SOKLET_VALIDATION_MAVEN ?? 'mvn';
    const base = ['-B', '-ntp', ...(offline ? ['-o'] : []), `-Dmaven.repo.local=${repository}`, '-Dgpg.skip=true'];
    const installedJar = join(repository, 'com/soklet/soklet', version, `soklet-${version}.jar`);
    if (projects.some(name => name !== 'barebones-app')) {
      run(maven, [...base, 'org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file', `-Dfile=${stagedJar}`, '-DgroupId=com.soklet', '-DartifactId=soklet', `-Dversion=${version}`, '-Dpackaging=jar', '-DgeneratePom=true'], { cwd: scratch, env: env21 ?? env25 });
      verifySokletCoreJar(installedJar, selectedHash);
    }
    for (const name of projects) {
      const source = join(selectedWorkspace, name);
      const checkout = join(scratch, name);
      cpSync(source, checkout, { recursive: true, mode: constants.COPYFILE_FICLONE, filter: path => path === source || !excludedDirectories.has(basename(path)) });
      if (name === 'barebones-app') {
        await smoke(checkout, stagedJar, env21, run);
      } else {
        run(maven, [...base, `-Dsoklet.version=${version}`, '-DskipTests=false', '-Dmaven.test.skip=false', '-Dmaven.javadoc.skip=true', '-DfailIfNoTests=true', 'clean', 'verify'], { cwd: checkout, env: name === 'toystore-app' ? env25 : env21 });
        const result = verifySurefireReports(join(checkout, 'target/surefire-reports'), name, 'candidate', installedJar, selectedHash);
        verifySokletCoreJar(installedJar, selectedHash);
        console.log(`${name}: ${result.tests} tests (${result.skipped} skipped) used the selected JAR.`);
      }
    }
  } finally {
    rmSync(scratch, { recursive: true, force: true });
  }
}

export function parseSiblingArguments(args) {
  if (!args.length || args[0].startsWith('--'))
    throw new Error('Usage: node scripts/verify-sibling-projects.mjs <built-jar> [--workspace DIR] [--project NAME] [--jdk21-home DIR] [--jdk25-home DIR] [--maven-cache DIR] [--offline]');
  const options = { candidateJar: args[0] };
  const projects = [];
  const names = { '--workspace': 'workspace', '--jdk21-home': 'jdk21Home', '--jdk25-home': 'jdk25Home', '--maven-cache': 'mavenCache' };
  for (let index = 1; index < args.length; index++) {
    const flag = args[index];
    if (flag === '--offline') options.offline = true;
    else if (flag === '--project' || Object.hasOwn(names, flag)) {
      const value = args[++index];
      if (!value || value.startsWith('--')) throw new Error(`Missing value for ${flag}`);
      if (flag === '--project') projects.push(value);
      else options[names[flag]] = value;
    } else throw new Error(`Unknown option: ${flag}`);
  }
  if (projects.length) options.projects = projects;
  return options;
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
  try { await verifySiblingProjects(parseSiblingArguments(process.argv.slice(2))); }
  catch (error) { console.error(error instanceof Error ? error.message : String(error)); process.exitCode = 1; }
}
