#!/usr/bin/env sh
# 文件: tests/module_packaging_test.sh
# 功能: 检查标准模块包与独立管理器 APK 的构建、发布命名契约。
# 用法: sh tests/module_packaging_test.sh
# 依赖: POSIX sh、grep

set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BUILD_ACTION="$ROOT/.github/actions/build-module/action.yml"
MANAGER_ACTION="$ROOT/.github/actions/build-manager/action.yml"
ANDROID_BUILD="$ROOT/src/android/app/build.gradle.kts"
RELEASE_WORKFLOW="$ROOT/.github/workflows/release.yml"
CI_WORKFLOW="$ROOT/.github/workflows/ci.yml"
SYNC_WORKFLOW="$ROOT/.github/workflows/sync-upstream.yml"

assert_contains() {
  grep -Fq "$2" "$1" || {
    printf '缺少发行契约: %s\n' "$2" >&2
    return 1
  }
}

assert_not_contains() {
  if grep -Eq "$2" "$1"; then
    printf '发现已废弃的发行命名: %s\n' "$2" >&2
    return 1
  fi
}

assert_contains "$BUILD_ACTION" 'standard_name=NetProxy_${VERSION}_${COMMIT_COUNT}.zip'
assert_contains "$BUILD_ACTION" '7z a -tzip -mx=9 "../../$STANDARD_NAME" . -x!"NetProxy.apk"'
assert_not_contains "$BUILD_ACTION" 'full_name|lite_name|_lite'
assert_not_contains "$BUILD_ACTION" 'manager_name|with-manager'

assert_contains "$MANAGER_ACTION" 'apk_name="NetProxyManager_${version}_${commit_count}.apk"'
assert_contains "$MANAGER_ACTION" 'echo "apk_name=$apk_name"'
assert_contains "$MANAGER_ACTION" 'keytool -genkeypair'
assert_contains "$MANAGER_ACTION" 'NETPROXY_RELEASE_STORE_FILE'
assert_contains "$ANDROID_BUILD" 'providers.environmentVariable("NETPROXY_RELEASE_STORE_FILE")'
assert_contains "$ANDROID_BUILD" 'signingConfig = signingConfigs.getByName("ciRelease")'

assert_contains "$RELEASE_WORKFLOW" 'STANDARD_NAME: ${{ steps.pack.outputs.standard_name }}'
assert_contains "$RELEASE_WORKFLOW" 'APK_NAME: ${{ steps.manager.outputs.apk_name }}'
assert_contains "$RELEASE_WORKFLOW" '[标准包]'
assert_contains "$RELEASE_WORKFLOW" '[管理器 APK]'
assert_not_contains "$RELEASE_WORKFLOW" 'update.json|manager_name|with-manager'
assert_not_contains "$RELEASE_WORKFLOW" 'full_name|lite_name|FULL_NAME|LITE_NAME'

assert_contains "$CI_WORKFLOW" '${{ steps.pack.outputs.standard_name }}'
assert_contains "$CI_WORKFLOW" '${{ steps.manager.outputs.apk_name }}'
assert_not_contains "$CI_WORKFLOW" 'full_name|lite_name'

assert_contains "$SYNC_WORKFLOW" "cron: '*/15 * * * *'"
assert_contains "$SYNC_WORKFLOW" 'UPSTREAM_REPO: Fanju6/NetProxy-Magisk'
assert_contains "$SYNC_WORKFLOW" 'gh workflow run ci.yml --repo "$GITHUB_REPOSITORY" --ref main'

printf '%s\n' 'module packaging test passed'
