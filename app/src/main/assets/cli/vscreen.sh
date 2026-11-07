#!/bin/sh
# vscreen — command-line client for Agent-VScreen @@VERSION@@ (talks to the app on 127.0.0.1; needs curl).
#
#   vscreen launch 设置
#   vscreen act "tap 搜索; type 耳机; key enter until text:综合"
#   vscreen help all
#
# Pictures the command made are downloaded to $VSCREEN_SHOT_DIR and their paths printed in the text.
# Config: ~/.config/agent-vscreen/env (written by `install`, same names), overridden by environment variables:
#   VSCREEN_URL      default http://127.0.0.1:@@PORT@@
#   VSCREEN_TOKEN    the access token shown in the app
#   VSCREEN_SESSION  who owns the screen (default "default"; one session at a time)
#   VSCREEN_SHOT_DIR where pictures go (default $TMPDIR/vscreen-shots or /tmp/vscreen-shots)
#   VSCREEN_SHOTS    "none" = do not download pictures (the text keeps their URLs)

CLI_VERSION="@@VERSION@@"
CONF_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/agent-vscreen"
# The file holds the same names as the environment (VSCREEN_URL / VSCREEN_TOKEN); a variable already set wins.
# It is read line by line, not sourced, so it can never override what the caller set. Old *_SAVED names still work.
conf() { [ -f "$CONF_DIR/env" ] && sed -n -e "s/^$1=//p" -e "s/^$1_SAVED=//p" "$CONF_DIR/env" | head -n1; }
URL="${VSCREEN_URL:-$(conf VSCREEN_URL)}"; URL="${URL:-http://127.0.0.1:@@PORT@@}"
TOKEN="${VSCREEN_TOKEN:-$(conf VSCREEN_TOKEN)}"
SESSION="${VSCREEN_SESSION:-default}"
SHOT_DIR="${VSCREEN_SHOT_DIR:-${TMPDIR:-/tmp}/vscreen-shots}"

if ! command -v curl >/dev/null 2>&1; then
  echo "vscreen: 需要 curl（Termux: pkg install curl；Alpine: apk add curl；Debian: apt install curl）" >&2; exit 3
fi

# The app is local (or on the tailnet): never route it through an http(s)_proxy set for the internet.
c() { curl --noproxy '*' "$@"; }

# The skill: how an agent should use vscreen (and not build its own virtual screen or reach for adb). Nothing is
# written into any agent's own folders: install prints it for the agent to save as its own skill / memory, and keeps
# a copy in $CONF_DIR/SKILL.md (refreshed when the app is updated).
skill_sync() {
  mkdir -p "$CONF_DIR" && c -fsS "$URL/skill" -o "$CONF_DIR/SKILL.md.new" || return 0
  mv -f "$CONF_DIR/SKILL.md.new" "$CONF_DIR/SKILL.md"
  if [ "$1" = "show" ]; then
    echo
    echo "===== 给 agent：把下面这段存成你自己的 skill（或写进长期记忆），以后在手机上打开/查看/操作应用都用 vscreen ====="
    cat "$CONF_DIR/SKILL.md"
    echo "===== skill 结束（副本在 $CONF_DIR/SKILL.md） ====="
  else
    echo "（skill 已随 App 更新：$CONF_DIR/SKILL.md，如果你存过旧版，请换成新的）" >&2
  fi
}

if [ "$1" = "install" ]; then
  tok="$2"; dest="$3"
  [ -n "$tok" ] || { echo "用法: vscreen install <令牌> [安装目录]" >&2; exit 2; }
  if [ -z "$dest" ]; then
    for d in "$PREFIX/bin" "$HOME/.local/bin" /usr/local/bin; do
      [ -n "$d" ] && [ "$d" != "/bin" ] || continue
      mkdir -p "$d" 2>/dev/null && [ -w "$d" ] && { dest="$d"; break; }
    done
  fi
  [ -n "$dest" ] || { echo "vscreen: 找不到可写的安装目录，请指定：vscreen install <令牌> <目录>" >&2; exit 2; }
  c -fsS "$URL/cli" -o "$dest/vscreen" || { echo "vscreen: 下载失败：Agent-VScreen 在运行吗？" >&2; exit 3; }
  chmod +x "$dest/vscreen"
  mkdir -p "$CONF_DIR"
  printf 'VSCREEN_URL=%s\nVSCREEN_TOKEN=%s\n' "$URL" "$tok" > "$CONF_DIR/env"
  chmod 600 "$CONF_DIR/env" 2>/dev/null
  echo "已安装：$dest/vscreen（令牌存在 $CONF_DIR/env）"
  skill_sync show
  case ":$PATH:" in *":$dest:"*) ;; *) echo "注意：$dest 不在 PATH 里，用全路径调用或加进 PATH" ;; esac
  "$dest/vscreen" status >/dev/null 2>&1 && echo "连接正常：vscreen help 看用法" || echo "已安装，但现在连不上：确认 App 里服务已启动"
  exit 0
fi

if [ "$1" = "events" ] && [ "$2" = "--raw" ]; then
  c -sS -H "Authorization: Bearer $TOKEN" "$URL/v1/events?session=$SESSION&wait=${3:-0}"; echo; exit $?
fi

tmp="${TMPDIR:-/tmp}/vscreen.$$"
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
  echo "vscreen: Agent-VScreen 5 秒没有响应，多半是系统把它冻结在后台了。请用户打开一次 Agent-VScreen，按首页“后台运行受限”的提示允许它后台活动（荣耀/华为：应用启动管理 › 手动管理）。" >&2
  exit 3
fi
c -sS --connect-timeout 5 -X POST -D "$tmp/h" -o "$tmp/b" \
  -H "Authorization: Bearer $TOKEN" -H "X-VScreen-Session: $SESSION" -H "Accept: text/plain" \
  --data-urlencode "session=$SESSION" "$@" "$URL/v1/run?format=text"
rc=$?
if [ $rc -ne 0 ]; then
  echo "vscreen: 连不上 Agent-VScreen（$URL，curl $rc）：打开 Agent-VScreen，确认“服务”已启动；手机重启后要先启动 Shizuku 再打开它。" >&2
  exit 3
fi
code=$(sed -n '1s/^HTTP\/[0-9.]* \([0-9]*\).*/\1/p' "$tmp/h")
if [ "$code" = "401" ]; then
  echo "vscreen: 令牌不对：在 Agent-VScreen 里点“复制接入命令”重新执行一遍（会更新 $CONF_DIR/env 里的 VSCREEN_TOKEN），或设置环境变量 VSCREEN_TOKEN。" >&2; cat "$tmp/b" >&2; echo >&2; exit 3
fi
# Version parity: the app serves the matching client; when the app was updated, this script replaces itself once.
srv=$(tr -d '\r' < "$tmp/h" | sed -n 's/^[Xx]-[Vv][Ss]creen-[Vv]ersion: *//p' | head -n1)
if [ -n "$srv" ] && [ "$srv" != "$CLI_VERSION" ] && [ -z "$VSCREEN_NO_UPDATE" ]; then
  self=$(command -v "$0" 2>/dev/null || echo "$0")
  if [ -w "$self" ] && [ -w "$(dirname "$self")" ] && c -fsS "$URL/cli" -o "$tmp/new" && grep -q "^CLI_VERSION=\"$srv\"" "$tmp/new"; then
    # A new file moved over the old one: this running copy keeps reading the old file safely.
    cp "$tmp/new" "$self.new.$$" && chmod +x "$self.new.$$" && mv -f "$self.new.$$" "$self" &&
      echo "（vscreen 命令行已随 App 更新到 $srv）" >&2
    skill_sync
  fi
fi
exitc=$(tr -d '\r' < "$tmp/h" | sed -n 's/^[Xx]-[Vv][Ss]creen-[Ee]xit: *//p' | head -n1)
imgs=$(tr -d '\r' < "$tmp/h" | sed -n 's/^[Xx]-[Vv][Ss]creen-[Ii]mages: *//p' | head -n1)
if [ -n "$imgs" ] && [ "$VSCREEN_SHOTS" != "none" ]; then
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
