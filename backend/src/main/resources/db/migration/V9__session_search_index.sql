-- 2단계 회차 검색: 기본 목록(시작 전 회차, 시작 임박순)과 날짜 범위는 일반 회차의 starts_at 순서로 읽고 한 쪽(limit)만큼에서 멈춘다.
-- 정렬과 같은 (starts_at, id) 순서라 확정·대기 수 집계도 그 쪽의 회차에만 한다.
-- 로컬 측정 (회차 20,000·신청 100,000): 기본 1쪽 2.6ms → 0.03ms, '예약 가능' 필터 1쪽 143.8ms → 0.2ms (README "회차 검색").
-- 운영 회차 수가 적어 잠금은 짧다 (CREATE INDEX 는 쓰기를 잠깐 막는다. Flyway 트랜잭션 안이라 CONCURRENTLY 는 쓰지 않음).
create index ix_study_session_regular_starts_at on study_session (starts_at, id) where demo_trial_id is null;
