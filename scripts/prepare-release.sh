#!/usr/bin/env bash
# Builds the version already reviewed and merged through the release PR.
set -euo pipefail

version="${1:?usage: prepare-release.sh <version>}"
manifest="kiosk-satellite-plugin.json"
zip="dist/smt101-sensors-$version.zip"

test "$(jq -er '.version' "$manifest")" = "$version"
test "$(cat version.txt)" = "$version"
test "$(jq -er '."."' .release-please-manifest.json)" = "$version"

gradle --no-daemon clean build -PandroidPlatform="${ANDROID_PLATFORM:-35}"

test -f "$zip"
test -f "$zip.sha256"
test -f dist/kiosk-satellite-plugin.json
(cd dist && sha256sum --check "$(basename "$zip").sha256")
test "$(unzip -p "$zip" kiosk-satellite-plugin.json | jq -er '.version')" = "$version"
test "$(jq -er '.version' dist/kiosk-satellite-plugin.json)" = "$version"
