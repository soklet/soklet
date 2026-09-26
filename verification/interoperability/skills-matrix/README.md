# Skills operational host matrix

This development probe compiles a disposable, public-API-only Soklet listener
against a packaged candidate JAR. It runs the **unmodified Inspector 2.7.0 CLI**
against that listener using an isolated client home and static synthetic bearer
credentials. It does not modify Inspector, use saved user authentication, or
contact an external service.

Use Node 26.5.0, JDK 17+, and the original pinned dependency installation from
[`../inspector/dependency-review.md`](../inspector/dependency-review.md). The
candidate POM must match the copy embedded in its JAR. The work directory must
be new and outside the source tree.

```sh
node verification/interoperability/skills-matrix/run.mjs \
  --candidate-jar /absolute/path/to/soklet-candidate.jar \
  --candidate-pom /absolute/path/to/original-pom.xml \
  --java /absolute/path/to/jdk/bin/java \
  --dependencies /absolute/path/to/original-locked-install \
  --work-dir /absolute/path/to/new-run-directory
```

The exact matrix checks:

- Inspector walks two `skills/list` pages for each admitted caller. The English
  caller sees the English variant even with a French `Accept-Language` header;
  the French caller sees the French variant with an English header. A caller
  without Skill access sees an empty list.
- Inspector verifies all four listed Skills, including a parent and nested
  child that share two file URIs and byte-identical content. Exact `skills/get`
  and `resources/read` from the opposite request language retain the admitted
  caller's variant. An authorized, unlisted Skill remains available by exact
  URI even though it is absent from both callers' lists.
- Direct protocol controls show that the first page contains only the common
  overview; the second page requires a caller-bound, single-use cursor. Another
  caller and a duplicate continuation receive an invalid-cursor error.
- Direct `skills/get` and `resources/read` recheck access. After the fixture
  revokes an admitted credential, its next list and file-read requests and an
  Inspector list attempt are denied.
- A bounded local observer changes one returned root document after Soklet
  sends it. Inspector's `skills/get --verify` must reject both the changed
  digest and frontmatter. The fixture's stored bytes stay intact.

The script checks the original Inspector dependency tree, candidate/POM match,
public-only fixture compilation, class dependencies, clean shutdown, isolated
client-state removal, and unchanged inputs. Each child has a process-group
supervisor: client and command runs have 30-second defaults, compilation and
dependency checks have 60-second bounds, and the fixture has a 120-second bound.
The ready/revoked controls have ten-second waits; direct requests have five-second
abort deadlines and one-MiB response bounds. The one-shot observer accepts at
most twelve loopback requests with five-second socket and upstream deadlines,
64-KiB request bodies, and one-MiB response bodies. The retained receipt contains no
bearer credential, cursor, raw Skill text, or response body.

This is scoped retrieval, pagination, per-request authorization, and variant
selection evidence for the released CLI. It does not establish agent activation,
user consent, instruction execution, all host implementations, production OAuth,
general YAML compatibility, or an immutable release-candidate gate result.
