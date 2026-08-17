#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 5 ]]; then
	printf 'Usage: %s <java|toystoreJava> <runner-temp> <github-path> <github-env> <evidence-file>\n' "$0" >&2
	exit 64
fi

toolchain_name=$1
runner_temp=$2
github_path=$3
github_env=$4
evidence_file=$5
manifest=release/release-validation-manifest.json
helper=scripts/release-validation-evidence.mjs

case "$toolchain_name" in
	java)
		expected_major=17
		environment_name=JAVA_HOME
		;;
	toystoreJava)
		expected_major=25
		environment_name=SOKLET_RELEASE_TOYSTORE_JAVA_HOME
		;;
	*)
		printf 'Unsupported Corretto toolchain: %s\n' "$toolchain_name" >&2
		exit 64
		;;
esac

manifest_value() {
	node "$helper" value "$manifest" "toolchains.$toolchain_name.$1"
}

java_version=$(manifest_value version)
runtime_version=$(manifest_value runtimeVersion)
vendor_version=$(manifest_value vendorVersion)
distribution=$(manifest_value distribution)
archive=$(manifest_value archive)
archive_sha256=$(manifest_value archiveSha256)
distribution_url=$(manifest_value distributionUrl)
distribution_version=${vendor_version#Corretto-}

[[ "$distribution" == "corretto" ]] \
	|| { printf 'Pinned Java distribution must be Corretto.\n' >&2; exit 1; }
[[ "$java_version" =~ ^${expected_major}\.0\.[0-9]+$ ]] \
	|| { printf 'Invalid pinned Java version: %s\n' "$java_version" >&2; exit 1; }
[[ "$vendor_version" =~ ^Corretto-${expected_major}\.0\.[0-9]+\.[0-9]+\.[0-9]+$ ]] \
	|| { printf 'Invalid pinned Corretto build: %s\n' "$vendor_version" >&2; exit 1; }

IFS=. read -r release_major release_minor release_security release_build release_package \
	<<< "$distribution_version"
[[ "$java_version" == "$release_major.$release_minor.$release_security" \
		&& "$runtime_version" == "$java_version+$release_build-LTS" \
		&& "$release_package" =~ ^[0-9]+$ ]] \
	|| { printf 'Pinned Corretto version fields are inconsistent.\n' >&2; exit 1; }

expected_archive="amazon-corretto-$distribution_version-linux-x64.tar.gz"
expected_url="https://corretto.aws/downloads/resources/$distribution_version/$expected_archive"
[[ "$archive" == "$expected_archive" ]] \
	|| { printf 'Pinned Corretto archive does not match its build.\n' >&2; exit 1; }
[[ "$archive_sha256" =~ ^[0-9a-f]{64}$ ]] \
	|| { printf 'Pinned Corretto SHA-256 is malformed.\n' >&2; exit 1; }
[[ "$distribution_url" == "$expected_url" ]] \
	|| { printf 'Pinned Corretto distribution URL is not canonical.\n' >&2; exit 1; }

staging_root="$runner_temp/soklet-release-$toolchain_name-$distribution_version"
archive_path="$staging_root/$archive"
java_home="$staging_root/amazon-corretto-$distribution_version-linux-x64"
[[ ! -e "$staging_root" ]] \
	|| { printf 'Pinned Corretto staging directory already exists: %s\n' "$staging_root" >&2; exit 1; }

mkdir -p "$staging_root"
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
	"$distribution_url" --output "$archive_path"
printf '%s  %s\n' "$archive_sha256" "$archive_path" \
	| sha256sum --check --strict
tar -xzf "$archive_path" -C "$staging_root"

[[ -x "$java_home/bin/java" && -x "$java_home/bin/javac" ]] \
	|| { printf 'Extracted Corretto JDK executables are missing.\n' >&2; exit 1; }

java_property() {
	"$java_home/bin/java" -XshowSettings:properties -version 2>&1 \
		| sed -n "s/^[[:space:]]*$1 = //p" | head -n 1
}

actual_version=$(java_property java.version)
actual_runtime_version=$(java_property java.runtime.version)
actual_vendor=$(java_property java.vendor)
actual_vendor_version=$(java_property java.vendor.version)
actual_javac_version=$("$java_home/bin/javac" -version 2>&1)
[[ "$actual_version" == "$java_version" \
		&& "$actual_runtime_version" == "$runtime_version" \
		&& "$actual_vendor" == "Amazon.com Inc." \
		&& "$actual_vendor_version" == "$vendor_version" \
		&& "$actual_javac_version" == "javac $java_version" ]] \
	|| { printf 'Extracted Corretto JDK identity does not match the reviewed pin.\n' >&2; exit 1; }

if [[ "$toolchain_name" == "java" ]]; then
	printf '%s\n' "$java_home/bin" >> "$github_path"
fi
printf '%s=%s\n' "$environment_name" "$java_home" >> "$github_env"
printf 'distribution=%s\nversion=%s\nruntimeVersion=%s\nvendorVersion=%s\nurl=%s\narchive=%s\narchiveSha256=%s\n' \
	"$distribution" "$java_version" "$runtime_version" "$vendor_version" \
	"$distribution_url" "$archive" "$archive_sha256" > "$evidence_file"
printf 'Verified %s from %s.\n' "$vendor_version" "$archive"
