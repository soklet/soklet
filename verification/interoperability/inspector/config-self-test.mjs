import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  createEnvironment,
  createSessionConfig,
  inspectorArguments,
  sanitizedSessionConfig,
} from './config.mjs';

const url = 'http://127.0.0.1:48321/mcp';
const token = 'disposable-ABCD_123';
const isolationRoot = '/private/tmp/soklet-inspector-isolation';
const javaExecutable = '/opt/jdk/bin/java';
const entryPath = '/private/tmp/inspector/node_modules/@modelcontextprotocol/inspector/clients/launcher/build/index.js';
const configPath = '/private/tmp/soklet-inspector-isolation/session.json';

test('session config pins modern loopback HTTP and exact requested extensions', () => {
  const enabled = createSessionConfig(url, token, true);
  assert.deepEqual(enabled, {
    mcpServers: {
      soklet: {
        type: 'http',
        url,
        protocolEra: 'modern',
        headers: { Authorization: `Bearer ${token}` },
        advertisedExtensions: {
          'io.modelcontextprotocol/ui': true,
          'io.modelcontextprotocol/skills': true,
        },
      },
    },
  });
  const disabled = createSessionConfig(url, token,
    { apps: false, skills: false });
  assert.deepEqual(disabled.mcpServers.soklet.advertisedExtensions, {
    'io.modelcontextprotocol/ui': false,
    'io.modelcontextprotocol/skills': false,
  });
  assert.deepEqual(createSessionConfig(url, token,
    { apps: true, skills: false }).mcpServers.soklet.advertisedExtensions, {
    'io.modelcontextprotocol/ui': true,
    'io.modelcontextprotocol/skills': false,
  });
});

test('session config rejects noncanonical or non-loopback fixture URLs', () => {
  for (const bad of [
    'http://localhost:48321/mcp',
    'http://[::1]:48321/mcp',
    'http://192.0.2.1:48321/mcp',
    'https://127.0.0.1:48321/mcp',
    'http://user@127.0.0.1:48321/mcp',
    'http://127.0.0.1:48321/mcp?token=abc',
    'http://127.0.0.1:48321/mcp#fragment',
    'http://127.0.0.1:48321/',
    'http://127.0.0.1:48321/mcp/',
    'http://127.0.0.1/mcp',
    'http://127.0.0.1:0/mcp',
    'http://127.0.0.1:65536/mcp',
    'http://127.0.0.1:048321/mcp',
    'http://127.0.0.1:48321/%6dcp',
    `${url}\n`,
    `${url}\r`,
    `${url}\0`,
    null,
  ])
    assert.throws(() => createSessionConfig(bad, token, true),
      { message: /Invalid Inspector fixture URL|exact loopback HTTP/ });
});

test('session config rejects unsafe bearer and extension inputs', () => {
  for (const bad of ['', 'has space', 'abc\r\nInjected: yes',
    'abc\0def', null, 'x'.repeat(4097)])
    assert.throws(() => createSessionConfig(url, bad, true),
      { message: /Invalid disposable Inspector bearer token/ });
  for (const bad of [null, undefined, 1, {}, { apps: true },
    { apps: true, skills: 'false' },
    { apps: true, skills: false, extra: true }])
    assert.throws(() => createSessionConfig(url, token, bad),
      { message: /Invalid requested extension mode/ });
});

test('persisted session representation contains neither token nor live port', () => {
  const config = createSessionConfig(url, token,
    { apps: false, skills: true });
  config.mcpServers.soklet.unrelatedSecret = 'must-not-escape';
  const sanitized = sanitizedSessionConfig(config);
  assert.deepEqual(sanitized, {
    mcpServers: {
      soklet: {
        type: 'http',
        url: 'http://127.0.0.1:<LOOPBACK_PORT>/mcp',
        protocolEra: 'modern',
        headers: { Authorization: 'Bearer <REDACTED>' },
        advertisedExtensions: {
          'io.modelcontextprotocol/ui': false,
          'io.modelcontextprotocol/skills': true,
        },
      },
    },
  });
  const serialized = JSON.stringify(sanitized);
  assert.equal(serialized.includes(token), false);
  assert.equal(serialized.includes('48321'), false);
  assert.equal(serialized.includes('must-not-escape'), false);
  assert.throws(() => sanitizedSessionConfig({}),
    { message: /Invalid Inspector session config/ });
});

test('child environment uses only isolated state and curated executable paths', () => {
  const env = createEnvironment(isolationRoot, javaExecutable);
  assert.deepEqual(Object.keys(env).sort(), [
    'HOME', 'MCP_AUTO_OPEN_ENABLED', 'MCP_CLIENT_CONFIG_PATH',
    'MCP_INSPECTOR_OAUTH_STATE_PATH', 'MCP_INSPECTOR_SECRET_FILE',
    'MCP_INSPECTOR_SECRET_STORE', 'MCP_STORAGE_DIR', 'NO_COLOR',
    'PATH', 'TMPDIR', 'XDG_CACHE_HOME', 'XDG_CONFIG_HOME',
  ].sort());
  assert.equal(env.HOME, `${isolationRoot}/home`);
  assert.equal(env.MCP_STORAGE_DIR, `${isolationRoot}/storage`);
  assert.equal(env.MCP_INSPECTOR_OAUTH_STATE_PATH,
    `${isolationRoot}/storage/oauth.json`);
  assert.equal(env.MCP_CLIENT_CONFIG_PATH,
    `${isolationRoot}/storage/client.json`);
  assert.equal(env.MCP_INSPECTOR_SECRET_FILE,
    `${isolationRoot}/storage/secrets.json`);
  assert.equal(env.MCP_INSPECTOR_SECRET_STORE, 'memory');
  assert.equal(env.MCP_AUTO_OPEN_ENABLED, 'false');
  assert.equal(env.TMPDIR, `${isolationRoot}/tmp`);
  assert.ok(env.PATH.split(':').includes('/opt/jdk/bin'));
  assert.ok(env.PATH.split(':').includes('/usr/bin'));
  for (const key of [
    'MCP_CATALOG_PATH', 'MCP_INSPECTOR_API_TOKEN',
    'MCP_INSPECTOR_SECRET_KEY', 'MCP_AUTO_OPEN_ENABLED_OVERRIDE',
    'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'NPM_TOKEN',
    'NPM_CONFIG_USERCONFIG', 'NODE_OPTIONS', 'DEBUG',
    'DANGEROUSLY_OMIT_AUTH', 'AWS_SECRET_ACCESS_KEY',
  ])
    assert.equal(Object.hasOwn(env, key), false, key);
  assert.throws(() => createEnvironment('/', javaExecutable));
  assert.throws(() => createEnvironment('relative', javaExecutable));
  assert.throws(() => createEnvironment(isolationRoot,
    '/opt/jdk/bin/not-java'));
});

test('launcher arguments use only read-only config, fixed methods, and no shell', () => {
  assert.deepEqual(inspectorArguments(entryPath, configPath, 'tools/list'), [
    entryPath, '--cli', '--config', configPath, '--server', 'soklet',
    '--method', 'tools/list', '--format', 'json', '--stored-auth-only',
  ]);
  assert.deepEqual(inspectorArguments(entryPath, configPath, 'tools/call'), [
    entryPath, '--cli', '--config', configPath, '--server', 'soklet',
    '--method', 'tools/call', '--tool-name', 'test_simple_text',
    '--format', 'json', '--stored-auth-only',
  ]);
  for (const bad of ['initialize', 'skills/list', 'tools/list --relogin',
    null])
    assert.throws(() => inspectorArguments(entryPath, configPath, bad));
  assert.throws(() => inspectorArguments('/tmp/other.js', configPath,
    'tools/list'));
  assert.throws(() => inspectorArguments(entryPath, '/tmp/../tmp/config.json',
    'tools/list'));
});
