#!/bin/sh

set -eu

fail() {
	printf 'Go interoperability failed: %s\n' "$*" >&2
	exit 1
}

[ "$#" -eq 2 ] || fail "usage: verify.sh <candidate-jar> <go-sdk-checkout>"
candidate_jar=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd -P)/$(basename -- "$1")
[ -d "$2" ] && [ ! -L "$2" ] \
	|| fail "Go SDK checkout must be a regular nonsymlink directory"
sdk_checkout=$(CDPATH= cd -- "$2" && pwd -P)
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
project_root=$(CDPATH= cd -- "$script_directory/../../.." && pwd -P)
expected_commit=bc72835f62eb94d0fb484439f886b6885b075f36
artifact_identity=github.com/modelcontextprotocol/go-sdk@v1.7.0
artifact_checksum='h1:yqjY2dsbKAC0LSuWZVBMrHgiG8ukXv6NRo0JiALay44='
node_command=$(command -v node) || fail "node is not on PATH"
go_command=$(command -v go) || fail "go is not on PATH"
command_runner=$project_root/verification/interoperability/run-command.mjs

[ -f "$candidate_jar" ] && [ ! -L "$candidate_jar" ] \
	|| fail "candidate JAR must be a regular nonsymlink file"
[ "$(git -C "$sdk_checkout" rev-parse --show-toplevel)" = "$sdk_checkout" ] \
	|| fail "Go SDK path is not its Git checkout root"
[ "$(git -C "$sdk_checkout" rev-parse HEAD)" = "$expected_commit" ] \
	|| fail "Go SDK checkout is not the reviewed v1.7.0 commit"
[ -z "$(git -C "$sdk_checkout" status --porcelain --untracked-files=all)" ] \
	|| fail "Go SDK checkout is dirty"
grep -Fxq 'module github.com/modelcontextprotocol/go-sdk' "$sdk_checkout/go.mod" \
	|| fail "reviewed checkout has the wrong Go module identity"

work_root=$(mktemp -d "${TMPDIR:-/tmp}/soklet-go-interop.XXXXXX")
cleanup() {
	case "$work_root" in
		*/soklet-go-interop.*)
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

cp "$script_directory/go.mod" "$script_directory/go.sum" \
	"$script_directory/client.go" "$work_root/"
mkdir -p "$work_root/go-cache" "$work_root/go-mod-cache" "$work_root/go-path"
(
	cd "$work_root"
	env -i PATH="$PATH" HOME="$work_root" LANG=C.UTF-8 CI=true NO_COLOR=1 \
		GOCACHE="$work_root/go-cache" GOMODCACHE="$work_root/go-mod-cache" \
		GOPATH="$work_root/go-path" GOPROXY=https://proxy.golang.org \
		GOSUMDB=sum.golang.org GOTOOLCHAIN=local \
		"$node_command" "$command_runner" 600 "$work_root" "$go_command" \
		mod download
	env -i PATH="$PATH" HOME="$work_root" LANG=C.UTF-8 CI=true NO_COLOR=1 \
		GOCACHE="$work_root/go-cache" GOMODCACHE="$work_root/go-mod-cache" \
		GOPATH="$work_root/go-path" GOPROXY=https://proxy.golang.org \
		GOSUMDB=sum.golang.org GOTOOLCHAIN=local \
		"$node_command" "$command_runner" 120 "$work_root" "$go_command" \
		mod verify
	env -i PATH="$PATH" HOME="$work_root" LANG=C.UTF-8 CI=true NO_COLOR=1 \
		GOCACHE="$work_root/go-cache" GOMODCACHE="$work_root/go-mod-cache" \
		GOPATH="$work_root/go-path" GOPROXY=off GOSUMDB=off GOTOOLCHAIN=local \
		"$node_command" "$command_runner" 300 "$work_root" "$go_command" \
		build -mod=readonly -trimpath -o "$work_root/client" ./client.go
)
cmp -s "$script_directory/go.mod" "$work_root/go.mod" \
	&& cmp -s "$script_directory/go.sum" "$work_root/go.sum" \
	|| fail "Go dependency manifests changed during verification"

"$node_command" "$project_root/verification/interoperability/run-against-public-fixture.mjs" \
	"$candidate_jar" "$work_root" go "$artifact_identity" \
	"$artifact_checksum" "$expected_commit" "$work_root/client"
[ -z "$(git -C "$sdk_checkout" status --porcelain --untracked-files=all)" ] \
	|| fail "Go SDK checkout changed during verification"
