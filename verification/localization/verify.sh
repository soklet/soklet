#!/bin/sh

set -eu

# Candidate-artifact-only, library-neutral localization verification.

verification_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
soklet_project=$(CDPATH= cd -- "$verification_root/../.." && pwd)
candidate_input=${1:-${SOKLET_CANDIDATE_ARTIFACT:-}}

fail() {
	echo "Candidate-artifact localization verification failed: $*" >&2
	exit 1
}

if [ -z "$candidate_input" ]; then
	fail "pass the packaged Soklet jar as argument 1 or SOKLET_CANDIDATE_ARTIFACT."
fi
if [ "$#" -gt 1 ]; then
	fail "expected only the packaged Soklet jar; translation-library adapters live in documentation."
fi
if [ ! -f "$candidate_input" ]; then
	fail "candidate jar does not exist: $candidate_input"
fi
for command in java javac; do
	command -v "$command" >/dev/null 2>&1 \
		|| fail "$command was not found on PATH."
done

candidate_directory=$(CDPATH= cd -- "$(dirname -- "$candidate_input")" && pwd)
candidate_artifact="$candidate_directory/$(basename -- "$candidate_input")"

"$verification_root/verify-candidate-pom.sh" \
	"$candidate_artifact" "$soklet_project/pom.xml"

temporary_directory=$(mktemp -d \
	"${TMPDIR:-/tmp}/soklet-localization-verification.XXXXXX")
cleanup() {
	case "$temporary_directory" in
		*/soklet-localization-verification.*)
			rm -rf -- "$temporary_directory"
			;;
		*)
			echo "Refusing to remove unexpected temporary path." >&2
			;;
	esac
}
trap cleanup EXIT HUP INT TERM

generic_sources="$temporary_directory/generic-sources.txt"
find "$verification_root/src/main/java/examples/generic" \
	-type f -name '*.java' -print | LC_ALL=C sort > "$generic_sources"
[ -s "$generic_sources" ] \
	|| fail "the library-neutral provider source set is empty."

generic_classes="$temporary_directory/generic-classes"
mkdir -p "$generic_classes"

# The generic provider sees only the packaged Soklet jar.
javac --release 17 -proc:none -Xlint:all -Werror \
	-classpath "$candidate_artifact" \
	-d "$generic_classes" \
	@"$generic_sources"
java -classpath "$candidate_artifact:$generic_classes" \
	examples.generic.GenericLocalizationProviderExample \
	> "$temporary_directory/generic-output.txt"
grep -Fqx 'Generic localization provider example is usable against the candidate artifact.' \
	"$temporary_directory/generic-output.txt" \
	|| fail "generic candidate-artifact smoke did not report success."

generic_count=$(wc -l < "$generic_sources" | tr -d ' ')

echo "Candidate-artifact localization verification passed."
echo "  Soklet artifact: $(basename -- "$candidate_artifact") (embedded POM declares zero runtime dependencies)"
echo "  Generic: $generic_count source(s) compiled and ran against the jar alone"
echo "  Toolchain: $(javac -version 2>&1)"
