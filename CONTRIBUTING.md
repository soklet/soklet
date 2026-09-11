## How To Contribute
 
#### Basics

Pull requests and bug reports are welcomed.  For enhancement pull requests, please ask first to save time!  It's possible the proposed enhancement is outside the scope or design goals of the project.

#### Local Installation

```shell
$ mvn install
```

This will build artifacts and install to your local system's Maven repository.
Both `mvn verify` and `mvn install` invoke repository verifier scripts through
`node`. The reviewed toolchain uses Node.js 26.5.0; the official MCP
conformance toolchain also pins npm 11.17.0 in
`conformance/official/upstream-pins.json`.

#### Benchmarks

Soklet's formal microbenchmarks live in `benchmarks/` and use JMH. If a pull request may affect request parsing, response writing, routing, allocation behavior, or other hot paths, run the relevant benchmarks and include enough before/after results to show that it does not introduce a performance regression. See `BENCHMARKS.md` for build, run, and reporting guidance.

#### Publishing to Maven Central

Publishing is a project-owner operation, not the last step of an ordinary
contributor build. Do not run `mvn deploy` for a release: it rebuilds and
uploads outside Soklet's candidate-validation and no-rebuild promotion chain.

The authoritative maintainer procedure is the
[G5 release-promotion runbook](release/G5_RELEASE_RUNBOOK.md), with the exact
core signing, upload, recovery, and post-publication commands in
[No-rebuild release promotion](release/PROMOTION.md). G5 requires explicit
project-owner approval and consumes the four already-built artifacts plus the
completed, checksum-bound release-validation evidence. Any source,
documentation, version, pin, artifact, or receipt change requires a new
candidate; promotion never repairs or rebuilds one.

Central credentials are supplied only through the private mode-0600 token file
required by the promotion tooling. Unlock the exact approved signing key
through `gpg-agent`; do not place Portal credentials or a GPG passphrase in a
command line, environment variable, Maven settings file, project file, log, or
release evidence. The tool uploads a `USER_MANAGED` bundle and cannot publish
it. An authorized maintainer performs the irreversible publish action in the
Central Portal only after validation, then runs the documented
`verify-published` mode against the public bytes.

Snapshot publishing, if introduced, must have a separately reviewed procedure
and must use only `-SNAPSHOT` coordinates. The commands above do not authorize
or describe snapshot deployment.
