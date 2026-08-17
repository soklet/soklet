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

go_version=$(manifest_value toolchains.go.version)
archive=$(manifest_value toolchains.go.archive)
archive_sha256=$(manifest_value toolchains.go.archiveSha256)
distribution_url=$(manifest_value toolchains.go.distributionUrl)
staging_root="$runner_temp/soklet-release-go-$go_version"
archive_path="$staging_root/$archive"

[[ "$go_version" =~ ^1\.25\.[0-9]+$ ]] \
	|| { printf 'Invalid pinned Go version: %s\n' "$go_version" >&2; exit 1; }
[[ "$archive" == "go$go_version.linux-amd64.tar.gz" ]] \
	|| { printf 'Pinned Go archive does not match its version.\n' >&2; exit 1; }
[[ "$archive_sha256" =~ ^[0-9a-f]{64}$ ]] \
	|| { printf 'Pinned Go SHA-256 is malformed.\n' >&2; exit 1; }
[[ "$distribution_url" == "https://go.dev/dl/$archive" ]] \
	|| { printf 'Pinned Go distribution URL is not canonical.\n' >&2; exit 1; }
[[ ! -e "$staging_root" ]] \
	|| { printf 'Pinned Go staging directory already exists: %s\n' "$staging_root" >&2; exit 1; }

mkdir -p "$staging_root"
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
	"$distribution_url" --output "$archive_path"
printf '%s  %s\n' "$archive_sha256" "$archive_path" \
	| sha256sum --check --strict
tar -xzf "$archive_path" -C "$staging_root"

go_bin="$staging_root/go/bin"
[[ -x "$go_bin/go" ]] \
	|| { printf 'Extracted Go executable is missing.\n' >&2; exit 1; }
export PATH="$go_bin:$PATH"
actual_version=$(go version)
[[ "$actual_version" == "go version go$go_version linux/amd64" ]] \
	|| { printf 'Extracted Go identity is %s; expected go%s linux/amd64.\n' "$actual_version" "$go_version" >&2; exit 1; }

printf '%s\n' "$go_bin" >> "$github_path"
printf 'version=%s\nurl=%s\narchive=%s\narchiveSha256=%s\n' \
	"$go_version" "$distribution_url" "$archive" "$archive_sha256" \
	> "$evidence_file"
printf 'Verified Go %s from %s.\n' "$go_version" "$archive"
