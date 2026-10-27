#!/usr/bin/env bash
# 마지막 정상 배포(last-good) 이후 운영에 들어가는 파일이 바뀌었는지 판정한다. 첫 줄에 deploy=true|false 를 쓴다.
# 모르는 경로는 배포 쪽으로 센다 — 제외 목록에는 이미지·서버 설정에 들어가지 않는 것만 둔다.
# 사용: deploy_needed.sh <last-good-sha> <running-sha|-> <target-sha>  (전체 이력이 있는 체크아웃 안에서)
#   running-sha 는 지금 healthy 로 도는 app 컨테이너의 이미지 SHA, 없거나 healthy 가 아니면 '-'.
# 종료 코드: 0 판정함, 2 인자 오류, 3 옛 커밋으로 되돌리는 배포라 거부.
set -euo pipefail

usage() {
    echo "usage: $0 <last-good-sha> <running-sha|-> <target-sha>  (SHA 는 40자리)" >&2
    exit 2
}
sha_re='^[0-9a-f]{40}$'
# 워크플로의 출력·env 이름이 어긋나 빈 값이 오면, 판정 불가로 배포되면서 옛 커밋 거부까지 건너뛴다.
[[ $# -eq 3 ]] || usage
last_good="$1"
running="$2"
target="$3"
[[ "$last_good" =~ $sha_re && "$target" =~ $sha_re && ( "$running" == - || "$running" =~ $sha_re ) ]] || usage
# pathspec 은 현재 디렉터리 기준이라 하위 디렉터리에서 부르면 그 아래만 비교한다.
cd "$(git rev-parse --show-toplevel)"

if ! git cat-file -e "${target}^{commit}" 2>/dev/null; then
    echo "ERROR: 배포 대상 $target 이 이 체크아웃의 커밋이 아니다" >&2
    exit 2
fi
if ! git cat-file -e "${last_good}^{commit}" 2>/dev/null; then
    echo "deploy=true"
    echo "마지막 정상 배포 $last_good 가 이 체크아웃에 없어 차이를 알 수 없다 — 배포한다"
    exit 0
fi
# 수동 실행 두 개의 배포 job 은 시작 순서가 아니라 테스트·빌드가 끝난 순서로 줄을 선다. 늦게 선 옛 커밋이
# 새 배포를 덮으면 새 스키마 위에 옛 앱이 뜰 수 있다.
if [[ "$target" != "$last_good" ]] && git merge-base --is-ancestor "$target" "$last_good"; then
    echo "ERROR: 배포 대상 $target 이 마지막 정상 배포 $last_good 보다 옛 커밋이다 — 되돌리려면 force 로 실행한다" >&2
    exit 3
fi
# 실패한 배포가 남긴 컨테이너·수동 롤백·정지 뒤에는 .last-good-sha 가 지금 도는 것과 다르다.
if [[ "$running" != "$last_good" ]]; then
    echo "deploy=true"
    echo "지금 운영 컨테이너($running)가 마지막 정상 배포 $last_good 의 healthy 컨테이너가 아니다 — 배포한다"
    exit 0
fi

# 이미지는 bootJar -x test 로 빌드해 테스트 소스가 들어가지 않는다. 루트 docker-compose.yml·.env.example 과
# gradlew 는 로컬 개발용이다(서버는 deploy/vultr 의 파일을, 이미지 빌드는 gradle 이미지를 쓴다).
changes="$(git diff --name-only "$last_good" "$target" -- . \
    ':(exclude)**.md' ':(exclude)docs/**' ':(exclude)**/src/test/**' ':(exclude)scripts/**' \
    ':(exclude).gitignore' ':(exclude)docker-compose.yml' ':(exclude).env.example' \
    ':(exclude)gradlew' ':(exclude)gradlew.bat')"

if [[ -z "$changes" ]]; then
    echo "deploy=false"
    echo "$last_good 이후 운영에 들어가는 파일 변경이 없다"
else
    echo "deploy=true"
    echo "$last_good 이후 운영에 들어가는 파일 변경:"
    printf '%s\n' "$changes"
fi
