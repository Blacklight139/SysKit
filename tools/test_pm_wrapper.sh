#!/usr/bin/env bash
# pm 包装器主机端行为测试（无需 Android 设备：stub 执行器 + 临时目录模拟文件协议）
# 覆盖：语法检查 / 非卸载透传 / 守卫关闭透传 / 包名提取 / ALLOW 放行 / DENY 拒绝 / 超时拒绝 / uninstall-existing 拦截
set -u
cd "$(dirname "$0")/.."

WRAPPER=module/system/bin/pm
PASS=0
FAIL=0

say() { printf '  [ok]   %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  [FAIL] %s\n' "$1"; FAIL=$((FAIL + 1)); }
check() { # check "描述" "期望子串" "实际输出"
  case "$3" in
    *"$2"*) say "$1" ;;
    *) bad "$1（期望包含「$2」，实际「$3」）" ;;
  esac
}

[ -f "$WRAPPER" ] || { echo "包装器不存在：$WRAPPER"; exit 1; }

echo "[1/9] sh 语法检查"
if sh -n "$WRAPPER" 2>/dev/null; then say "sh -n 通过"; else bad "sh -n 语法错误"; fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/syskit"

STUB="$WORK/stub.sh"
cat > "$STUB" <<'EOF'
#!/bin/sh
echo "STUB_ARGS:$*"
EOF
chmod +x "$STUB"

run_pm() { # $1=超时秒数 其余=pm 参数
  local t="$1"
  shift
  SYSKIT_DIR="$WORK/syskit" SYSKIT_PM_CMD="$STUB" SYSKIT_TIMEOUT_SECS="$t" \
    sh "$WRAPPER" "$@"
}

echo "[2/9] 非卸载子命令：守卫开启也应零改动透传"
echo 1 > "$WORK/syskit/guard_state"
out=$(run_pm 30 list packages 2>&1)
check "list packages 透传" "STUB_ARGS:list packages" "$out"
out=$(run_pm 30 install-existing com.example 2>&1)
check "install-existing 透传" "STUB_ARGS:install-existing com.example" "$out"

echo "[3/9] 守卫关闭：uninstall 直接透传"
rm -f "$WORK/syskit/guard_state"
out=$(run_pm 30 uninstall com.example 2>&1)
check "guard_state 缺失 → 透传" "STUB_ARGS:uninstall com.example" "$out"

echo "[4/9] 守卫开启 + uninstall：解析出的目标包名写入 req"
echo 1 > "$WORK/syskit/guard_state"
rm -f "$WORK/syskit"/req_* "$WORK/syskit"/resp_* "$WORK"/req_content
( for i in $(seq 1 80); do
    f=$(ls "$WORK/syskit"/req_* 2>/dev/null | head -n1)
    [ -n "$f" ] && break
    sleep 0.25
  done
  [ -n "$f" ] && { printf '%s' "$(cat "$f")" > "$WORK/req_content"; printf DENY > "$WORK/syskit/resp_${f##*req_}"; }
) &
out=$(run_pm 10 uninstall --user 0 -k com.android.vending 2>&1)
wait
check "请求被拒绝" "已拒绝卸载" "$out"
check "req 内容为目标包名" "com.android.vending" "$(cat "$WORK/req_content" 2>/dev/null)"

echo "[5/9] 守卫开启 + ALLOW：原样放行"
rm -f "$WORK/syskit"/req_* "$WORK/syskit"/resp_*
( for i in $(seq 1 80); do
    f=$(ls "$WORK/syskit"/req_* 2>/dev/null | head -n1)
    [ -n "$f" ] && break
    sleep 0.25
  done
  [ -n "$f" ] && printf ALLOW > "$WORK/syskit/resp_${f##*req_}"
) &
out=$(run_pm 10 uninstall --user 0 com.android.vending 2>&1)
wait
check "ALLOW 后透传且参数完整" "STUB_ARGS:uninstall --user 0 com.android.vending" "$out"

echo "[6/9] 无响应超时（fail-closed）"
rm -f "$WORK/syskit"/req_* "$WORK/syskit"/resp_*
out=$(run_pm 1 uninstall com.example 2>&1)
check "超时自动拒绝" "已拒绝卸载" "$out"

echo "[7/9] 守卫目录不可写：fail-closed"
rm -rf "$WORK/syskit"
out=$(run_pm 1 uninstall com.example 2>&1)
check "目录缺失拒绝执行" "无法写入请求" "$out"

echo "[8/9] uninstall-existing 同样被拦截"
mkdir -p "$WORK/syskit"
echo 1 > "$WORK/syskit/guard_state"
rm -f "$WORK/syskit"/req_* "$WORK/syskit"/resp_*
( for i in $(seq 1 80); do
    f=$(ls "$WORK/syskit"/req_* 2>/dev/null | head -n1)
    [ -n "$f" ] && break
    sleep 0.25
  done
  [ -n "$f" ] && printf DENY > "$WORK/syskit/resp_${f##*req_}"
) &
out=$(run_pm 10 uninstall-existing com.example 2>&1)
wait
check "uninstall-existing 被拒绝" "已拒绝卸载" "$out"

echo "[9/9] 无包名的 uninstall：透传由原生 pm 报错"
rm -f "$WORK/syskit"/req_* "$WORK/syskit"/resp_*
out=$(run_pm 10 uninstall 2>&1)
check "空参数透传" "STUB_ARGS:uninstall" "$out"

echo
echo "结果：$PASS 通过 / $FAIL 失败"
[ "$FAIL" -eq 0 ] || exit 1
