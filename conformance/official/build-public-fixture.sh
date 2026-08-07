#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)
CANDIDATE_JAR=${1:-"$PROJECT_ROOT/target/soklet-3.6.0-SNAPSHOT.jar"}
OUTPUT_ROOT=${2:-"$PROJECT_ROOT/target/conformance/public-fixture"}
SOURCE_ROOT="$SCRIPT_DIR/public-fixture-src"
CLASSES_DIR="$OUTPUT_ROOT/classes"
DEPENDENCIES_FILE="$OUTPUT_ROOT/dependencies.txt"

[ -f "$CANDIDATE_JAR" ] || {
  echo "Missing Soklet candidate JAR: $CANDIDATE_JAR" >&2
  exit 1
}

if [ -d "$CLASSES_DIR" ] && find "$CLASSES_DIR" -type f -print -quit | grep -q .; then
  echo "Public fixture output must be empty: $CLASSES_DIR" >&2
  exit 1
fi

if grep -R -n 'com\.soklet\.internal' "$SOURCE_ROOT"; then
  echo "The public conformance fixture must not reference Soklet internals" >&2
  exit 1
fi

mkdir -p "$CLASSES_DIR"

# This fixture uses only programmatic registration. The candidate JAR also
# contains SokletProcessor's service descriptor, so disable processor discovery
# rather than letting javac execute an irrelevant processor and emit
# JDK-dependent processing warnings under -Werror.
javac --release 17 -proc:none -Xlint:all -Werror \
  -classpath "$CANDIDATE_JAR" \
  -d "$CLASSES_DIR" \
  "$SOURCE_ROOT/com/soklet/McpOfficialSchemaConformanceTool.java" \
  "$SOURCE_ROOT/com/soklet/conformance/McpConformanceFixture.java"

jdeps -q --multi-release 17 -verbose:class \
  -classpath "$CANDIDATE_JAR" "$CLASSES_DIR" > "$DEPENDENCIES_FILE"

if grep -n 'com\.soklet\.internal' "$DEPENDENCIES_FILE"; then
  echo "Compiled public conformance fixture depends on Soklet internals" >&2
  exit 1
fi

echo "$CLASSES_DIR:$CANDIDATE_JAR"
