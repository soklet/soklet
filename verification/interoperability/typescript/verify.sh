#!/bin/sh

set -eu

fail() {
	printf 'TypeScript interoperability failed: %s\n' "$*" >&2
	exit 1
}

[ "$#" -eq 2 ] || fail "usage: verify.sh <candidate-jar> <typescript-sdk-checkout>"
candidate_jar=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd -P)/$(basename -- "$1")
[ -d "$2" ] && [ ! -L "$2" ] \
	|| fail "TypeScript SDK checkout must be a regular nonsymlink directory"
sdk_checkout=$(CDPATH= cd -- "$2" && pwd -P)
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
project_root=$(CDPATH= cd -- "$script_directory/../../.." && pwd -P)
expected_commit=cc4b41617ce3601b1290d67216ea0b194a3cd9ac
artifact_identity=npm:@modelcontextprotocol/client@2.0.0
artifact_checksum='sha512-8f1OghQ2rjzIOfqgUCP+8GiUWqRs89njoWLNqAe8kWmDePv3s1fZXseej+QXemssEuuOvLLmLO/kqM3IQHtISw=='
node_command=$(command -v node) || fail "node is not on PATH"
npm_command=$(command -v npm) || fail "npm is not on PATH"
command_runner=$project_root/verification/interoperability/run-command.mjs

[ -f "$candidate_jar" ] && [ ! -L "$candidate_jar" ] \
	|| fail "candidate JAR must be a regular nonsymlink file"
[ "$(git -C "$sdk_checkout" rev-parse --show-toplevel)" = "$sdk_checkout" ] \
	|| fail "TypeScript SDK path is not its Git checkout root"
[ "$(git -C "$sdk_checkout" rev-parse HEAD)" = "$expected_commit" ] \
	|| fail "TypeScript SDK checkout is not the reviewed 2.0.0 commit"
[ -z "$(git -C "$sdk_checkout" status --porcelain --untracked-files=all)" ] \
	|| fail "TypeScript SDK checkout is dirty"
"$node_command" -e 'const p=require(process.argv[1]); if(p.name!=="@modelcontextprotocol/client"||p.version!=="2.0.0")process.exit(1)' \
	"$sdk_checkout/packages/client/package.json" \
	|| fail "reviewed checkout does not contain @modelcontextprotocol/client 2.0.0"

work_root=$(mktemp -d "${TMPDIR:-/tmp}/soklet-typescript-interop.XXXXXX")
cleanup() {
	case "$work_root" in
		*/soklet-typescript-interop.*)
			chmod -R u+w "$work_root" 2>/dev/null || true
			rm -rf -- "$work_root"
			;;
		*) fail "refusing to remove unexpected temporary directory" ;;
	esac
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

cp "$script_directory/package.json" "$script_directory/package-lock.json" \
	"$script_directory/client.mjs" "$work_root/"
mkdir -p "$work_root/npm-cache" "$work_root/npm-home"
touch "$work_root/npm-user.npmrc" "$work_root/npm-global.npmrc"
(
	cd "$work_root"
	env -i PATH="$PATH" HOME="$work_root/npm-home" LANG=C.UTF-8 CI=true \
		NO_COLOR=1 npm_config_cache="$work_root/npm-cache" \
		npm_config_userconfig="$work_root/npm-user.npmrc" \
		npm_config_globalconfig="$work_root/npm-global.npmrc" \
		"$node_command" "$command_runner" 600 "$work_root" "$npm_command" \
		ci --ignore-scripts --no-audit --no-fund
)
"$node_command" -e 'const p=require(process.argv[1]); if(p.name!=="@modelcontextprotocol/client"||p.version!=="2.0.0")process.exit(1)' \
	"$work_root/node_modules/@modelcontextprotocol/client/package.json" \
	|| fail "installed TypeScript client identity is wrong"
cmp -s "$script_directory/package.json" "$work_root/package.json" \
	&& cmp -s "$script_directory/package-lock.json" "$work_root/package-lock.json" \
	|| fail "TypeScript dependency manifests changed during installation"

"$node_command" "$project_root/verification/interoperability/run-against-public-fixture.mjs" \
	"$candidate_jar" "$work_root" typescript "$artifact_identity" \
	"$artifact_checksum" "$expected_commit" "$node_command" \
	"$work_root/client.mjs"
[ -z "$(git -C "$sdk_checkout" status --porcelain --untracked-files=all)" ] \
	|| fail "TypeScript SDK checkout changed during verification"
