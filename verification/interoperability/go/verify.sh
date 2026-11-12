#!/bin/sh

set -eu

fail() {
	printf 'Go interoperability failed: %s\n' "$*" >&2
	exit 1
}

[ "$#" -eq 1 ] || fail "usage: verify.sh <candidate-jar>"
candidate_jar=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd -P)/$(basename -- "$1")
script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
project_root=$(CDPATH= cd -- "$script_directory/../../.." && pwd -P)
node_command=$(command -v node) || fail "node is not on PATH"
go_command=$(command -v go) || fail "go is not on PATH"
command_runner=$project_root/verification/interoperability/run-command.mjs

[ -f "$candidate_jar" ] && [ ! -L "$candidate_jar" ] \
	|| fail "candidate JAR must be a regular nonsymlink file"
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
	"$candidate_jar" "$work_root" go "$work_root/client"
