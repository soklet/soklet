# G5 release-promotion runbook

> **DO NOT EXECUTE THIS RUNBOOK WITHOUT EXPLICIT G5 APPROVAL FROM THE PROJECT
> OWNER.** G4 approval, U9 completion, green CI, a signed bundle, or possession
> of credentials does not grant publication authority.

This runbook promotes one already accepted, immutable Soklet 4.0.0 candidate.
It performs no candidate repair. If any source, documentation, version, pin,
artifact, or required receipt is wrong, stop and return to U8, approve a new G4
identity, and repeat U9.

The commands below are operational instructions, not a claim that promotion
has happened. No signing, tag creation, GitHub release, Central upload,
publication, downstream publication, or deployment was performed while this
document was written.

## Frozen release decisions

These versions and labels must already be present in the G4-approved core and
downstream commits. G5 does not change them.

| Repository/output | Exact release identity | Semantic-version decision |
| --- | --- | --- |
| `soklet` | Maven `com.soklet:soklet:4.0.0`; Git tag `v4.0.0` | Major version: aggregate lifecycle, transport, simulator, MCP wire, and MCP Java API are intentionally incompatible with 3.5.1. |
| `soklet-servlet-javax` | Maven `com.soklet:soklet-servlet-javax:1.3.0`; Git tag `v1.3.0` | Minor version: the public adapter API is retained, while this release adds the reviewed Soklet 4.0.0 compatibility claim. The already-published 1.2.0 coordinate must not be reused. |
| `soklet-servlet-jakarta` | Maven `com.soklet:soklet-servlet-jakarta:1.3.0`; Git tag `v1.3.0` | Same compatibility-only minor decision as the `javax` adapter; do not reuse 1.2.0. |
| `soklet-otel` | Maven `com.soklet:soklet-otel:2.0.0`; Git tag `v2.0.0` | Major version: public removals and the six-value shutdown-outcome vocabulary are breaking changes; publishing them as 1.4.0 would violate the project policy. |
| `barebones-app` | Git tag `soklet-4.0.0`; vendored `soklet-4.0.0.jar` | Example application, not a Central artifact. The tag names the exact core compatibility release without inventing an application package version. |
| `toystore-app` | Git tag `soklet-4.0.0`; existing application version remains 1.0.0 | Example application/deployment, not a Central library. The compatibility tag binds its exact Soklet migration. |
| `soklet.com` | Deployment label and Git tag `soklet-4.0.0`; private package version remains 0.1.0 | Website deployment identity follows the core release; it is not an npm package publication. |

The adapter and OpenTelemetry coordinates above were selected after confirming
on 2026-09-01 that Central already contained adapter 1.2.0 releases and
`soklet-otel` 1.3.1. If an approved U8 downstream pin does not contain the
corresponding next version, **stop**. Do not edit it in G5 and do not select a
different version at the console.

## Required G5 authorization record

Before touching a signing key or network publication endpoint, create an
external, append-only authorization record (not a change to the candidate)
containing:

- approving owner, UTC timestamp, and the exact phrase “G5 promotion approved”;
- core candidate commit and tree IDs;
- G4 approval receipt SHA-256 and U9 acceptance-index SHA-256;
- release-validation evidence SHA-256 and reviewed release-manifest SHA-256;
- main, sources, Javadoc, and POM filenames, sizes, and SHA-256 values;
- the literal build-output timestamp and pinned build recipe/toolchain identity;
- all six downstream repository commit/tree IDs and receipt SHA-256 values;
- the version/tag table above;
- the full signing-key fingerprint approved for Maven artifacts and Git tags;
- GitHub organization/repositories, Central namespace, deployment accounts,
  website target, and four Javadoc deployment targets; and
- operator names for the reversible preparation steps and each irreversible
  publication action.

Two operators should compare this record with the retained G4/U9 records. A
missing field, mismatch, dirty checkout, non-exact version, expired receipt, or
ambiguous account is a stop condition.

## Global stop rules

Stop immediately if any of these is true:

- an input is not bound to the one approved commit/tree and artifact tuple;
- a repository is dirty, on the wrong commit, or its tag already exists with a
  different target;
- a version or coordinate is already present on Central or GitHub unexpectedly;
- a signature, checksum, source/Javadoc inventory, license, NOTICE, or SBOM-like
  inventory differs from its reviewed value;
- any required U9 receipt is not PASS for the exact candidate;
- a credential appears in a command line, log, receipt, or terminal capture;
- Central returns an unexpected state, redirect, deployment ID, or checksum;
- a public resolver returns bytes different from the approved artifact;
- a downstream resolves any core JAR other than public
  `com.soklet:soklet:4.0.0` after core publication; or
- an operator is tempted to “fix forward” by editing the approved source or
  replacing a published/tagged byte.

The scheduled fuzz, soak, and operational histories are optional post-release
monitoring. They are not release-blocking G5 receipts. Release scans and the
reviewed benchmark evidence remain required only where the approved candidate
policy names them.

## 1. Signing-key preflight and custody

Perform this on the approved signing host before preparing any artifact:

1. Verify the secret key is held in the approved encrypted store or hardware
   device, backup/recovery custody is current, and no key export is required.
2. Display the full fingerprint from the key store and compare every
   hexadecimal character with the authorization record. Never select by short
   key ID or email address.
3. Verify the primary key and signing subkey are not expired or revoked, carry
   the expected signing capability, and have an approved public-key
   distribution path.
4. Confirm the host clock, GPG executable path/hash/version, Git version, and
   GPG-agent configuration. Unlock through the agent interactively; never pass
   a passphrase as a command argument or environment variable.
5. Make a detached test signature over a new nonsensitive random receipt,
   verify it with a fresh public-key-only keyring, then destroy only that test
   material according to the operator policy.
6. Verify Git is configured to create an annotated signed tag with this full
   fingerprint. Do not enable automatic signing for unrelated repositories.

Record only tool identities, public fingerprint, verification result, and
operator/time. Do not retain passphrases, agent sockets, secret-key packets, or
credentials.

## 2. Re-verify the accepted core input

From a new read-only checkout of the approved core commit:

```sh
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}
git tag --list v4.0.0
```

Require an empty status, exact recorded commit/tree, and no existing tag. Run
the read-only U9 receipt/index verifier and compare all four artifact hashes
with the authorization record. Re-list the main JAR and require
`META-INF/LICENSE` and `META-INF/NOTICE`. Re-read `CHANGELOG.md`,
`MIGRATING_TO_4_0.md`, `SECURITY.md`, and the compatibility matrix.

Do not rebuild. G5 consumes the exact four unsigned artifacts and complete
validation evidence retained by U9.

## 3. Prepare the signed core Central bundle offline

Follow [No-rebuild release promotion](PROMOTION.md), using its exact `prepare`
command and the authorization record's values. In particular:

- pass the independently recorded evidence and release-manifest SHA-256 values;
- pass the exact candidate commit and four U9 artifacts;
- use the absolute reviewed GPG executable and full fingerprint; and
- choose a new private output directory that does not already exist.

Preparation must remain offline. It verifies candidate evidence, copies exact
artifact bytes, creates and verifies detached signatures, writes required
checksums, and creates the deterministic Maven-layout bundle. Review
`promotion-preparation.json`, independently hash it and the bundle, and add
those hashes to the external G5 record.

Stop if preparation builds anything, changes a base artifact, cannot verify a
signature, finds a different candidate identity, or produces an unlisted ZIP
entry.

## 4. Upload core for Central validation, without publishing

Place the Central Publisher token in the approved mode-0600 credential file.
Use the exact `upload` command from [PROMOTION.md](PROMOTION.md). The upload is
fixed to `USER_MANAGED`; it may create one deployment and poll it to
`VALIDATED`, but cannot publish it.

If the command is interrupted or times out after HTTP 201, preserve
`central-upload-accepted.json` and use the documented `status` mode. **Never
rerun `upload` after a deployment ID has been accepted.**

At `VALIDATED`, compare the Central deployment name, coordinate, file list,
signatures, and checksums with `promotion-preparation.json`. Have the second
operator record approval. Leave the deployment unpublished while the tag and
draft release are prepared.

At `FAILED` or any unexpected state, retain the terminal evidence and stop.

## 5. Create the signed core tag and draft GitHub release

In the exact clean core checkout:

```sh
git tag -s v4.0.0 <approved-40-hex-commit> \
  -u <approved-full-signing-fingerprint> \
  -m 'Soklet 4.0.0'
git verify-tag --raw v4.0.0
git rev-list -n 1 v4.0.0
```

Require the approved commit and fingerprint. Push only that tag to the
canonical `soklet/soklet` repository, then verify the remote ref independently.
Treat the public tag as immutable; do not delete or retarget it.

Create a **draft** GitHub release for `v4.0.0`. Its user-facing notes must lead
with the concise 4.0.0 lifecycle/MCP release notes and link the migration,
quickstart, compatibility, support/EOL, OAuth-boundary, security, and NOTICE
documents. Attach only the reviewed main, sources, and Javadoc JARs plus public
checksums/signatures intended for GitHub distribution. Do not attach private
validation evidence, Central credentials, internal paths, or the Publisher
bundle unless the release policy explicitly approved it.

Using an authenticated account with access to the draft, download each draft
attachment before proceeding and compare its size/SHA-256 with the approved
tuple. Draft assets are not anonymously accessible; anonymous download
verification happens only after the release is published below.

## 6. Publish core in Central and verify synchronization

This is the first irreversible artifact-publication action. The explicitly
named owner/operator must re-read the G5 authorization and click **Publish** on
the exact `VALIDATED` deployment in the Central Portal. No script in this
repository performs that click.

Then use `verify-published` from [PROMOTION.md](PROMOTION.md). It must observe
the same deployment reach `PUBLISHED`, download all four public artifacts, and
match their sizes and SHA-256 values. Archive the preparation, accepted upload,
validated upload, and published-verification records and their independent
hashes.

Wait for normal Maven Central synchronization. From a new empty local Maven
repository, resolve without using any file/snapshot/staging repository:

```sh
mvn -B -ntp -Dmaven.repo.local=/new/empty/m2 \
  dependency:get \
  -Dartifact=com.soklet:soklet:4.0.0 \
  -Dtransitive=false
```

Hash the downloaded POM and JAR and require the approved bytes. Fetch the
sources and Javadoc coordinates directly from the public repository and do the
same. A successful local-repository resolution is not acceptable evidence.

Only after these checks pass, publish the draft GitHub release. Verify its tag,
release text, attachments, signatures, and anonymous download links.

## 7. Publish the three downstream libraries

Core public resolution is a hard dependency for this step. For each downstream
below, use its G4-pinned clean commit and owner-approved release procedure. The
version, tag, and effective core dependency must match the frozen table.

| Order | Artifact | Required public-input proof |
| ---: | --- | --- |
| 1 | `com.soklet:soklet-servlet-javax:1.3.0` | Clean default test plus the recorded exact Soklet 4.0.0 compatibility leg; no local replacement repository during the public smoke. |
| 2 | `com.soklet:soklet-servlet-jakarta:1.3.0` | Same released-default/exact-core split and public core hash proof. |
| 3 | `com.soklet:soklet-otel:2.0.0` | Exact core 4.0.0, full tests, six literal shutdown outcomes, API/release notes, and public core hash proof. |

For each library, in order:

1. verify clean commit/tree, exact POM version, no existing tag, and no existing
   Central coordinate;
2. resolve core 4.0.0 from a new clean repository and record its public hash;
3. run the repository's exact clean verify/reproducibility/signing procedure;
4. inspect license/NOTICE, POM metadata, main/sources/Javadoc artifacts,
   signatures, and checksums;
5. stage to Central under user-managed publication when available, stop at its
   validated/review boundary, and have the owner approve the exact file set;
6. create and verify the signed exact Git tag and a draft GitHub release;
7. perform the explicit Central publication action, wait for synchronization,
   and resolve the exact coordinate from a new empty repository;
8. publish the GitHub release only after public bytes match; and
9. retain build, signature, Central deployment, tag, release, and clean public-
   resolution receipts.

Do not use a generic `mvn deploy` command until its plugin behavior and publish
mode have been reviewed for that repository; it must not silently skip the
human validation boundary. If a downstream lacks a no-rebuild or user-managed
path, the G5 operator records the exact approved Maven command and its behavior
in that downstream's receipt before execution.

An adapter failure does not authorize skipping to the next public library.
Stop the downstream wave, because the website and Javadocs describe the set as
one release.

## 8. Tag and verify the example applications

After core and the three libraries resolve publicly:

1. At the exact `barebones-app` pin, require the vendored
   `soklet-4.0.0.jar` SHA-256 to equal public Central, compile with Java 17 and
   `-parameters`/`SokletProcessor`, start on loopback, probe its documented
   route, request graceful shutdown, and prove port release. Create and verify
   signed tag `soklet-4.0.0`, then publish its GitHub release.
2. At the exact `toystore-app` pin, resolve public core 4.0.0 and all public
   downstream coordinates, run its clean tests and production build, start the
   application with non-production secrets/data, execute the documented HTTP
   and MCP smoke, shut down cleanly, and prove port release. Create and verify
   signed tag `soklet-4.0.0`. Deploy only to an explicitly approved demo target;
   the tag itself is not deployment authorization.

Neither application is uploaded to Maven Central. Record the exact public
dependency hashes, tag objects, GitHub release URLs, smoke output, and any
deployment receipt.

## 9. Regenerate and deploy all four Javadoc sites

Generate from the exact public source/tag and public dependency coordinates,
never from an arbitrary working directory:

| Site | Source/tag | Published API identity |
| --- | --- | --- |
| `https://javadoc.soklet.com/` | `soklet` `v4.0.0` | `com.soklet:soklet:4.0.0` |
| `https://javax.javadoc.soklet.com/` | `soklet-servlet-javax` `v1.3.0` | `com.soklet:soklet-servlet-javax:1.3.0` |
| `https://jakarta.javadoc.soklet.com/` | `soklet-servlet-jakarta` `v1.3.0` | `com.soklet:soklet-servlet-jakarta:1.3.0` |
| `https://otel.javadoc.soklet.com/` | `soklet-otel` `v2.0.0` | `com.soklet:soklet-otel:2.0.0` |

For each site, compare generated top-level inventories and representative
class pages with the corresponding public Javadoc JAR. Run an offline link
check first. Deploy to a versioned/atomic target, smoke the preview URL, then
switch the public alias. Verify page title/version, package index, at least five
representative type links, cross-site core links, and absence of development
versions or local filesystem paths.

Retain source tag/commit, public artifact hashes, generator command/toolchain,
generated-tree hash, preview and production deployment IDs, link-check report,
and rollback target for each site.

## 10. Deploy soklet.com

The website is last so it never advertises unavailable coordinates or docs.
At its exact G4-pinned commit:

```sh
npm ci --ignore-scripts
npm run lint
npm run ssg-build
git diff --exit-code
```

Require a clean generated tree under the repository's documented generated-
artifact policy. Check that installation, direct-download, quickstart,
migration, MCP, support/EOL, servlet, OTel, Javadocs, release notes, sitemap,
and `llms.txt`/`llms-full.txt` references all name the exact public versions.

Deploy to the approved preview target, run its link/download/metadata smoke,
then atomically promote it to `https://www.soklet.com`. Create and verify signed
tag `soklet-4.0.0` only at the exact deployed commit. Retain build/generator
hashes, preview and production deployment IDs, DNS/CDN purge result if used,
tag object, and rollback target.

## 11. Post-publish smoke

Run all checks from a clean consumer environment with no Soklet artifacts in
its local dependency cache:

- resolve and hash core 4.0.0, both servlet 1.3.0 artifacts, and OTel 2.0.0;
- download core main/sources/Javadoc/POM/signatures/checksums through the public
  Central path and GitHub release, comparing exact bytes;
- copy the [MCP quickstart](../MCP_QUICKSTART.md) into a new minimal Maven
  project, compile with Java 17, start it, run raw discovery plus Inspector
  2.3.0 `tools/list` and `tools/call`, shut down, and prove port release;
- compile/run the README raw-`javac` HTTP example with only the public core JAR;
- run the barebones and ToyStore documented public-dependency smoke;
- verify the soklet.com homepage, installation, migration, MCP, security,
  servlet, OTel, license, release, sitemap, and LLM-text URLs;
- verify the four Javadoc roots and representative deep links;
- check every GitHub tag/release and anonymous attachment download; and
- record DNS resolver, HTTP status, redirect chain, content length, SHA-256,
  TLS hostname/expiry, UTC observation time, and operator for each public URL.

Repeat public coordinate resolution after the expected synchronization window.
Do not declare completion while only one CDN/resolver sees the release.

## Stop, rollback, and correction policy

| Point | Allowed response |
| --- | --- |
| Before upload/tag/deployment | Delete only newly created private preparation output according to custody policy; fix by returning to U8/U9 if candidate bytes change. |
| Central upload accepted but not published | Preserve deployment ID/evidence. Resume status rather than uploading again. If validation fails, stop; discard/close the unpublished deployment only through the documented Portal action and retain its receipt. |
| Signed tag pushed, Central still unpublished | Stop. Do not delete or retarget the public tag. Resolve the cause and use an explicitly approved correction strategy. |
| Central artifact published | Maven coordinates are immutable. Never overwrite or attempt deletion. Publish a correctly versioned follow-up release and advisory if correction is required. |
| GitHub release published | Preserve the tag and original release record. Correct prose transparently or issue a follow-up release; never replace an attachment with different bytes under the same name. |
| Website/Javadocs deployed | Roll the alias back to the exact recorded prior deployment if necessary, without changing published Maven/tag bytes. Retain both deployment receipts and publish a status note when user impact warrants it. |
| Downstream wave partly published | Do not hide the partial state. Stop later dependent deployments, record exactly what is public, correct with new semantic versions where needed, then update site/release notes honestly. |

## Final retained receipt set

G5 is complete only when one immutable index binds:

- G5 authorization and signing-key preflight;
- G4 approval and U9 candidate acceptance;
- core preparation, accepted deployment, validation, publication, public-byte
  verification, signed tag, GitHub release, and clean consumer smoke;
- equivalent tag/publication/public-resolution records for three downstream
  libraries;
- exact tag/smoke/deployment records for barebones and ToyStore;
- website tag, build, preview, production, and link/download records;
- all four Javadoc source/artifact/generation/deployment/link records;
- every public URL/coordinate hash and observation time;
- any stop, partial-publication, rollback, correction, or advisory decision;
  and
- a final owner sign-off naming the exact receipt-index SHA-256.

Store secrets nowhere in this set. Preserve the index and records in the
approved durable release archive; do not check generated operational receipts
into the frozen candidate commit.
