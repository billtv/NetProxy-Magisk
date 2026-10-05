#!/usr/bin/env sh
# 文件: tests/module_packaging_test.sh
# 功能: 验证标准模块 ZIP、独立管理器 APK 以及构建发布契约。
# 用法: sh tests/module_packaging_test.sh
# 依赖: POSIX sh、7z、grep、cmp、mktemp

set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
BUILD_ACTION="$ROOT/.github/actions/build-module/action.yml"
MANAGER_ACTION="$ROOT/.github/actions/build-manager/action.yml"
RELEASE_WORKFLOW="$ROOT/.github/workflows/build-release.yml"
SYNC_WORKFLOW="$ROOT/.github/workflows/sync-upstream.yml"
VERIFY_SCRIPT="$ROOT/tests/ci_verify.sh"

assert_contains() {
  grep -Fq -- "$2" "$1" || {
    printf '缺少发行契约: %s\n' "$2" >&2
    return 1
  }
}

assert_not_contains() {
  if grep -Eq -- "$2" "$1"; then
    printf '发现已废弃的发行命名: %s\n' "$2" >&2
    return 1
  fi
}

assert_contains "$BUILD_ACTION" 'standard_name=NetProxy_${VERSION}_${COMMIT_COUNT}.zip'
assert_contains "$BUILD_ACTION" 'go build -v'
assert_contains "$BUILD_ACTION" '7z a -tzip -mx=9 "../../$STANDARD_NAME" . -x!"NetProxy.apk"'
assert_contains "$VERIFY_SCRIPT" './cmd/netproxyctl'
assert_contains "$VERIFY_SCRIPT" "-ldflags='-s -w -buildid='"
assert_not_contains "$BUILD_ACTION" 'full_name|lite_name|_lite'
assert_not_contains "$BUILD_ACTION" 'netproxy-native|cmd/netproxy-native'
assert_not_contains "$BUILD_ACTION" 'manager_name|with-manager'

assert_contains "$MANAGER_ACTION" 'apk_name:'
assert_contains "$MANAGER_ACTION" ':app:assembleRelease'
assert_contains "$MANAGER_ACTION" 'apksigner'

[ ! -e "$ROOT/src/module/bin/netproxy-native" ] || {
  printf '%s\n' '模块目录仍包含已删除的 netproxy-native' >&2
  exit 1
}

assert_contains "$RELEASE_WORKFLOW" 'needs: verify'
assert_contains "$RELEASE_WORKFLOW" 'uses: ./.github/actions/build-module'
assert_contains "$RELEASE_WORKFLOW" 'uses: ./.github/actions/build-manager'
assert_contains "$RELEASE_WORKFLOW" 'STANDARD_NAME: ${{ steps.pack.outputs.standard_name }}'
assert_contains "$RELEASE_WORKFLOW" 'APK_NAME: ${{ steps.manager.outputs.apk_name }}'
assert_contains "$RELEASE_WORKFLOW" '"$STANDARD_NAME"'
assert_contains "$RELEASE_WORKFLOW" '"$APK_NAME"'
assert_contains "$RELEASE_WORKFLOW" 'gh release upload'
assert_not_contains "$RELEASE_WORKFLOW" 'manager_name|with-manager'
assert_not_contains "$RELEASE_WORKFLOW" 'full_name|lite_name|FULL_NAME|LITE_NAME'

assert_contains "$SYNC_WORKFLOW" 'tests/module_packaging_test.sh'

TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT HUP INT TERM
mkdir -p "$TEMP/module/config/singbox" "$TEMP/module/runtime" "$TEMP/module/bin"
printf 'id=netproxy\nversion=test\n' > "$TEMP/module/module.prop"
printf 'binary fixture\n' > "$TEMP/module/bin/netproxyctl"
printf '{}\n' > "$TEMP/module/config/singbox/config.json"
printf 'manager fixture\n' > "$TEMP/module/NetProxy.apk"
: > "$TEMP/module/runtime/.gitkeep"

sh "$ROOT/.github/scripts/package-module.sh" "$TEMP/module" "$TEMP/output" standard.zip manager.zip > "$TEMP/package.log"
for name in standard manager; do
  7z l -slt "$TEMP/output/$name.zip" | tr '\\' '/' > "$TEMP/$name.list"
  assert_contains "$TEMP/$name.list" 'Path = module.prop'
  assert_contains "$TEMP/$name.list" 'Path = runtime/.gitkeep'
  for file in module.prop bin/netproxyctl config/singbox/config.json; do
    7z x -so "$TEMP/output/$name.zip" "$file" > "$TEMP/extracted"
    cmp "$TEMP/module/$file" "$TEMP/extracted"
  done
done
assert_not_contains "$TEMP/standard.list" '^Path = NetProxy[.]apk$'
assert_contains "$TEMP/manager.list" 'Path = NetProxy.apk'
7z x -so "$TEMP/output/manager.zip" NetProxy.apk > "$TEMP/extracted"
cmp "$TEMP/module/NetProxy.apk" "$TEMP/extracted"
if sh "$ROOT/.github/scripts/package-module.sh" "$TEMP/module" "$TEMP/output" standard.zip manager.zip >/dev/null 2>&1; then
  printf '%s\n' '打包程序不应复用已有输出归档' >&2
  exit 1
fi

printf '%s\n' 'module packaging test passed'
