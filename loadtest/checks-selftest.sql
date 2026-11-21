-- checks.sql 이 위반을 실제로 잡는지 확인한다. 한 트랜잭션에서 위반 데이터를 만들고 검사한 뒤 되돌린다.
-- 기대: ① 1행, ③ 1행, ④ 1행. ②는 DB unique 인덱스(ux_reservation_active)가 막아 만들 수 없다.
begin;
insert into account (login_id, password_hash, display_name, role, created_at)
values ('selftest_a', '!x', 'a', 'PARTICIPANT', now()), ('selftest_b', '!x', 'b', 'PARTICIPANT', now()),
       ('selftest_c', '!x', 'c', 'PARTICIPANT', now());
insert into study_session (title, starts_at, location, capacity, created_at)
values ('SELFTEST over', now() + interval '1 day', 'x', 1, now()),
       ('SELFTEST skip', now() + interval '1 day', 'x', 2, now()),
       ('SELFTEST missed', now() + interval '1 day', 'x', 2, now());
-- ① 정원 1 에 확정 2
insert into reservation (study_session_id, account_id, status, created_at)
select s.id, a.id, 'CONFIRMED', now() from study_session s, account a
where s.title = 'SELFTEST over' and a.login_id in ('selftest_a', 'selftest_b');
-- ③ 먼저 온 대기(b)가 남았는데 늦게 온 c 가 확정 (정원 2, 확정 a·c)
insert into reservation (study_session_id, account_id, status, created_at)
select s.id, a.id, case a.login_id when 'selftest_b' then 'WAITING' else 'CONFIRMED' end, now()
from study_session s, account a where s.title = 'SELFTEST skip' and a.login_id like 'selftest_%' order by a.login_id;
-- ④ 정원 2 에 확정 1, 대기 1
insert into reservation (study_session_id, account_id, status, created_at)
select s.id, a.id, case a.login_id when 'selftest_a' then 'CONFIRMED' else 'WAITING' end, now()
from study_session s, account a where s.title = 'SELFTEST missed' and a.login_id in ('selftest_a', 'selftest_b');
\i loadtest/checks.sql
rollback;
