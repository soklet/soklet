#!/usr/bin/env bash

set -euo pipefail

usage() {
	printf 'Usage: scripts/validate-release-candidate.sh\n'
	printf '\nRuns the current working tree through Maven tests and artifact packaging.\n'
	printf 'Use a supported build JDK and set SOKLET_JAVADOC_HOME for Javadoc generation.\n'
	printf 'Signing and publication are separate actions.\n'
}

if [[ $# -gt 0 ]]; then
	if [[ $# -eq 1 && ( $1 == --help || $1 == -h ) ]]; then
		usage
		exit 0
	fi
	usage >&2
	exit 64
fi

script_directory=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
project_root=$(CDPATH= cd -- "$script_directory/.." && pwd -P)
maven_executable=${SOKLET_VALIDATION_MAVEN:-mvn}

command -v "$maven_executable" >/dev/null 2>&1 || {
	printf 'Validation failed: Maven was not found: %s\n' "$maven_executable" >&2
	exit 1
}

# The POM runs ${env.SOKLET_JAVADOC_HOME}/bin/javadoc while packaging, after the whole test suite.
javadoc_home=${SOKLET_JAVADOC_HOME:-}
[[ -n "$javadoc_home" && -f "$javadoc_home/bin/javadoc" && -x "$javadoc_home/bin/javadoc" ]] || {
	printf 'Validation failed: SOKLET_JAVADOC_HOME must name a JDK that contains bin/javadoc.\n' >&2
	exit 1
}

if command -v sha256sum >/dev/null 2>&1; then
	sha256_command=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
	sha256_command=(shasum -a 256)
else
	printf 'Validation failed: SHA-256 reporting requires sha256sum or shasum.\n' >&2
	exit 1
fi

cd "$project_root"
printf 'Running tests and packaging the current working tree.\n'
"$maven_executable" -B -ntp -Dgpg.skip=true -DskipTests=false -Dmaven.test.skip=false clean verify

artifacts=(
	pom.xml
	target/soklet-4.0.0.jar
	target/soklet-4.0.0-sources.jar
	target/soklet-4.0.0-javadoc.jar
)
for artifact in "${artifacts[@]}"; do
	[[ -f "$artifact" && -s "$artifact" ]] || {
		printf 'Validation failed: expected artifact is missing or empty: %s\n' "$artifact" >&2
		exit 1
	}
done

printf '\nArtifact SHA-256 values:\n'
"${sha256_command[@]}" "${artifacts[@]}"
printf 'Validation passed. Build outputs are in %s/target.\n' "$project_root"
