-- 엔티티가 더 매핑하지 않는 칼럼을 지운다.
--   users.public_profile·public_strategy (V4), users.admin (V8) — 읽는 코드가 없다.
--   trade_executions.order_type·status (V11) — 앱은 기본값('MARKET'·'EXECUTED')만 넣었다(다른 값을 쓰던 KIS 행은 V27 이 지웠다).
-- 엔티티에서는 V39 와 같은 배포에서 뺐다. 그 이미지(마지막 정상 배포)가 이 칼럼을 넣지도 읽지도 않으므로 롤백해도 기동하고 쓴다.
--
-- 롤백: V39 이전 이미지는 이 칼럼을 INSERT 에 넣으므로 V40 스키마에서 가입·체결 기록이 실패한다. 그 이미지로 내려가려면 앱을 멈추고
-- deploy/vultr/sql/V40_down.sql 을 먼저 돌린다(값은 되살아나지 않는다 — 기본값이 아니던 값은 배포 전 덤프에만 있다).
--
-- CASCADE 를 쓰지 않는다: 예상 밖의 의존 객체가 있으면 DROP 이 실패하고 이 마이그레이션 전체가 되돌려져 자동 롤백으로 끝난다.

-- 무엇을 버렸는지 기동 로그에 남긴다(기본값이 아닌 값을 가진 행 수).
DO $$
DECLARE
    n BIGINT;
BEGIN
    SELECT count(*) INTO n FROM users WHERE admin OR public_profile OR public_strategy;
    RAISE NOTICE 'V40 drop: users rows with admin/public_profile/public_strategy set=%', n;
    SELECT count(*) INTO n FROM trade_executions WHERE order_type <> 'MARKET' OR status <> 'EXECUTED';
    RAISE NOTICE 'V40 drop: trade_executions rows with a non-default order_type/status=%', n;
END $$;

ALTER TABLE users
    DROP COLUMN public_profile,
    DROP COLUMN public_strategy,
    DROP COLUMN admin;

ALTER TABLE trade_executions
    DROP COLUMN order_type,
    DROP COLUMN status;
