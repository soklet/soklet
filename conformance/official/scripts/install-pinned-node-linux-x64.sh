#!/usr/bin/env bash

set -euo pipefail

if [[ "$#" -ne 3 ]]; then
  echo "Usage: install-pinned-node-linux-x64.sh <runner-temp> <github-path> <evidence-file>" >&2
  exit 64
fi

mcp_runner_temp="$1"
mcp_github_path="$2"
mcp_evidence_file="$3"

mcp_pin_output="$(node --input-type=module -e '
  import { verifyManifestSet } from "./conformance/official/verify.mjs";
  const { pins } = verifyManifestSet();
  const distribution = pins.toolchain.nodeDistribution;
  process.stdout.write([
    pins.toolchain.node,
    pins.toolchain.npm,
    distribution.checksumsUrl,
    distribution.checksumsSha256,
    distribution.linuxX64Artifact,
    distribution.linuxX64Sha256,
  ].join("\n"));
')"
mapfile -t mcp_pins <<< "${mcp_pin_output}"
if [[ "${#mcp_pins[@]}" -ne 6 ]]; then
  echo "Pinned Node distribution metadata has an unexpected shape." >&2
  exit 1
fi

mcp_node_version="${mcp_pins[0]}"
mcp_npm_version="${mcp_pins[1]}"
mcp_checksums_url="${mcp_pins[2]}"
mcp_checksums_sha256="${mcp_pins[3]}"
mcp_archive="${mcp_pins[4]}"
mcp_archive_sha256="${mcp_pins[5]}"
mcp_node_root="${mcp_runner_temp}/soklet-mcp-node-${mcp_node_version}"
mcp_checksums_path="${mcp_node_root}/SHASUMS256.txt"
mcp_archive_path="${mcp_node_root}/${mcp_archive}"

if [[ -e "${mcp_node_root}" ]]; then
  echo "Pinned Node staging directory already exists: ${mcp_node_root}" >&2
  exit 1
fi
mkdir -p "${mcp_node_root}"

curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
  "${mcp_checksums_url}" --output "${mcp_checksums_path}"
printf '%s  %s\n' "${mcp_checksums_sha256}" "${mcp_checksums_path}" \
  | sha256sum --check --strict

mcp_manifest_matches="$(awk -v artifact="${mcp_archive}" \
  '$2 == artifact { count++ } END { print count + 0 }' "${mcp_checksums_path}")"
if [[ "${mcp_manifest_matches}" -ne 1 ]] \
    || ! grep --fixed-strings --line-regexp --quiet \
      "${mcp_archive_sha256}  ${mcp_archive}" "${mcp_checksums_path}"; then
  echo "Pinned Node archive is absent or ambiguous in the verified checksum manifest." >&2
  exit 1
fi

curl --proto '=https' --tlsv1.2 --fail --location --silent --show-error \
  "https://nodejs.org/dist/v${mcp_node_version}/${mcp_archive}" \
  --output "${mcp_archive_path}"
printf '%s  %s\n' "${mcp_archive_sha256}" "${mcp_archive_path}" \
  | sha256sum --check --strict
tar -xJf "${mcp_archive_path}" -C "${mcp_node_root}"

mcp_node_bin="${mcp_node_root}/node-v${mcp_node_version}-linux-x64/bin"
export PATH="${mcp_node_bin}:${PATH}"
if [[ "$(node --version)" != "v${mcp_node_version}" ]] \
    || [[ "$(npm --version)" != "${mcp_npm_version}" ]]; then
  echo "Extracted Node/npm versions differ from the reviewed pin." >&2
  exit 1
fi

printf '%s\n' "${mcp_node_bin}" >> "${mcp_github_path}"
printf 'node=%s\nnpm=%s\nchecksumsUrl=%s\nchecksumsSha256=%s\narchive=%s\narchiveSha256=%s\n' \
  "${mcp_node_version}" "${mcp_npm_version}" "${mcp_checksums_url}" \
  "${mcp_checksums_sha256}" "${mcp_archive}" "${mcp_archive_sha256}" \
  > "${mcp_evidence_file}"
printf 'Verified Node %s and npm %s from %s.\n' \
  "${mcp_node_version}" "${mcp_npm_version}" "${mcp_archive}"
