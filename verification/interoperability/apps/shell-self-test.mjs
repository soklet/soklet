import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { runInNewContext } from 'node:vm';
import { BRIDGE_TIMEOUT_MS, mountCatalogShell, projectCatalogResult } from './assets/catalog-shell.mjs';
import { assertInputSnapshotUnchanged, buildArguments, buildCliArguments, BUILD_TIMEOUT_MS, inlineShell, SHELL_MAX_BYTES } from './build-shell.mjs';

// Deliberately a DOM/bridge mock. These tests are not a browser render, an
// official Apps host interaction, CSP enforcement, or interoperability proof.
class Element {
  attributes = new Map();
  dataset = {};
  listeners = new Map();
  textContent = '';
  hidden = false;
  disabled = false;
  set innerHTML(value) { throw new Error('HTML_SINK_FORBIDDEN'); }
  setAttribute(name, value) { this.attributes.set(name, value); }
  removeAttribute(name) { this.attributes.delete(name); }
  getAttribute(name) { return this.attributes.get(name); }
  addEventListener(name, handler) { this.listeners.set(name, handler); }
  click() { return this.listeners.get('click')?.(); }
}

function deferred() {
  let resolve; let reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

function fixture({ connect = Promise.resolve(), context = { locale: 'en-US', timeZone: 'UTC' }, followup } = {}) {
  const nodes = Object.fromEntries(['root', 'status', 'view', 'tenant', 'title', 'summary', 'item', 'amount', 'updated', 'refresh']
    .map(id => [id, new Element()]));
  const document = { documentElement: new Element(), getElementById: id => nodes[id.replace('catalog-', '')] };
  const timeouts = new Map(); let nextTimer = 0;
  const timers = {
    setTimeout(callback, delay) { assert.equal(delay, BRIDGE_TIMEOUT_MS); timeouts.set(++nextTimer, callback); return nextTimer; },
    clearTimeout(id) { timeouts.delete(id); },
  };
  const calls = [];
  const app = {
    closed: 0,
    connect(transport, options) {
      assert.equal(typeof app.ontoolresult, 'function');
      assert.equal(typeof app.onhostcontextchanged, 'function');
      assert.equal(transport, undefined);
      calls.push({ kind: 'connect', options });
      return connect;
    },
    getHostContext: () => context,
    callServerTool(params, options) { calls.push({ kind: 'tool', params, options }); return followup?.promise ?? Promise.resolve(result()); },
    close() { app.closed++; return Promise.resolve(); },
  };
  const shell = mountCatalogShell({ app, document, timers });
  return { nodes, document, app, shell, calls, timeouts,
    expire() { for (const callback of [...timeouts.values()]) callback(); } };
}

function result(overrides = {}) {
  return { content: [{ type: 'text', text: 'fallback not used by this UI' }], structuredContent: {
    locale: 'en-US', direction: 'ltr', tenant: 'alpha', title: 'Catalog', refreshLabel: 'Refresh',
    summary: 'Available toys', itemLabel: 'Wooden train', amount: 1234.5, currency: 'USD',
    updatedAt: '2026-09-19T12:00:00Z', timeZone: 'UTC', ...overrides,
  } };
}

function assertCleared(f, state) {
  assert.equal(f.nodes.root.dataset.state, state);
  assert.equal(f.nodes.view.hidden, true);
  assert.equal(f.nodes.refresh.disabled, true);
  assert.equal(f.document.documentElement.getAttribute('lang'), undefined);
  assert.equal(f.document.documentElement.getAttribute('dir'), undefined);
  for (const key of ['tenant', 'title', 'summary', 'item', 'amount', 'updated', 'refresh'])
    assert.equal(f.nodes[key].textContent, '', key);
  assert.equal(f.nodes.updated.getAttribute('datetime'), undefined);
}

test('shell is static, locale/tenant neutral, and has no runtime network imports', async () => {
  const html = await readFile(new URL('./assets/catalog-shell.html', import.meta.url), 'utf8');
  assert.equal((html.match(/SOKLET_APPS_SCRIPT/g) ?? []).length, 1);
  assert.doesNotMatch(html, /\blang=|\bdir=|alpha|beta|pt-BR|ar-SA|fetch\(|https?:|src=|<script/i);
  const source = await readFile(new URL('./assets/catalog-shell.mjs', import.meta.url), 'utf8');
  assert.doesNotMatch(source, /innerHTML|outerHTML|insertAdjacentHTML|localStorage|sessionStorage|fetch\(|console\./);
  assert.match(await readFile(new URL('./assets/catalog-entry.mjs', import.meta.url), 'utf8'), /import \{ App \} from '@modelcontextprotocol\/ext-apps'/);
});

test('initial shell has no personalized content and initializes bounded official bridge', async () => {
  const f = fixture();
  assertCleared(f, 'waiting');
  await f.shell.ready;
  assert.equal(f.calls[0].options.timeout, 10_000);
  assert.equal(f.calls[0].options.signal.aborted, false);
  assert.equal(f.timeouts.size, 0);
  assert.equal(f.calls.length, 1);
});

test('server locale, direction, tenant and Intl formatting control each rendered view', async () => {
  for (const [locale, direction, tenant] of [['en-US', 'ltr', 'alpha'], ['pt-BR', 'ltr', 'beta'], ['ar', 'rtl', 'alpha']]) {
    const f = fixture({ context: { locale: 'de-DE', timeZone: 'Europe/Berlin' } });
    await f.shell.ready;
    const input = result({ locale, direction, tenant });
    Object.defineProperty(input, '_meta', { get() { throw new Error('METADATA_READ_FORBIDDEN'); } });
    f.app.ontoolresult(input);
    assert.equal(f.document.documentElement.getAttribute('lang'), locale);
    assert.equal(f.document.documentElement.getAttribute('dir'), direction);
    assert.equal(f.nodes.tenant.textContent, tenant);
    assert.equal(f.nodes.amount.textContent, new Intl.NumberFormat(locale, { style: 'currency', currency: 'USD' }).format(1234.5));
    assert.equal(f.nodes.updated.textContent, new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' }).format(new Date('2026-09-19T12:00:00Z')));
    assert.equal(f.nodes.refresh.disabled, false);
    assert.equal(f.nodes.view.hidden, false);
    assert.equal(f.nodes.root.dataset.state, 'ready');
  }
});

test('hostile translations are assigned as literal text and never interpreted as markup', async () => {
  const hostile = '<img src=x onerror="globalThis.pwned=true"><script>bad()</script>&';
  const f = fixture(); await f.shell.ready;
  f.app.ontoolresult(result({ title: hostile, summary: hostile, itemLabel: hostile, refreshLabel: hostile }));
  for (const key of ['title', 'summary', 'item', 'refresh']) assert.equal(f.nodes[key].textContent, hostile);
});

test('display projection rejects malformed or unsupported data instead of metadata/fallback promotion', () => {
  for (const input of [null, {}, { structuredContent: [] }, { _meta: result().structuredContent },
    { content: result().content }, { ...result(), isError: true },
    ...[{ locale: 'fr' }, { direction: 'rtl' }, { tenant: 'gamma' }, { amount: Infinity },
      { currency: 'EUR' }, { timeZone: 'Europe/Berlin' }, { updatedAt: 'not a date' },
      { updatedAt: '2026-99-19T12:00:00Z' }, { title: '' }, { title: '<'.repeat(2049) }, { refreshLabel: 1 }].map(result)])
    assert.throws(() => projectCatalogResult(input), { message: 'CATALOG_RESULT_INVALID' });
});

test('initial tool result can arrive during connect but button stays disabled until handshake completion', async () => {
  const connect = deferred(); const f = fixture({ connect: connect.promise });
  f.app.ontoolresult(result());
  assert.equal(f.nodes.view.hidden, false);
  assert.equal(f.nodes.refresh.disabled, true);
  await f.nodes.refresh.click(); assert.equal(f.calls.length, 1);
  connect.resolve(); await f.shell.ready;
  assert.equal(f.nodes.refresh.disabled, false);
});

test('refresh uses bridge tools/call with no tenant or locale override and clears old view while pending', async () => {
  const followup = deferred(); const f = fixture({ followup }); await f.shell.ready;
  f.app.ontoolresult(result());
  const refreshed = f.nodes.refresh.click();
  assertCleared(f, 'pending');
  assert.deepEqual(f.calls[1].params, { name: 'refresh_catalog', arguments: {} });
  assert.equal(f.calls[1].options.timeout, 10_000);
  await f.nodes.refresh.click(); assert.equal(f.calls.length, 2);
  followup.resolve(result({ locale: 'ar', direction: 'rtl', tenant: 'beta', title: 'الكتالوج' }));
  await refreshed;
  assert.equal(f.nodes.tenant.textContent, 'beta');
  assert.equal(f.nodes.title.textContent, 'الكتالوج');
  assert.equal(f.document.documentElement.getAttribute('lang'), 'ar');
  assert.equal(f.timeouts.size, 0);
});

test('duplicate initial results cannot overwrite a newer refresh or in-flight view', async () => {
  const followup = deferred(); const f = fixture({ followup }); await f.shell.ready;
  f.app.ontoolresult(result());
  const refreshed = f.nodes.refresh.click();
  f.app.ontoolresult(result({ title: 'stale' })); assertCleared(f, 'pending');
  followup.resolve(result({ title: 'new' })); await refreshed;
  f.app.ontoolresult(result({ title: 'stale' }));
  assert.equal(f.nodes.title.textContent, 'new');
});

test('refresh rejection/error/malformed result clears old tenant and never shows diagnostics', async () => {
  for (const mode of ['reject', 'error', 'malformed']) {
    const followup = deferred(); const f = fixture({ followup }); await f.shell.ready;
    f.app.ontoolresult(result()); const refreshed = f.nodes.refresh.click();
    if (mode === 'reject') followup.reject(new Error('SECRET_DIAGNOSTIC'));
    else followup.resolve(mode === 'error' ? { ...result(), isError: true } : { content: [{ text: 'SECRET_DIAGNOSTIC' }] });
    await refreshed;
    assertCleared(f, 'error');
    assert.doesNotMatch(f.nodes.status.textContent, /SECRET|alpha|Catalog/);
    f.app.ontoolresult(result()); assertCleared(f, 'error');
  }
});

test('theme, dimensions and unchanged language preferences do not invalidate data', async () => {
  const f = fixture(); await f.shell.ready; f.app.ontoolresult(result());
  for (const context of [{ theme: 'dark' }, { containerDimensions: { width: 400, height: 200 } },
    { locale: 'en-US' }, { timeZone: 'UTC' }, {}]) {
    f.app.onhostcontextchanged(context);
    assert.equal(f.nodes.root.dataset.state, 'ready');
    assert.equal(f.nodes.title.textContent, 'Catalog');
  }
});

test('host language/timezone change clears view and requires new authorized instance', async () => {
  for (const context of [{ locale: 'pt-BR' }, { timeZone: 'Europe/Lisbon' }, { locale: undefined }]) {
    const f = fixture(); await f.shell.ready; f.app.ontoolresult(result());
    f.app.onhostcontextchanged(context); assertCleared(f, 'invalidated');
    f.app.ontoolresult(result({ tenant: 'beta' })); assertCleared(f, 'invalidated');
    await f.nodes.refresh.click(); assert.equal(f.calls.length, 1);
  }
});

test('host missing-to-present preferences invalidate even if result locale happened to match', async () => {
  for (const context of [{ locale: 'en-US' }, { timeZone: 'UTC' }]) {
    const f = fixture({ context: {} }); await f.shell.ready; f.app.ontoolresult(result());
    f.app.onhostcontextchanged(context); assertCleared(f, 'invalidated');
  }
});

test('context change while refresh is pending aborts and suppresses late tenant-bearing response', async () => {
  const followup = deferred(); const f = fixture({ followup }); await f.shell.ready;
  f.app.ontoolresult(result()); const refreshed = f.nodes.refresh.click();
  f.app.onhostcontextchanged({ locale: 'ar' });
  assert.equal(f.calls[1].options.signal.aborted, true);
  followup.resolve(result({ tenant: 'beta', locale: 'ar', direction: 'rtl' })); await refreshed;
  assertCleared(f, 'invalidated'); assert.equal(f.timeouts.size, 0);
});

test('context change before connect baseline is fail-closed and late handshake cannot reopen', async () => {
  const connect = deferred(); const f = fixture({ connect: connect.promise });
  f.app.onhostcontextchanged({ theme: 'light' }); assertCleared(f, 'waiting');
  f.app.ontoolresult(result());
  f.app.onhostcontextchanged({ locale: 'en-US' });
  connect.resolve(); await f.shell.ready;
  assertCleared(f, 'invalidated');
  f.app.ontoolresult(result()); assertCleared(f, 'invalidated');
});

test('connect rejection and bounded timeout clear view without leaking exceptions', async () => {
  for (const mode of ['reject', 'timeout']) {
    const connect = deferred(); const f = fixture({ connect: connect.promise });
    f.app.ontoolresult(result());
    if (mode === 'reject') connect.reject(new Error('SECRET_CONNECT'));
    else f.expire();
    await f.shell.ready; assertCleared(f, 'error');
    assert.equal(f.timeouts.size, 0);
    connect.resolve(); await Promise.resolve(); assertCleared(f, 'error');
  }
});

test('refresh timeout aborts the bridge request and rejects late successful data', async () => {
  const followup = deferred(); const f = fixture({ followup }); await f.shell.ready;
  f.app.ontoolresult(result()); const refreshed = f.nodes.refresh.click();
  f.expire(); await refreshed;
  assert.equal(f.calls[1].options.signal.aborted, true);
  assertCleared(f, 'error');
  followup.resolve(result({ tenant: 'beta' })); await Promise.resolve();
  assertCleared(f, 'error'); assert.equal(f.timeouts.size, 0);
});

test('cancel/error/close/teardown/dispose erase view and cannot be undone by a late notification', async () => {
  for (const operation of ['ontoolcancelled', 'onerror', 'onclose', 'onteardown', 'dispose']) {
    const f = fixture(); await f.shell.ready; f.app.ontoolresult(result());
    if (operation === 'dispose') f.shell.dispose(); else await f.app[operation]();
    const state = ['dispose', 'onteardown'].includes(operation) ? 'closed' : 'error';
    assertCleared(f, state); f.app.ontoolresult(result()); assertCleared(f, state);
  }
});

test('build arguments only accept canonical absolute dependency and new html output paths', () => {
  assert.deepEqual(buildArguments(['--dependencies', '/private/tmp/sdk', '--output', '/private/tmp/shell.html']),
    { dependencies: '/private/tmp/sdk', output: '/private/tmp/shell.html' });
  for (const args of [[], ['--output', '/private/tmp/shell.html', '--dependencies', '/private/tmp/sdk'],
    ['--dependencies', 'sdk', '--output', '/private/tmp/shell.html'],
    ['--dependencies', '/', '--output', '/private/tmp/shell.html'],
    ['--dependencies', '/private/tmp/../sdk', '--output', '/private/tmp/shell.html'],
    ['--dependencies', '/private/tmp/sdk\n', '--output', '/private/tmp/shell.html'],
    ['--dependencies', '/private/tmp/sdk', '--output', '/private/tmp/shell.js']])
    assert.throws(() => buildArguments(args));
});

test('inline builder escapes mixed-case closing tags and preserves JavaScript replacement tokens', () => {
  const template = '<html><!-- SOKLET_APPS_SCRIPT --></html>';
  const script = 'globalThis.value = "</ScRiPt> $& $$ $` $\'";';
  const html = inlineShell(template, script);
  assert.equal((html.match(/<\/script>/gi) ?? []).length, 1);
  const context = {}; runInNewContext(html.slice('<html><script>'.length, -'</script></html>'.length), context);
  assert.equal(context.value, '</ScRiPt> $& $$ $` $\'');
});

test('build worker switch is explicit, nonrecursive and retains strict remaining argument validation', () => {
  const args = ['--dependencies', '/private/tmp/sdk', '--output', '/private/tmp/shell.html'];
  assert.equal(buildCliArguments(args).worker, false);
  assert.deepEqual(buildCliArguments(['--worker', ...args]), { worker: true, ...buildArguments(args) });
  assert.equal(BUILD_TIMEOUT_MS, 60_000);
  for (const bad of [['--worker'], ['--worker', '--worker', ...args], [...args, '--worker'], ['--worker', '--help']])
    assert.throws(() => buildCliArguments(bad));
});

test('build pre/post snapshot check rejects changed recipe, dependency, license, native binding and file inventory', () => {
  const initial = {
    sourceInputs: [{ path: 'build-shell.mjs', bytes: 10, sha256: 'a'.repeat(64) },
      { path: '../inspector/process.mjs', bytes: 20, sha256: 'b'.repeat(64) }],
    packageTrees: [{ name: '@modelcontextprotocol/ext-apps', version: '2.0.0', sha256: 'c'.repeat(64), files: 3, bytes: 90 }],
    packageFiles: [{ path: 'node_modules/@modelcontextprotocol/ext-apps/dist/src/app.js', bytes: 30, sha256: 'd'.repeat(64) },
      { path: 'node_modules/@modelcontextprotocol/ext-apps/LICENSE', bytes: 40, sha256: 'e'.repeat(64) },
      { path: 'node_modules/@rolldown/binding-darwin-arm64/rolldown-binding.darwin-arm64.node', bytes: 50, sha256: 'f'.repeat(64) }],
  };
  assert.doesNotThrow(() => assertInputSnapshotUnchanged(initial, structuredClone(initial)));
  for (const mutate of [
    state => { state.sourceInputs[0].sha256 = '0'.repeat(64); },
    state => { state.sourceInputs[1].sha256 = '0'.repeat(64); },
    state => { state.packageTrees[0].version = '9.0.0'; },
    state => { state.packageFiles[0].sha256 = '0'.repeat(64); },
    state => { state.packageFiles[1].sha256 = '0'.repeat(64); },
    state => { state.packageFiles[2].sha256 = '0'.repeat(64); },
    state => { state.packageFiles.pop(); },
    state => { state.packageFiles.push({ path: 'new.js', bytes: 1, sha256: '0'.repeat(64) }); },
  ]) {
    const current = structuredClone(initial); mutate(current);
    assert.throws(() => assertInputSnapshotUnchanged(initial, current), { message: 'APPS_BUILD_INPUT_CHANGED' });
  }
});

test('inline builder rejects multiple/missing scripts, HTML parser alternate states, invalid syntax and oversized output', () => {
  const marker = '<!-- SOKLET_APPS_SCRIPT -->';
  for (const [template, script] of [[marker + marker, '1'], ['<html></html>', '1'],
    ['<script></script>' + marker, '1'], [marker, ''], [marker, '"<script>"'],
    [marker, '"<!--"'], [marker, 'let ='], [marker, `"${'x'.repeat(SHELL_MAX_BYTES)}"`]])
    assert.throws(() => inlineShell(template, script));
});
