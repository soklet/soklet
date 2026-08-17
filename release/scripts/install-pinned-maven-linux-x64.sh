#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 3 ]]; then
	printf 'Usage: %s <runner-temp> <github-path> <evidence-file>\n' "$0" >&2
	exit 64
fi

runner_temp=$1
github_path=$2
evidence_file=$3
manifest=release/release-validation-manifest.json
helper=scripts/release-validation-evidence.mjs

manifest_value() {
	node "$helper" value "$manifest" "$1"
}

maven_version=$(manifest_value toolchains.maven.version)
archive=$(manifest_value toolchains.maven.archive)
archive_sha512=$(manifest_value toolchains.maven.archiveSha512)
distribution_url=$(manifest_value toolchains.maven.distributionUrl)
staging_root="$runner_temp/soklet-release-maven-$maven_version"
archive_path="$staging_root/$archive"

[[ "$maven_version" =~ ^3\.9\.[0-9]+$ ]] \
	|| { printf 'Invalid pinned Maven version: %s\n' "$maven_version" >&2; exit 1; }
[[ "$archive" == "apache-maven-$maven_version-bin.tar.gz" ]] \
	|| { printf 'Pinned Maven archive does not match its version.\n' >&2; exit 1; }
[[ "$archive_sha512" =~ ^[0-9a-f]{128}$ ]] \
	|| { printf 'Pinned Maven SHA-512 is malformed.\n' >&2; exit 1; }
[[ "$distribution_url" == "https://dlcdn.apache.org/maven/maven-3/$maven_version/binaries/$archive" ]] \
	|| { printf 'Pinned Maven distribution URL is not canonical.\n' >&2; exit 1; }
[[ ! -e "$staging_root" ]] \
	|| { printf 'Pinned Maven staging directory already exists: %s\n' "$staging_root" >&2; exit 1; }

mkdir -p "$staging_root"
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
	"$distribution_url" --output "$archive_path"
printf '%s  %s\n' "$archive_sha512" "$archive_path" \
	| sha512sum --check --strict
tar -xzf "$archive_path" -C "$staging_root"

maven_bin="$staging_root/apache-maven-$maven_version/bin"
[[ -x "$maven_bin/mvn" ]] \
	|| { printf 'Extracted Maven executable is missing.\n' >&2; exit 1; }
export PATH="$maven_bin:$PATH"
actual_version=$(mvn -version | sed -n '1s/^Apache Maven \([^ ]*\).*/\1/p')
[[ "$actual_version" == "$maven_version" ]] \
	|| { printf 'Extracted Maven version is %s; expected %s.\n' "$actual_version" "$maven_version" >&2; exit 1; }

printf '%s\n' "$maven_bin" >> "$github_path"
printf 'version=%s\nurl=%s\narchive=%s\narchiveSha512=%s\n' \
	"$maven_version" "$distribution_url" "$archive" "$archive_sha512" \
	> "$evidence_file"
printf 'Verified Maven %s from %s.\n' "$maven_version" "$archive"
