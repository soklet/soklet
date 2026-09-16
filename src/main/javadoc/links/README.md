# Pinned external Javadoc indexes

These package/element indexes are inputs to Javadoc's `-linkoffline` option.
The generated hyperlinks still point to public documentation, but the build
does not fetch those sites to discover packages. `manifest.json` records the
upstream versioned archive (or Java API index URL), its SHA-256, and the SHA-256
of each checked-in index. Indexes use UTF-8 and LF line endings; when upstream
line endings differ, its original index checksum is recorded separately.

Run `node scripts/verify-javadoc-links.mjs` from the repository root to verify
the local checksums. When upgrading a dependency, extract the index from that
exact version's official Maven Central Javadoc JAR, update the POM's link target
and local directory, and record the new provenance and checksums. Do not fetch a
mutable "latest" index during release packaging.

Companion releases additionally pass
`-Dsoklet.javadoc.location=/absolute/path/to/unpacked-core-javadoc-jar`.
That directory must come from the exact core candidate's packaged Javadoc JAR,
not the currently deployed website. It is intentionally not vendored here.
Java API links currently target Java 26; the library's compile/runtime floor
remains Java 17.
