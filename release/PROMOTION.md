# No-rebuild release promotion

Soklet's promotion tooling is deliberately separate from candidate validation
and ordinary CI. It consumes the completed canonical release-validation
evidence and the four already-built unsigned artifacts. It cannot compile,
package, generate Javadocs, or invoke a build lifecycle.

The four modes are intentionally distinct:

1. `prepare` is offline. It verifies the independently supplied validation-
   evidence SHA-256, separately pinned reviewed release-manifest SHA-256,
   candidate commit, fixed `com.soklet:soklet:3.6.0` coordinates, exact
   13-gate PASS set and gate pins, reviewed workflow identity, and all four
   artifact names, sizes, and SHA-256 values. It stages copies of those exact
   bytes, creates and verifies
   four detached armored signatures with one explicit full GPG fingerprint,
   writes MD5, SHA-1, SHA-256, and SHA-512 files for the four base artifacts
   only, and creates a deterministic Maven-layout ZIP plus canonical evidence.
2. `upload` is an explicit network operation. It atomically reserves both
   evidence outputs before the request, uploads that exact ZIP to the fixed
   Publisher Portal `USER_MANAGED` endpoint, accepts only HTTP 201 plus a
   deployment UUID, and durably journals the UUID before its first poll. It
   then polls only through `PENDING`/`VALIDATING` to `VALIDATED` or `FAILED`.
   It cannot publish the deployment.
3. `status` resumes polling from the checksum-verified accepted-deployment
   receipt. It never uploads, so a timeout or interruption after HTTP 201 must
   be recovered with `status`, never by rerunning `upload`.
4. `verify-published` is a later explicit read-only operation. After a
   maintainer publishes in the Central UI, it waits through `VALIDATED` and
   `PUBLISHING` for `PUBLISHED`, then downloads the POM, main JAR, sources JAR,
   and Javadocs JAR from Central and rechecks their recorded sizes and SHA-256
   values. It also cannot publish a deployment.

The implementation follows Central's [Publisher API contract][publisher-api],
[bundle upload layout][bundle-upload], and [signature/checksum
requirements][requirements]. Central publication itself is an irreversible
maintainer action in the Portal UI and is outside this tooling.

## Offline preparation

Obtain the canonical `release-validation-evidence.json`, its SHA-256 through a
separate trusted channel (for example the immutable workflow artifact digest),
and the four artifact files from the same completed validation run. Do not use
a hash copied out of an untrusted evidence download as the independent value.

The signer path must be absolute and must identify a regular nonsymlink
executable. The fingerprint must be the full 40- or 64-hexadecimal fingerprint
of the exact signing key or subkey. The command uses GPG's noninteractive mode;
unlock the key through the GPG agent before running it. No passphrase option is
accepted. The reviewed manifest pins the exact helper and wrapper paths and
SHA-256 values; preparation verifies the currently executing files against
those pins and records them.

```sh
scripts/promote-release-candidate.sh prepare \
  --evidence /secure/input/release-validation-evidence.json \
  --evidence-sha256 <independently-recorded-64-lowercase-hex> \
  --release-manifest /reviewed/candidate/release/release-validation-manifest.json \
  --release-manifest-sha256 <independently-reviewed-manifest-sha256> \
  --candidate-commit <40-lowercase-hex> \
  --pom /secure/input/pom.xml \
  --main-jar /secure/input/soklet-3.6.0.jar \
  --sources-jar /secure/input/soklet-3.6.0-sources.jar \
  --javadoc-jar /secure/input/soklet-3.6.0-javadoc.jar \
  --signing-fingerprint <full-fingerprint> \
  --gpg /absolute/path/to/gpg \
  --output-directory /secure/output/soklet-3.6.0-promotion
```

The output directory must not already exist. A successful run creates only:

- `soklet-3.6.0-central-bundle.zip`, containing the four Maven-layout base
  artifacts, four `.asc` files, and four checksum files per base artifact; and
- `promotion-preparation.json`, canonically recording the validation evidence,
  commit, coordinates, signing fingerprint, every artifact/signature/checksum
  hash, reviewed release-manifest hash, the executed helper/wrapper names,
  sizes, and hashes, every ZIP entry hash, and the final bundle hash.

The ZIP writer stores entries in byte-sorted order with fixed ZIP timestamps,
permissions, flags, and no extras or comments. Its bytes are deterministic for
the exact artifact, signature, and checksum entry bytes from that preparation
run. The base artifact bytes are rehashed after signing so a signer cannot
silently mutate them.

## Explicit USER_MANAGED upload

Create a private regular file containing the canonical Base64 Publisher Portal
user token (`base64(username:password)`) on one line and restrict it to the
current user. The token itself is read only from the file named by the
environment; it is never accepted on the command line, printed, or placed in
evidence.

```sh
chmod 600 /secure/credentials/central-token
SOKLET_CENTRAL_TOKEN_FILE=/secure/credentials/central-token \
  scripts/promote-release-candidate.sh upload \
  --preparation /secure/output/soklet-3.6.0-promotion/promotion-preparation.json \
  --preparation-sha256 <reviewed-preparation-sha256> \
  --bundle /secure/output/soklet-3.6.0-promotion/soklet-3.6.0-central-bundle.zip \
  --accepted-output /secure/output/central-upload-accepted.json \
  --output /secure/output/central-upload-evidence.json \
  --timeout-seconds 900 \
  --poll-interval-seconds 5
```

The tool revalidates the canonical preparation and every deterministic ZIP
entry before sending anything. The only upload URL is:

```text
https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED
```

`central-upload-accepted.json` is atomically written and synced immediately
after HTTP 201 and before the first status request. If polling times out, is
interrupted, or receives a transient error, preserve this receipt and do not
rerun `upload`. Resume the same deployment ID instead:

```sh
SOKLET_CENTRAL_TOKEN_FILE=/secure/credentials/central-token \
  scripts/promote-release-candidate.sh status \
  --preparation /secure/output/soklet-3.6.0-promotion/promotion-preparation.json \
  --preparation-sha256 <reviewed-preparation-sha256> \
  --bundle /secure/output/soklet-3.6.0-promotion/soklet-3.6.0-central-bundle.zip \
  --accepted-evidence /secure/output/central-upload-accepted.json \
  --accepted-evidence-sha256 <reviewed-accepted-evidence-sha256> \
  --output /secure/output/central-upload-evidence.json \
  --timeout-seconds 900 \
  --poll-interval-seconds 5
```

On `VALIDATED`, inspect the deployment in the Portal and perform the final
publish action there. There is no script mode, URL constant, or HTTP path that
invokes Central's publish endpoint. On `FAILED`, canonical terminal evidence
is still written with the deployment ID and state, and the command exits
unsuccessfully.

## Verify after UI publication

Only after the maintainer's Portal action, run the separate verifier. It
requires independently supplied SHA-256 values for both prior canonical
records and the same private status credential:

```sh
SOKLET_CENTRAL_TOKEN_FILE=/secure/credentials/central-token \
  scripts/promote-release-candidate.sh verify-published \
  --preparation /secure/output/soklet-3.6.0-promotion/promotion-preparation.json \
  --preparation-sha256 <reviewed-preparation-sha256> \
  --bundle /secure/output/soklet-3.6.0-promotion/soklet-3.6.0-central-bundle.zip \
  --upload-evidence /secure/output/central-upload-evidence.json \
  --upload-evidence-sha256 <reviewed-upload-evidence-sha256> \
  --output /secure/output/central-published-evidence.json \
  --timeout-seconds 900 \
  --poll-interval-seconds 5
```

Archive all four canonical JSON records (preparation, accepted deployment,
terminal validation, and published verification), their independently recorded
hashes, the bundle, the reviewed release manifest, the original workflow
evidence, and the four validation artifacts.
A mismatch, unexpected Central state, redirect, timeout, extra ZIP entry,
credential-permission issue, or existing output target fails closed.

## Safe local checks

These checks are dependency-free and perform no build, network, real signing,
upload, or publication:

```sh
node --check scripts/release-promotion.mjs
node --check scripts/release-promotion-self-test.mjs
node scripts/release-promotion-self-test.mjs
bash -n scripts/promote-release-candidate.sh
```

[publisher-api]: https://central.sonatype.org/publish/publish-portal-api/
[bundle-upload]: https://central.sonatype.org/publish/publish-portal-upload/
[requirements]: https://central.sonatype.org/publish/requirements/
