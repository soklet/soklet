import { dirname, isAbsolute, join, resolve, sep } from 'node:path';

// Source pin: Inspector 2.7.0, commit 2e90a628e6296c62e4bef942afbb43d3faa4baf4.
// The CLI reads protocolEra and headers from a read-only --config session, but
// does not forward that session's advertisedExtensions to InspectorClient.
// Therefore the OFF config below is a requested mode, never proof of an OFF
// wire advertisement; the runner must classify the observed wire separately.
const serverName = 'soklet';
const appsExtension = 'io.modelcontextprotocol/ui';
const skillsExtension = 'io.modelcontextprotocol/skills';
const allowedMethods = new Set(['tools/list', 'tools/call']);
const bearerToken = /^[A-Za-z0-9._~+/-]+=*$/;

function absolutePath(value, label) {
  if (typeof value !== 'string' || value.length === 0
      || !isAbsolute(value) || value !== resolve(value)
      || /[\u0000-\u001f\u007f]/u.test(value))
    throw new Error(`Invalid ${label}`);
  return value;
}

function requestedExtensions(enabled) {
  let apps;
  let skills;
  if (typeof enabled === 'boolean') {
    apps = enabled;
    skills = enabled;
  } else if (enabled !== null && typeof enabled === 'object'
      && !Array.isArray(enabled)
      && Object.keys(enabled).length === 2
      && Object.hasOwn(enabled, 'apps')
      && Object.hasOwn(enabled, 'skills')) {
    apps = enabled.apps;
    skills = enabled.skills;
  }
  if (typeof apps !== 'boolean' || typeof skills !== 'boolean')
    throw new Error('Invalid requested extension mode');
  return { [appsExtension]: apps, [skillsExtension]: skills };
}

function loopbackMcpUrl(value) {
  if (typeof value !== 'string' || /[\u0000-\u0020\u007f]/u.test(value))
    throw new Error('Invalid Inspector fixture URL');
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error('Invalid Inspector fixture URL');
  }
  if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1'
      || !/^[1-9][0-9]{0,4}$/u.test(url.port)
      || Number(url.port) > 65535 || url.pathname !== '/mcp'
      || url.search !== '' || url.hash !== ''
      || url.username !== '' || url.password !== ''
      || url.href !== value)
    throw new Error('Inspector fixture URL must be exact loopback HTTP /mcp');
  return value;
}

function tokenValue(value) {
  if (typeof value !== 'string' || value.length < 1
      || value.length > 4096 || !bearerToken.test(value))
    throw new Error('Invalid disposable Inspector bearer token');
  return value;
}

/** Construct one explicit, per-run, read-only Inspector session config. */
export function createSessionConfig(url, token, enabled) {
  return {
    mcpServers: {
      [serverName]: {
        type: 'http',
        url: loopbackMcpUrl(url),
        protocolEra: 'modern',
        headers: { Authorization: `Bearer ${tokenValue(token)}` },
        advertisedExtensions: requestedExtensions(enabled),
      },
    },
  };
}

/** Persist only this redaction, never a live config or a token hash. */
export function sanitizedSessionConfig(config) {
  const server = config?.mcpServers?.[serverName];
  if (server?.type !== 'http' || server.protocolEra !== 'modern'
      || typeof server.headers?.Authorization !== 'string'
      || !server.headers.Authorization.startsWith('Bearer '))
    throw new Error('Invalid Inspector session config');
  loopbackMcpUrl(server.url);
  tokenValue(server.headers.Authorization.slice('Bearer '.length));
  const extensions = server.advertisedExtensions;
  if (extensions === null || typeof extensions !== 'object'
      || Array.isArray(extensions)
      || Object.keys(extensions).length !== 2
      || typeof extensions[appsExtension] !== 'boolean'
      || typeof extensions[skillsExtension] !== 'boolean')
    throw new Error('Invalid Inspector extension config');
  return {
    mcpServers: {
      [serverName]: {
        type: 'http',
        url: 'http://127.0.0.1:<LOOPBACK_PORT>/mcp',
        protocolEra: 'modern',
        headers: { Authorization: 'Bearer <REDACTED>' },
        advertisedExtensions: {
          [appsExtension]: extensions[appsExtension],
          [skillsExtension]: extensions[skillsExtension],
        },
      },
    },
  };
}

/** No inherited proxy, npm, OAuth, keychain, debug, or user catalog state. */
export function createEnvironment(isolationRoot, javaExecutable) {
  const root = absolutePath(isolationRoot, 'Inspector isolation root');
  const java = absolutePath(javaExecutable, 'Java executable');
  if (root === sep || dirname(root) === root || root === process.env.HOME
      || java.split(sep).at(-1) !== 'java')
    throw new Error('Invalid Inspector isolation root or Java executable');
  const nodeBin = dirname(process.execPath);
  const javaBin = dirname(java);
  return {
    PATH: [...new Set([nodeBin, javaBin, '/usr/bin', '/bin'])].join(sep === '\\' ? ';' : ':'),
    HOME: join(root, 'home'),
    XDG_CONFIG_HOME: join(root, 'xdg-config'),
    XDG_CACHE_HOME: join(root, 'xdg-cache'),
    TMPDIR: join(root, 'tmp'),
    MCP_STORAGE_DIR: join(root, 'storage'),
    MCP_INSPECTOR_OAUTH_STATE_PATH: join(root, 'storage', 'oauth.json'),
    MCP_CLIENT_CONFIG_PATH: join(root, 'storage', 'client.json'),
    MCP_INSPECTOR_SECRET_FILE: join(root, 'storage', 'secrets.json'),
    MCP_INSPECTOR_SECRET_STORE: 'memory',
    MCP_AUTO_OPEN_ENABLED: 'false',
    NO_COLOR: '1',
  };
}

/** Return argv for `node`, not for a shell or npx. */
export function inspectorArguments(entryPath, configPath, method) {
  const entry = absolutePath(entryPath, 'Inspector launcher entry');
  const config = absolutePath(configPath, 'Inspector session config path');
  if (!entry.endsWith(`${sep}clients${sep}launcher${sep}build${sep}index.js`)
      || !allowedMethods.has(method))
    throw new Error('Invalid Inspector launcher or method');
  return [
    entry,
    '--cli',
    '--config', config,
    '--server', serverName,
    '--method', method,
    ...(method === 'tools/call'
      ? ['--tool-name', 'test_simple_text'] : []),
    '--format', 'json',
    '--stored-auth-only',
  ];
}
