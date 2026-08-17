#!/bin/sh

set -eu

# Fail-closed metadata check used by the downstream localization verifier.

candidate_input=${1:-}
checkout_pom_input=${2:-}

fail() {
	echo "Candidate-artifact metadata verification failed: $*" >&2
	exit 1
}

[ -n "$candidate_input" ] \
	|| fail "pass the packaged Soklet jar as argument 1."
[ -n "$checkout_pom_input" ] \
	|| fail "pass the checkout pom.xml as argument 2."
[ -f "$candidate_input" ] \
	|| fail "candidate jar does not exist: $candidate_input"
[ -f "$checkout_pom_input" ] \
	|| fail "checkout POM does not exist: $checkout_pom_input"

for command in cmp jar; do
	command -v "$command" >/dev/null 2>&1 \
		|| fail "$command was not found on PATH."
done

candidate_directory=$(CDPATH= cd -- "$(dirname -- "$candidate_input")" && pwd)
candidate_artifact="$candidate_directory/$(basename -- "$candidate_input")"
checkout_pom_directory=$(CDPATH= cd -- "$(dirname -- "$checkout_pom_input")" && pwd)
checkout_pom="$checkout_pom_directory/$(basename -- "$checkout_pom_input")"

temporary_directory=$(mktemp -d \
	"${TMPDIR:-/tmp}/soklet-candidate-pom-verification.XXXXXX")
cleanup() {
	case "$temporary_directory" in
		*/soklet-candidate-pom-verification.*)
			rm -rf -- "$temporary_directory"
			;;
		*)
			echo "Refusing to remove unexpected temporary path." >&2
			;;
	esac
}
trap cleanup EXIT HUP INT TERM

artifact_entries="$temporary_directory/artifact-entries.txt"
jar tf "$candidate_artifact" > "$artifact_entries" \
	|| fail "candidate artifact is not a readable jar."
grep -Fqx 'com/soklet/McpLocalizer.class' "$artifact_entries" \
	|| fail "candidate jar does not contain the Soklet localization API."

candidate_pom_entry='META-INF/maven/com.soklet/soklet/pom.xml'
candidate_pom_count=$(grep -Fxc "$candidate_pom_entry" "$artifact_entries" || true)
if [ "$candidate_pom_count" -ne 1 ]; then
	fail "candidate jar must contain exactly one $candidate_pom_entry; found $candidate_pom_count."
fi

candidate_pom_root="$temporary_directory/candidate-pom"
mkdir -p "$candidate_pom_root"
(
	cd "$candidate_pom_root"
	jar xf "$candidate_artifact" "$candidate_pom_entry"
) || fail "candidate POM could not be extracted from the jar."
candidate_pom="$candidate_pom_root/$candidate_pom_entry"
[ -f "$candidate_pom" ] \
	|| fail "candidate POM was not extracted from the jar."

# This development-candidate gate is checkout-coupled by design: the generic
# example comes from the checkout. Refuse a stale or substituted artifact
# instead of combining its API/POM with evidence from a different source tree.
cmp -s "$candidate_pom" "$checkout_pom" \
	|| fail "candidate embedded POM does not match the checkout pom.xml."

# Audit the supplied artifact's own dependency declaration, not merely the
# checkout. Every direct core dependency must remain compile-time-only
# (provided) or test-only.
dependency_report="$temporary_directory/core-dependencies.txt"
if ! awk '
	BEGIN {
		in_dependencies = 0
		in_dependency = 0
		found_dependencies = 0
		dependency_count = 0
	}
	!found_dependencies && /^[[:space:]]*<dependencies>[[:space:]]*$/ {
		found_dependencies = 1
		in_dependencies = 1
		next
	}
	in_dependencies && /^[[:space:]]*<dependency>[[:space:]]*$/ {
		in_dependency = 1
		artifact = ""
		scope = "compile"
		next
	}
	in_dependency && /<artifactId>/ {
		line = $0
		sub(/.*<artifactId>/, "", line)
		sub(/<\/artifactId>.*/, "", line)
		artifact = line
	}
	in_dependency && /<scope>/ {
		line = $0
		sub(/.*<scope>/, "", line)
		sub(/<\/scope>.*/, "", line)
		scope = line
	}
	in_dependency && /^[[:space:]]*<\/dependency>[[:space:]]*$/ {
		dependency_count++
		if (artifact == "") exit 41
		if (scope != "provided" && scope != "test")
			print artifact " (" scope ")"
		in_dependency = 0
		next
	}
	in_dependencies && /^[[:space:]]*<\/dependencies>[[:space:]]*$/ {
		in_dependencies = 0
		next
	}
	END {
		if (!found_dependencies || dependency_count == 0 ||
				in_dependencies || in_dependency) exit 42
	}
' "$candidate_pom" > "$dependency_report"; then
	fail "could not audit Soklet core dependencies from the candidate POM."
fi
if [ -s "$dependency_report" ]; then
	echo "Soklet core must have zero runtime dependencies, but found:" >&2
	sed 's/^/  /' "$dependency_report" >&2
	exit 1
fi
