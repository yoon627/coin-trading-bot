#!/bin/bash
# check_links.py 가 검사하지 않는 불변식을 확인한다 (wiki/WIKI.md §6).
#   - stem 이 [a-z0-9-]+ 이고 pages/ 전체에서 유니크한가 (검증기는 중복을 조용히 덮어쓴다)
#   - frontmatter 의 category 가 실제 디렉토리와 일치하는가 (검증기는 키 존재만 본다)
#   - sources 가 비어 있지 않은가 / created·updated 가 ISO 날짜인가
#   - 페이지 수가 기대 범위 안인가 (상한도 본다 — 무한 증식 감지)
# bash 3.2 호환 (macOS 기본). 사용: bash wiki/verify.sh   (repo 루트에서)
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1
fail=0

list=$(find wiki/pages -name '*.md' | sort)
count=$(printf '%s\n' "$list" | grep -c . )
echo "pages: $count"
if [ "$count" -lt 23 ] || [ "$count" -gt 27 ]; then
  echo "FAIL: 페이지 수 $count 가 기대 범위(25±2) 밖"; fail=1
fi

# stem 정규식
while IFS= read -r f; do
  [ -z "$f" ] && continue
  stem=$(basename "$f" .md)
  if ! printf '%s' "$stem" | grep -qE '^[a-z0-9-]+$'; then
    echo "FAIL: stem 정규식 위반 — $f"; fail=1
  fi
done <<EOF
$list
EOF

# stem 중복 (카테고리가 달라도 검증기는 덮어쓴다)
dups=$(printf '%s\n' "$list" | while IFS= read -r f; do [ -n "$f" ] && basename "$f" .md; done | sort | uniq -d)
if [ -n "$dups" ]; then
  echo "FAIL: stem 중복 — $dups"; fail=1
fi

# frontmatter 값
while IFS= read -r f; do
  [ -z "$f" ] && continue
  dir_cat=$(basename "$(dirname "$f")")
  fm_cat=$(awk 'NR>1 && /^category:/ {print $2; exit}' "$f")
  if [ "$fm_cat" != "$dir_cat" ]; then
    echo "FAIL: category 불일치 — $f (frontmatter=$fm_cat, dir=$dir_cat)"; fail=1
  fi
  for key in created updated; do
    val=$(awk -v k="^$key:" 'NR>1 && $0 ~ k {print $2; exit}' "$f")
    if ! printf '%s' "$val" | grep -qE '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'; then
      echo "FAIL: $key 가 ISO 날짜가 아님 — $f ($val)"; fail=1
    fi
  done
  if ! awk '/^sources:/{getline; if ($0 ~ /^[[:space:]]+- [^[:space:]]/) ok=1} END{exit !ok}' "$f"; then
    echo "FAIL: sources 비어 있음 — $f"; fail=1
  fi
done <<EOF
$list
EOF

if [ "$fail" -eq 0 ]; then echo "wiki extra check: clean"; else echo "위반 있음"; fi
exit $fail
