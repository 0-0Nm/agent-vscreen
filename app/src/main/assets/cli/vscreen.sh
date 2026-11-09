#!/bin/sh
# atvsx — command-line client for ATVSX @@VERSION@@ (talks to the app on 127.0.0.1; needs curl).
#
#   atvsx launch 设置
#   atvsx act "tap 搜索; type 耳机; key enter until text:综合"
#   atvsx help all
#
# Pictures the command made are downloaded to $ATVSX_SHOT_DIR and their paths printed in the text.
# Config: ~/.config/atvsx/env (written by `install`, same names), overridden by environment variables:
#   ATVSX_URL      default http://127.0.0.1:@@PORT@@
#   ATVSX_TOKEN    the access token shown in the app
#   ATVSX_SESSION  who owns the screen (default "default"; one session at a time)
#   ATVSX_SHOT_DIR where pictures go (default $TMPDIR/atvsx-shots or /tmp/atvsx-shots)
#   ATVSX_SHOTS    "none" = do not download pictures (the text keeps their URLs)

CLI_VERSION="@@VERSION@@"
CONF_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/atvsx"
# The file holds the same names as the environment (ATVSX_URL / ATVSX_TOKEN); a variable already set wins.
# It is read line by line, not sourced, so it can never override what the caller set. Old *_SAVED names still work.
conf() { [ -f "$CONF_DIR/env" ] && sed -n -e "s/^$1=//p" -e "s/^$1_SAVED=//p" "$CONF_DIR/env" | head -n1; }
URL="${ATVSX_URL:-$(conf ATVSX_URL)}"; URL="${URL:-http://127.0.0.1:@@PORT@@}"
TOKEN="${ATVSX_TOKEN:-$(conf ATVSX_TOKEN)}"
SESSION="${ATVSX_SESSION:-default}"
SHOT_DIR="${ATVSX_SHOT_DIR:-${TMPDIR:-/tmp}/atvsx-shots}"

if ! command -v curl >/dev/null 2>&1; then
  echo "atvsx: 需要 curl（Termux: pkg install curl；Alpine: apk add curl；Debian: apt install curl）" >&2; exit 3
fi

# The app is local (or on the tailnet): never route it through an http(s)_proxy set for the internet.
c() { curl --noproxy '*' "$@"; }

# The skill: how an agent should use atvsx (and not build its own virtual screen or reach for adb). Nothing is
# written into any agent's own folders: install prints it for the agent to save as its own skill / memory, and keeps
# a copy in $CONF_DIR/SKILL.md (refreshed when the app is updated).
skill_sync() {
  mkdir -p "$CONF_DIR" && c -fsS "$URL/skill" -o "$CONF_DIR/SKILL.md.new" || return 0
  mv -f "$CONF_DIR/SKILL.md.new" "$CONF_DIR/SKILL.md"
  if [ "$1" = "show" ]; then
    # 1.1.6: short. The request to remember comes from the user's own message (copied from the app), not from here.
    echo
    echo "要记住的说明（存进你的长期记忆，存好后执行 atvsx remembered \"<存在哪里>\"）："
    c -fsS "$URL/memory" 2>/dev/null || echo "（取不到，见 $CONF_DIR/SKILL.md）"
    echo
    echo "完整的 skill 版本在 $CONF_DIR/SKILL.md；用法速查：atvsx guide"
  else
    echo "（skill 已随 App 更新：$CONF_DIR/SKILL.md，如果你存过旧版，请换成新的）" >&2
  fi
}

if [ "$1" = "install" ]; then
  tok="$2"; dest="$3"
  [ -n "$tok" ] || { echo "用法: atvsx install <令牌> [安装目录]" >&2; exit 2; }
  if [ -z "$dest" ]; then
    for d in "$PREFIX/bin" "$HOME/.local/bin" /usr/local/bin; do
      [ -n "$d" ] && [ "$d" != "/bin" ] || continue
      mkdir -p "$d" 2>/dev/null && [ -w "$d" ] && { dest="$d"; break; }
    done
  fi
  [ -n "$dest" ] || { echo "atvsx: 找不到可写的安装目录，请指定：atvsx install <令牌> <目录>" >&2; exit 2; }
  c -fsS "$URL/cli?via=install" -o "$dest/atvsx" || { echo "atvsx: 下载失败：ATVSX 在运行吗？" >&2; exit 3; }
  chmod +x "$dest/atvsx"
  mkdir -p "$CONF_DIR"
  printf 'ATVSX_URL=%s\nATVSX_TOKEN=%s\n' "$URL" "$tok" > "$CONF_DIR/env"
  chmod 600 "$CONF_DIR/env" 2>/dev/null
  echo "已安装：$dest/atvsx（令牌存在 $CONF_DIR/env）"
  skill_sync show
  case ":$PATH:" in *":$dest:"*) ;; *) echo "注意：$dest 不在 PATH 里，用全路径调用或加进 PATH" ;; esac
  "$dest/atvsx" status >/dev/null 2>&1 && echo "连接正常：atvsx help 看用法" || echo "已安装，但现在连不上：确认 App 里服务已启动"
  exit 0
fi

if [ "$1" = "events" ] && [ "$2" = "--raw" ]; then
  c -sS -H "Authorization: Bearer $TOKEN" "$URL/v1/events?session=$SESSION&wait=${3:-0}"; echo; exit $?
fi

tmp="${TMPDIR:-/tmp}/atvsx.$$"
mkdir -p "$tmp" || exit 3
trap 'rm -rf "$tmp"' EXIT INT TERM

i=0
for a in "$@"; do
  i=$((i+1)); printf '%s' "$a" > "$tmp/a$i"
done
set --
j=1
while [ $j -le $i ]; do set -- "$@" --data-urlencode "a@$tmp/a$j"; j=$((j+1)); done
# A frozen app still accepts the connection (the kernel does) but never answers: find out in 5 s, not minutes.
c -s -m 5 -o /dev/null "$URL/v1/ping"
if [ $? -eq 28 ]; then
  echo "atvsx: ATVSX 5 秒没有响应，多半是系统把它冻结在后台了。请用户打开一次 ATVSX，按首页“后台运行受限”的提示允许它后台活动（荣耀/华为：应用启动管理 › 手动管理）。" >&2
  exit 3
fi
c -sS --connect-timeout 5 -X POST -D "$tmp/h" -o "$tmp/b" \
  -H "Authorization: Bearer $TOKEN" -H "X-VScreen-Session: $SESSION" -H "Accept: text/plain" \
  --data-urlencode "session=$SESSION" "$@" "$URL/v1/run?format=text"
rc=$?
if [ $rc -ne 0 ]; then
  echo "atvsx: 连不上 ATVSX（$URL，curl $rc）：打开 ATVSX，确认“服务”已启动；手机重启后要先启动 Shizuku 再打开它。" >&2
  exit 3
fi
code=$(sed -n '1s/^HTTP\/[0-9.]* \([0-9]*\).*/\1/p' "$tmp/h")
if [ "$code" = "401" ]; then
  echo "atvsx: 令牌不对：在 ATVSX 里点“复制接入命令”重新执行一遍（会更新 $CONF_DIR/env 里的 ATVSX_TOKEN），或设置环境变量 ATVSX_TOKEN。" >&2; cat "$tmp/b" >&2; echo >&2; exit 3
fi
# Version parity: the app serves the matching client; when the app was updated, this script replaces itself once.
srv=$(tr -d '\r' < "$tmp/h" | sed -n 's/^[Xx]-[Vv][Ss]creen-[Vv]ersion: *//p' | head -n1)
if [ -n "$srv" ] && [ "$srv" != "$CLI_VERSION" ] && [ -z "$ATVSX_NO_UPDATE" ]; then
  self=$(command -v "$0" 2>/dev/null || echo "$0")
  if [ -w "$self" ] && [ -w "$(dirname "$self")" ] && c -fsS "$URL/cli" -o "$tmp/new" && grep -q "^CLI_VERSION=\"$srv\"" "$tmp/new"; then
    # A new file moved over the old one: this running copy keeps reading the old file safely.
    cp "$tmp/new" "$self.new.$$" && chmod +x "$self.new.$$" && mv -f "$self.new.$$" "$self" &&
      echo "（atvsx 命令行已随 App 更新到 $srv）" >&2
    skill_sync
  fi
fi
exitc=$(tr -d '\r' < "$tmp/h" | sed -n 's/^[Xx]-[Vv][Ss]creen-[Ee]xit: *//p' | head -n1)
imgs=$(tr -d '\r' < "$tmp/h" | sed -n 's/^[Xx]-[Vv][Ss]creen-[Ii]mages: *//p' | head -n1)
if [ -n "$imgs" ] && [ "$ATVSX_SHOTS" != "none" ]; then
  mkdir -p "$SHOT_DIR"
  old_ifs=$IFS; IFS=,
  for n in $imgs; do
    IFS=$old_ifs
    case "$n" in vscreen_*.jpg) ;; *) continue ;; esac
    if c -fsS -H "Authorization: Bearer $TOKEN" "$URL/v1/shot/$n" -o "$SHOT_DIR/$n"; then
      sed "s|$URL/v1/shot/$n|$SHOT_DIR/$n|g" "$tmp/b" > "$tmp/b2" && mv "$tmp/b2" "$tmp/b"
    fi
    IFS=,
  done
  IFS=$old_ifs
fi
mkdir -p "$SHOT_DIR" 2>/dev/null && cp "$tmp/b" "$SHOT_DIR/last.txt" 2>/dev/null
cat "$tmp/b"
exit "${exitc:-0}"
