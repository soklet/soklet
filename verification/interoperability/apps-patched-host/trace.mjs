import { adjudicateAppsTrace } from '../apps/host-trace.mjs';

const METHODS = new Set(['server/discover', 'tools/list', 'resources/list',
  'resources/templates/list', 'tools/call', 'resources/read', 'subscriptions/listen']);
const REQUIRED = ['authorized', 'authorizationForwarded', 'requestEnvelopeValid', 'protocolMetadataMatches',
  'protocolHeaderMatches', 'methodHeaderMatches', 'nameHeaderMatches', 'perRequestCapabilitiesPresent',
  'appsMimeMatches', 'skillsAbsent', 'noSessionState', 'requestSelectionValid', 'responseJson',
  'responseNoStore', 'responseCorrelated', 'responseEnvelopeValid', 'resultMatchesFixture'];
const FIELDS = new Set(['surface', 'sequence', 'method', 'tool', ...REQUIRED,
  'responseStatus', 'subscriptionDenied', 'requestBytes', 'responseBytes']);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

// Experimental patched-host profile only. The released-host adjudicator remains
// unchanged. The runner independently requires six seconds of observation, a
// sealed trace after disconnect, no gate rejections, and real DOM render/refresh
// evidence. These rows alone cannot establish those facts or host qualification.
export function adjudicatePatchedAppsTrace(rows) {
  if (!Array.isArray(rows) || rows.length < 9 || rows.length > 16) return 'FAILED';
  // Validate all rows before filtering. In particular, an invalid retry must
  // never disappear while normalizing the old exact-one-denial control profile.
  if (!rows.every((row, index) => object(row)
    && Object.keys(row).length === FIELDS.size && Object.keys(row).every(key => FIELDS.has(key))
    && row.surface === 'apps-web' && row.sequence === index + 1 && METHODS.has(row.method)
    && row.responseStatus === (row.method === 'subscriptions/listen' ? 403 : 200)
    && row.subscriptionDenied === (row.method === 'subscriptions/listen')
    && REQUIRED.every(key => row[key] === true)
    && (row.method === 'tools/call' ? ['show_catalog', 'refresh_catalog'].includes(row.tool) : row.tool === 'NONE')
    && Number.isSafeInteger(row.requestBytes) && row.requestBytes > 0 && row.requestBytes <= 64 * 1024
    && Number.isSafeInteger(row.responseBytes) && row.responseBytes > 0 && row.responseBytes <= 1024 * 1024))
    return 'FAILED';
  const denials = rows.filter(row => row.method === 'subscriptions/listen');
  if (denials.length < 1 || denials.length > 8 || rows.length - denials.length !== 8) return 'FAILED';
  // The first denial remains at its real position; never move it ahead of the
  // initial tool call. Every positive exchange remains, in its original order.
  const normalized = rows.filter(row => row.method !== 'subscriptions/listen' || row === denials[0])
    .map((row, index) => ({...row, sequence: index + 1}));
  return adjudicateAppsTrace(normalized);
}
