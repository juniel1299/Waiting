-- 커뮤니티 5단계: 모임 내부 게시판(CLUB_BOARD)과 모임 공지(CLUB_NOTICE).
-- 글·댓글·추천·멘션·알림 구조는 그대로 쓰고, 모임 글은 club_id 로 모임에 묶는다.
-- 모임을 지우면 그 모임 글이, 글을 따라 댓글·답글·추천·멘션·알림이 같은 문장에서 함께 지워진다.
alter table board_post add column club_id bigint references club (id) on delete cascade;
alter table board_post drop constraint board_post_board_check;
alter table board_post add constraint board_post_board_check
    check (board in ('COMMUNITY', 'NOTICE', 'CLUB_BOARD', 'CLUB_NOTICE'));
-- 모임 게시판 글에만 club_id 가 있다
alter table board_post add constraint board_post_club_check
    check ((board in ('CLUB_BOARD', 'CLUB_NOTICE')) = (club_id is not null));

create index ix_board_post_club on board_post (club_id, board, created_at desc, id desc) where club_id is not null;
