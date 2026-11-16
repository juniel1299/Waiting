-- 로그인 시도 집계 (고정 창). 여러 인스턴스가 같은 DB 를 쓰면 함께 세고, 재시작해도 유지된다.
-- throttle_key 는 "계정+IP" 또는 "IP" 원문의 SHA-256 (입력한 아이디·IP 원문을 저장하지 않는다).
create table login_attempt (
    throttle_key varchar(64) primary key,
    window_start timestamptz not null,
    attempts     integer     not null check (attempts >= 0)
);

-- 만료 행 정리용
create index ix_login_attempt_window_start on login_attempt (window_start);
