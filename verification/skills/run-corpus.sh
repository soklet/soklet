#!/bin/sh
set -eu

# Offline, report-only qualification aid. No download or production dependency.
if [ "$#" -lt 4 ] || [ "$#" -gt 5 ]; then
	echo "Usage: $0 JAVA_HOME CORE_CLASSES CORPUS_CHECKOUT OUTPUT_DIRECTORY [SNAKEYAML_ENGINE_JAR]" >&2
	exit 2
fi
java_home=$1
core_classes=$2
corpus=$3
output=$4
reference_jar=${5:-}
verification_root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
expected_commit=6e6c296ae9c9d2d5c4134b4b64d01b29ac19ff6f
[ "$(git -C "$corpus" rev-parse HEAD)" = "$expected_commit" ] || {
	echo "Corpus commit does not match the reviewed pin." >&2; exit 2;
}
git -C "$corpus" diff --quiet HEAD -- || {
	echo "Corpus tracked files differ from the reviewed pin." >&2; exit 2;
}
[ -x "$java_home/bin/java" ] && [ -x "$java_home/bin/javac" ] || {
	echo "JAVA_HOME must provide java and javac." >&2; exit 2;
}
[ -d "$core_classes" ] || { echo "CORE_CLASSES must be a compiled classes directory." >&2; exit 2; }
if [ -n "$reference_jar" ] && [ ! -f "$reference_jar" ]; then
	echo "Optional reference jar does not exist." >&2; exit 2
fi
# Keep artifacts for diagnosis; use a fresh directory so previous results cannot
# be mistaken for this run after compilation or pin validation fails.
[ ! -e "$output" ] || { echo "OUTPUT_DIRECTORY must not already exist." >&2; exit 2; }
mkdir -p "$output/classes"
"$java_home/bin/javac" --release 17 -proc:none -classpath "$core_classes" \
	-d "$output/classes" "$verification_root/SkillYamlCorpusRunner.java" \
	"$verification_root/SkillYamlEvents.java" "$verification_root/SkillYamlReferenceEvents.java"
classpath="$output/classes:$core_classes"
[ -z "$reference_jar" ] || classpath="$classpath:$reference_jar"
"$java_home/bin/java" -Xmx256m -classpath "$classpath" \
	com.soklet.internal.mcp.skills.SkillYamlCorpusRunner \
	"$core_classes" "$corpus" "$output" "$reference_jar"
