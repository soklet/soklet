#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
CANDIDATE_JAR=${1:?Usage: build-fixture.sh <candidate-jar> <empty-output-root>}
OUTPUT_ROOT=${2:?Usage: build-fixture.sh <candidate-jar> <empty-output-root>}
SOURCE="$SCRIPT_DIR/McpLegacyConformanceFixture.java"
RUNTIME_SOURCE="$SCRIPT_DIR/McpLegacyRuntimeFixture.java"
CLASSES_DIR="$OUTPUT_ROOT/classes"
DEPENDENCIES_FILE="$OUTPUT_ROOT/dependencies.txt"

case "$CANDIDATE_JAR:$OUTPUT_ROOT" in
  /*:/*) ;;
  *) echo "Legacy fixture paths must be absolute." >&2; exit 1 ;;
esac
[ -f "$CANDIDATE_JAR" ] && [ ! -L "$CANDIDATE_JAR" ] || {
  echo "Legacy fixture requires a regular candidate JAR." >&2
  exit 1
}
[ -f "$SOURCE" ] && [ ! -L "$SOURCE" ] && [ -f "$RUNTIME_SOURCE" ] && [ ! -L "$RUNTIME_SOURCE" ] || {
  echo "Legacy fixture source is missing or unsafe." >&2
  exit 1
}
if [ -L "$OUTPUT_ROOT" ] || { [ -e "$OUTPUT_ROOT" ] \
    && { [ ! -d "$OUTPUT_ROOT" ] \
      || find "$OUTPUT_ROOT" -mindepth 1 -print -quit | grep -q .; }; }; then
  echo "Legacy fixture output root must be a real empty directory: $OUTPUT_ROOT" >&2
  exit 1
fi
if grep -n 'com\.soklet\.internal' "$SOURCE" "$RUNTIME_SOURCE"; then
  echo "Legacy fixture must use only the public Soklet API." >&2
  exit 1
fi

mkdir -p "$CLASSES_DIR"
javac --release 17 -proc:none -Xlint:all -Werror \
  -classpath "$CANDIDATE_JAR" \
  -d "$CLASSES_DIR" \
  "$SOURCE" "$RUNTIME_SOURCE"

jdeps -q --multi-release 17 -verbose:class \
  -classpath "$CANDIDATE_JAR" "$CLASSES_DIR" \
  > "$DEPENDENCIES_FILE"
if grep -n 'com\.soklet\.internal' "$DEPENDENCIES_FILE"; then
  echo "Compiled legacy fixture depends on Soklet internals." >&2
  exit 1
fi

printf '%s:%s\n' "$CLASSES_DIR" "$CANDIDATE_JAR"
