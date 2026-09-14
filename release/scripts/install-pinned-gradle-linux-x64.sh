#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 3 ]]; then
	printf 'Usage: %s <runner-temp> <github-path> <evidence-file>\n' "$0" >&2
	exit 64
fi

runner_temp=$1
github_path=$2
evidence_file=$3
for path in "$runner_temp" "$github_path" "$evidence_file"; do
	[[ "$path" == /* ]] || { printf 'Installer paths must be absolute.\n' >&2; exit 1; }
done

# The Gradle distribution is platform-neutral; this installer is used by the
# Linux x64 consumer matrix with the independently pinned Corretto runtimes.
# Keep this reviewed pin aligned with verification/consumer-build/README.md.
gradle_version=9.1.0
archive="gradle-$gradle_version-bin.zip"
archive_sha256=a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806
distribution_url="https://services.gradle.org/distributions/$archive"
staging_root="$runner_temp/soklet-release-gradle-$gradle_version"
archive_path="$staging_root/$archive"
gradle_bin="$staging_root/gradle-$gradle_version/bin"

[[ ! -e "$staging_root" && ! -L "$staging_root" ]] \
	|| { printf 'Pinned Gradle staging directory already exists.\n' >&2; exit 1; }
[[ ! -e "$evidence_file" && ! -L "$evidence_file" ]] \
	|| { printf 'Pinned Gradle evidence must be a create-new path.\n' >&2; exit 1; }
mkdir -p "$staging_root"
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
	--retry 3 "$distribution_url" --output "$archive_path"
printf '%s  %s\n' "$archive_sha256" "$archive_path" \
	| sha256sum --check --strict
# No archive member or executable is used before the checksum succeeds.
unzip -q "$archive_path" -d "$staging_root"
[[ -f "$gradle_bin/gradle" && -x "$gradle_bin/gradle" && ! -L "$gradle_bin/gradle" ]] \
	|| { printf 'Extracted Gradle executable is missing or unsafe.\n' >&2; exit 1; }
actual_version=$(GRADLE_USER_HOME="$staging_root/version-cache" \
	"$gradle_bin/gradle" --offline --no-daemon --version \
	| sed -n 's/^Gradle \([^ ]*\)$/\1/p')
[[ "$actual_version" == "$gradle_version" ]] \
	|| { printf 'Extracted Gradle version differs from the reviewed pin.\n' >&2; exit 1; }

printf '%s\n' "$gradle_bin" >> "$github_path"
printf 'version=%s\nurl=%s\narchive=%s\narchiveSha256=%s\n' \
	"$gradle_version" "$distribution_url" "$archive" "$archive_sha256" \
	> "$evidence_file"
printf 'Verified Gradle %s from %s.\n' "$gradle_version" "$archive"
