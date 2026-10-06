#!/usr/bin/env sh
# 文件: tests/module_install_test.sh
# 功能: 用真实模块 ZIP 执行全新安装入口，验证随附 APK 与独立资产一致。
# 用法: sh tests/module_install_test.sh <模块 ZIP> <管理器 APK>
# 依赖: POSIX sh、7z、sed、grep、cmp、mktemp；可注入 NETPROXY_TEST_BUSYBOX。

set -eu
[ "$#" -eq 2 ] || exit 2
PACKAGE="$(CDPATH= cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")"
EXPECTED_APK="$(CDPATH= cd -- "$(dirname -- "$2")" && pwd)/$(basename -- "$2")"
test -s "$PACKAGE"
test -s "$EXPECTED_APK"
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT HUP INT TERM
mkdir -p "$WORKDIR/mocks" "$WORKDIR/stage" "$WORKDIR/tmp"
INSTALL_SCRIPT="$WORKDIR/customize.sh"
PM_LOG="$WORKDIR/pm.log"
: > "$PM_LOG"

7z x -so "$PACKAGE" customize.sh > "$WORKDIR/original.sh"
test -s "$WORKDIR/original.sh"
# 只重定位设备目录，安装入口、解压、必需文件校验和 APK 清理均执行包内代码。
sed "s@^LIVE_DIR=/data/adb/modules/netproxy@LIVE_DIR=$WORKDIR/live@;s@/dev/netproxy@$WORKDIR/state@g" \
  "$WORKDIR/original.sh" > "$INSTALL_SCRIPT"
for mock_command in chown chcon getevent sleep; do
  printf '#!/bin/sh\nexit 0\n' > "$WORKDIR/mocks/$mock_command"
done
cat > "$WORKDIR/mocks/pm" <<'MOCK'
#!/bin/sh
[ "$#" -eq 3 ] && [ "$1" = install ] && [ "$2" = -r ] || exit 1
cmp "$3" "$EXPECTED_APK" || exit 1
printf '%s\n' "$3" >> "$PM_LOG"
printf '%s\n' Success
MOCK
cat > "$WORKDIR/mocks/unzip" <<'MOCK'
#!/bin/sh
if [ -n "${NETPROXY_TEST_BUSYBOX:-}" ]; then
  exec "$NETPROXY_TEST_BUSYBOX" unzip "$@"
fi
[ "$1" = -o ] || exit 2
case "$3" in
  module.prop) exec 7z x -y "-o$5" "$2" module.prop ;;
  -x) exec 7z x -y "-o$6" "$2" "-x!$4" ;;
  *) exit 2 ;;
esac
MOCK
chmod +x "$WORKDIR/mocks/"*
export EXPECTED_APK PM_LOG INSTALL_SCRIPT

if ! PATH="$WORKDIR/mocks:$PATH" TMPDIR="$WORKDIR/tmp" MODPATH="$WORKDIR/stage" \
  ZIPFILE="$PACKAGE" BOOTMODE=false ASH_STANDALONE=0 sh -c '
    # 参数: $@ 安装文案。返回: 0=已输出。
    ui_print() { printf "%s\n" "$*"; }
    # 参数: $1 属性名，$2 文件。返回: sed 读取状态。
    grep_prop() { sed -n "s/^$1=//p" "$2"; }
    . "$INSTALL_SCRIPT"
  ' > "$WORKDIR/install.log" 2>&1; then
  cat "$WORKDIR/install.log" >&2
  exit 1
fi
grep -Fq '全新安装' "$WORKDIR/install.log"
grep -Fq '管理器安装成功' "$WORKDIR/install.log"
grep -Fq '安装完成' "$WORKDIR/install.log"
test "$(wc -l < "$PM_LOG")" -eq 1
test ! -e "$WORKDIR/stage/NetProxy.apk"
test -x "$WORKDIR/stage/bin/netproxyctl"
test -x "$WORKDIR/stage/bin/sing-box"
test -s "$WORKDIR/stage/data/catalog/default/provider.json"
printf '%s\n' 'module archive installation test passed'
