#!/bin/sh

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
PROJECT_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)
NODE_EXECUTABLE=${SOKLET_API_DIFF_NODE:-node}
API_DIRECTORY="$PROJECT_ROOT/api/mcp"
API_FREEZE_REPORT="$PROJECT_ROOT/target/japicmp/mcp-api-freeze.xml"
GENERATED_DIRECTORY="$PROJECT_ROOT/target/mcp-api-freezes"
FROZEN_PHASES="$API_DIRECTORY/frozen-phases"

[ "$#" -eq 0 ] || {
  echo "Usage: scripts/verify-mcp-api-freezes.sh" >&2
  exit 64
}

[ -f "$FROZEN_PHASES" ] || {
  echo "Missing frozen-phase inventory: $FROZEN_PHASES" >&2
  exit 1
}

expected_phase=4
frozen_phase_count=0
while IFS= read -r phase || [ -n "$phase" ]; do
  case "$phase" in
    4|5|6)
      ;;
    *)
      echo "Invalid frozen phase '$phase'; expected one of 4, 5, or 6" >&2
      exit 1
      ;;
  esac
  [ "$phase" -eq "$expected_phase" ] || {
    echo "Frozen phases must be the contiguous sorted prefix beginning with Phase 4" >&2
    exit 1
  }
  expected_phase=$((expected_phase + 1))
  frozen_phase_count=$((frozen_phase_count + 1))
done < "$FROZEN_PHASES"

[ "$frozen_phase_count" -gt 0 ] || {
  echo "Frozen-phase inventory must contain at least one phase" >&2
  exit 1
}

"$SCRIPT_DIR/api-diff/verify.sh"

"$NODE_EXECUTABLE" "$SCRIPT_DIR/api-diff/japicmp-symbols.mjs" \
  --verify-inventory \
  "$API_FREEZE_REPORT" \
  "$API_DIRECTORY/non-mcp-public-api.allowlist" \
  "$API_DIRECTORY/phase-4.includes" \
  "$API_DIRECTORY/phase-5.includes" \
  "$API_DIRECTORY/phase-6.includes" \
  "$API_DIRECTORY/provisional.includes"

mkdir -p "$GENERATED_DIRECTORY"

while IFS= read -r phase || [ -n "$phase" ]; do
  phase_inventory="$API_DIRECTORY/phase-$phase.includes"
  reviewed_signatures="$API_DIRECTORY/phase-$phase.signatures.jsonl"
  generated_signatures="$GENERATED_DIRECTORY/phase-$phase.signatures.jsonl"

  [ -f "$phase_inventory" ] || {
    echo "Missing Phase $phase API inventory: $phase_inventory" >&2
    exit 1
  }
  [ -f "$reviewed_signatures" ] || {
    echo "Missing reviewed Phase $phase signature snapshot: $reviewed_signatures" >&2
    exit 1
  }

  "$NODE_EXECUTABLE" "$SCRIPT_DIR/api-diff/japicmp-symbols.mjs" \
    --extract-signatures "$API_FREEZE_REPORT" "$phase_inventory" \
    "$generated_signatures"
  "$NODE_EXECUTABLE" "$SCRIPT_DIR/api-diff/japicmp-symbols.mjs" \
    --verify-signatures "$API_FREEZE_REPORT" "$phase_inventory" \
    "$reviewed_signatures"
done < "$FROZEN_PHASES"

"$NODE_EXECUTABLE" "$SCRIPT_DIR/verify-mcp-metadata-builders-self-test.mjs"
"$NODE_EXECUTABLE" "$SCRIPT_DIR/verify-mcp-metadata-builders.mjs"
"$NODE_EXECUTABLE" \
  "$PROJECT_ROOT/conformance/official/verify-profile-evidence-self-test.mjs"
"$NODE_EXECUTABLE" \
  "$PROJECT_ROOT/conformance/official/verify-profile-evidence.mjs"

echo "Verified frozen MCP API phases against reviewed signature snapshots"
