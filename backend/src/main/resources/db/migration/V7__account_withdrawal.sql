-- 회원탈퇴: 행은 남기고(신청 FK·시작된 회차 기록 보존) 개인정보를 익명화한 뒤 탈퇴 시각을 남긴다.
-- 탈퇴하면 login_id 를 'deleted-{id}' 로 바꿔 원래 아이디를 풀어 주므로 같은 아이디로 다시 가입할 수 있다.
alter table account add column deleted_at timestamptz;
