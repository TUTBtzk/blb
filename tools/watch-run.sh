#!/bin/sh
# 跟一趟真机运行：每 2 秒记一次通知上的当前步骤 + 当前前台窗口 + 菠萝包挂着几个窗口。
# 广告页起来时顺手把窗口列表存一份，事后能对照「按钮到底在哪一层窗口里」。
export MSYS_NO_PATHCONV=1
OUT="$1"
: > "$OUT"
i=0
while [ $i -lt 400 ]; do
  i=$((i+1))
  ts=$(date +%H:%M:%S)
  note=$(adb shell dumpsys notification --noredact 2>/dev/null | grep -o 'android.text=String ([^)]*)' | tail -1)
  top=$(adb shell dumpsys activity activities 2>/dev/null | grep -m1 'topResumedActivity' )
  echo "$ts | $note | $top" >> "$OUT"
  case "$top" in
    *bytedance*|*qq.e.ads*|*Stub_*)
      adb shell dumpsys window windows 2>/dev/null | grep -E "Window #|package=|mOwnerUid" > "${OUT}.win.$ts" 2>/dev/null
      ;;
  esac
  sleep 2
done
