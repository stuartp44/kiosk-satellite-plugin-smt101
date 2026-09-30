#!/usr/bin/env bash
# Called by semantic-release: writes the next version into the manifest,
# then runs the clean build/tests and verifies the packaged artifacts.
set -euo pipefail

version="${1:?usage: prepare-release.sh <version>}"
manifest="kiosk-satellite-plugin.json"
zip="dist/smt101-sensors-$version.zip"

tmp="$(mktemp)"
jq --arg v "$version" '.version = $v' "$manifest" > "$tmp"
mv "$tmp" "$manifest"

gradle --no-daemon clean build -PandroidPlatform="${ANDROID_PLATFORM:-35}"

test -f "$zip"
test -f "$zip.sha256"
test -f dist/kiosk-satellite-plugin.json
(cd dist && sha256sum --check "$(basename "$zip").sha256")
test "$(unzip -p "$zip" kiosk-satellite-plugin.json | jq -er '.version')" = "$version"
test "$(jq -er '.version' dist/kiosk-satellite-plugin.json)" = "$version"
