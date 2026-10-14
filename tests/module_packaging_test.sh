#!/usr/bin/env sh
# 文件: tests/module_packaging_test.sh
# 功能: 验证内置管理器的模块 ZIP、独立 APK 以及构建发布契约。
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
assert_contains "$BUILD_ACTION" 'sh .github/scripts/package-module.sh src/module . "$STANDARD_NAME"'
assert_contains "$VERIFY_SCRIPT" './cmd/netproxyctl'
for script in "$BUILD_ACTION" "$VERIFY_SCRIPT"; do
  grep -Eq -- "-ldflags=['\"]-s -w -buildid=([ '\"])" "$script" || {
    printf '缺少发行链接参数: %s\n' "$script" >&2
    exit 1
  }
done
assert_not_contains "$BUILD_ACTION" 'full_name|lite_name|_lite'
assert_not_contains "$BUILD_ACTION" 'netproxy-native|cmd/netproxy-native'
assert_not_contains "$BUILD_ACTION" 'manager_name|with-manager'

assert_contains "$MANAGER_ACTION" 'apk_name:'
assert_contains "$MANAGER_ACTION" ':app:assembleRelease'
assert_contains "$MANAGER_ACTION" '"$apksigner" sign'

[ ! -e "$ROOT/src/module/bin/netproxy-native" ] || {
  printf '%s\n' '模块目录仍包含已删除的 netproxy-native' >&2
  exit 1
}

assert_contains "$RELEASE_WORKFLOW" 'needs: verify'
assert_contains "$RELEASE_WORKFLOW" 'uses: ./.github/actions/build-module'
assert_contains "$RELEASE_WORKFLOW" 'uses: ./.github/actions/build-manager'
assert_contains "$RELEASE_WORKFLOW" 'sh .github/scripts/complete-module.sh "$STANDARD_NAME" "$bundle_dir/NetProxy.apk"'
assert_contains "$RELEASE_WORKFLOW" 'sh tests/module_install_test.sh "$STANDARD_NAME" "$APK_NAME"'
assert_contains "$RELEASE_WORKFLOW" 'STANDARD_NAME: ${{ steps.pack.outputs.standard_name }}'
assert_contains "$RELEASE_WORKFLOW" 'APK_NAME: ${{ steps.manager.outputs.apk_name }}'
assert_contains "$RELEASE_WORKFLOW" '"$STANDARD_NAME"'
assert_contains "$RELEASE_WORKFLOW" '"$APK_NAME"'
assert_contains "$RELEASE_WORKFLOW" 'gh release upload'
assert_not_contains "$RELEASE_WORKFLOW" 'manager_name|with-manager'
assert_not_contains "$RELEASE_WORKFLOW" 'full_name|lite_name|FULL_NAME|LITE_NAME'

assert_contains "$SYNC_WORKFLOW" 'tests/module_packaging_test.sh'
assert_contains "$SYNC_WORKFLOW" 'tests/module_install_test.sh'

TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT HUP INT TERM
mkdir -p "$TEMP/module/config/singbox" "$TEMP/module/runtime" "$TEMP/module/bin"
mkdir -p "$TEMP/module/config/ebpf" "$TEMP/module/data/catalog/default"
for file in customize.sh netproxyctl service.sh action.sh uninstall.sh \
  config/module.conf config/ebpf/ebpf.conf \
  data/catalog/default/meta.json data/catalog/default/provider.json; do
  cp "$ROOT/src/module/$file" "$TEMP/module/$file"
done
printf 'core fixture\n' > "$TEMP/module/bin/sing-box"
printf 'id=netproxy\nversion=test\n' > "$TEMP/module/module.prop"
fixture_line=0
while [ "$fixture_line" -lt 256 ]; do
  printf 'binary fixture\n'
  fixture_line=$((fixture_line + 1))
done > "$TEMP/module/bin/netproxyctl"
printf '{}\n' > "$TEMP/module/config/singbox/config.json"
printf 'manager fixture\n' > "$TEMP/module/NetProxy.apk"
: > "$TEMP/module/runtime/.gitkeep"

sh "$ROOT/.github/scripts/package-module.sh" "$TEMP/module" "$TEMP/output" standard.zip > "$TEMP/package.log"
7z l -slt "$TEMP/output/standard.zip" | tr '\\' '/' > "$TEMP/standard.list"
assert_contains "$TEMP/standard.list" 'Path = module.prop'
assert_contains "$TEMP/standard.list" 'Path = runtime/.gitkeep'
grep -iq '^Method = xz$' "$TEMP/standard.list" || {
  printf '%s\n' '模块内容未使用 XZ 压缩' >&2
  exit 1
}
for file in module.prop bin/netproxyctl config/singbox/config.json; do
  7z x -so "$TEMP/output/standard.zip" "$file" > "$TEMP/extracted"
  cmp "$TEMP/module/$file" "$TEMP/extracted"
done
assert_not_contains "$TEMP/standard.list" '^Path = NetProxy[.]apk$'
if sh "$ROOT/.github/scripts/package-module.sh" "$TEMP/module" "$TEMP/output" standard.zip >/dev/null 2>&1; then
  printf '%s\n' '打包程序不应复用已有输出归档' >&2
  exit 1
fi

if sh "$ROOT/tests/module_install_test.sh" "$TEMP/output/standard.zip" "$TEMP/module/NetProxy.apk" > "$TEMP/install.log" 2>&1; then
  printf '%s\n' '缺少管理器的模块 ZIP 不应通过安装检查' >&2
  exit 1
fi
assert_contains "$TEMP/install.log" '安装包或权限检查失败'
sh "$ROOT/.github/scripts/complete-module.sh" "$TEMP/output/standard.zip" "$TEMP/module/NetProxy.apk" > "$TEMP/complete.log"
7z x -so "$TEMP/output/standard.zip" NetProxy.apk > "$TEMP/extracted"
cmp "$TEMP/module/NetProxy.apk" "$TEMP/extracted"
sh "$ROOT/tests/module_install_test.sh" "$TEMP/output/standard.zip" "$TEMP/module/NetProxy.apk"
printf 'different manager\n' > "$TEMP/different.apk"
if sh "$ROOT/tests/module_install_test.sh" "$TEMP/output/standard.zip" "$TEMP/different.apk" > "$TEMP/install.log" 2>&1; then
  printf '%s\n' '模块随附 APK 与独立资产不一致时不应通过检查' >&2
  exit 1
fi

printf '%s\n' 'module packaging test passed'
