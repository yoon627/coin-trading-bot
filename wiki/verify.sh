#!/bin/bash
# check_links.py 가 검사하지 않는 불변식을 확인한다 (wiki/WIKI.md §6).
#   - stem 이 [a-z0-9-]+ 이고 pages/ 전체에서 유니크한가 (검증기는 중복을 조용히 덮어쓴다)
#   - frontmatter 의 category 가 실제 디렉토리와 일치하는가 (검증기는 키 존재만 본다)
#   - sources 가 비어 있지 않은가 / created·updated 가 ISO 날짜인가
#   - provenance 키(claim_state·verified)가 있고 claim_state 값이 허용값인가
#   - 페이지 수가 기대 범위 안인가 (상한도 본다 — 무한 증식 감지)
#
# false pass 방어: 검사한 페이지 수를 세어 발견한 페이지 수와 일치하지 않으면 실패한다.
# (루프가 통째로 건너뛰어도 "clean" 이 나오던 결함 — codex 리뷰 P1)
#
# bash 3.2 호환 (macOS 기본). 사용: bash wiki/verify.sh   (repo 루트에서)
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
fail=0

if [ ! -d wiki/pages ]; then
  echo "FAIL: wiki/pages 디렉토리 없음"; exit 1
fi

# 경로에 공백이 없다는 전제(stem 규칙이 [a-z0-9-]+ 라 보장). 서브셸을 만들지 않으려고
# 파이프 대신 positional parameter 를 쓴다 — 서브셸 안에서 올린 fail 은 밖으로 안 나온다.
set -- $(find wiki/pages -name '*.md' | sort)
count=$#
echo "pages: $count"

if [ "$count" -eq 0 ]; then
  echo "FAIL: 페이지를 하나도 찾지 못했다 (검사가 통째로 생략되는 상태)"; exit 1
fi
if [ "$count" -lt 23 ] || [ "$count" -gt 27 ]; then
  echo "FAIL: 페이지 수 $count 가 기대 범위(25±2) 밖"; fail=1
fi

checked=0
stems=""
for f in "$@"; do
  [ -f "$f" ] || { echo "FAIL: 파일 없음 — $f"; fail=1; continue; }
  stem=$(basename "$f" .md)

  printf '%s' "$stem" | grep -qE '^[a-z0-9-]+$' \
    || { echo "FAIL: stem 정규식 위반 — $f"; fail=1; }

  case " $stems " in
    *" $stem "*) echo "FAIL: stem 중복 — $stem (검증기는 조용히 덮어쓴다)"; fail=1 ;;
  esac
  stems="$stems $stem"

  dir_cat=$(basename "$(dirname "$f")")
  fm_cat=$(awk 'NR>1 && /^category:/ {print $2; exit}' "$f")
  [ "$fm_cat" = "$dir_cat" ] \
    || { echo "FAIL: category 불일치 — $f (frontmatter=$fm_cat, dir=$dir_cat)"; fail=1; }

  for key in created updated; do
    val=$(awk -v k="^$key:" 'NR>1 && $0 ~ k {print $2; exit}' "$f")
    printf '%s' "$val" | grep -qE '^[0-9]{4}-[0-9]{2}-[0-9]{2}$' \
      || { echo "FAIL: $key 가 ISO 날짜가 아님 — $f ($val)"; fail=1; }
  done

  # provenance (WIKI.md §3 이 요구하지만 check_links.py 는 안 보는 키)
  cs=$(awk 'NR>1 && /^claim_state:/ {print $2; exit}' "$f")
  case "$cs" in
    current|historical|superseded) ;;
    *) echo "FAIL: claim_state 누락·허용값 아님 — $f ($cs)"; fail=1 ;;
  esac
  grep -qE '^verified:[[:space:]]*[0-9]{4}-[0-9]{2}-[0-9]{2}' "$f" \
    || { echo "FAIL: verified 누락 또는 날짜 없음 — $f"; fail=1; }

  awk '/^sources:/{getline; if ($0 ~ /^[[:space:]]+- [^[:space:]]/) ok=1} END{exit !ok}' "$f" \
    || { echo "FAIL: sources 비어 있음 — $f"; fail=1; }

  checked=$((checked+1))
done

if [ "$checked" -ne "$count" ]; then
  echo "FAIL: 검사한 페이지 $checked ≠ 발견한 페이지 $count (검사 생략됨)"; fail=1
fi

if [ "$fail" -eq 0 ]; then
  echo "wiki extra check: clean ($checked pages checked)"
else
  echo "위반 있음"
fi
exit $fail
