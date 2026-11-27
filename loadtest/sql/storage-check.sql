-- 저장 용량 점검 (읽기 전용: SELECT 와 통계 함수만 쓴다. 쓰기·VACUUM 없음).
-- Neon SQL 편집기에 통째로 붙여 넣고 실행한다. 결과 4개 표를 그대로 알려 주면 기록한다.

-- 1) DB 전체 크기 (Neon 저장 한도와 비교하는 값에 가장 가깝다. Neon 콘솔의 사용량에는 변경 기록(history)이 더해질 수 있다)
select pg_size_pretty(pg_database_size(current_database())) as database_size,
       pg_database_size(current_database())                 as database_bytes;

-- 2) 주요 테이블: 본 테이블 · TOAST(큰 값이 따로 저장되는 곳) · 인덱스 · 합계, 살아 있는 행·지워졌지만 아직 정리 안 된 행
select c.relname                                                     as table_name,
       pg_size_pretty(pg_relation_size(c.oid))                       as heap,
       pg_size_pretty(coalesce(pg_total_relation_size(c.reltoastrelid), 0)) as toast,
       pg_size_pretty(pg_indexes_size(c.oid))                        as indexes,
       pg_size_pretty(pg_total_relation_size(c.oid))                 as total,
       pg_total_relation_size(c.oid)                                 as total_bytes,
       s.n_live_tup                                                  as live_rows,
       s.n_dead_tup                                                  as dead_rows,
       s.last_autovacuum                                             as last_autovacuum
from pg_class c
join pg_namespace n on n.oid = c.relnamespace and n.nspname = 'public'
left join pg_stat_user_tables s on s.relid = c.oid
where c.relkind = 'r'
order by pg_total_relation_size(c.oid) desc
limit 15;

-- 3) 사진: 앱이 세는 논리 용량(상한 300MB 와 비교하는 값) vs 실제 바이트 합계 vs photo_object 가 차지하는 디스크
select (select count(*) from club_photo)                                     as photos,
       (select coalesce(sum(stored_bytes), 0) from club_photo)              as logical_bytes,
       (select coalesce(sum(octet_length(data)), 0) from photo_object)      as stored_data_bytes,
       pg_total_relation_size('photo_object')                                as photo_object_disk_bytes,
       round(100.0 * (select coalesce(sum(stored_bytes), 0) from club_photo) / 314572800, 2) as percent_of_300mb;

-- 4) 채팅·오류 로그·세션 등 행 수 (크기는 2) 표)
select (select count(*) from club_chat_message)                              as chat_messages,
       (select count(*) from club_chat_message where deleted_at is not null) as chat_deleted,
       (select count(*) from error_group)                                    as error_groups,
       (select count(*) from spring_session)                                 as sessions,
       (select count(*) from notification)                                   as notifications,
       (select count(*) from account)                                        as accounts;
