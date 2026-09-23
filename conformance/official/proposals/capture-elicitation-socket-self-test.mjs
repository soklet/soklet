#!/usr/bin/env node

import assert from 'node:assert/strict';
import { validateControlExchange } from './capture-elicitation-socket.mjs';

const tool = 'test_missing_elicitation_capability';
const meta = (capabilities) => ({
  'io.modelcontextprotocol/protocolVersion': '2026-07-28',
  'io.modelcontextprotocol/clientCapabilities': capabilities,
});
const request = (id, capabilities) => ({
  jsonrpc: '2.0', id, method: 'tools/call',
  params: { _meta: meta(capabilities), name: tool, arguments: {} },
});
const negativeRequest = request(9101, {});
const negativeResponse = {
  jsonrpc: '2.0', id: 9101,
  error: {
    code: -32021,
    message: 'Missing required client capability',
    data: { requiredCapabilities: { elicitation: { form: {} } } },
  },
};
const positiveRequest = request(9102, { elicitation: { form: {} } });
const positiveResponse = {
  jsonrpc: '2.0', id: 9102,
  result: { resultType: 'complete', content: [] },
};

const check = (which, wireRequest, wireResponse, status) =>
  validateControlExchange(which, JSON.stringify(wireRequest),
    JSON.stringify(wireResponse), status);

assert.equal(check('negative', negativeRequest, negativeResponse, 400), 9101);
assert.equal(check('positive', positiveRequest, positiveResponse, 200), 9102);

for (const [label, which, wireRequest, wireResponse, status] of [
  ['negative status', 'negative', negativeRequest, negativeResponse, 200],
  ['wrong JSON-RPC ID', 'negative', negativeRequest,
    { ...negativeResponse, id: 9999 }, 400],
  ['wrong diagnostic', 'negative',
    { ...negativeRequest, params: { ...negativeRequest.params,
      name: 'test_missing_capability' } }, negativeResponse, 400],
  ['fabricated Sampling requirement', 'negative', negativeRequest,
    { ...negativeResponse, error: { ...negativeResponse.error,
      data: { requiredCapabilities: { sampling: {} } } } }, 400],
  ['missing form declaration', 'positive', negativeRequest,
    positiveResponse, 200],
  ['positive error', 'positive', positiveRequest,
    { ...positiveResponse, error: { code: -32021 } }, 200],
  ['positive incomplete', 'positive', positiveRequest,
    { ...positiveResponse, result: { resultType: 'input_required' } }, 200],
]) {
  assert.throws(() => check(which, wireRequest, wireResponse, status),
    Error, label);
}

console.log('Elicitation socket control self-test passed (9 cases)');
