# Artifact preparation and publication verification

Publication tooling takes the four already-built artifacts directly. It requires
no release-validation receipts, reviewed manifest, source freeze, version census,
formal approval record, or committed candidate. It does not compile, rebuild,
generate Javadocs, or publish automatically. See [current project policy](README.md).

The four modes are:

1. `prepare`: offline artifact checks, signing and signature verification,
   checksum generation, and deterministic Maven-layout bundle creation.
2. `upload`: explicitly upload that bundle for Central `USER_MANAGED` validation.
3. `status`: resume checking an existing deployment without uploading again.
4. `verify-published`: after the user publishes, check that public artifacts
   match the selected artifact bytes.

The user controls signing, upload, publication, tags, and deployments. No G4/G5
record is required. The main website and generated Javadoc sites are maintained
independently of these tools.

## Offline preparation

Build and test the intended source with Java 21 and a separate Java 26 Javadoc
toolchain first. Use the JDK 21 publication inputs from **Release Verification**
or an equivalent local build; the JDK 25 leg tests compatibility. Validation
prints SHA-256 values for the four files. Compare those values with the artifact
hashes in `promotion-preparation.json` before upload.

Select the POM, main JAR, sources JAR,
and Javadoc JAR you intend to publish. Preparation verifies the expected filenames,
`com.soklet:soklet:4.0.0` coordinates, JAR/ZIP headers, and the selected bytes.
It signs staged copies, verifies signatures, and checks that signing did not
change the original artifact bytes.

The signer path must be absolute and identify a regular executable. Supply the
full 40- or 64-character fingerprint of the intended key or subkey, with the key
already available to the GPG agent. Passphrases are not command-line arguments.

```sh
scripts/promote-release-candidate.sh prepare \
  --pom /path/to/pom.xml \
  --main-jar /path/to/soklet-4.0.0.jar \
  --sources-jar /path/to/soklet-4.0.0-sources.jar \
  --javadoc-jar /path/to/soklet-4.0.0-javadoc.jar \
  --signing-fingerprint <full-fingerprint> \
  --gpg /absolute/path/to/gpg \
  --output-directory /secure/output/soklet-4.0.0-promotion
```

`--candidate-commit <40-lowercase-hex>` is optional provenance metadata. It is
not authenticated against Git and does not constrain the source working tree.
The output directory must be new. A successful preparation produces:

- `soklet-4.0.0-central-bundle.zip`: four artifacts, detached signatures, and
  MD5, SHA-1, SHA-256, and SHA-512 checksum files under the Maven coordinates;
- `promotion-preparation.json`: artifact, signature, checksum, and bundle hashes,
  signing fingerprint, optional source commit, and observed tool metadata.

Tool hashes are recorded for information; changing the helper does not freeze
later upload or verification. The ZIP has sorted entries and fixed timestamps,
permissions, and flags. Existing version-1 preparations remain readable without
revalidating their historical release-evidence references.

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
  --preparation /secure/output/soklet-4.0.0-promotion/promotion-preparation.json \
  --preparation-sha256 <preparation-sha256> \
  --bundle /secure/output/soklet-4.0.0-promotion/soklet-4.0.0-central-bundle.zip \
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
  --preparation /secure/output/soklet-4.0.0-promotion/promotion-preparation.json \
  --preparation-sha256 <preparation-sha256> \
  --bundle /secure/output/soklet-4.0.0-promotion/soklet-4.0.0-central-bundle.zip \
  --accepted-evidence /secure/output/central-upload-accepted.json \
  --accepted-evidence-sha256 <accepted-evidence-sha256> \
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
  --preparation /secure/output/soklet-4.0.0-promotion/promotion-preparation.json \
  --preparation-sha256 <preparation-sha256> \
  --bundle /secure/output/soklet-4.0.0-promotion/soklet-4.0.0-central-bundle.zip \
  --upload-evidence /secure/output/central-upload-evidence.json \
  --upload-evidence-sha256 <upload-evidence-sha256> \
  --output /secure/output/central-published-evidence.json \
  --timeout-seconds 900 \
  --poll-interval-seconds 5
```

Keep the preparation, accepted deployment, terminal status, published-byte
verification, and bundle files if you need to resume or inspect an upload. No
release manifest or historical workflow evidence directory is required.
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
