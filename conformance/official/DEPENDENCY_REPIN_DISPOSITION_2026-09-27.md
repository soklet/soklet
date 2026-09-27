# Conformance dependency repin disposition — 2026-09-27

**Recommended for owner acceptance with this commit.** The conformance gate may
be marked `READY` for an exact release-candidate run using the reviewed
alpha.11 dependency overlay. `READY` does not mean that conformance passed or
that publication is approved. The owner commit containing this disposition,
the gate change, and the audit check records acceptance of this narrow residual
risk; until then the existing blocked gate remains authoritative.

The original upstream source stays pinned to commit
`a983ba93c91e0bb31d0b6849eeb52f0ad1083107`. The only install overlay is
the candidate-tracked lock with SHA-256
`4bbf44df937f30f99f56dcb359ec5ca67c8200241b279f49f25d4b646e38fa1f`.
The runner verifies the pristine upstream tree before applying it, installs
with `npm ci --ignore-scripts` using pinned Node 26.5.0/npm 11.17.0, restores
the original lock, and verifies the original tree and built CLI. The proposed
overlay and its earlier Linux build, scenario listing, and negative checks are
documented in [the exact repin review](proposals/alpha11-dependency-repin-2026-09-23/REVIEW.md).

Fresh public npm audits on September 27 used those exact lock bytes. The
runtime-only report has **zero** affected packages. The full report has exactly
one **low** finding: `esbuild` 0.27.4,
[GHSA-g7r4-m6w7-qqqr](https://github.com/advisories/GHSA-g7r4-m6w7-qqqr).
It is a development dependency; the advisory concerns the development server
on Windows. The selected candidate gate builds and runs the CLI on Linux and
does not start that server. The reviewed residual risk is limited to that
finding and that use. It is not a general waiver for `esbuild`, another
platform, another conformance command, or a new advisory.

The release runner now retains both fresh candidate-run audit reports. Its
verifier requires zero runtime findings and exactly that single low development
finding; any changed count, package, or advisory fails the gate. The two audits
run before the CLI build, and the candidate evidence records their hashes and
the overlay hash. These checks establish the toolchain disposition only.

The separate [P0-C owner decision](P0C_CHECK_DISPOSITION_2026-09-22.md) is
already integrated into the release runner. It preserves the two raw official
`FAILURE` rows and exit 1 while requiring the independent Elicitation control;
its final result is explicitly `PASSED_WITH_REVIEWED_EXCEPTION`, never an
unqualified official pass. The complete selected conformance run and all other
candidate gates must still execute against the exact owner-committed candidate.
