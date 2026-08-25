#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd -P)
MAVEN_EXECUTABLE=${SOKLET_API_DIFF_MAVEN:-mvn}
NODE_EXECUTABLE=${SOKLET_API_DIFF_NODE:-node}
REVIEWED_SET="$PROJECT_ROOT/api/mcp/current-incompatibilities.jsonl"

[ "$#" -eq 0 ] || {
  echo "Usage: scripts/api-diff/verify.sh" >&2
  exit 64
}

"$NODE_EXECUTABLE" "$SCRIPT_DIR/self-test.mjs"

(
  cd "$PROJECT_ROOT"
  "$MAVEN_EXECUTABLE" -B -ntp \
    -Papi-diff \
    -Dgpg.skip=true \
    -Dmaven.javadoc.skip=true \
    -DskipTests \
    clean package \
    com.github.siom79.japicmp:japicmp-maven-plugin:0.26.1:cmp@mcp-api-diff \
    com.github.siom79.japicmp:japicmp-maven-plugin:0.26.1:cmp@mcp-api-freeze
)

RAW_REPORT="$PROJECT_ROOT/target/japicmp/mcp-api-diff.xml"
FULL_REPORT="$PROJECT_ROOT/target/japicmp/mcp-api-freeze.xml"
GENERATED_SET="$PROJECT_ROOT/target/japicmp/mcp-api-diff.incompatibilities.jsonl"

[ -f "$FULL_REPORT" ] || {
  echo "Missing full MCP API freeze report: $FULL_REPORT" >&2
  exit 1
}

"$NODE_EXECUTABLE" "$SCRIPT_DIR/japicmp-symbols.mjs" --verify-report-pair \
  "$RAW_REPORT" "$FULL_REPORT"

"$NODE_EXECUTABLE" "$SCRIPT_DIR/japicmp-symbols.mjs" --extract \
  "$RAW_REPORT" "$GENERATED_SET"
"$NODE_EXECUTABLE" "$SCRIPT_DIR/japicmp-symbols.mjs" --verify \
  "$RAW_REPORT" "$REVIEWED_SET"

echo "Verified Soklet 4.0 public API against the reviewed 3.5.1 incompatibility set"
