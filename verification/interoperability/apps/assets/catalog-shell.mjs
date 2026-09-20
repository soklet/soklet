export const BRIDGE_TIMEOUT_MS = 10_000;

const directions = Object.freeze({ 'en-US': 'ltr', 'pt-BR': 'ltr', ar: 'rtl' });
const statuses = Object.freeze({
  waiting: '…', pending: '…', error: 'View unavailable. Reopen this view.',
  invalidated: 'Context changed. Reopen this view.', closed: '', ready: '',
});

// A narrow display projection, never an identity or authorization decision.
// Ignore result metadata, host language, fallback text, and extra server fields.
export function projectCatalogResult(result) {
  if (!result || result.isError || !result.structuredContent
      || typeof result.structuredContent !== 'object' || Array.isArray(result.structuredContent))
    throw new Error('CATALOG_RESULT_INVALID');
  const data = result.structuredContent;
  if (!Object.hasOwn(directions, data.locale) || data.direction !== directions[data.locale]
      || !['alpha', 'beta'].includes(data.tenant) || data.currency !== 'USD'
      || data.timeZone !== 'UTC' || !Number.isFinite(data.amount)
      || typeof data.updatedAt !== 'string'
      || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(data.updatedAt)
      || !Number.isFinite(Date.parse(data.updatedAt)))
    throw new Error('CATALOG_RESULT_INVALID');
  for (const field of ['title', 'refreshLabel', 'summary', 'itemLabel'])
    if (typeof data[field] !== 'string' || data[field].length === 0 || data[field].length > 2048)
      throw new Error('CATALOG_RESULT_INVALID');
  return Object.freeze({
    locale: data.locale, direction: data.direction, tenant: data.tenant,
    title: data.title, refreshLabel: data.refreshLabel, summary: data.summary,
    itemLabel: data.itemLabel,
    amount: new Intl.NumberFormat(data.locale, { style: 'currency', currency: data.currency }).format(data.amount),
    updatedAt: data.updatedAt,
    updatedLabel: new Intl.DateTimeFormat(data.locale, {
      dateStyle: 'medium', timeStyle: 'short', timeZone: data.timeZone,
    }).format(new Date(data.updatedAt)),
  });
}

export function mountCatalogShell({ app, document, timers = globalThis }) {
  const ids = ['root', 'status', 'view', 'tenant', 'title', 'summary', 'item', 'amount', 'updated', 'refresh'];
  const nodes = Object.fromEntries(ids.map(id => [id, document.getElementById(`catalog-${id}`)]));
  if (Object.values(nodes).some(node => !node)) throw new Error('CATALOG_SHELL_INVALID');
  let generation = 0;
  let connected = false;
  let terminal = false;
  let initialResultSeen = false;
  let hostPreferences;
  let pending;
  let state = 'waiting';

  function clear(nextState) {
    state = nextState;
    nodes.root.dataset.state = nextState;
    nodes.root.setAttribute('aria-busy', String(nextState === 'waiting' || nextState === 'pending'));
    document.documentElement.removeAttribute('lang');
    document.documentElement.removeAttribute('dir');
    nodes.view.hidden = true;
    nodes.refresh.disabled = true;
    for (const key of ['tenant', 'title', 'summary', 'item', 'amount', 'updated', 'refresh'])
      nodes[key].textContent = '';
    nodes.updated.removeAttribute('datetime');
    nodes.status.textContent = statuses[nextState];
    nodes.status.hidden = !statuses[nextState];
  }

  function stop(nextState, closeBridge = true) {
    if (terminal) return;
    terminal = true;
    generation += 1;
    pending?.abort();
    clear(nextState);
    // No host/server diagnostic is displayed or copied into a console or log.
    if (closeBridge) Promise.resolve().then(() => app.close()).catch(() => {});
  }

  function render(result) {
    const data = projectCatalogResult(result);
    clear('ready');
    document.documentElement.setAttribute('lang', data.locale);
    document.documentElement.setAttribute('dir', data.direction);
    for (const [id, field] of Object.entries({ tenant: 'tenant', title: 'title', summary: 'summary',
      item: 'itemLabel', amount: 'amount', updated: 'updatedLabel', refresh: 'refreshLabel' }))
      nodes[id].textContent = data[field];
    nodes.updated.setAttribute('datetime', data.updatedAt);
    nodes.view.hidden = false;
    nodes.refresh.disabled = !connected;
  }

  async function bounded(operation) {
    const controller = new AbortController();
    pending = controller;
    let timer;
    const timeout = new Promise((resolve, reject) => {
      timer = timers.setTimeout(() => {
        controller.abort();
        reject(new Error('CATALOG_BRIDGE_TIMEOUT'));
      }, BRIDGE_TIMEOUT_MS);
      controller.signal.addEventListener('abort', () => reject(new Error('CATALOG_BRIDGE_ABORTED')), { once: true });
    });
    try {
      return await Promise.race([operation({ timeout: BRIDGE_TIMEOUT_MS, signal: controller.signal }), timeout]);
    } finally {
      timers.clearTimeout(timer);
      if (pending === controller) pending = undefined;
    }
  }

  async function refresh() {
    if (terminal || !connected || state !== 'ready') return;
    const started = ++generation;
    clear('pending');
    try {
      // The host must proxy this app-only tool with a newly authorized server
      // request. No UI-provided tenant, principal, or locale override is sent.
      const result = await bounded(options => app.callServerTool({ name: 'refresh_catalog', arguments: {} }, options));
      if (!terminal && generation === started) render(result);
    } catch {
      if (!terminal && generation === started) stop('error');
    }
  }

  app.ontoolresult = result => {
    if (terminal || initialResultSeen) return;
    initialResultSeen = true;
    try { render(result); } catch { stop('error'); }
  };
  app.ontoolcancelled = () => stop('error');
  app.onerror = () => stop('error');
  app.onclose = () => stop('error', false);
  app.onteardown = async () => { stop('closed', false); return {}; };
  app.onhostcontextchanged = context => {
    if (terminal || !context || typeof context !== 'object') return;
    // Theme/layout changes are not language changes. Missing-to-present counts
    // as a preference change. Before connect establishes the baseline, any
    // explicit preference update is conservatively invalidating.
    if (['locale', 'timeZone'].some(key => Object.hasOwn(context, key)
        && (!hostPreferences || context[key] !== hostPreferences[key])))
      stop('invalidated');
    // A context-change notification cannot be correlated with older tool
    // notifications. Never reopen this instance; reload/re-authorize instead.
  };
  nodes.refresh.addEventListener('click', refresh);
  clear('waiting');
  const ready = (async () => {
    try {
      await bounded(options => app.connect(undefined, options));
      if (terminal) return;
      const context = app.getHostContext() ?? {};
      hostPreferences = { locale: context.locale, timeZone: context.timeZone };
      connected = true;
      nodes.refresh.disabled = state !== 'ready';
    } catch { stop('error'); }
  })();
  return Object.freeze({ ready, dispose: () => stop('closed') });
}
