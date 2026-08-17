import assert from 'node:assert/strict';
import {
  Client,
  StreamableHTTPClientTransport,
} from '@modelcontextprotocol/client';

const url = process.env.SOKLET_INTEROP_URL;
const protocolVersion = process.env.SOKLET_INTEROP_PROTOCOL_VERSION;
const expectedTool = process.env.SOKLET_INTEROP_EXPECTED_TOOL;
const expectedTools = [
  'json_schema_2020_12_tool',
  'test_audio_content',
  'test_custom_header',
  'test_embedded_resource',
  'test_error_handling',
  'test_image_content',
  'test_multiple_content_types',
  'test_simple_text',
  'test_tool_with_progress',
];
assert.match(url ?? '', /^http:\/\/127\.0\.0\.1:[0-9]+\/mcp$/u);
assert.equal(protocolVersion, '2026-07-28');
assert.equal(expectedTool, 'test_simple_text');

const transport = new StreamableHTTPClientTransport(new URL(url));
const client = new Client(
  { name: 'soklet-typescript-interoperability', version: '1.0.0' },
  { versionNegotiation: { mode: { pin: protocolVersion } } },
);

try {
  await client.connect(transport);
  assert.equal(transport.protocolVersion, protocolVersion);
  const listing = await client.listTools();
  assert.equal(listing.nextCursor, undefined);
  assert.deepEqual(
    listing.tools.map(({ name }) => name).sort(),
    expectedTools,
  );
  const result = await client.callTool({ name: expectedTool, arguments: {} });
  assert.equal(result.isError, undefined);
  assert.equal(result.content?.length, 1);
  assert.equal(result.content?.[0]?.type, 'text');
  assert.equal(
    result.content[0].text,
    'This is a simple text response for testing.',
  );
  console.log(`SOKLET_INTEROP_PASS ${protocolVersion} typescript`);
} finally {
  await client.close();
}
