#!/usr/bin/env bash

set -euo pipefail

if [[ $# -ne 5 ]]; then
	printf 'Usage: %s <candidate-root> <codeql-artifact-root> <work-root> <evidence-root> <bundle-output>\n' "$0" >&2
	exit 64
fi

candidate_root=$1
codeql_artifact_root=$2
work_root=$3
evidence_root=$4
bundle_output=$5

for path in "$candidate_root" "$codeql_artifact_root" "$work_root" "$evidence_root" "$bundle_output"; do
	[[ "$path" == /* ]] || { printf 'All release-scan paths must be absolute: %s\n' "$path" >&2; exit 1; }
done
[[ -d "$candidate_root" && ! -L "$candidate_root" ]] \
	|| { printf 'Candidate root must be a real directory.\n' >&2; exit 1; }
[[ -d "$codeql_artifact_root" && ! -L "$codeql_artifact_root" ]] \
	|| { printf 'CodeQL artifact root must be a real directory.\n' >&2; exit 1; }
[[ ! -e "$work_root" && ! -e "$evidence_root" && ! -e "$bundle_output" ]] \
	|| { printf 'Release-scan work, evidence, and bundle outputs must be create-new paths.\n' >&2; exit 1; }
[[ -n ${SOKLET_RELEASE_CORE_JDK_21_HOME:-} \
		&& -x "$SOKLET_RELEASE_CORE_JDK_21_HOME/bin/java" \
		&& -x "$SOKLET_RELEASE_CORE_JDK_21_HOME/bin/javac" ]] \
	|| { printf 'SOKLET_RELEASE_CORE_JDK_21_HOME must name the pinned JDK 21 installation.\n' >&2; exit 1; }

candidate_commit=$(git -C "$candidate_root" rev-parse --verify HEAD)
[[ "$candidate_commit" =~ ^[0-9a-f]{40}$ ]] \
	|| { printf 'Candidate commit is malformed.\n' >&2; exit 1; }
[[ -z $(git -C "$candidate_root" status --porcelain=v1 --untracked-files=no) ]] \
	|| { printf 'Candidate has tracked working-tree changes.\n' >&2; exit 1; }
approvals="$candidate_root/release/release-scan-exceptions.json"
[[ -f "$approvals" && ! -L "$approvals" ]] \
	|| { printf 'Candidate-tracked release-scan exception registry is missing.\n' >&2; exit 1; }

raw_reports_root="$work_root/raw-reports"
provenance_root="$work_root/provenance"
tools_root="$work_root/tools"
maven_repository="$work_root/maven-repository"
mkdir -p "$raw_reports_root" "$provenance_root" "$tools_root" "$maven_repository" \
	"$(dirname "$bundle_output")"

codeql_report="$codeql_artifact_root/00-codeql-java.sarif"
codeql_provenance="$codeql_artifact_root/provenance"
[[ -f "$codeql_report" && ! -L "$codeql_report" \
		&& -d "$codeql_provenance" && ! -L "$codeql_provenance" ]] \
	|| { printf 'CodeQL release artifact is incomplete.\n' >&2; exit 1; }
cp "$codeql_report" "$raw_reports_root/00-codeql-java.sarif"
for name in \
	codeql-bundle-linux64.tar.gz \
	codeql-java-queries-qlpack.yml \
	codeql-java-security-extended-selectors.yml \
	codeql-java-security-extended.qls; do
	[[ -f "$codeql_provenance/$name" && ! -L "$codeql_provenance/$name" ]] \
		|| { printf 'CodeQL provenance is missing %s.\n' "$name" >&2; exit 1; }
	cp "$codeql_provenance/$name" "$provenance_root/$name"
done

gitleaks_archive="$provenance_root/gitleaks_8.30.1_linux_x64.tar.gz"
gitleaks_config="$provenance_root/gitleaks.toml"
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
	--retry 3 \
	https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz \
	--output "$gitleaks_archive"
printf '%s  %s\n' \
	551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb \
	"$gitleaks_archive" | sha256sum --check --strict
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
	--retry 3 \
	https://raw.githubusercontent.com/gitleaks/gitleaks/83d9cd684c87d95d656c1458ef04895a7f1cbd8e/config/gitleaks.toml \
	--output "$gitleaks_config"
printf '%s  %s\n' \
	e163e53b9e7e8a8511e77271e2b323ed057759542a6d988258afe3a1fa329caf \
	"$gitleaks_config" | sha256sum --check --strict
tar -xzf "$gitleaks_archive" -C "$tools_root" gitleaks
gitleaks="$tools_root/gitleaks"
[[ -x "$gitleaks" ]] || { printf 'Gitleaks executable is missing after extraction.\n' >&2; exit 1; }
"$gitleaks" version | grep -Eq '(^|[^0-9])8\.30\.1([^0-9]|$)' \
	|| { printf 'Gitleaks executable has the wrong version.\n' >&2; exit 1; }

# Exit 1 means findings, not an incomplete scan. Capture that status so both
# independent formats are always emitted; the Node producer below performs the
# exact exception decision. Any other exit status remains a hard scanner error.
run_gitleaks_report() {
	local format=$1
	local report_path=$2
	local gitleaks_exit
	set +e
	"$gitleaks" git "$candidate_root" \
		--config "$gitleaks_config" \
		--log-opts="$candidate_commit" \
		--no-banner \
		--redact=100 \
		--report-format "$format" \
		--report-path "$report_path" \
		--exit-code 1
	gitleaks_exit=$?
	set -e
	if [[ $gitleaks_exit -ne 0 && $gitleaks_exit -ne 1 ]]; then
		printf 'Gitleaks %s scan failed with exit status %s.\n' "$format" "$gitleaks_exit" >&2
		exit "$gitleaks_exit"
	fi
	[[ -f "$report_path" && ! -L "$report_path" ]] \
		|| { printf 'Gitleaks %s report is missing.\n' "$format" >&2; exit 1; }
}

run_gitleaks_report sarif "$raw_reports_root/02-gitleaks.sarif"
run_gitleaks_report json "$raw_reports_root/03-gitleaks.json"

spotbugs_filter="$provenance_root/spotbugs-exclude.xml"
git -C "$candidate_root" cat-file blob \
	a66f83d1c401ca0c4829d2a75ce0b38ca2d7eb4f > "$spotbugs_filter"
printf '%s  %s\n' \
	2c7559cc6d288da637316de4957ffd8cc86aa22014dede34f3a581716f82f63c \
	"$spotbugs_filter" | sha256sum --check --strict

# Materialize and verify the two approved executable SpotBugs artifacts before
# Maven is allowed to load either one. Maven strict-checksum mode applies to
# every remaining POM and transitive artifact resolved into this isolated
# repository; the producer rechecks the approved executable JARs after the run.
spotbugs_plugin="$maven_repository/com/github/spotbugs/spotbugs-maven-plugin/4.9.8.3/spotbugs-maven-plugin-4.9.8.3.jar"
spotbugs_engine="$maven_repository/com/github/spotbugs/spotbugs/4.9.8/spotbugs-4.9.8.jar"
mkdir -p "$(dirname "$spotbugs_plugin")" "$(dirname "$spotbugs_engine")"
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
	--retry 3 \
	https://repo.maven.apache.org/maven2/com/github/spotbugs/spotbugs-maven-plugin/4.9.8.3/spotbugs-maven-plugin-4.9.8.3.jar \
	--output "$spotbugs_plugin"
curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
	--retry 3 \
	https://repo.maven.apache.org/maven2/com/github/spotbugs/spotbugs/4.9.8/spotbugs-4.9.8.jar \
	--output "$spotbugs_engine"
printf '%s  %s\n' \
	bceba1f3c178e36d9a5ca1f76b86cd15bed73150ce7a820df470c6c3f5fa8757 \
	"$spotbugs_plugin" | sha256sum --check --strict
printf '%s  %s\n' \
	4469bc080afe7cd2290a20bf63e28392b80abcc7c7ace33c8f55da52a17c7ca5 \
	"$spotbugs_engine" | sha256sum --check --strict
(
	cd "$candidate_root"
	env JAVA_HOME="$SOKLET_RELEASE_CORE_JDK_21_HOME" \
		PATH="$SOKLET_RELEASE_CORE_JDK_21_HOME/bin:$PATH" \
		mvn -B -ntp -C -Dgpg.skip=true -DskipTests \
		-Dmaven.repo.local="$maven_repository" \
		-Dsoklet.spotbugs.excludeFilterFile="$spotbugs_filter" \
		-Pspotbugs compile spotbugs:check
)
spotbugs_report="$candidate_root/target/spotbugsXml.xml"
[[ -f "$spotbugs_report" && ! -L "$spotbugs_report" ]] \
	|| { printf 'SpotBugs XML report is missing.\n' >&2; exit 1; }
cp "$spotbugs_report" "$raw_reports_root/01-spotbugs.xml"

[[ -f "$spotbugs_plugin" && ! -L "$spotbugs_plugin" \
		&& -f "$spotbugs_engine" && ! -L "$spotbugs_engine" ]] \
	|| { printf 'Pinned SpotBugs artifacts are missing from the isolated Maven repository.\n' >&2; exit 1; }
printf '%s  %s\n' \
	bceba1f3c178e36d9a5ca1f76b86cd15bed73150ce7a820df470c6c3f5fa8757 \
	"$spotbugs_plugin" | sha256sum --check --strict
printf '%s  %s\n' \
	4469bc080afe7cd2290a20bf63e28392b80abcc7c7ace33c8f55da52a17c7ca5 \
	"$spotbugs_engine" | sha256sum --check --strict
cp "$spotbugs_plugin" "$provenance_root/spotbugs-maven-plugin.jar"
cp "$spotbugs_engine" "$provenance_root/spotbugs.jar"

node "$candidate_root/scripts/verify-runtime-dependency-surface.mjs" \
	"$candidate_root/pom.xml" \
	"$raw_reports_root/04-runtime-dependency-surface.json"
node "$candidate_root/scripts/produce-release-scans.mjs" \
	--candidate-root "$candidate_root" \
	--approvals "$approvals" \
	--raw-reports-root "$raw_reports_root" \
	--provenance-root "$provenance_root" \
	--evidence-root "$evidence_root" \
	--output "$bundle_output"
