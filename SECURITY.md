# Security Policy

## Reporting a Vulnerability

Please report suspected vulnerabilities privately by emailing security@revetware.com.

Include the affected Soklet version, a concise description of the issue, and any reproduction steps or proof-of-concept details that can be shared safely. Please do not open a public GitHub issue for suspected vulnerabilities until we have coordinated disclosure.

You should receive an acknowledgment within 3 business days. We will work with you on a coordinated disclosure timeline appropriate to the severity of the issue.

## Supported Versions

Security fixes are prioritized for the latest released version of `com.soklet:soklet`. Snapshot builds are development artifacts and may change without notice.

## Scope

Soklet has zero runtime dependencies, so vulnerabilities in the released artifact are limited to first-party code. Reports against the embedded HTTP, SSE, and MCP transports — including request parsing, connection lifecycle, and resource-limit enforcement — are especially appreciated.
