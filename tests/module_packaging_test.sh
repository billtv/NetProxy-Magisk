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
BUILD_WORKFLOW="$ROOT/.github/workflows/build-release.yml"
SYNC_WORKFLOW="$ROOT/.github/workflows/sync-upstream.yml"
WORKFLOW_DIR="$ROOT/.github/workflows"

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

assert_contains "$BUILD_WORKFLOW" 'STANDARD_NAME: ${{ steps.pack.outputs.standard_name }}'
assert_contains "$BUILD_WORKFLOW" 'APK_NAME: ${{ steps.manager.outputs.apk_name }}'
assert_contains "$BUILD_WORKFLOW" 'gh release upload'
assert_contains "$BUILD_WORKFLOW" 'cleanup_assets'
assert_not_contains "$BUILD_WORKFLOW" 'update.json|manager_name|with-manager'
assert_not_contains "$BUILD_WORKFLOW" 'full_name|lite_name|FULL_NAME|LITE_NAME'

workflow_count=$(find "$WORKFLOW_DIR" -maxdepth 1 -type f \
  \( -name '*.yml' -o -name '*.yaml' \) | wc -l | tr -d ' ')
[ "$workflow_count" -eq 2 ] || {
  printf '工作流数量不是 2，而是 %s\n' "$workflow_count" >&2
  exit 1
}
[ -f "$BUILD_WORKFLOW" ] && [ -f "$SYNC_WORKFLOW" ] || {
  printf '%s\n' '缺少保留的构建或同步工作流' >&2
  exit 1
}

assert_contains "$SYNC_WORKFLOW" "cron: '*/15 * * * *'"
assert_contains "$SYNC_WORKFLOW" 'UPSTREAM_REPO: Fanju6/NetProxy-Magisk'
assert_contains "$SYNC_WORKFLOW" 'git checkout --ours -- "$path"'
assert_contains "$SYNC_WORKFLOW" 'git checkout --theirs -- "$path"'
assert_contains "$SYNC_WORKFLOW" 'gh workflow run build-release.yml --repo "$GITHUB_REPOSITORY" --ref main'

printf '%s\n' 'module packaging test passed'
