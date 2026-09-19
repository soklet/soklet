import assert from 'node:assert/strict';
import { test } from 'node:test';
import { browserRequestPolicy, browserVersion, chromeArguments, validWebConfig } from './web-probe.mjs';

const profile = '/private/tmp/soklet-inspector-web-test/chrome-profile';
const origin = 'http://127.0.0.1:48291';
const goodConfig = () => ({
  writable: false,
  secretStorage: { kind: 'memory', reason: 'configured', durable: false },
  sandboxUrl: 'http://127.0.0.1:48292/sandbox',
});
const goodVersion = () => ({
  product: 'Chrome/147.0.7727.24',
  revision: `@${'a'.repeat(40)}`,
  protocolVersion: '1.3',
});

test('Chrome arguments isolate profile, debugging, credentials and background services', () => {
  const args = chromeArguments(profile);
  for (const required of [
    '--headless=new', `--user-data-dir=${profile}`,
    '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0',
    '--no-first-run', '--no-default-browser-check', '--disable-background-networking',
    '--disable-component-update', '--disable-sync', '--disable-default-apps',
    '--disable-domain-reliability', '--disable-breakpad', '--password-store=basic',
    '--use-mock-keychain', '--no-proxy-server',
    '--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost',
  ]) assert.equal(args.filter(arg => arg === required).length, 1, required);
  assert.equal(args.at(-1), 'about:blank');
  assert.equal(args.filter(arg => arg.startsWith('--user-data-dir=')).length, 1);
  assert.equal(args.filter(arg => arg.startsWith('--remote-debugging-port=')).length, 1);
  assert.ok(args.every(arg => typeof arg === 'string'));
  assert.ok(args.every(arg => !/token|authorization|cookie/i.test(arg)));
  const spaced = '/private/tmp/soklet inspector web/chrome-profile';
  assert.ok(chromeArguments(spaced).includes(`--user-data-dir=${spaced}`));
});

test('Chrome arguments never disable sandbox, CSP, certificate or origin protections', () => {
  const args = chromeArguments(profile);
  for (const dangerous of [
    '--no-sandbox', '--disable-setuid-sandbox', '--disable-gpu-sandbox',
    '--disable-web-security', '--disable-site-isolation-trials',
    '--allow-file-access-from-files', '--allow-running-insecure-content',
    '--ignore-certificate-errors', '--ignore-certificate-errors-spki-list',
    '--allow-insecure-localhost', '--disable-csp', '--single-process',
  ]) assert.ok(args.every(arg => arg !== dangerous && !arg.startsWith(`${dangerous}=`)), dangerous);
  assert.ok(args.every(arg => !arg.includes('IsolateOrigins') && !arg.includes('site-per-process')));
});

test('Chrome arguments reject missing, relative, broad, noncanonical and control-character profiles', () => {
  for (const bad of [undefined, null, false, {}, [], '', '.', 'chrome-profile', '/',
    '/private/tmp/../tmp/chrome-profile', '/private/tmp/chrome-profile/',
    `${profile}\n`, `${profile}\r`, `${profile}\0`, `${profile}\t`])
    assert.throws(() => chromeArguments(bad), { message: 'BROWSER_PROFILE_INVALID' });
});

test('web configuration requires read-only catalog and explicitly configured nondurable memory store', () => {
  assert.equal(validWebConfig(goodConfig(), origin), true);
  for (const bad of [undefined, null, {}, [],
    { ...goodConfig(), writable: true }, { ...goodConfig(), writable: 'false' },
    { ...goodConfig(), writable: undefined },
    ...['kind', 'reason', 'durable'].map(key => {
      const config = goodConfig(); delete config.secretStorage[key]; return config;
    }),
    ...[
      { kind: 'keychain', reason: 'configured', durable: true },
      { kind: 'file', reason: 'configured', durable: true },
      { kind: 'memory', reason: 'fallback', durable: false },
      { kind: 'memory', reason: 'configured', durable: 'false' },
    ].map(secretStorage => ({ ...goodConfig(), secretStorage })),
  ]) assert.equal(validWebConfig(bad, origin), false);
});

test('web configuration requires a distinct canonical credential-free loopback sandbox', () => {
  for (const sandboxUrl of [
    undefined, null, '', 'not-a-url', `${origin}/sandbox`,
    'http://localhost:48292/sandbox', 'http://[::1]:48292/sandbox',
    'http://192.0.2.1:48292/sandbox', 'https://127.0.0.1:48292/sandbox',
    'http://127.0.0.1/sandbox', 'http://127.0.0.1:0/sandbox',
    'http://127.0.0.1:65536/sandbox', 'http://127.0.0.1:48292/',
    'http://127.0.0.1:48292/sandbox/', 'http://127.0.0.1:48292/sandbox?token=private',
    'http://127.0.0.1:48292/sandbox#private',
    'http://private@127.0.0.1:48292/sandbox',
    'http://user:private@127.0.0.1:48292/sandbox',
    'http://127.1:48292/sandbox', 'http://127.0.0.1:048292/sandbox',
    'http://127.0.0.1:48292/other/../sandbox',
    'http://127.0.0.1:48292/sandbox\n',
  ]) assert.equal(validWebConfig({ ...goodConfig(), sandboxUrl }, origin), false, String(sandboxUrl));
  for (const badOrigin of [undefined, null, '', {}, origin + '/', origin + '/private',
    origin + '?token=private', origin + '\n', 'https://127.0.0.1:48291',
    'http://localhost:48291', 'http://192.0.2.1:48291'])
    assert.equal(validWebConfig(goodConfig(), badOrigin), false, String(badOrigin));
});

test('browser version receipt projects only validated version fields', () => {
  const version = goodVersion();
  const input = { ...version, userAgent: 'must-not-persist', jsVersion: 'must-not-persist',
    error: { message: 'must-not-persist' }, unrelated: 'must-not-persist' };
  assert.deepEqual(browserVersion(input), version);
  assert.equal(JSON.stringify(browserVersion(input)).includes('must-not-persist'), false);
  const headless = { ...version, product: 'HeadlessChrome/147.0.7727.24' };
  assert.deepEqual(browserVersion(headless), headless);
});

test('browser version rejects malformed, coercible or unbounded fields with fixed redacted errors', () => {
  const version = goodVersion();
  const badVersions = [undefined, null, {}, [], 'private error',
    ...[undefined, null, 147, [], [version.product], {}, true,
      'Chromium/147.0.7727.24', 'Chrome/147', 'Chrome/147.0.7727.24 private',
      `${version.product}\n`, `${version.product}\r`, `${version.product}\0`,
      `Chrome/${'1'.repeat(257)}.0.7727.24`,
    ].map(product => ({ ...version, product })),
    ...[undefined, null, [], [version.revision], {}, true,
      `@${'a'.repeat(39)}`, `@${'g'.repeat(40)}`, '@private',
      `${version.revision}\n`, `${version.revision}\r`, `${version.revision}\0`,
    ].map(revision => ({ ...version, revision })),
    ...[undefined, null, ['1.3'], 1.3, '1.3\n', 'private'].map(protocolVersion =>
      ({ ...version, protocolVersion })),
    { error: { code: -1, message: 'private upstream diagnostic' } },
  ];
  for (const bad of badVersions)
    assert.throws(() => browserVersion(bad), { message: 'BROWSER_VERSION_INVALID' });
});

test('page request policy permits only same-origin requests and structurally identifies the denied pinned font', () => {
  const pinnedFont = 'https://fonts.googleapis.com/css2?family=Fredoka:wght@300..700&family=Roboto+Mono:ital,wght@0,100..700;1,100..700&display=swap';
  for (const [url, method, type] of [
    [`${origin}/`, 'GET', 'Document'],
    [`${origin}/assets/index.js`, 'GET', 'Script'],
    [`${origin}/api/config`, 'GET', 'Fetch'],
    [`${origin}/api/mcp/send`, 'POST', 'Fetch'],
    [`${origin}/api/storage/private?token=synthetic-secret`, 'POST', 'XHR'],
  ]) assert.equal(browserRequestPolicy({ url, method }, origin, type), 'SAME_ORIGIN');
  assert.equal(browserRequestPolicy({ url: pinnedFont, method: 'GET' }, origin, 'Stylesheet'),
    'BLOCKED_PINNED_FONT');
  for (const request of [
    { url: pinnedFont, method: 'POST' },
    { url: pinnedFont, method: 'HEAD' },
    { url: pinnedFont, method: 'get' },
    { url: pinnedFont, method: undefined },
    { url: `${pinnedFont}&token=synthetic-secret`, method: 'GET' },
    { url: `${pinnedFont}#synthetic-secret`, method: 'GET' },
    { url: `${pinnedFont}\n`, method: 'GET' },
    { url: pinnedFont.replace('display=swap', 'display=block'), method: 'GET' },
    { url: pinnedFont.replace('https:', 'http:'), method: 'GET' },
    { url: pinnedFont.replace('fonts.googleapis.com', 'fonts.googleapis.com.invalid'), method: 'GET' },
    { url: 'https://fonts.gstatic.com/s/private.woff2', method: 'GET' },
    { url: 'http://127.0.0.1:48292/sandbox', method: 'GET' },
    { url: 'https://127.0.0.1:48291/api/config', method: 'GET' },
    { url: 'http://localhost:48291/api/config', method: 'GET' },
    { url: 'http://synthetic-secret@127.0.0.1:48291/api/config', method: 'GET' },
    { url: 'http://user:synthetic-secret@127.0.0.1:48291/api/config', method: 'GET' },
    { url: 'private invalid URL synthetic-secret', method: 'GET' },
    { url: ['synthetic-secret'], method: 'GET' },
    { url: `${origin}/api/config`, method: ['GET'] },
    { url: 'file:///private/synthetic-secret', method: 'GET' },
    { url: 'data:text/plain,synthetic-secret', method: 'GET' },
    null, undefined, {}, [],
  ]) assert.equal(browserRequestPolicy(request, origin, 'Stylesheet'), 'BLOCKED_UNEXPECTED');
  for (const type of [undefined, null, 'stylesheet', 'Script', 'Document', 'Fetch', 'XHR',
    'Image', ['Stylesheet']])
    assert.equal(browserRequestPolicy({ url: pinnedFont, method: 'GET' }, origin, type),
      'BLOCKED_UNEXPECTED');
  const throwingRequest = { get url() { throw new Error('synthetic-secret'); } };
  assert.equal(browserRequestPolicy(throwingRequest, origin, 'Stylesheet'), 'BLOCKED_UNEXPECTED');
});
