-- 대기(WAITING) 상태 추가.
-- 활성 신청 유일성(ux_reservation_active, status <> 'CANCELLED')에 WAITING 도 자동으로 포함된다.
alter table reservation drop constraint reservation_status_check;
alter table reservation
    add constraint reservation_status_check check (status in ('CONFIRMED', 'WAITING', 'CANCELLED'));

-- 대기 순번 = 같은 회차 WAITING 중 id 가 더 작은 행 수 + 1.
-- 신청은 회차 행 잠금 아래 하나씩 들어오므로 id 순서가 곧 신청 순서다 (같은 시각이어도 순서가 갈리지 않음).
create index ix_reservation_waiting
    on reservation (study_session_id, id)
    where status = 'WAITING';
