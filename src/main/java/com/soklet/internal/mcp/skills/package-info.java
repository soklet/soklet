/**
 * Internal construction behind public Skills bundle/registration values.
 * Bundles own supplied byte snapshots, metadata and canonical file representations;
 * registration preflights manifests and read/get/single-entry-list wrappers.
 * This package performs no filesystem access, route publication or authorization.
 * Endpoint/page/server-metadata preflights support the runtime's final
 * request-envelope validation. No process-wide memory guarantee is provided. The pinned YAML
 * corpus has no syntax-acceptance gaps; three reviewed specification/corpus
 * discrepancies remain visible as raw mismatches. Broader compatibility and
 * long-duration fuzz qualification remain pending. Lifetime-wide shared memory
 * accounting is deferred; application-retained values use ordinary Java lifetime.
 *
 * @author <a href="https://www.revetkn.com">Mark Allen</a>
 */
package com.soklet.internal.mcp.skills;
