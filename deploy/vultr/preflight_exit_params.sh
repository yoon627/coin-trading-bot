#!/usr/bin/env bash
# 배포 preflight — 렌더된 서버 .env 의 청산·주문 파라미터를 **업로드 전에** 검사한다 (#179, #230).
# deploy.sh 가 render_server_env 직후 부른다.
#
# 앱이 아니라 여기서 막는 이유: 기동을 실패시키면 보유 포지션의 손절·트레일링이 평가되지 않는 공백이 생기고,
# 자동 롤백은 이미지만 되돌려 같은 .env 로 다시 기동한다. 여기서 멈추면 잘못된 설정이 서버에 닿지 않는다.
#   값   선언된 키가 형식·의미상 구간 안인가. 자동매매 여부와 무관하다 — 수동 시작도 이 값으로 거래한다.
#   선언 자동매매 배포면 청산 6개 키가 모두 선언됐는가. 빠지면 앱이 코드 기본값으로 조용히 거래한다.
#
# 아래 표는 사본이다. 구간의 정의처는 common/src/main/kotlin/com/trading/common/config/ExitParamRanges.kt,
# 선언 키의 정의처는 ExitParamsDeclarationCheck.REQUIRED_KEYS, 불리언은 TradingProperties·ShadowExitProperties 의
# Boolean 설정 전부이고, ExitParamsPreflightScriptTest 가 셋을 대조한다.
# 형식은 앱(Spring)보다 좁다 — 여기서 받는 값은 앱도 같은 수로 받는다. 값은 찍지 않는다(배포 로그가 공개 CI 로그다).
# bash 3.2(macOS)에서도 돈다 — ${v,,}·declare -A·mapfile 을 쓰지 않는다.
#
# 사용법: preflight_exit_params.sh <렌더된 env 파일>    종료코드 0=통과 1=위반 2=사용법 오류
set -euo pipefail
export LC_ALL=C

if [[ $# -ne 1 || ! -r "$1" ]]; then
    echo "사용법: $0 <렌더된 env 파일>" >&2
    exit 2
fi
env_file="$1"

log() { echo -e "\n=== $1 ==="; }

# 키 형식 하한 하한포함 상한 상한포함 — 끝이 - 면 그쪽으로 열려 있다.
RANGE_RULES=(
    "TRADING_TAKE_PROFIT_PCT decimal 0 0 100 1"
    "TRADING_MAX_LOSS_PCT decimal 0 0 100 0"
    "TRADING_TRAILING_STOP_PCT decimal 0 0 100 0"
    "TRADING_TRAILING_ARM_PCT decimal 0 1 100 1"
    "TRADING_MAX_HOLD_DAYS integer 1 1 - 0"
    "TRADING_INVEST_RATIO decimal 0 0 1 1"
    "TRADING_ROUND_TRIP_FEE_RATE decimal 0 1 0.01 0"
    "TRADING_SHADOW_EXIT_TRAILING_STOP_PCT decimal 0 0 100 0"
    "TRADING_SHADOW_EXIT_TRAILING_ARM_PCT decimal 0 1 - 0"
)
# 앱(Spring)은 TRUE·yes·1·on 도 받지만 여기서는 true/false 만 받는다 — 오기(ture)는 앱 바인딩에 실패해 기동을
# 막으므로 좁게 본다. 자동매매 스위치는 아래 게이트가 읽는다.
BOOLEAN_KEYS=(
    TRADING_AUTO_START
    TRADING_CHART_EXIT_ENABLED
    TRADING_SHADOW_EXIT_ENABLED
)
EXIT_PARAM_KEYS=(
    TRADING_TAKE_PROFIT_PCT
    TRADING_MAX_LOSS_PCT
    TRADING_TRAILING_STOP_PCT
    TRADING_TRAILING_ARM_PCT
    TRADING_MAX_HOLD_DAYS
    TRADING_CHART_EXIT_ENABLED
)

# 정수부를 9자리로 묶는다 — 넘치면 앱이 Int 바인딩에 실패하거나(보유일) Double 이 무한대가 된다.
DECIMAL_FORMAT='^-?[0-9]{1,9}([.][0-9]+)?$'
INTEGER_FORMAT='^-?[0-9]{1,9}$'
BOOLEAN_FORMAT='^(true|false)$'

# 키의 선언 값들 — 같은 키가 여러 줄이어도 모두 검사한다.
values_of() { grep "^$1=" "$env_file" | cut -d= -f2- || true; }

# 형식을 통과한 평범한 소수만 받는다 — awk 구현마다 다른 수 해석(16진·지수·NaN)이 끼어들지 않는다.
in_range() {
    awk -v x="$1" -v lo="$2" -v loi="$3" -v hi="$4" -v hii="$5" 'BEGIN {
        x += 0
        if (lo != "-" && (loi == 1 ? x < lo + 0 : x <= lo + 0)) exit 1
        if (hi != "-" && (hii == 1 ? x > hi + 0 : x >= hi + 0)) exit 1
        exit 0
    }'
}

interval() {
    local open close upper
    if [[ $2 == 1 ]]; then open='['; else open='('; fi
    if [[ $3 == - ]]; then
        upper='∞'; close=')'
    else
        upper=$3
        if [[ $4 == 1 ]]; then close=']'; else close=')'; fi
    fi
    printf '%s%s, %s%s' "$open" "$1" "$upper" "$close"
}

violations=()
for rule in "${RANGE_RULES[@]}"; do
    read -r key kind lo loi hi hii <<< "$rule"
    if [[ $kind == integer ]]; then format=$INTEGER_FORMAT; shape='정수'; else format=$DECIMAL_FORMAT; shape='소수'; fi
    while IFS= read -r value; do
        if [[ ! $value =~ $format ]]; then
            violations+=("$key: 형식 — ${shape}로 적으세요(정수부 9자리까지, 부호는 - 만, 지수·공백·쉼표 없이)")
        elif ! in_range "$value" "$lo" "$loi" "$hi" "$hii"; then
            violations+=("$key: 구간 밖 — 허용 $(interval "$lo" "$loi" "$hi" "$hii")")
        fi
    done < <(values_of "$key")
done
for key in "${BOOLEAN_KEYS[@]}"; do
    while IFS= read -r value; do
        [[ $value =~ $BOOLEAN_FORMAT ]] || violations+=("$key: 형식 — true 또는 false(소문자)로 적으세요")
    done < <(values_of "$key")
done

# 게이트는 앱과 같게 읽는다: 선언하지 않으면 앱 기본값(false)이다. 형식이 틀린 값은 위에서 이미 위반이고,
# 켜졌을 수도 있으니 선언까지 본다.
auto_start="$(values_of TRADING_AUTO_START | tail -n 1)"
check_declared=false
if [[ -n $auto_start && $auto_start != false ]]; then check_declared=true; fi
missing=()
if $check_declared; then
    if [[ $auto_start == true ]]; then
        missing_reason='자동매매 배포라 코드 기본값으로 조용히 거래하게 됩니다'
    else
        missing_reason='자동매매 스위치 형식이 틀려 켜졌을 수 있으므로 선언도 봅니다'
    fi
    # 빈 값(KEY=)은 선언으로 본다 — 앱도 선언된 키로 보고 바인딩에서 실패하며, 여기서는 위 형식 검사가 잡는다.
    for key in "${EXIT_PARAM_KEYS[@]}"; do
        grep -q "^$key=" "$env_file" || missing+=("$key")
    done
fi

if (( ${#violations[@]} + ${#missing[@]} > 0 )); then
    echo "ERROR: 청산·주문 파라미터 검사에 걸려 배포를 중단합니다(값은 출력하지 않습니다)." >&2
    if (( ${#violations[@]} > 0 )); then printf '  %s\n' "${violations[@]}" >&2; fi
    if (( ${#missing[@]} > 0 )); then
        for key in "${missing[@]}"; do echo "  $key: 미선언 — $missing_reason" >&2; done
    fi
    echo "  VULTR_DEPLOY_ENV 시크릿(또는 deploy/vultr/.env)을 고치고 다시 실행하세요." >&2
    exit 1
fi
if $check_declared; then
    log "청산·주문 파라미터 값 확인, 청산 파라미터 ${#EXIT_PARAM_KEYS[@]}개 선언 확인"
else
    log "청산·주문 파라미터 값 확인 (자동매매 off — 선언 검사 생략)"
fi
