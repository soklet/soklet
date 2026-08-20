#!/usr/bin/env bash

set -euo pipefail
umask 077
export GIT_TERMINAL_PROMPT=0

fail() {
	printf 'Release-candidate validation failed: %s\n' "$*" >&2
	exit 1
}

usage() {
	printf 'Usage: %s <candidate-commit> [release-validation-manifest]\n' "$0" >&2
	exit 64
}

if [[ $# -lt 1 || $# -gt 2 ]]; then
	usage
fi

candidate_commit=$1
[[ "$candidate_commit" =~ ^[0-9a-f]{40}$ ]] \
	|| fail "candidate commit must be a full lowercase SHA."

for command in cmp curl find git go grep java javac mvn node npm sha256sum sort tee timeout; do
	command -v "$command" >/dev/null 2>&1 \
		|| fail "$command was not found on PATH."
done

project_root=$(git rev-parse --show-toplevel 2>/dev/null) \
	|| fail "run from the Soklet Git checkout."
project_root=$(cd "$project_root" && pwd -P)
cd "$project_root"

manifest_input=${2:-release/release-validation-manifest.json}
[[ -f "$manifest_input" ]] || fail "release manifest does not exist: $manifest_input"
manifest_path=$(cd "$(dirname "$manifest_input")" && pwd -P)/$(basename "$manifest_input")
evidence_helper="$project_root/scripts/release-validation-evidence.mjs"
[[ -f "$evidence_helper" ]] || fail "release evidence helper is missing."
surefire_verifier="$project_root/scripts/verify-surefire-reports.mjs"
[[ -f "$surefire_verifier" ]] || fail "Surefire report verifier is missing."
downstream_pom_verifier="$project_root/scripts/verify-maven-downstream-pom.mjs"
[[ -f "$downstream_pom_verifier" ]] \
	|| fail "Maven downstream POM verifier is missing."
loopback_port_reserver="$project_root/scripts/reserve-loopback-port.mjs"
[[ -f "$loopback_port_reserver" && ! -L "$loopback_port_reserver" ]] \
	|| fail "loopback port reservation helper is missing or is a symlink."

node_distribution_evidence=${SOKLET_RELEASE_NODE_DISTRIBUTION_EVIDENCE:-}
maven_distribution_evidence=${SOKLET_RELEASE_MAVEN_DISTRIBUTION_EVIDENCE:-}
go_distribution_evidence=${SOKLET_RELEASE_GO_DISTRIBUTION_EVIDENCE:-}
java_distribution_evidence=${SOKLET_RELEASE_JAVA_DISTRIBUTION_EVIDENCE:-}
core_jdk_21_distribution_evidence=${SOKLET_RELEASE_CORE_JDK_21_DISTRIBUTION_EVIDENCE:-}
toystore_java_distribution_evidence=${SOKLET_RELEASE_TOYSTORE_JAVA_DISTRIBUTION_EVIDENCE:-}
for distribution_evidence in \
	"$node_distribution_evidence" "$maven_distribution_evidence" \
	"$go_distribution_evidence" "$java_distribution_evidence" \
	"$core_jdk_21_distribution_evidence" \
	"$toystore_java_distribution_evidence"; do
	[[ -n "$distribution_evidence" && -f "$distribution_evidence" \
			&& ! -L "$distribution_evidence" ]] \
		|| fail "checksum-pinned toolchain distribution evidence is missing."
done

node "$evidence_helper" validate-config "$manifest_path"

assert_ready_gate_has_dispatch() {
	local gate_id=$1
	case "$gate_id" in
		candidate-build|core-jdk-21|core-jdk-25|isolated-install|api-freeze|\
		candidate-javadocs|static-analysis|spotbugs|schema-replay|fuzz-replay|\
		soak-smoke|release-soak|localization-fleet|matrix-closure|\
		candidate-conformance|candidate-localization|barebones-app|\
		soklet-servlet-javax|soklet-servlet-jakarta|toystore-app|soklet-otel|\
		soklet-website|typescript-interop|go-interop)
			return 0
			;;
		*)
			fail "gate $gate_id is READY but has no release-validator dispatch."
			;;
	esac
}

configured_gate_count=0
while IFS=$'\t' read -r configured_gate_id configured_gate_status; do
	configured_gate_count=$((configured_gate_count + 1))
	[[ -n "$configured_gate_id" ]] \
		|| fail "release manifest returned an empty gate ID."
	[[ -n "$configured_gate_status" ]] \
		|| fail "release manifest returned an empty status for gate $configured_gate_id."
	if [[ "$configured_gate_status" == "READY" ]]; then
		assert_ready_gate_has_dispatch "$configured_gate_id"
	fi
done < <(node "$evidence_helper" list-gate-ids "$manifest_path")
[[ "$configured_gate_count" -eq 29 ]] \
	|| fail "release manifest dispatch inventory must contain exactly 29 gates."

head_commit=$(git rev-parse --verify HEAD)
[[ "$head_commit" == "$candidate_commit" ]] \
	|| fail "checkout HEAD is $head_commit; expected candidate $candidate_commit."
if [[ -n ${GITHUB_SHA:-} && "$GITHUB_SHA" != "$candidate_commit" ]]; then
	fail "workflow trigger SHA is $GITHUB_SHA; dispatch the workflow from candidate $candidate_commit."
fi
git cat-file -e "$candidate_commit^{commit}" \
	|| fail "candidate commit is not present in this checkout."

checkout_status=$(git status --porcelain --untracked-files=all)
[[ -z "$checkout_status" ]] \
	|| fail "candidate checkout is dirty; create a new immutable candidate commit."

manifest_relative=${manifest_path#"$project_root"/}
[[ "$manifest_relative" != "$manifest_path" ]] \
	|| fail "release manifest must be inside the candidate checkout."
git ls-files --error-unmatch "$manifest_relative" >/dev/null 2>&1 \
	|| fail "release manifest must be tracked by the candidate commit."

# This is intentionally checked before tool setup and the expensive build. A
# provisional or blocked downstream pin makes the entire candidate unrunnable.
node "$evidence_helper" validate-config "$manifest_path" --require-ready

manifest_value() {
	node "$evidence_helper" value "$manifest_path" "$1"
}

expected_java_version=$(manifest_value toolchains.java.version)
expected_java_runtime_version=$(manifest_value toolchains.java.runtimeVersion)
expected_java_vendor_version=$(manifest_value toolchains.java.vendorVersion)
expected_core_jdk_21_version=$(manifest_value toolchains.coreJdk21.version)
expected_core_jdk_21_runtime_version=$(manifest_value toolchains.coreJdk21.runtimeVersion)
expected_core_jdk_21_vendor_version=$(manifest_value toolchains.coreJdk21.vendorVersion)
expected_toystore_java_version=$(manifest_value toolchains.toystoreJava.version)
expected_toystore_java_runtime_version=$(manifest_value toolchains.toystoreJava.runtimeVersion)
expected_toystore_java_vendor_version=$(manifest_value toolchains.toystoreJava.vendorVersion)
expected_maven_version=$(manifest_value toolchains.maven.version)
expected_go_version=$(manifest_value toolchains.go.version)
install_file_goal=$(manifest_value toolchains.maven.installFileGoal)
soak_timeout_seconds=$(manifest_value toolchains.releaseSoakTimeoutSeconds)
candidate_version=$(manifest_value candidate.version)

java_property() {
	local java_command=$1
	local property=$2
	"$java_command" -XshowSettings:properties -version 2>&1 \
		| sed -n "s/^[[:space:]]*$property = //p" | head -n 1
}

core_java_home=${JAVA_HOME:-}
[[ -n "$core_java_home" && "$core_java_home" == /* \
		&& -d "$core_java_home" && ! -L "$core_java_home" \
		&& -x "$core_java_home/bin/java" && -x "$core_java_home/bin/javac" ]] \
	|| fail "JAVA_HOME must name the installed nonsymlink candidate JDK."
core_java_home=$(cd "$core_java_home" && pwd -P)
default_java_bin=$(cd "$(dirname "$(command -v java)")" && pwd -P)
default_javac_bin=$(cd "$(dirname "$(command -v javac)")" && pwd -P)
[[ "$default_java_bin" == "$core_java_home/bin" \
		&& "$default_javac_bin" == "$core_java_home/bin" ]] \
	|| fail "Default java/javac are not from the exact candidate JAVA_HOME."
actual_java_version=$(java_property "$core_java_home/bin/java" java.version)
actual_java_runtime_version=$(java_property "$core_java_home/bin/java" java.runtime.version)
actual_java_vendor=$(java_property "$core_java_home/bin/java" java.vendor)
actual_java_vendor_version=$(java_property "$core_java_home/bin/java" java.vendor.version)
actual_javac_version=$("$core_java_home/bin/javac" -version 2>&1)
[[ "$actual_java_version" == "$expected_java_version" ]] \
	|| fail "Java is $actual_java_version; expected Corretto $expected_java_version."
[[ "$actual_java_runtime_version" == "$expected_java_runtime_version" ]] \
	|| fail "Java runtime is $actual_java_runtime_version; expected $expected_java_runtime_version."
[[ "$actual_java_vendor" == "Amazon.com Inc." ]] \
	|| fail "Java vendor is $actual_java_vendor; expected Amazon.com Inc."
[[ "$actual_java_vendor_version" == "$expected_java_vendor_version" ]] \
	|| fail "Java vendor build is $actual_java_vendor_version; expected $expected_java_vendor_version."
[[ "$actual_javac_version" == "javac $expected_java_version" ]] \
	|| fail "javac is $actual_javac_version; expected javac $expected_java_version."

core_jdk_21_home=${SOKLET_RELEASE_CORE_JDK_21_HOME:-}
[[ -n "$core_jdk_21_home" && "$core_jdk_21_home" == /* \
		&& -d "$core_jdk_21_home" && ! -L "$core_jdk_21_home" \
		&& -x "$core_jdk_21_home/bin/java" \
		&& -x "$core_jdk_21_home/bin/javac" ]] \
	|| fail "SOKLET_RELEASE_CORE_JDK_21_HOME must name the installed nonsymlink core JDK 21."
core_jdk_21_home=$(cd "$core_jdk_21_home" && pwd -P)
actual_core_jdk_21_version=$(java_property "$core_jdk_21_home/bin/java" java.version)
actual_core_jdk_21_runtime_version=$(java_property \
	"$core_jdk_21_home/bin/java" java.runtime.version)
actual_core_jdk_21_vendor=$(java_property "$core_jdk_21_home/bin/java" java.vendor)
actual_core_jdk_21_vendor_version=$(java_property \
	"$core_jdk_21_home/bin/java" java.vendor.version)
actual_core_jdk_21_javac_version=$("$core_jdk_21_home/bin/javac" -version 2>&1)
[[ "$actual_core_jdk_21_version" == "$expected_core_jdk_21_version" \
		&& "$actual_core_jdk_21_runtime_version" == "$expected_core_jdk_21_runtime_version" \
		&& "$actual_core_jdk_21_vendor" == "Amazon.com Inc." \
		&& "$actual_core_jdk_21_vendor_version" == "$expected_core_jdk_21_vendor_version" \
		&& "$actual_core_jdk_21_javac_version" == "javac $expected_core_jdk_21_version" ]] \
	|| fail "Core JDK 21 Java/Javac identity does not match the exact manifest pin."

toystore_java_home=${SOKLET_RELEASE_TOYSTORE_JAVA_HOME:-}
[[ -n "$toystore_java_home" && "$toystore_java_home" == /* \
		&& -d "$toystore_java_home" && ! -L "$toystore_java_home" \
		&& -x "$toystore_java_home/bin/java" \
		&& -x "$toystore_java_home/bin/javac" ]] \
	|| fail "SOKLET_RELEASE_TOYSTORE_JAVA_HOME must name the installed nonsymlink ToyStore JDK."
toystore_java_home=$(cd "$toystore_java_home" && pwd -P)
actual_toystore_java_version=$(java_property "$toystore_java_home/bin/java" java.version)
actual_toystore_java_runtime_version=$(java_property "$toystore_java_home/bin/java" java.runtime.version)
actual_toystore_java_vendor=$(java_property "$toystore_java_home/bin/java" java.vendor)
actual_toystore_java_vendor_version=$(java_property "$toystore_java_home/bin/java" java.vendor.version)
actual_toystore_javac_version=$("$toystore_java_home/bin/javac" -version 2>&1)
[[ "$actual_toystore_java_version" == "$expected_toystore_java_version" \
		&& "$actual_toystore_java_runtime_version" == "$expected_toystore_java_runtime_version" \
		&& "$actual_toystore_java_vendor" == "Amazon.com Inc." \
		&& "$actual_toystore_java_vendor_version" == "$expected_toystore_java_vendor_version" \
		&& "$actual_toystore_javac_version" == "javac $expected_toystore_java_version" ]] \
	|| fail "ToyStore Java/Javac identity does not match the exact manifest pin."

actual_maven_output=$(mvn -version)
actual_maven_version=$(printf '%s\n' "$actual_maven_output" \
	| sed -n '1s/^Apache Maven \([^ ]*\).*/\1/p')
actual_maven_java_version=$(printf '%s\n' "$actual_maven_output" \
	| sed -n 's/^Java version: \([^,]*\), vendor: .*/\1/p')
actual_maven_java_vendor=$(printf '%s\n' "$actual_maven_output" \
	| sed -n 's/^Java version: [^,]*, vendor: \([^,]*\), runtime: .*/\1/p')
actual_maven_java_home=$(printf '%s\n' "$actual_maven_output" \
	| sed -n 's/^Java version: [^,]*, vendor: [^,]*, runtime: //p')
[[ "$actual_maven_version" == "$expected_maven_version" ]] \
	|| fail "Maven is $actual_maven_version; expected $expected_maven_version."
[[ "$actual_maven_java_version" == "$expected_java_version" \
		&& "$actual_maven_java_vendor" == "Amazon.com Inc." \
		&& -d "$actual_maven_java_home" ]] \
	|| fail "Maven is not running on the exact candidate Corretto JDK."
actual_maven_java_home=$(cd "$actual_maven_java_home" && pwd -P)
[[ "$actual_maven_java_home" == "$core_java_home" ]] \
	|| fail "Maven runtime $actual_maven_java_home differs from JAVA_HOME $core_java_home."

actual_go_version_output=$(go version)
actual_go_version=$(printf '%s\n' "$actual_go_version_output" \
	| sed -n 's/^go version go\([^ ]*\) linux\/amd64$/\1/p')
[[ "$actual_go_version" == "$expected_go_version" ]] \
	|| fail "Go is $actual_go_version; expected $expected_go_version for linux/amd64."

node_pin_path=$(manifest_value toolchains.nodePin.path)
expected_node_version=$(node -e \
	"const p=require(process.argv[1]); process.stdout.write(p.toolchain.node)" \
	"$project_root/$node_pin_path")
expected_npm_version=$(node -e \
	"const p=require(process.argv[1]); process.stdout.write(p.toolchain.npm)" \
	"$project_root/$node_pin_path")
actual_node_version=$(node --version)
actual_node_version=${actual_node_version#v}
actual_npm_version=$(npm --version)
[[ "$actual_node_version" == "$expected_node_version" ]] \
	|| fail "Node is $actual_node_version; expected $expected_node_version."
[[ "$actual_npm_version" == "$expected_npm_version" ]] \
	|| fail "npm is $actual_npm_version; expected $expected_npm_version."

temporary_directory=$(mktemp -d "${TMPDIR:-/tmp}/soklet-release-validation.XXXXXX")
active_pid=
stop_active_process() {
	local pid=$active_pid
	[[ -n "$pid" ]] || return 0
	if ! kill -0 "$pid" 2>/dev/null; then
		wait "$pid" 2>/dev/null || true
		active_pid=
		return 0
	fi
	kill -TERM "$pid" 2>/dev/null || true
	local attempt
	for ((attempt = 0; attempt < 50; attempt++)); do
		if ! kill -0 "$pid" 2>/dev/null; then
			wait "$pid" 2>/dev/null || true
			active_pid=
			return 0
		fi
		sleep 0.1
	done
	kill -KILL "$pid" 2>/dev/null || true
	wait "$pid" 2>/dev/null || true
	active_pid=
	return 1
}
assert_loopback_port_available() {
	local port=$1
	node --input-type=module -e \
		"import net from 'node:net'; const port=Number(process.argv[1]); const server=net.createServer(); server.once('error',()=>process.exit(1)); server.listen({exclusive:true,host:'127.0.0.1',port},()=>server.close((error)=>process.exit(error===undefined?0:1)));" \
		"$port"
}
reserved_loopback_port=
reserve_loopback_port() {
	local output_file=$1
	local log_file=$2
	[[ ! -e "$output_file" ]] \
		|| fail "loopback port reservation output already exists: $output_file"
	node "$loopback_port_reserver" "$output_file" >"$log_file" 2>&1 &
	active_pid=$!
	local reservation_pid=$active_pid
	local attempt
	for ((attempt = 0; attempt < 100; attempt++)); do
		if [[ -s "$output_file" ]]; then
			break
		fi
		if ! kill -0 "$reservation_pid" 2>/dev/null; then
			wait "$reservation_pid" 2>/dev/null || true
			active_pid=
			fail "loopback port reservation exited before selecting a port; inspect $log_file."
		fi
		sleep 0.05
	done
	[[ -f "$output_file" && ! -L "$output_file" && -s "$output_file" ]] \
		|| fail "loopback port reservation did not produce a regular port file."
	IFS= read -r reserved_loopback_port < "$output_file" \
		|| fail "loopback port reservation output could not be read."
	[[ "$reserved_loopback_port" =~ ^[0-9]+$ \
			&& "$reserved_loopback_port" -ge 1 \
			&& "$reserved_loopback_port" -le 65535 ]] \
		|| fail "loopback port reservation produced an invalid port."
	kill -0 "$reservation_pid" 2>/dev/null \
		|| fail "loopback port reservation was not held after selection."
}
verify_reviewed_soklet_jar() {
	local jar_path=$1
	local expected_sha256=$2
	node "$surefire_verifier" verify-jar "$jar_path" "$expected_sha256" \
		>/dev/null
}
assert_installed_candidate_unchanged() {
	verify_reviewed_soklet_jar "$installed_jar" "$candidate_jar_sha256" \
		|| fail "installed candidate Soklet JAR changed during validation."
}
cleanup() {
	stop_active_process || true
	case "$temporary_directory" in
		*/soklet-release-validation.*)
			rm -rf -- "$temporary_directory"
			;;
		*)
			printf 'Refusing to remove unexpected temporary path: %s\n' \
				"$temporary_directory" >&2
			;;
	esac
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

build_log="$temporary_directory/candidate-build.log"
mvn -B -ntp -Dgpg.skip=true clean verify 2>&1 | tee "$build_log"

validation_root="$project_root/target/release-validation"
evidence_root="$validation_root/evidence"
gate_evidence_root="$evidence_root/gates"
work_root="$validation_root/work"
checkout_root="$work_root/checkouts"
mkdir -p "$gate_evidence_root" "$checkout_root"
cp "$build_log" "$evidence_root/candidate-build.log"
node "$surefire_verifier" "$project_root/target/surefire-reports" \
	candidate-build candidate
candidate_build_raw_root="$evidence_root/raw/candidate-build"
candidate_build_surefire_reports="$candidate_build_raw_root/surefire-reports"
[[ ! -e "$candidate_build_surefire_reports" ]] \
	|| fail "candidate-build Surefire evidence destination already exists."
mkdir -p "$candidate_build_raw_root"
cp -R "$project_root/target/surefire-reports" \
	"$candidate_build_surefire_reports"

candidate_pom="$project_root/pom.xml"
candidate_jar="$project_root/target/soklet-$candidate_version.jar"
candidate_sources_jar="$project_root/target/soklet-$candidate_version-sources.jar"
candidate_javadoc_jar="$project_root/target/soklet-$candidate_version-javadoc.jar"
for artifact in "$candidate_pom" "$candidate_jar" \
	"$candidate_sources_jar" "$candidate_javadoc_jar"; do
	[[ -f "$artifact" && ! -L "$artifact" ]] \
		|| fail "required candidate artifact is missing or is a symlink: $artifact"
done
candidate_jar_sha256=$(node "$evidence_helper" sha256 "$candidate_jar")
verify_reviewed_soklet_jar "$candidate_jar" "$candidate_jar_sha256" \
	|| fail "candidate main JAR is not a valid marked Soklet archive."

artifact_descriptor="$evidence_root/candidate-artifacts.json"
node "$evidence_helper" record-artifacts \
	"$manifest_path" "$candidate_commit" "$artifact_descriptor" \
	"$candidate_pom" "$candidate_jar" "$candidate_sources_jar" \
	"$candidate_javadoc_jar"
node "$evidence_helper" record-gate \
	"$manifest_path" "$candidate_commit" "$artifact_descriptor" candidate-build \
	"$gate_evidence_root/candidate-build.json" \
	"artifact-descriptor=$artifact_descriptor" \
	"build-log=$evidence_root/candidate-build.log" \
	"surefire-reports=$candidate_build_surefire_reports" \
	"node-distribution=$node_distribution_evidence" \
	"maven-distribution=$maven_distribution_evidence" \
	"go-distribution=$go_distribution_evidence" \
	"java-distribution=$java_distribution_evidence"

declare -A gate_repository gate_commit gate_version_property \
	gate_artifact_identity gate_default_artifact_identity \
	gate_default_artifact_sha256
while IFS=$'\t' read -r gate_id _gate_status; do
	gate_repository["$gate_id"]=$(node "$evidence_helper" gate-value \
		"$manifest_path" "$gate_id" repository)
	gate_commit["$gate_id"]=$(node "$evidence_helper" gate-value \
		"$manifest_path" "$gate_id" commit)
	gate_version_property["$gate_id"]=$(node "$evidence_helper" gate-value \
		"$manifest_path" "$gate_id" versionProperty)
	gate_artifact_identity["$gate_id"]=$(node "$evidence_helper" gate-value \
		"$manifest_path" "$gate_id" artifactIdentity)
	gate_default_artifact_identity["$gate_id"]=$(node "$evidence_helper" gate-value \
		"$manifest_path" "$gate_id" defaultArtifactIdentity)
	gate_default_artifact_sha256["$gate_id"]=$(node "$evidence_helper" gate-value \
		"$manifest_path" "$gate_id" defaultArtifactSha256)
done < <(node "$evidence_helper" list-gate-ids "$manifest_path")

clone_pinned_gate() {
	local gate_id=$1
	local repository=${gate_repository[$gate_id]:-}
	local commit=${gate_commit[$gate_id]:-}
	local checkout="$checkout_root/$gate_id"
	[[ -n "$repository" && "$commit" =~ ^[0-9a-f]{40}$ ]] \
		|| fail "gate $gate_id does not have an immutable repository pin."
	[[ ! -e "$checkout" ]] || fail "checkout path already exists: $checkout"
	mkdir -p "$checkout"
	git -C "$checkout" init --quiet
	git -C "$checkout" remote add origin "$repository"
	git -C "$checkout" fetch --quiet --no-tags --depth=1 origin "$commit"
	git -C "$checkout" checkout --quiet --detach FETCH_HEAD
	[[ $(git -C "$checkout" rev-parse HEAD) == "$commit" ]] \
		|| fail "$gate_id checkout did not resolve the pinned commit."
	[[ -z $(git -C "$checkout" status --porcelain --untracked-files=all) ]] \
		|| fail "$gate_id checkout is dirty immediately after checkout."
	printf '%s\n' "$checkout"
}

assert_pinned_checkout_unchanged() {
	local gate_id=$1
	local checkout=$2
	[[ $(git -C "$checkout" rev-parse HEAD) == "${gate_commit[$gate_id]}" ]] \
		|| fail "$gate_id HEAD changed during validation."
	[[ -z $(git -C "$checkout" status --porcelain --untracked-files=no) ]] \
		|| fail "$gate_id tracked checkout changed during validation."
}

clone_candidate_gate() {
	local gate_id=$1
	local checkout="$checkout_root/candidate-$gate_id"
	[[ ! -e "$checkout" ]] || fail "checkout path already exists: $checkout"
	git clone --quiet --no-checkout --no-hardlinks "$project_root" "$checkout"
	git -C "$checkout" checkout --quiet --detach "$candidate_commit"
	[[ $(git -C "$checkout" rev-parse HEAD) == "$candidate_commit" ]] \
		|| fail "$gate_id candidate checkout did not resolve the candidate commit."
	[[ -z $(git -C "$checkout" status --porcelain --untracked-files=all) ]] \
		|| fail "$gate_id candidate checkout is dirty immediately after checkout."
	printf '%s\n' "$checkout"
}

assert_candidate_checkout_unchanged() {
	local gate_id=$1
	local checkout=$2
	[[ $(git -C "$checkout" rev-parse HEAD) == "$candidate_commit" ]] \
		|| fail "$gate_id candidate checkout HEAD changed during validation."
	[[ -z $(git -C "$checkout" status --porcelain --untracked-files=no) ]] \
		|| fail "$gate_id changed tracked candidate files during validation."
}

copy_surefire_evidence() {
	local source=$1
	local gate_id=$2
	local raw_root="$evidence_root/raw/$gate_id"
	local destination="$raw_root/surefire-reports"
	[[ -d "$source" && ! -L "$source" ]] \
		|| fail "$gate_id Surefire reports are missing or are a symlink."
	[[ ! -e "$destination" ]] \
		|| fail "$gate_id Surefire evidence destination already exists."
	mkdir -p "$raw_root"
	cp -R "$source" "$destination"
	printf '%s\n' "$destination"
}

record_gate() {
	local gate_id=$1
	shift
	node "$evidence_helper" record-gate \
		"$manifest_path" "$candidate_commit" "$artifact_descriptor" "$gate_id" \
		"$gate_evidence_root/$gate_id.json" "$@"
}

run_isolated_install() {
	isolated_maven_repository="$work_root/isolated-maven-repository"
	[[ ! -e "$isolated_maven_repository" ]] \
		|| fail "isolated Maven repository already exists: $isolated_maven_repository"
	mkdir -p "$isolated_maven_repository"
	local install_log="$evidence_root/isolated-install.log"
	mvn -B -ntp "$install_file_goal" \
		-Dfile="$candidate_jar" \
		-DpomFile="$candidate_pom" \
		-DgeneratePom=false \
		-DlocalRepositoryPath="$isolated_maven_repository" \
		2>&1 | tee "$install_log"

	local installed_root="$isolated_maven_repository/com/soklet/soklet/$candidate_version"
	installed_pom="$installed_root/soklet-$candidate_version.pom"
	installed_jar="$installed_root/soklet-$candidate_version.jar"
	cmp -s "$candidate_pom" "$installed_pom" \
		|| fail "isolated Maven repository POM differs from the candidate POM."
	cmp -s "$candidate_jar" "$installed_jar" \
		|| fail "isolated Maven repository JAR differs from the candidate JAR."
	assert_installed_candidate_unchanged
	record_gate isolated-install \
		"installed-pom=$installed_pom" \
		"installed-main-jar=$installed_jar" \
		"install-log=$install_log"
}

run_core_jdk_21() {
	local checkout
	checkout=$(clone_candidate_gate core-jdk-21)
	local log="$evidence_root/core-jdk-21.log"
	(
		cd "$checkout"
		env JAVA_HOME="$core_jdk_21_home" \
			PATH="$core_jdk_21_home/bin:$PATH" \
			mvn -B -ntp -Dgpg.skip=true clean test
	) 2>&1 | tee "$log"
	node "$surefire_verifier" "$checkout/target/surefire-reports" \
		core-jdk-21 candidate
	local reports
	reports=$(copy_surefire_evidence "$checkout/target/surefire-reports" core-jdk-21)
	assert_candidate_checkout_unchanged core-jdk-21 "$checkout"
	record_gate core-jdk-21 \
		"build-log=$log" \
		"java-distribution=$core_jdk_21_distribution_evidence" \
		"surefire-reports=$reports"
}

run_core_jdk_25() {
	local checkout
	checkout=$(clone_candidate_gate core-jdk-25)
	local log="$evidence_root/core-jdk-25.log"
	(
		cd "$checkout"
		env JAVA_HOME="$toystore_java_home" \
			PATH="$toystore_java_home/bin:$PATH" \
			mvn -B -ntp -Dgpg.skip=true clean test
	) 2>&1 | tee "$log"
	node "$surefire_verifier" "$checkout/target/surefire-reports" \
		core-jdk-25 candidate
	local reports
	reports=$(copy_surefire_evidence "$checkout/target/surefire-reports" core-jdk-25)
	assert_candidate_checkout_unchanged core-jdk-25 "$checkout"
	record_gate core-jdk-25 \
		"build-log=$log" \
		"java-distribution=$toystore_java_distribution_evidence" \
		"surefire-reports=$reports"
}

run_api_freeze() {
	local checkout
	checkout=$(clone_candidate_gate api-freeze)
	local log="$evidence_root/api-freeze.log"
	(
		cd "$checkout"
		env JAVA_HOME="$core_java_home" PATH="$core_java_home/bin:$PATH" \
			scripts/verify-mcp-api-freezes.sh
	) 2>&1 | tee "$log"
	local raw_root="$evidence_root/raw/api-freeze"
	mkdir -p "$raw_root"
	local diff="$raw_root/mcp-api-diff.xml"
	local incompatibilities="$raw_root/mcp-api-diff.incompatibilities.jsonl"
	local report="$raw_root/mcp-api-freeze.xml"
	local signatures="$raw_root/mcp-api-freezes"
	cp "$checkout/target/japicmp/mcp-api-diff.xml" "$diff"
	cp "$checkout/target/japicmp/mcp-api-diff.incompatibilities.jsonl" \
		"$incompatibilities"
	cp "$checkout/target/japicmp/mcp-api-freeze.xml" "$report"
	cp -R "$checkout/target/mcp-api-freezes" "$signatures"
	assert_candidate_checkout_unchanged api-freeze "$checkout"
	record_gate api-freeze \
		"api-freeze-log=$log" \
		"japicmp-diff=$diff" \
		"japicmp-incompatibilities=$incompatibilities" \
		"api-freeze-report=$report" \
		"signatures=$signatures"
}

run_candidate_javadocs() {
	local checkout
	checkout=$(clone_candidate_gate candidate-javadocs)
	local log="$evidence_root/candidate-javadocs.log"
	(
		cd "$checkout"
		env JAVA_HOME="$core_java_home" PATH="$core_java_home/bin:$PATH" \
			mvn -B -ntp -Dgpg.skip=true -Dtest=McpPublicJavadocTests \
			clean package javadoc:javadoc
	) 2>&1 | tee "$log"
	node "$surefire_verifier" "$checkout/target/surefire-reports" \
		candidate-javadocs candidate
	local raw_root="$evidence_root/raw/candidate-javadocs"
	local apidocs="$raw_root/apidocs"
	[[ -d "$checkout/target/reports/apidocs" \
			&& ! -L "$checkout/target/reports/apidocs" ]] \
		|| fail "standalone public Javadocs were not generated."
	mkdir -p "$raw_root"
	cp -R "$checkout/target/reports/apidocs" "$apidocs"
	local reports
	reports=$(copy_surefire_evidence \
		"$checkout/target/surefire-reports" candidate-javadocs)
	assert_candidate_checkout_unchanged candidate-javadocs "$checkout"
	record_gate candidate-javadocs \
		"javadoc-log=$log" \
		"javadoc-jar=$candidate_javadoc_jar" \
		"apidocs=$apidocs" \
		"surefire-reports=$reports"
}

run_static_analysis() {
	local checkout
	checkout=$(clone_candidate_gate static-analysis)
	local log="$evidence_root/static-analysis.log"
	(
		cd "$checkout"
		env JAVA_HOME="$core_jdk_21_home" \
			PATH="$core_jdk_21_home/bin:$PATH" \
			mvn -B -ntp -Dgpg.skip=true -Pstatic-analysis clean compile
	) 2>&1 | tee "$log"
	assert_candidate_checkout_unchanged static-analysis "$checkout"
	record_gate static-analysis \
		"analysis-log=$log" \
		"java-distribution=$core_jdk_21_distribution_evidence"
}

run_spotbugs() {
	local checkout
	checkout=$(clone_candidate_gate spotbugs)
	local log="$evidence_root/spotbugs.log"
	(
		cd "$checkout"
		env JAVA_HOME="$core_jdk_21_home" \
			PATH="$core_jdk_21_home/bin:$PATH" \
			mvn -B -ntp -Dgpg.skip=true -Pspotbugs -DskipTests \
			clean compile spotbugs:check
	) 2>&1 | tee "$log"
	local source_report="$checkout/target/spotbugsXml.xml"
	[[ -f "$source_report" && ! -L "$source_report" ]] \
		|| fail "SpotBugs XML report is missing or is a symlink."
	local raw_root="$evidence_root/raw/spotbugs"
	local report="$raw_root/spotbugsXml.xml"
	mkdir -p "$raw_root"
	cp "$source_report" "$report"
	assert_candidate_checkout_unchanged spotbugs "$checkout"
	record_gate spotbugs \
		"spotbugs-log=$log" \
		"java-distribution=$core_jdk_21_distribution_evidence" \
		"spotbugs-report=$report"
}

run_schema_replay() {
	local checkout
	checkout=$(clone_candidate_gate schema-replay)
	local log="$evidence_root/schema-replay.log"
	(
		cd "$checkout"
		node scripts/json-schema-test-suite/verify.mjs
		env JAVA_HOME="$core_java_home" PATH="$core_java_home/bin:$PATH" \
			mvn -B -ntp -Dgpg.skip=true \
			-Dtest='JsonSchemaTestSuitePinTests,McpToolSchemaProfile*' test
	) 2>&1 | tee "$log"
	node "$surefire_verifier" "$checkout/target/surefire-reports" \
		schema-replay candidate
	local reports
	reports=$(copy_surefire_evidence "$checkout/target/surefire-reports" schema-replay)
	assert_candidate_checkout_unchanged schema-replay "$checkout"
	record_gate schema-replay "replay-log=$log" "surefire-reports=$reports"
}

run_fuzz_replay() {
	local log="$evidence_root/fuzz-replay.log"
	env JAVA_HOME="$toystore_java_home" PATH="$toystore_java_home/bin:$PATH" \
		mvn -B -ntp -f fuzz/pom.xml clean test 2>&1 | tee "$log"
	node scripts/verify-json-corpus.mjs 2>&1 | tee -a "$log"
	node "$surefire_verifier" "$project_root/fuzz/target/surefire-reports" \
		fuzz-replay candidate
	local reports
	reports=$(copy_surefire_evidence \
		"$project_root/fuzz/target/surefire-reports" fuzz-replay)
	record_gate fuzz-replay "replay-log=$log" "surefire-reports=$reports"
}

run_soak_profile() {
	local gate_id=$1
	local profile=$2
	local timeout_seconds=$3
	local log="$evidence_root/$gate_id.log"
	local soak_java_home=$core_java_home
	if [[ "$profile" == "smoke" ]]; then
		soak_java_home=$toystore_java_home
	fi
	assert_installed_candidate_unchanged
	timeout --signal=TERM --kill-after=30s "${timeout_seconds}s" \
		env JAVA_HOME="$soak_java_home" PATH="$soak_java_home/bin:$PATH" \
		SOKLET_SOAK_PROFILE="$profile" \
		mvn -B -ntp -f soak/pom.xml clean test 2>&1 | tee "$log"
	assert_installed_candidate_unchanged
	node scripts/verify-soak-evidence.mjs "$profile"
	node "$surefire_verifier" "$project_root/soak/target/surefire-reports" \
		"$gate_id" candidate
	local raw_root="$evidence_root/raw/$gate_id"
	local report="$raw_root/soak-report.md"
	mkdir -p "$raw_root"
	cp "$project_root/soak/target/soak-report.md" "$report"
	local reports
	reports=$(copy_surefire_evidence \
		"$project_root/soak/target/surefire-reports" "$gate_id")
	if [[ "$gate_id" == "release-soak" ]]; then
		record_gate "$gate_id" \
			"soak-report=$report" "surefire-reports=$reports" "soak-log=$log"
	else
		record_gate "$gate_id" \
			"soak-log=$log" "soak-report=$report" "surefire-reports=$reports"
	fi
}

run_localization_fleet() {
	local checkout
	checkout=$(clone_candidate_gate localization-fleet)
	local log="$evidence_root/localization-fleet.log"
	(
		cd "$checkout"
		env JAVA_HOME="$core_java_home" PATH="$core_java_home/bin:$PATH" \
			mvn -B -ntp -Dtest=McpLocalizationFleetPublicRuntimeTests test
	) 2>&1 | tee "$log"
	node "$surefire_verifier" "$checkout/target/surefire-reports" \
		localization-fleet candidate
	local reports
	reports=$(copy_surefire_evidence \
		"$checkout/target/surefire-reports" localization-fleet)
	assert_candidate_checkout_unchanged localization-fleet "$checkout"
	assert_installed_candidate_unchanged
	record_gate localization-fleet "fleet-log=$log" "surefire-reports=$reports"
}

run_matrix_closure() {
	local registry="$project_root/release/mcp-conformance-matrix-closure.json"
	local verifier="$project_root/scripts/verify-release-matrix-closure.mjs"
	local verifier_self_test="$project_root/scripts/verify-release-matrix-closure-self-test.mjs"
	local source relative
	for source in "$registry" "$verifier" "$verifier_self_test"; do
		[[ -f "$source" && ! -L "$source" ]] \
			|| fail "matrix-closure source is missing or is a symlink: $source"
		relative=${source#"$project_root"/}
		[[ "$relative" != "$source" ]] \
			|| fail "matrix-closure source is outside the candidate checkout: $source"
		git ls-files --error-unmatch "$relative" >/dev/null 2>&1 \
			|| fail "matrix-closure source is not tracked by the candidate commit: $relative"
	done

	local raw_root="$evidence_root/raw/matrix-closure"
	local report="$raw_root/matrix-closure.json"
	[[ ! -e "$report" ]] \
		|| fail "matrix-closure report destination already exists."
	mkdir -p "$raw_root"
	assert_installed_candidate_unchanged
	node scripts/verify-release-matrix-closure.mjs > "$report"
	[[ -s "$report" && -f "$report" && ! -L "$report" ]] \
		|| fail "matrix-closure report is missing, empty, or is a symlink."
	assert_candidate_checkout_unchanged matrix-closure "$project_root"
	assert_installed_candidate_unchanged
	record_gate matrix-closure "matrix-report=$report"
}

run_candidate_conformance() {
	local checkout
	checkout=$(clone_pinned_gate candidate-conformance)
	local npm_cache="$work_root/npm-cache-conformance"
	local npm_home="$work_root/npm-home-conformance"
	local npm_user_config="$npm_home/user.npmrc"
	local npm_global_config="$npm_home/global.npmrc"
	mkdir -p "$npm_cache" "$npm_home"
	touch "$npm_user_config" "$npm_global_config"
	(
		cd "$checkout"
		env -i PATH="$PATH" HOME="$npm_home" LANG=C.UTF-8 CI=true NO_COLOR=1 \
			npm_config_cache="$npm_cache" npm_config_userconfig="$npm_user_config" \
			npm_config_globalconfig="$npm_global_config" npm ci --ignore-scripts
		env -i PATH="$PATH" HOME="$npm_home" LANG=C.UTF-8 CI=true NO_COLOR=1 \
			npm_config_cache="$npm_cache" npm_config_userconfig="$npm_user_config" \
			npm_config_globalconfig="$npm_global_config" npm run build
	)
	node conformance/official/self-test.mjs --suite-dir "$checkout"
	node conformance/official/runner-self-test.mjs
	local fixture_root="$project_root/target/conformance/public-fixture"
	local classpath
	classpath=$(sh conformance/official/build-public-fixture.sh \
		"$candidate_jar" "$fixture_root")
	local conformance_work="$project_root/target/conformance/official/release"
	mkdir -p "$conformance_work"
	local pom_sha main_sha sources_sha javadoc_sha
	pom_sha=$(node "$evidence_helper" sha256 "$candidate_pom")
	main_sha=$(node "$evidence_helper" sha256 "$candidate_jar")
	sources_sha=$(node "$evidence_helper" sha256 "$candidate_sources_jar")
	javadoc_sha=$(node "$evidence_helper" sha256 "$candidate_javadoc_jar")
	node conformance/official/run.mjs \
		--suite-dir "$checkout" \
		--work-dir "$conformance_work" \
		--classpath "$classpath" \
		--project-root "$project_root" \
		--phase 5 \
		--mode release \
		--candidate-commit "$candidate_commit" \
		--candidate-pom "$candidate_pom" \
		--candidate-pom-sha256 "$pom_sha" \
		--candidate-jar "$candidate_jar" \
		--candidate-jar-sha256 "$main_sha" \
		--candidate-sources-jar "$candidate_sources_jar" \
		--candidate-sources-jar-sha256 "$sources_sha" \
		--candidate-javadoc-jar "$candidate_javadoc_jar" \
		--candidate-javadoc-jar-sha256 "$javadoc_sha"
	node "$evidence_helper" verify-conformance \
		"$manifest_path" "$candidate_commit" "$artifact_descriptor" \
		"$conformance_work/evidence.json"
	assert_pinned_checkout_unchanged candidate-conformance "$checkout"
	record_gate candidate-conformance "conformance-evidence=$conformance_work"
}

run_candidate_localization() {
	local log="$evidence_root/candidate-localization.log"
	verification/localization/verify.sh "$candidate_jar" \
		2>&1 | tee "$log"
	record_gate candidate-localization "localization-log=$log"
}

prepare_servlet_default_jar() {
	local default_version=$1
	local expected_identity=$2
	local expected_sha256=$3
	[[ "$expected_identity" == "com.soklet:soklet:$default_version" ]] \
		|| fail "servlet default Soklet identity does not match its POM version."
	local default_root="$isolated_maven_repository/com/soklet/soklet/$default_version"
	local default_jar="$default_root/soklet-$default_version.jar"
	if [[ ! -e "$default_jar" ]]; then
		local download="$work_root/reviewed-soklet-$default_version.jar"
		[[ ! -e "$download" ]] \
			|| fail "reviewed servlet default download path already exists."
		curl --fail --silent --show-error --location \
			--proto '=https' --proto-redir '=https' --tlsv1.2 \
			"https://repo1.maven.org/maven2/com/soklet/soklet/$default_version/soklet-$default_version.jar" \
			--output "$download"
		verify_reviewed_soklet_jar "$download" "$expected_sha256" \
			|| fail "downloaded servlet default Soklet JAR failed its reviewed pin."
		mkdir -p "$default_root"
		[[ ! -e "$default_jar" ]] \
			|| fail "servlet default Soklet JAR appeared during staging."
		cp "$download" "$default_jar"
	fi
	verify_reviewed_soklet_jar "$default_jar" "$expected_sha256" \
		|| fail "servlet default Soklet JAR differs from its reviewed pin."
	printf '%s\n' "$default_jar"
}

run_maven_downstream() {
	local gate_id=$1
	local checkout
	checkout=$(clone_pinned_gate "$gate_id")
	local version_property=${gate_version_property[$gate_id]:-}
	[[ "$version_property" == "soklet.version" ]] \
		|| fail "$gate_id does not declare the required soklet.version override."
	local artifact_identity=${gate_artifact_identity[$gate_id]:-}
	local default_artifact_identity=${gate_default_artifact_identity[$gate_id]:-}
	local default_artifact_sha256=${gate_default_artifact_sha256[$gate_id]:-}
	local downstream_pom="$checkout/pom.xml"
	[[ -f "$downstream_pom" && ! -L "$downstream_pom" ]] \
		|| fail "$gate_id POM is missing or is a symlink."
	local default_soklet_version
	default_soklet_version=$(node "$downstream_pom_verifier" \
		"$downstream_pom" "$artifact_identity" "$version_property" \
		"$default_artifact_identity") \
		|| fail "$gate_id POM does not satisfy its release contract."
	if [[ "$gate_id" == "toystore-app" || "$gate_id" == "soklet-otel" ]]; then
		local candidate_log="$evidence_root/$gate_id-candidate.log"
		local downstream_java_home=$core_java_home
		if [[ "$gate_id" == "toystore-app" ]]; then
			downstream_java_home=$toystore_java_home
		fi
		assert_installed_candidate_unchanged
		(
			cd "$checkout"
			env JAVA_HOME="$downstream_java_home" \
				PATH="$downstream_java_home/bin:$PATH" \
				mvn -B -ntp -Dgpg.skip=true \
				-Dmaven.repo.local="$isolated_maven_repository" \
				-DfailIfNoTests=true \
				-D"$version_property"="$candidate_version" clean verify
		) 2>&1 | tee "$candidate_log"
		assert_installed_candidate_unchanged
		local surefire_reports="$checkout/target/surefire-reports"
		node "$surefire_verifier" "$surefire_reports" "$gate_id" candidate \
			"$installed_jar" "$candidate_jar_sha256"
		assert_pinned_checkout_unchanged "$gate_id" "$checkout"
		local raw_root="$evidence_root/raw/$gate_id"
		mkdir -p "$raw_root"
		local retained_pom="$raw_root/pom.xml"
		cp "$downstream_pom" "$retained_pom"
		local retained_surefire_reports
		retained_surefire_reports=$(copy_surefire_evidence \
			"$surefire_reports" "$gate_id")
		if [[ "$gate_id" == "toystore-app" ]]; then
			record_gate "$gate_id" \
				"project-pom=$retained_pom" \
				"candidate-log=$candidate_log" \
				"candidate-surefire-reports=$retained_surefire_reports" \
				"java-distribution=$toystore_java_distribution_evidence"
		else
			record_gate "$gate_id" \
				"project-pom=$retained_pom" \
				"candidate-log=$candidate_log" \
				"candidate-surefire-reports=$retained_surefire_reports"
		fi
		return
	fi
	local default_log="$evidence_root/$gate_id-default.log"
	local candidate_log="$evidence_root/$gate_id-candidate.log"
	local default_surefire_reports="$evidence_root/$gate_id-default-surefire-reports"
	local default_jar
	default_jar=$(prepare_servlet_default_jar "$default_soklet_version" \
		"$default_artifact_identity" "$default_artifact_sha256")
	verify_reviewed_soklet_jar "$default_jar" "$default_artifact_sha256" \
		|| fail "$gate_id default Soklet JAR changed before its default leg."
	assert_installed_candidate_unchanged
	(
		cd "$checkout"
		mvn -B -ntp -Dgpg.skip=true \
			-Dmaven.repo.local="$isolated_maven_repository" \
			-DfailIfNoTests=true clean verify
	) 2>&1 | tee "$default_log"
	assert_installed_candidate_unchanged
	verify_reviewed_soklet_jar "$default_jar" "$default_artifact_sha256" \
		|| fail "$gate_id default Soklet JAR changed during its default leg."
	node "$surefire_verifier" "$checkout/target/surefire-reports" \
		"$gate_id" default "$default_jar" "$default_artifact_sha256"
	[[ ! -e "$default_surefire_reports" ]] \
		|| fail "$gate_id default Surefire evidence path already exists."
	cp -R "$checkout/target/surefire-reports" "$default_surefire_reports"
	verify_reviewed_soklet_jar "$default_jar" "$default_artifact_sha256" \
		|| fail "$gate_id default Soklet JAR changed before its candidate leg."
	assert_installed_candidate_unchanged
	(
		cd "$checkout"
		mvn -B -ntp -Dgpg.skip=true \
			-Dmaven.repo.local="$isolated_maven_repository" \
			-DfailIfNoTests=true \
			-D"$version_property"="$candidate_version" clean verify
	) 2>&1 | tee "$candidate_log"
	assert_installed_candidate_unchanged
	verify_reviewed_soklet_jar "$default_jar" "$default_artifact_sha256" \
		|| fail "$gate_id default Soklet JAR changed during its candidate leg."
	node "$surefire_verifier" "$checkout/target/surefire-reports" \
		"$gate_id" candidate "$installed_jar" "$candidate_jar_sha256"
	local raw_root="$evidence_root/raw/$gate_id"
	mkdir -p "$raw_root"
	local retained_pom="$raw_root/pom.xml"
	local retained_default_jar="$raw_root/soklet-$default_soklet_version.jar"
	cp "$downstream_pom" "$retained_pom"
	cp "$default_jar" "$retained_default_jar"
	local candidate_surefire_reports
	candidate_surefire_reports=$(copy_surefire_evidence \
		"$checkout/target/surefire-reports" "$gate_id")
	assert_pinned_checkout_unchanged "$gate_id" "$checkout"
	record_gate "$gate_id" \
		"project-pom=$retained_pom" \
		"default-jar=$retained_default_jar" \
		"default-log=$default_log" \
		"default-surefire-reports=$default_surefire_reports" \
		"candidate-log=$candidate_log" \
		"candidate-surefire-reports=$candidate_surefire_reports"
}

run_barebones() {
	local checkout
	checkout=$(clone_pinned_gate barebones-app)
	local candidate_copy="$checkout/soklet-release-candidate.jar"
	while IFS= read -r tracked_jar; do
		[[ -n "$tracked_jar" ]] && rm -f -- "$checkout/$tracked_jar"
	done < <(git -C "$checkout" ls-files '*soklet*.jar')
	cp "$candidate_jar" "$candidate_copy"
	local classes="$checkout/release-validation-classes"
	local sources="$work_root/barebones-sources.txt"
	mkdir -p "$classes"
	find "$checkout/src" -type f -name '*.java' -print | LC_ALL=C sort > "$sources"
	[[ -s "$sources" ]] || fail "barebones-app has no Java sources."
	javac --release 17 -parameters -processor com.soklet.SokletProcessor \
		-classpath "$candidate_copy" -d "$classes" @"$sources"
	local log="$evidence_root/barebones-app.log"
	local port_file="$work_root/barebones-loopback-port.txt"
	local reservation_log="$evidence_root/barebones-port-reservation.log"
	reserve_loopback_port "$port_file" "$reservation_log"
	local barebones_port=$reserved_loopback_port
	local reservation_pid=$active_pid
	stop_active_process \
		|| fail "barebones-app loopback port reservation required SIGKILL."
	! kill -0 "$reservation_pid" 2>/dev/null \
		|| fail "barebones-app loopback port reservation remains alive after handoff."
	env RUNNING_IN_DOCKER=true SOKLET_BAREBONES_LOOPBACK_PORT="$barebones_port" \
		java -classpath "$candidate_copy:$classes" com.soklet.barebones.App \
		>"$log" 2>&1 &
	active_pid=$!
	local app_pid=$active_pid
	local startup_marker="Soklet Barebones App started on port $barebones_port"
	local ready=false
	for _ in {1..60}; do
		if ! kill -0 "$active_pid" 2>/dev/null; then
			wait "$active_pid" 2>/dev/null || true
			active_pid=
			fail "barebones-app exited before becoming ready; inspect $log."
		fi
		if grep --fixed-strings --line-regexp --quiet "$startup_marker" "$log" \
				&& curl --fail --silent --show-error --max-time 2 \
			"http://127.0.0.1:$barebones_port/" >/dev/null; then
			ready=true
			break
		fi
		sleep 1
	done
	[[ "$ready" == true ]] || fail "barebones-app did not become ready."
	local root_response input_response
	root_response=$(curl --fail --silent --show-error --max-time 5 \
		"http://127.0.0.1:$barebones_port/")
	[[ "$root_response" == "Hello, world!" ]] \
		|| fail "barebones-app root response was unexpected."
	input_response=$(curl --fail --silent --show-error --max-time 5 \
		"http://127.0.0.1:$barebones_port/test-input?input=123")
	[[ "$input_response" == '{"input": 123}' ]] \
		|| fail "barebones-app query response was unexpected."
	kill -0 "$active_pid" 2>/dev/null \
		|| fail "barebones-app exited during its candidate probes."
	stop_active_process \
		|| fail "barebones-app process required SIGKILL after its candidate probes."
	! kill -0 "$app_pid" 2>/dev/null \
		|| fail "barebones-app process remains alive after its candidate probes."
	assert_loopback_port_available "$barebones_port" \
		|| fail "barebones-app did not release loopback port $barebones_port."
	[[ $(git -C "$checkout" rev-parse HEAD) == "${gate_commit[barebones-app]}" ]] \
		|| fail "barebones-app HEAD changed during validation."
	local unexpected_barebones_changes
	unexpected_barebones_changes=$(git -C "$checkout" diff --name-only \
		| grep -v -E '(^|/)soklet[^/]*\.jar$' || true)
	[[ -z "$unexpected_barebones_changes" ]] \
		|| fail "barebones-app changed tracked files other than its replaced Soklet JAR."
	local raw_root="$evidence_root/raw/barebones-app"
	local retained_port_file="$raw_root/barebones-loopback-port.txt"
	mkdir -p "$raw_root"
	cp "$port_file" "$retained_port_file"
	record_gate barebones-app \
		"port-file=$retained_port_file" \
		"reservation-log=$reservation_log" \
		"runtime-log=$log"
}

run_website() {
	local checkout
	checkout=$(clone_pinned_gate soklet-website)
	local npm_cache="$work_root/npm-cache-website"
	local npm_home="$work_root/npm-home-website"
	local npm_user_config="$npm_home/user.npmrc"
	local npm_global_config="$npm_home/global.npmrc"
	local log="$evidence_root/soklet-website.log"
	mkdir -p "$npm_cache" "$npm_home"
	touch "$npm_user_config" "$npm_global_config"
	(
		cd "$checkout"
		env -i PATH="$PATH" HOME="$npm_home" LANG=C.UTF-8 CI=true NO_COLOR=1 \
			npm_config_cache="$npm_cache" npm_config_userconfig="$npm_user_config" \
			npm_config_globalconfig="$npm_global_config" npm ci --ignore-scripts
		env -i PATH="$PATH" HOME="$npm_home" LANG=C.UTF-8 CI=true NO_COLOR=1 \
			npm_config_cache="$npm_cache" npm_config_userconfig="$npm_user_config" \
			npm_config_globalconfig="$npm_global_config" npm run lint
		env -i PATH="$PATH" HOME="$npm_home" LANG=C.UTF-8 CI=true NO_COLOR=1 \
			npm_config_cache="$npm_cache" npm_config_userconfig="$npm_user_config" \
			npm_config_globalconfig="$npm_global_config" npm run ssg-build
		git diff --exit-code
	) 2>&1 | tee "$log"
	assert_pinned_checkout_unchanged soklet-website "$checkout"
	local raw_root="$evidence_root/raw/soklet-website"
	local distribution="$raw_root/dist"
	mkdir -p "$raw_root"
	cp -R "$checkout/dist" "$distribution"
	record_gate soklet-website "build-log=$log" "distribution=$distribution"
}

run_interoperability() {
	local gate_id=$1
	local checkout
	checkout=$(clone_pinned_gate "$gate_id")
	local entrypoint="$project_root/verification/interoperability/${gate_id%-interop}/verify.sh"
	[[ -x "$entrypoint" ]] \
		|| fail "$gate_id is READY but its checked-in executable hook is missing: $entrypoint"
	local log="$evidence_root/$gate_id.log"
	"$entrypoint" "$candidate_jar" "$checkout" \
		2>&1 | tee "$log"
	assert_pinned_checkout_unchanged "$gate_id" "$checkout"
	record_gate "$gate_id" \
		"interop-log=$log" "candidate-main-jar=$candidate_jar"
}

run_core_jdk_21
run_core_jdk_25
run_isolated_install
run_api_freeze
run_candidate_javadocs
run_static_analysis
run_spotbugs
run_schema_replay
run_fuzz_replay
run_soak_profile soak-smoke smoke 600
run_soak_profile release-soak release "$soak_timeout_seconds"
run_localization_fleet
run_matrix_closure
run_candidate_conformance
run_candidate_localization
run_barebones
run_maven_downstream soklet-servlet-javax
run_maven_downstream soklet-servlet-jakarta
run_maven_downstream toystore-app
run_maven_downstream soklet-otel
run_website
run_interoperability typescript-interop
run_interoperability go-interop

assert_installed_candidate_unchanged
final_default_identity=${gate_default_artifact_identity[soklet-servlet-javax]:-}
final_default_sha256=${gate_default_artifact_sha256[soklet-servlet-javax]:-}
final_default_version=${final_default_identity##*:}
final_default_jar="$isolated_maven_repository/com/soklet/soklet/$final_default_version/soklet-$final_default_version.jar"
verify_reviewed_soklet_jar "$final_default_jar" "$final_default_sha256" \
	|| fail "servlet default Soklet JAR changed before finalization."

git_status_after=$(git status --porcelain --untracked-files=all)
[[ -z "$git_status_after" ]] \
	|| fail "candidate checkout changed during validation."
[[ $(git rev-parse HEAD) == "$candidate_commit" ]] \
	|| fail "candidate HEAD changed during validation."

final_artifact_descriptor="$evidence_root/candidate-artifacts-final.json"
node "$evidence_helper" record-artifacts \
	"$manifest_path" "$candidate_commit" "$final_artifact_descriptor" \
	"$candidate_pom" "$candidate_jar" "$candidate_sources_jar" \
	"$candidate_javadoc_jar"
cmp -s "$artifact_descriptor" "$final_artifact_descriptor" \
	|| fail "candidate artifact bytes changed during validation."
assert_installed_candidate_unchanged
verify_reviewed_soklet_jar "$final_default_jar" "$final_default_sha256" \
	|| fail "servlet default Soklet JAR changed during finalization."

export SOKLET_EVIDENCE_GIT_VERSION
SOKLET_EVIDENCE_GIT_VERSION=$(git --version)
export SOKLET_EVIDENCE_CORE_JDK_21_VERSION=$actual_core_jdk_21_version
export SOKLET_EVIDENCE_GO_VERSION=$actual_go_version_output
export SOKLET_EVIDENCE_JAVA_VERSION=$actual_java_version
export SOKLET_EVIDENCE_MAVEN_VERSION=$actual_maven_version
export SOKLET_EVIDENCE_NODE_VERSION=$actual_node_version
export SOKLET_EVIDENCE_NPM_VERSION=$actual_npm_version
export SOKLET_EVIDENCE_TOYSTORE_JAVA_VERSION=$actual_toystore_java_version
final_evidence="$evidence_root/release-validation-evidence.json"
node "$evidence_helper" assemble \
	"$manifest_path" "$candidate_commit" "$artifact_descriptor" \
	"$gate_evidence_root" "$final_evidence"
assert_installed_candidate_unchanged
verify_reviewed_soklet_jar "$final_default_jar" "$final_default_sha256" \
	|| fail "servlet default Soklet JAR changed while assembling final evidence."
final_evidence_sha=$(node "$evidence_helper" sha256 "$final_evidence")
printf '%s  %s\n' "$final_evidence_sha" "$(basename "$final_evidence")" \
	> "$evidence_root/release-validation-evidence.sha256"

printf 'Release-candidate validation passed for %s.\n' "$candidate_commit"
printf 'Evidence SHA-256: %s\n' "$final_evidence_sha"
