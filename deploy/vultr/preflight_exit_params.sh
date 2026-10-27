#!/usr/bin/env bash
# 렌더된 서버 .env 의 설정 값을 업로드 전에 검사한다. 종료코드 0=통과 1=위반 2=사용법 오류.
# 앱 기동 실패로 막으면 보유 포지션의 손절이 평가되지 않는 공백이 생기고 롤백도 같은 .env 를 쓰므로 여기서 막는다.
# 구간 표는 ExitParamRanges.kt 와 KisExitParamRanges.kt 의 사본이며 ExitParamsPreflightScriptTest 가 대조한다.
# 배포 로그가 공개되므로 값은 출력하지 않는다. macOS bash 3.2 에서도 돌아야 한다.
set -euo pipefail
export LC_ALL=C

if [[ $# -ne 1 || ! -r "$1" ]]; then
    echo "사용법: $0 <렌더된 env 파일>" >&2
    exit 2
fi
env_file="$1"

log() { echo -e "\n=== $1 ==="; }

# 키 형식 하한 하한포함 상한 상한포함. - 는 그쪽 끝이 열려 있다는 뜻이다.
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
    "KIS_TRADING_TAKE_PROFIT_PCT decimal 0 0 100 1"
    "KIS_TRADING_MAX_LOSS_PCT decimal 0 0 100 0"
    "KIS_TRADING_TRAILING_STOP_PCT decimal 0 0 100 0"
    "KIS_TRADING_TRAILING_ARM_PCT decimal 0 1 100 1"
    "KIS_TRADING_MAX_HOLD_DAYS integer 1 1 - 0"
    "KIS_TRADING_INVEST_RATIO decimal 0 0 1 1"
    "KIS_TRADING_COMMISSION_RATE decimal 0 1 0.01 0"
    "KIS_TRADING_SELL_TAX_RATE decimal 0 1 0.01 0"
)
# Spring 은 yes·on 등도 받지만 여기서는 소문자 true/false 만 받는다.
# DISCORD_ERROR_ALERT_ENABLED 는 compose 가 값과 함께 넘기는 키라 오기면 새 컨테이너와 롤백이 함께 기동에 실패한다.
BOOLEAN_KEYS=(
    TRADING_AUTO_START
    TRADING_SHADOW_EXIT_ENABLED
    MARKETDATA_WATCHDOG_ENABLED
    KIS_TRADING_ENABLED
    KIS_TRADING_LIVE_ENABLED
    DISCORD_ERROR_ALERT_ENABLED
)
# 0 이하는 기동이 실패하거나 워치독 재연결·재구독이 쉬지 않고 돈다.
# KIS 금액 상한·주기·요청 간격이 0 이하면 앱이 KIS 클라이언트를 만들지 않으므로 배포 전에 막는다.
POSITIVE_INTEGER_KEYS=(
    TRADING_INTERVAL_SECONDS
    TRADING_RECONCILE_HALT_THRESHOLD
    MARKETDATA_WATCHDOG_STALE_MS
    MARKETDATA_WATCHDOG_INTERVAL_MS
    MARKETDATA_WATCHDOG_INITIAL_DELAY_MS
    MARKETDATA_WATCHDOG_RESTART_BACKOFF_MS
    KIS_TRADING_MAX_INVEST_AMOUNT
    KIS_TRADING_MAX_ORDER_AMOUNT
    KIS_TRADING_INTERVAL_SECONDS
    KIS_TRADING_PAPER_MIN_REQUEST_INTERVAL_MS
    KIS_TRADING_LIVE_MIN_REQUEST_INTERVAL_MS
)
# 구간 없이 형식만 보는 소수.
DECIMAL_KEYS=(
    TRADING_MAX_INVEST_AMOUNT
    TRADING_K_VALUE
)
# 쉼표로 구분한 대문자 마켓 코드. watchlist 는 하나만 틀려도 WS 구독 전체가 끊기고,
# TRADING_TICKERS 는 소문자를 그대로 주문에 쓴다.
TICKER_LIST_KEYS=(
    TRADING_TICKERS
    WATCHLIST_TICKERS
)
EXIT_PARAM_KEYS=(
    TRADING_TAKE_PROFIT_PCT
    TRADING_MAX_LOSS_PCT
    TRADING_TRAILING_STOP_PCT
    TRADING_TRAILING_ARM_PCT
    TRADING_MAX_HOLD_DAYS
)

# 정수부가 9자리를 넘으면 Int 바인딩이 실패한다.
DECIMAL_FORMAT='^-?[0-9]{1,9}([.][0-9]+)?$'
INTEGER_FORMAT='^-?[0-9]{1,9}$'
BOOLEAN_FORMAT='^(true|false)$'
POSITIVE_INTEGER_FORMAT='^[1-9][0-9]{0,8}$'
# Upbit 에는 KRW-T 같은 1글자 자산이 있어 길이는 보지 않는다.
MARKET_FORMAT='^[A-Z]+-[A-Z0-9]+$'

# 같은 키가 여러 줄이면 모두 검사한다.
values_of() { grep "^$1=" "$env_file" | cut -d= -f2- || true; }

# 형식 검사를 통과한 값만 넘긴다. awk 구현마다 16진·지수 해석이 다르다.
in_range() {
    awk -v x="$1" -v lo="$2" -v loi="$3" -v hi="$4" -v hii="$5" 'BEGIN {
        x += 0
        if (lo != "-" && (loi == 1 ? x < lo + 0 : x <= lo + 0)) exit 1
        if (hi != "-" && (hii == 1 ? x > hi + 0 : x >= hi + 0)) exit 1
        exit 0
    }'
}

ticker_list_ok() {
    local rest="$1," piece count=0
    while [[ -n $rest ]]; do
        piece=${rest%%,*}
        rest=${rest#*,}
        piece=${piece#"${piece%%[! ]*}"}
        piece=${piece%"${piece##*[! ]}"}
        [[ -z $piece ]] && continue
        [[ $piece =~ $MARKET_FORMAT ]] || return 1
        count=$((count + 1))
    done
    (( count > 0 ))
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
for key in "${POSITIVE_INTEGER_KEYS[@]}"; do
    while IFS= read -r value; do
        [[ $value =~ $POSITIVE_INTEGER_FORMAT ]] || violations+=("$key: 형식 — 1 이상 정수(9자리까지, 앞자리 0·단위·부호·소수점 없이)로 적으세요")
    done < <(values_of "$key")
done
for key in "${DECIMAL_KEYS[@]}"; do
    while IFS= read -r value; do
        [[ $value =~ $DECIMAL_FORMAT ]] || violations+=("$key: 형식 — 소수로 적으세요(정수부 9자리까지, 부호는 - 만, 지수·공백·쉼표 없이)")
    done < <(values_of "$key")
done
for key in "${TICKER_LIST_KEYS[@]}"; do
    while IFS= read -r value; do
        ticker_list_ok "$value" || violations+=("$key: 형식 — 쉼표로 구분한 대문자 마켓 코드로 적으세요(예: KRW-BTC,KRW-ETH)")
    done < <(values_of "$key")
done

# 미선언이면 앱 기본값 false 다. 형식이 틀린 값은 켜졌을 수도 있으니 선언 검사까지 한다.
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
    # 빈 값(KEY=)은 선언으로 보고 위 형식 검사에서 잡는다.
    for key in "${EXIT_PARAM_KEYS[@]}"; do
        grep -q "^$key=" "$env_file" || missing+=("$key")
    done
fi

if (( ${#violations[@]} + ${#missing[@]} > 0 )); then
    echo "ERROR: 배포 설정 검사에 걸려 배포를 중단합니다(값은 출력하지 않습니다)." >&2
    if (( ${#violations[@]} > 0 )); then printf '  %s\n' "${violations[@]}" >&2; fi
    if (( ${#missing[@]} > 0 )); then
        for key in "${missing[@]}"; do echo "  $key: 미선언 — $missing_reason" >&2; done
    fi
    echo "  VULTR_DEPLOY_ENV 시크릿(또는 deploy/vultr/.env)을 고치고 다시 실행하세요." >&2
    exit 1
fi
if $check_declared; then
    log "배포 설정 값 확인, 청산 파라미터 ${#EXIT_PARAM_KEYS[@]}개 선언 확인"
else
    log "배포 설정 값 확인 (자동매매 off — 선언 검사 생략)"
fi
