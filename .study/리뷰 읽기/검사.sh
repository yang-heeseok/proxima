#!/usr/bin/env bash
# .study/리뷰 읽기 — 상태 대장 검사
#
# 상태.md 의 머리말이 왜 이것이 있는지를 적는다. 여기 적는 것은 무엇을 검사하고,
# 무엇을 검사하지 않으며, 왜 그 경계에 있는지다.
#
# 검사 넷.
#   S1  장 대장 ↔ 파일시스템        네트워크 불필요
#   S2  앵커 대장 ↔ GitHub API      네트워크 필요. --offline 로 건너뛰되 반드시 출력한다
#   S3  이 폴더가 공용 게이트를 깨지 않는가 (docs-consistency CHECK 1 의 토큰 규칙)
#   S4  0장 §0.7 의 ✔ 가 대장과 일치하는가
#
# 산문은 읽지 않는다. docs-consistency.yml 이 산문 검사를 하나 쓰고 버린 이유가 이 폴더에
# 더 강하게 적용된다 — 이 폴더는 거짓이 된 문장을 ⚠ 로 **인용해서 보존**하므로, 어떤
# 키워드 검사든 정정문에 반드시 걸린다. 그래서 상태를 산문에서 빼서 대장으로 옮긴 것이다.
#
# 사용:
#   sh ".study/리뷰 읽기/검사.sh"              저장소 루트에서
#   sh ".study/리뷰 읽기/검사.sh" --offline    S2 를 건너뛴다 (건너뛴 사실을 출력한다)

set -eu

DIR=".study/리뷰 읽기"
LEDGER="$DIR/상태.md"
OFFLINE=0
[ "${1:-}" = "--offline" ] && OFFLINE=1

[ -f "$LEDGER" ] || { echo "FAIL: $LEDGER 이 없다. 저장소 루트에서 실행해야 한다."; exit 1; }

# python3 이 있다고 나오지만 실행하면 스토어 스텁인 환경이 있다(Windows). 이름이 아니라
# 실제 실행으로 고른다 — "있다고 했는데 안 된다"는 이 저장소가 반복해서 만난 부류다.
PY=""
for c in python3 python py; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c "import json,sys" >/dev/null 2>&1; then PY="$c"; break; fi
done
[ -n "$PY" ] || { echo "FAIL: 동작하는 python 이 없다. S2 는 JSON 을 읽어야 한다."; exit 1; }
fail=0
note() { printf '%s\n' "$1"; }

# ---------------------------------------------------------------- S1 장 대장
# 양방향이다. 대장에 있는 장의 파일이 없으면 대장이 거짓이고, 파일이 있는데 대장에
# 없으면 대장이 낡은 것이다. 두 번째가 이 폴더가 실제로 만든 실패다 — 3장이 생겼는데
# 2장이 "3장 미착수"라고 적고 있었다.
note "== S1  장 대장 ↔ 파일시스템"
ledger_files=$(mktemp); disk_files=$(mktemp)
awk -F'\t' '$1=="chapter"{print $6}' "$LEDGER" | sed '/^$/d' | sort > "$ledger_files"
git -c core.quotepath=false ls-files "$DIR" \
  | sed "s|^$DIR/||" | grep -E '^[0-9]+장\. .*\.md$' | sort > "$disk_files"

while IFS= read -r f; do
  [ -n "$f" ] || continue
  if [ ! -f "$DIR/$f" ]; then
    note "  FAIL 대장이 있다고 하는 장의 파일이 없다: $f"; fail=1
  fi
done < "$ledger_files"

missing=$(comm -13 "$ledger_files" "$disk_files")
if [ -n "$missing" ]; then
  note "  FAIL 파일은 있는데 대장에 행이 없다:"
  # 장 파일명에는 공백이 있다. $missing 을 인용 없이 넘기면 단어 단위로 쪼개져
  # "3장." 만 출력된다 — 무엇이 빠졌는지 못 읽는 검사는 잡아도 잡은 게 아니다.
  echo "$missing" | while IFS= read -r m; do note "        $m"; done
  note "        장을 쓰면 대장에 행을 더한다. 이 검사가 잡으려는 것이 정확히 이것이다."
  fail=1
fi
[ "$fail" = 0 ] && note "  OK   장 $(wc -l < "$ledger_files" | tr -d ' ')개, 양방향 일치"
rm -f "$ledger_files" "$disk_files"

# ---------------------------------------------------------------- S2 앵커 상태
# 이 폴더가 "#12880 은 열려 있다" 같은 문장을 쓰면, 그건 남의 저장소에 대한 주장이고
# 이 저장소는 그것이 언제 거짓이 되는지 볼 방법이 없다. 실제로 16일 동안 거짓이었다.
note "== S2  앵커 대장 ↔ GitHub API"
if [ "$OFFLINE" = 1 ]; then
  note "  SKIP --offline 으로 실행됨. 앵커 상태는 **검증되지 않았다.**"
  note "       건너뛴 것을 통과로 읽으면 이 검사가 있는 이유가 사라진다."
else
  # 파이프 안의 while 은 서브셸이라 변수를 못 내보낸다. 고정 경로를 쓰면 이전 실행이 남긴
  # 파일이 이번 실행을 실패시킨다 — 검사가 스스로 위양성을 만드는 것이다.
  drift_f=$(mktemp); err_f=$(mktemp); rm -f "$drift_f" "$err_f"
  awk -F'\t' '$1=="anchor"{print $2"\t"$3"\t"$4"\t"$5"\t"$6}' "$LEDGER" | sed '/^$/d' \
  | while IFS="$(printf '\t')" read -r repo id want measured ch; do
      [ -n "$repo" ] || continue
      body=$(curl -sS ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
             "https://api.github.com/repos/$repo/pulls/$id" 2>/dev/null) || {
        note "  UNVERIFIED $repo#$id 를 조회하지 못했다(네트워크). 조회 실패는 통과가 아니다."; echo x >> "$err_f"; continue; }
      live=$(printf '%s' "$body" | $PY -c '
import sys,json
try: d=json.load(sys.stdin)
except Exception: print("UNPARSEABLE"); raise SystemExit
if d.get("message"): print("ERROR:"+d["message"][:40])
else: print("merged" if d.get("merged_at") else d.get("state","?"))')
      # 조회 실패와 상태 불일치를 절대 섞지 않는다. 2026-08-21 첫 실행에서 비인증
      # 레이트리밋(시간당 60)에 걸렸고, 이 검사는 그것을 17건의 DRIFT 로 보고했다 —
      # 대장이 틀렸다고 **없는 사실을 만들어 낸 것**이다. 못 본 것은 못 봤다고 해야 한다.
      case "$live" in
        ERROR:*|UNPARSEABLE|"?")
          note "  UNVERIFIED $repo#$id  조회 실패 ($live)"
          echo x >> "$err_f" ;;
        "$want") ;;
        *)
          note "  DRIFT $repo#$id  대장=$want (측정 $measured, ${ch}장)  →  현재=$live"
          echo x >> "$drift_f" ;;
      esac
    done
  if [ -f "$err_f" ]; then
    n=$(wc -l < "$err_f" | tr -d ' '); rm -f "$err_f"
    note "  FAIL 앵커 $n 건을 **조회하지 못했다.** 대장이 틀렸다는 뜻이 아니라 확인이 안 됐다는 뜻이다."
    note "       비인증 GitHub API 는 시간당 60회다. 앵커가 $(awk -F"	" '"'"'$1=="anchor"'"'"' "$LEDGER" | wc -l | tr -d " ")건이므로"
    note "       한 시간에 세 번까지만 돌 수 있다. GITHUB_TOKEN 을 주면 이 한계가 사라진다."
    fail=1
  fi
  if [ -f "$drift_f" ]; then
    n=$(wc -l < "$drift_f" | tr -d ' '); rm -f "$drift_f"
    note "  FAIL 앵커 $n 건이 대장과 다르다."
    note "       대장의 state 와 measured 를 오늘 값으로 갱신하고, 그 상태를 인용한 장의"
    note "       문장을 ⚠ 정정한다. 원문을 지우지 않는다 — 0장 §0.1."
    fail=1
  elif [ ! -f "$err_f" ]; then
    note "  OK   앵커 전부 대장과 일치"
  fi
fi

# ------------------------------------------------- S3 공용 게이트를 깨지 않는가
# docs-consistency.yml CHECK 1 은 `.study/` 를 포함해서 모든 md 를 본다. 이 트랙은 남의
# 저장소 파일을 상시로 인용하는데, 그 게이트에는 "외부 아티팩트"라는 개념이 없다.
# 2026-08-21 에 실제로 f06cb1d 가 이 게이트를 빨갛게 만들었고, 이 검사가 그것을 잡는다.
# CI 가 잡기 전에 잡는 것이 요점이다 — 같은 규칙을 이 폴더에만 먼저 돌린다.
note "== S3  이 폴더가 docs-consistency CHECK 1 을 깨지 않는가"
tracked=$(mktemp); types=$(mktemp); found=$(mktemp)
git -c core.quotepath=false ls-files > "$tracked"
git -c core.quotepath=false ls-files -z '*.kt' '*.java' 2>/dev/null \
  | xargs -0 grep -ohE '^[[:space:]]*([a-z]+ )*(class|object|interface) [A-Za-z0-9_]+' 2>/dev/null \
  | awk '{print $NF}' | sort -u > "$types" || true
# `read -d ''` 는 bash 확장이다. 이 파일은 2026-08-21 부터 `#!/bin/sh` 를 선언했고
# 워크플로가 `sh` 로 불렀는데, ubuntu-latest 의 `sh` 는 dash 다. dash 에서 이 줄은
# `read: Illegal option -d` 로 즉시 실패하고 루프 본문이 한 번도 돌지 않는다 —
# 즉 S3 는 심어놓은 위반 위에서도 OK 를 찍었다. 2026-08-22 에 재현했다:
# 같은 트리에 남의 저장소 파일을 심고 dash 는 OK, bash 는 FAIL.
#
# 이 워크플로는 오늘 처음 origin 에 닿았으므로 S3 는 CI 에서 한 번도 참이었던 적이 없다.
# 그리고 **잡은 것은 이 워크플로 자신의 self-test 다** — 심은 위반을 S3 가 놓치면
# 빨간불이 되도록 짜여 있었고, 그것이 지불됐다. 헤더가 세는 "아무것도 거절한 적 없는
# 계측기" 목록의 여섯 번째이고, 이번에는 대조군이 먼저 말했다.
#
# 인터프리터를 bash 로 바꿔 고친다. 이 스크립트는 이미 bash 를 필요로 하고 있었고,
# 선언만 그렇지 않았다.
git -c core.quotepath=false ls-files -z "$DIR" | while IFS= read -r -d '' doc; do
  case "$doc" in *.md) ;; *) continue;; esac
  grep -vE '\(to come\)|\(planted\)|\bTBD\b|미작성|미구현' "$doc" 2>/dev/null \
    | grep -ohE '`[A-Za-z0-9_][A-Za-z0-9_./-]*(\.(kt|kts|sql|yml|yaml|toml|js|java|properties)|Test|Rules|Queries)`' \
    | tr -d '`' | sort -u | while IFS= read -r t; do
        [ -n "$t" ] || continue
        base=${t##*/}; stem=${base%.kt}
        grep -qxF "$t" "$tracked" && continue
        grep -qF "/$base" "$tracked" && continue
        grep -qF "/$stem.kt" "$tracked" && continue
        grep -qxF "$stem" "$types" && continue
        printf '%s -> %s\n' "${doc##*/}" "$t" >> "$found"
      done
done
if [ -s "$found" ]; then
  sort -u "$found" | sed 's/^/  FAIL /'
  note "       이 폴더가 남의 저장소 파일을 백틱 안에 확장자까지 붙여 적으면,"
  note "       공용 게이트는 그것을 **이 저장소에 없는 아티팩트**로 읽고 빨개진다."
  note "       확장자를 빼거나(\`SubscriptionState\`) 백틱 밖에 쓴다."
  fail=1
else
  note "  OK   외부 파일 인용이 공용 게이트를 건드리지 않는다"
fi
rm -f "$tracked" "$types" "$found"

# ---------------------------------------------------------------- S4 0장 §0.7
note "== S4  0장 §0.7 의 ✔ 가 대장과 일치하는가"
zero="$DIR/0장. 리뷰는 지식이 아니라 예측이다.md"
if [ ! -f "$zero" ]; then
  note "  FAIL 0장 파일이 없다"; fail=1
else
  bad=""
  for n in $(awk -F'\t' '$1=="chapter" && $4=="written" && $3!="0"{print $3}' "$LEDGER"); do
    grep -qF "**${n}장** ✔" "$zero" || bad="$bad ${n}장"
  done
  if [ -n "$bad" ]; then
    note "  FAIL 대장은 written 인데 0장 §0.7 표에 ✔ 가 없다:$bad"
    note "       §0.7 은 이 트랙의 계획표다. 쓴 장이 표에 반영되지 않으면 계획이 낡는다."
    fail=1
  else
    note "  OK   §0.7 표가 대장과 일치"
  fi
fi

note ""
if [ "$fail" = 0 ]; then
  note "통과. 다만 이 검사가 보는 것은 **상태 주장 두 종류**뿐이다 —"
  note "장이 존재하는가, PR 이 어떤 상태인가. 문장이 참인지는 여전히 사람이 읽어야 안다."
  exit 0
fi
note "실패. 위 항목을 고치기 전에는 이 폴더의 상태 주장을 믿을 수 없다."
exit 1
