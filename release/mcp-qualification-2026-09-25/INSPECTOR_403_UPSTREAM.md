# Inspector issue draft: plain MCP 403 starts OAuth discovery

**Title:** Plain HTTP 403 without `WWW-Authenticate` starts OAuth discovery in the Inspector web client

## Problem

With a valid credential, an MCP server may permanently deny one operation. For
example, a server can allow Apps catalog reads and tool calls while returning
HTTP 403 for `subscriptions/listen`, without a `WWW-Authenticate` header. No
additional OAuth scope can grant the denied subscription.

In Inspector 2.8.0 (tag commit `1e31c78fbf81a989e8eb47021c6281d7876ad7fd`),
the web transport treats that response as an OAuth challenge. Its
`createAuthChallengeInterceptFetch()` calls `parseAuthChallengeFromResponse()`
for every 401/403; the parser returns `reason: "unauthorized"` for a plain 403.
The web client then attempts OAuth metadata discovery and dynamic registration.
This prevents a host using static bearer credentials from treating the 403 as an
ordinary operation denial.

## Minimal reproduction

```ts
const denial = new Response(
  JSON.stringify({
    jsonrpc: "2.0",
    id: 1,
    error: { code: -32603, message: "Subscription denied" },
  }),
  { status: 403, headers: { "Content-Type": "application/json" } },
);

parseAuthChallengeFromResponse(denial);
// 2.8.0: { reason: "unauthorized", raw: { httpStatus: 403 } }
// Expected: undefined
```

Wrapping the same response with `createAuthChallengeInterceptFetch()` throws
`AuthChallengeError`; `createAuthChallengeObserverFetch()` reports it as a
challenge. Both should leave an unchallenged 403 response intact. A 401 and a
403 with `WWW-Authenticate: Bearer error="insufficient_scope"` must continue to
trigger the existing OAuth flows.

## Suggested fix and validation

Have `parseAuthChallengeFromResponse()` return `undefined` for HTTP 403 when
`WWW-Authenticate` is absent. The interceptor and passive observer already honor
an undefined result. Keep the response body available to the MCP transport so
the caller receives the actual denial.

In a clean 2.8.0 checkout, three focused regression tests failed before this
change and passed after it. The parser, interceptor, and observer tests then
passed 65/65; TypeScript build, targeted ESLint, and Prettier checks passed.
The corresponding uncommitted patch is retained in Soklet's qualification
record as `inspector-2.8.0-plain-403.patch`. This local patch is not a released
Inspector build or a completed Apps host qualification.

The [MCP authorization specification](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization#error-handling)
distinguishes HTTP 403 permission denial from a grantable
`insufficient_scope` challenge. Earlier Inspector issue
[#747](https://github.com/modelcontextprotocol/inspector/issues/747) concerns
recognizing an explicit scope challenge on the deprecated v1 line; this report
concerns a 403 without a challenge on v2.
