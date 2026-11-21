-- 부하 실행 뒤 정합성 검증. 모든 쿼리가 0행이어야 정상이다.
-- 시작 전 회차만 본다(시작된 회차는 예약·취소가 막혀 대기가 남는 것이 정상).

\echo '① 정원 초과: 회차별 CONFIRMED 수 > capacity'
select s.id as session_id, s.capacity, count(*) as confirmed
from study_session s
join reservation r on r.study_session_id = s.id and r.status = 'CONFIRMED'
group by s.id, s.capacity
having count(*) > s.capacity;

\echo '② 활성 신청 중복: 같은 (회차, 계정)에 CANCELLED 가 아닌 신청 2건 이상'
select study_session_id, account_id, count(*) as active
from reservation
where status <> 'CANCELLED'
group by study_session_id, account_id
having count(*) > 1;

\echo '③ 대기 추월: 아직 WAITING 인 신청보다 늦은 id 의 신청이 CONFIRMED'
-- id 순서 = 신청 순서(V3 주석). 대기자가 있으면 신규 예약은 막히고 승격은 id 순이므로,
-- 남은 대기자보다 늦게 들어온 확정은 "대기를 건너뛴 직접 확정" 또는 "순서를 어긴 승격" 이다.
-- 순서대로 승격된 대기자는 남은 대기자보다 id 가 작아 걸리지 않는다.
select c.study_session_id as session_id, c.id as confirmed_id, min(w.id) as earlier_waiting_id
from reservation c
join reservation w on w.study_session_id = c.study_session_id and w.status = 'WAITING' and w.id < c.id
join study_session s on s.id = c.study_session_id and s.starts_at > now()
where c.status = 'CONFIRMED'
group by c.study_session_id, c.id;

\echo '④ 승격 누락: 빈자리가 있는데 WAITING 이 남은 회차'
select s.id as session_id, s.capacity,
       count(*) filter (where r.status = 'CONFIRMED') as confirmed,
       count(*) filter (where r.status = 'WAITING') as waiting
from study_session s
join reservation r on r.study_session_id = s.id
where s.starts_at > now()
group by s.id, s.capacity
having count(*) filter (where r.status = 'WAITING') > 0
   and count(*) filter (where r.status = 'CONFIRMED') < s.capacity;
