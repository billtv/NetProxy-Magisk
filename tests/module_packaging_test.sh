#!/usr/bin/env sh
# 文件: tests/module_packaging_test.sh
# 功能: 检查标准模块 ZIP、独立管理器 APK 与 GitHub Release 的构建发布契约。
# 用法: sh tests/module_packaging_test.sh
# 依赖: POSIX sh、grep

set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BUILD_ACTION="$ROOT/.github/actions/build-module/action.yml"
MANAGER_ACTION="$ROOT/.github/actions/build-manager/action.yml"
RELEASE_WORKFLOW="$ROOT/.github/workflows/build-release.yml"

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
assert_contains "$BUILD_ACTION" './cmd/netproxyctl'
assert_not_contains "$BUILD_ACTION" 'manager_name|MANAGER_NAME|with-manager|full_name|lite_name|_lite'
assert_not_contains "$BUILD_ACTION" 'netproxy-native|cmd/netproxy-native'
[ ! -e "$ROOT/src/module/bin/netproxy-native" ] || {
  printf '%s\n' '模块目录仍包含已删除的 netproxy-native' >&2
  exit 1
}

assert_contains "$MANAGER_ACTION" 'apk_name="NetProxyManager_${version}_${commit_count}.apk"'
assert_contains "$MANAGER_ACTION" './gradlew :app:assembleRelease --no-daemon'
assert_contains "$MANAGER_ACTION" '"$apksigner" verify --verbose --print-certs'
assert_not_contains "$MANAGER_ACTION" 'with-manager|manager_name|full_name|lite_name'

assert_contains "$RELEASE_WORKFLOW" 'STANDARD_NAME: ${{ steps.pack.outputs.standard_name }}'
assert_contains "$RELEASE_WORKFLOW" 'APK_NAME: ${{ steps.manager.outputs.apk_name }}'
assert_contains "$RELEASE_WORKFLOW" 'gh release upload'
assert_contains "$RELEASE_WORKFLOW" '"$STANDARD_NAME"'
assert_contains "$RELEASE_WORKFLOW" '"$APK_NAME"'
assert_not_contains "$RELEASE_WORKFLOW" 'with-manager|manager_name|full_name|lite_name|FULL_NAME|LITE_NAME'

printf '%s\n' 'module packaging test passed'
