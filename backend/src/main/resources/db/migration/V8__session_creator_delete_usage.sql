-- 1단계 개편: 회차 생성자, 회차 물리 삭제(신청 함께 삭제), 누적 사용량 집계.

-- 회차 생성자. 일반 회원도 회차를 만들 수 있고, 생성자만 수정·정원·명단·삭제를 한다.
-- 데모 체험 회차는 생성자가 없다(null). 계정은 탈퇴해도 행이 남으므로(익명화) FK 는 계정 삭제를 막지 않는다.
alter table study_session add column creator_account_id bigint references account (id);

-- 기존 일반 회차 백필: 지금까지 회차는 운영자만 만들 수 있었으므로 가장 먼저 만든(가장 작은 id) 미탈퇴 운영자에게 연결한다.
-- 운영자가 없는 DB(테스트 등)는 null 로 남고, 생성자가 없는 일반 회차는 운영자가 관리한다 (SessionPermissions).
-- 행 수는 바뀌지 않는다 (study_session 의 새 칸만 채운다).
update study_session
set creator_account_id = (select id from account where role = 'OPERATOR' and deleted_at is null order by id limit 1)
where demo_trial_id is null;

create index ix_study_session_creator on study_session (creator_account_id) where creator_account_id is not null;

-- 회차를 물리 삭제하면 그 회차의 신청(확정·대기·취소)을 DB 가 같은 문장에서 함께 지운다.
-- (V2 의 이름 없는 FK 는 PostgreSQL 기본 이름 reservation_study_session_id_fkey)
alter table reservation drop constraint reservation_study_session_id_fkey;
alter table reservation
    add constraint reservation_study_session_id_fkey
        foreign key (study_session_id) references study_session (id) on delete cascade;

-- 누적 사용량: 성공한 동작의 횟수를 날짜(서울)·지표·데모 여부별로만 센다. 계정·회차·신청 id 같은 개인 식별 정보는 두지 않는다.
-- 원본 행을 지워도 줄지 않는다. 동작과 같은 트랜잭션에서 늘어나므로 롤백되면 함께 사라진다.
create table usage_daily (
    day    date        not null,
    metric varchar(40) not null,
    demo   boolean     not null,
    count  bigint      not null check (count >= 0),
    primary key (day, metric, demo)
);

-- 집계 시작 시각. 이 시각 이전의 누적치는 만들지 않는다 (확인할 수 없는 과거 값을 채우지 않음).
create table usage_meta (
    key   varchar(40) primary key,
    value timestamptz not null
);
insert into usage_meta (key, value) values ('counting_started_at', now());
