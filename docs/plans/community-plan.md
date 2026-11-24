# 커뮤니티·알림·모임 계획 (2026-11-23)

진행: **1단계 완료**(V11, PR #39·#40) · **2단계 완료**(V12, README "커뮤니티 2단계", 결정 1번은 추천안 A 로 구현: 공개 닉네임 중복 허용) · **3단계 완료·공개 검증 통과**(2026-11-24)(V13, README "커뮤니티 3단계": 알림 목록은 쪽 나눔 대신 최신 50건, 열기는 `POST /api/me/notifications/{id}/open` 으로 권한 재확인 뒤 주소를 받음) · **4단계 완료·공개 검증 통과**(2026-11-24)(V14, README "커뮤니티 4단계": `DRAFT` 없이 제출이 곧 신청, 모임 삭제 `DELETE /api/clubs/{id}` 는 4단계에 넣음, 결정 4 는 추천안 B) · **5단계 완료·공개 검증 통과**(2026-11-24)(V15, README "커뮤니티 5단계": 모임 글은 `board_post.club_id`, 모임 안 멘션은 회원만, 생성자 이전 뒤 탈퇴, 계정 탈퇴는 추천안 B 유지) · **6단계 완료**(전체 흐름 e2e·화면 상태·375px·키보드·동작 줄이기, 공개 전체 흐름 점검 스크립트; 아래 "최종 정리"). 2단계 API 는 답글을 `POST /api/posts/{id}/comments` 의 `parentId` 로 받는다(아래 표의 `/replies` 대신).

요청: 회원 게시판·서비스 공지 → 댓글·답글·추천·멘션 → 알림함 → 모임 신청·승인·가입 → 모임 역할·내부 게시판 → 전체 흐름·공개 검증. 단계마다 테스트 먼저, PR·배포 검증. 월 0원(Render Free·Neon Free·Vercel Hobby) 유지.

## 재사용할 기존 구조

| 영역 | 기존 코드 | 쓰는 곳 |
|---|---|---|
| 인증·역할 | `SessionUser`(id·role·demo), `PARTICIPANT`/`OPERATOR`, 데모 판별 `isDemo()`, `SecurityConfig` 경로 규칙, CSRF 쿠키 | 모든 쓰기 권한, 공지 비회원 조회는 GET 만 permitAll |
| 정지·탈퇴 세션 차단 | `WithdrawnAccountFilter`(요청마다 정지·탈퇴 확인) | 그대로 (커뮤니티 API 도 같은 필터 뒤) |
| 생성자 권한 | 회차 `creator_account_id` + 서비스에서 행 잠근 뒤 확인, 응답의 `canManage`·`canDelete` | 게시글 `canEdit`·`canDelete`, 모임 생성자 |
| 검색·페이지 | `SessionSearch`·`StudySessionSearchQuery`(바인딩 변수만, LIKE 이스케이프, 1쪽부터), 프런트 `sessionSearch.ts`(주소가 조건의 원본) | 게시판·모임 목록 검색 |
| 물리 삭제·정합성 | 부모 행 `FOR UPDATE` 뒤 한 문장 삭제 + FK `ON DELETE CASCADE`, 자식 쓰기는 부모 행 잠금으로 직렬화 | 게시글·댓글·모임 삭제 |
| 탈퇴 익명화 | `Account.withdraw`(아이디 `deleted-{id}`, 이름 `탈퇴한 회원`), `releaseAllOf` 한 트랜잭션 | 탈퇴 회원 글·댓글 작성자 표시, 생성 모임 처리 |
| 화면 | `AuthGate`·주 메뉴 `aria-current`, `SessionSearchList` 패턴, 카드 덮개 링크, 180ms·동작 줄이기, `useVisiblePolling`(보이는 동안만·탭 복귀·재연결) | 게시판·알림 버튼·모임 화면 |

## 데이터 모델 (마이그레이션은 단계별)

| 단계 | 표 | 핵심 칸·제약 | FK ON DELETE |
|---|---|---|---|
| 1 (V11) | `board_post` | `board` ∈ `COMMUNITY`·`NOTICE` (5단계에서 `CLUB_BOARD`·`CLUB_NOTICE` + `club_id` 추가), `author_account_id`, `title` 1~100, `body` 1~5000(원문 텍스트), `created_at`, `updated_at`(null 이면 수정 안 됨). 인덱스 `(board, created_at desc, id desc)` | 작성자 → 계정 (탈퇴는 익명화라 행 유지) |
| 2 (V12) | `comment` | `post_id`, `parent_id`(null=댓글, 답글의 부모는 댓글이어야 함: 서비스 검증 + 트리거로 2단계 이상 거절), `author_account_id`, `body` 1~1000, `created_at`, `updated_at` | `post_id` → `board_post` CASCADE, `parent_id` → `comment` CASCADE |
| 2 | `comment_like` | PK `(comment_id, account_id)`: 계정당 대상별 1번을 DB 가 보장 | `comment_id` CASCADE |
| 2 | `mention` | `post_id` 또는 `comment_id` 중 하나, `account_id`, 유니크 `(post_id, account_id)`·`(comment_id, account_id)` | 원본 CASCADE |
| 3 (V13) | `notification` | `recipient_account_id`, `type` ∈ `PROMOTED`·`MENTION`·`CLUB_APPROVED`·`CLUB_REJECTED`, 이동 대상 `session_id`/`post_id`/`comment_id`/`club_id`, `event_key` 유니크(같은 사건 중복 방지), `read_at`, `created_at`. 인덱스 `(recipient_account_id, read_at, created_at desc)` | 대상·수신자 CASCADE (대상이 지워지면 알림도 지워져 깨진 링크가 남지 않음) |
| 4 (V14) | `club` | `name` 1~60, `intro` 1~2000, `place` ≤200, `mode` ∈ `OFFLINE`·`ONLINE`·`MIXED`, `status` ∈ `PENDING`·`APPROVED`·`REJECTED`·`CANCELLED`, `reject_reason`, `creator_account_id`, `version`(낙관적 잠금), `submitted_at`·`decided_at` | 생성자 → 계정 |
| 4 | `club_member` | PK `(club_id, account_id)`: 중복 가입 DB 차단, `role` ∈ `MEMBER`·`MANAGER`, `joined_at` | `club_id` CASCADE |
| 5 (V15) | `board_post.club_id` | 모임 게시판 ⇔ `club_id` not null 체크. 모임 삭제 시 내부 글·댓글·추천·멘션·알림까지 CASCADE 로 한 문장 | `club` CASCADE |

## 권한표

| 동작 | 비회원 | 데모 | 일반 회원 | 서비스 운영자 | 모임 회원 | 모임 운영자 | 모임 생성자 |
|---|---|---|---|---|---|---|---|
| 회원 게시판 조회 | ✗ 401 | ○ | ○ | ○ | | | |
| 회원 게시판 작성 | ✗ | ✗ 403 | ○ | ○ | | | |
| 게시글 수정 | ✗ | ✗ | 본인 | 본인 | 본인(모임 게시판) | 본인 | 본인 |
| 게시글 삭제 | ✗ | ✗ | 본인 | 전체 | 본인(모임 게시판) | 모임 전체 | 모임 전체 |
| 서비스 공지 조회 | ○ | ○ | ○ | ○ | | | |
| 서비스 공지 작성·수정·삭제 | ✗ | ✗ | ✗ | ○ | | | |
| 댓글·답글 조회 | 글 조회 범위 | 글 조회 범위 | 글 조회 범위 | ○ | 모임 내부는 회원만 | | |
| 댓글·답글 작성·추천 | ✗ | ✗ | ○ | ○ | 모임 내부는 회원만 | | |
| 댓글 수정 / 삭제 | ✗ | ✗ | 본인 / 본인 | 본인 / 전체 | 본인 / 본인 | 본인 / 모임 전체 | 본인 / 모임 전체 |
| 모임 신청·내 모임 | ✗ | ✗ | ○ | ○ | | | |
| 모임 승인·반려 | ✗ | ✗ | ✗ | ○ | | | |
| 승인 모임 목록·소개 | ✗ 로그인 | ○ | ○ | ○ | | | |
| 모임 가입·탈퇴 | ✗ | ✗ | ○ | ○ | 탈퇴 | 탈퇴 | 이전·삭제 후 |
| 모임 내부 게시판 조회·작성 | ✗ | ✗ | ✗ | 조회·삭제만 | ○ | ○ | ○ |
| 모임 공지 작성·수정·삭제 | ✗ | ✗ | ✗ | 삭제만 | ✗ | ○ | ○ |
| 모임 운영자 임명·회수 | | | | ✗ | ✗ | ✗ | ○ |
| 모임 정보 수정 / 삭제 | ✗ | ✗ | ✗ | ✗ / ○ | ✗ | ✗ | ○ / ○ |

권한의 근거는 서버다. 응답에 `canEdit`·`canDelete`·`canWrite` 를 넣어 화면은 그 값으로 버튼을 보인다(회차의 `canManage` 와 같은 방식).

## API (단계별)

| 단계 | 메서드·경로 |
|---|---|
| 1 | `GET /api/boards/{community,notice}/posts?q&field&page&size`, `GET /api/posts/{id}`, `POST /api/boards/{board}/posts`, `PATCH /api/posts/{id}`, `DELETE /api/posts/{id}`. 공지 목록·상세 GET 은 비회원 허용 |
| 2 | `GET/POST /api/posts/{id}/comments`, `POST /api/comments/{id}/replies`, `PATCH/DELETE /api/comments/{id}`, `PUT/DELETE /api/comments/{id}/like`(멱등), `GET /api/mentions/candidates?q&clubId` |
| 3 | `GET /api/me/notifications?page`, `GET /api/me/notifications/unread-count`, `POST /api/me/notifications/{id}/read`, `POST /api/me/notifications/read-all` |
| 4 | `POST /api/clubs`(신청), `PATCH /api/clubs/{id}`, `POST /api/clubs/{id}/cancel`, `GET /api/clubs?q&page`(승인만), `GET /api/clubs/{id}`, `GET /api/me/clubs`, `POST /api/clubs/{id}/join`·`/leave`, 운영자 `GET /api/operator/clubs?status`, `POST /api/operator/clubs/{id}/approve`·`/reject {reason, version}` |
| 5 | `PUT/DELETE /api/clubs/{id}/managers/{accountId}`, `POST /api/clubs/{id}/transfer`, `DELETE /api/clubs/{id}`, 모임 게시판 `GET/POST /api/clubs/{id}/boards/{board,notice}/posts` (글·댓글 API 는 1·2단계 재사용) |

## 화면·라우트

| 경로 | 화면 |
|---|---|
| `/board`, `/board/new`, `/board/{id}`, `/board/{id}/edit` | 회원 게시판 목록(검색·쪽)·작성·상세(댓글)·수정 |
| `/notices`, `/notices/new`, `/notices/{id}`, `/notices/{id}/edit` | 서비스 공지 (목록·상세는 로그인 없이) |
| 머리글 알림 버튼 → `/me/notifications` | 읽지 않은 수 배지, 목록·개별 읽음·모두 읽음 |
| `/clubs/promo`, `/clubs`, `/clubs/{id}`, `/clubs/new`, `/clubs/{id}/edit`, `/me/clubs` | 모임 홍보·목록·소개·신청·수정·내 모임(진행 단계) |
| `/clubs/{id}/board/...`, `/clubs/{id}/notices/...`, `/clubs/{id}/members` | 모임 내부 게시판·공지·회원·운영자 임명 |
| `/operator/clubs` | 운영자 모임 승인·반려·관리 |

## 요청에 적힌 기본안 (그대로 적용)

- 게시글 삭제 → 댓글·답글·추천·멘션·관련 알림 물리 삭제 (FK CASCADE, 한 트랜잭션). 부모 댓글 삭제 → 답글과 연결 데이터 함께 물리 삭제, 답글이 있으면 확인창에 안내.
- 삭제와 작성·추천 경합: 작성·추천 때 부모(게시글·댓글) 행을 공유 잠금, 삭제 때 배타 잠금 → 삭제 전에 끝나 함께 지워지거나 삭제 뒤 404. 고아 0 을 동시성 테스트로 확인.
- 추천: PK `(comment_id, account_id)`. 화면은 토글이지만 API 는 `PUT`(추천)·`DELETE`(취소) 멱등이라 겹친 요청이 서로를 지우지 않는다.
- 답글은 한 단계: 답글의 답글은 400.
- 승격 알림: 승격과 같은 트랜잭션에서 저장(롤백되면 함께 사라짐), `event_key = PROMOTED:{reservationId}` 유니크로 재시도 중복 방지.
- 멘션 알림: `MENTION:{post|comment}:{id}:{accountId}` 유니크. 수정 때는 새로 추가된 사용자에게만 보낸다(이미 있던 멘션은 유지, 지운 멘션은 연결만 삭제). 자기 멘션은 알림 없음.
- 모임: 즉시 가입, `club_member` PK 로 중복 가입 차단. 데모는 승인 모임 소개 조회까지만.
- 알림 이동·모임 내부 접근 때마다 현재 가입·권한 검증.
- 본문: 텍스트 원문 저장, 화면은 React 텍스트 노드 + `white-space: pre-wrap` 으로만 그린다 (`dangerouslySetInnerHTML` 쓰지 않음, XSS 회귀 테스트).

## 정책안 (제안, 결정 전 이 값으로 진행)

- **모임 수정과 승인 상태:** 승인 대기 중 수정은 그대로 대기. 반려 뒤 수정하면 재신청(`PENDING`). 승인 뒤 생성자 수정은 **재승인 없이 즉시 반영**, 서비스 운영자는 사후 삭제 가능.
- **승인·반려와 신청자 수정 경합:** `club.version` 낙관적 잠금. 운영자는 화면에서 본 `version` 을 함께 보내고, 그 사이 신청자가 수정·취소했으면 409 `CLUB_CHANGED`("신청 내용이 바뀌었어요, 다시 확인"). 이미 결정된 신청의 수정·취소도 409.
- **생성자 모임 탈퇴:** 바로 탈퇴할 수 없다. 다른 회원에게 생성자를 이전한 뒤 탈퇴하거나 모임을 삭제한다.
- **회원탈퇴 일관성:** 기존 탈퇴 트랜잭션(계정 잠금 → 신청 정리 → 익명화)에 모임 처리·알림 정리를 같은 트랜잭션으로 넣어, 중간 실패 시 전부 롤백.

## 결정 필요 (추천안으로 진행 중, 바꾸려면 알려 주세요)

1. **멘션·작성자 표시에 보일 이름.** 지금 가입하면 `display_name` 이 가입 아이디와 같다(`SignupService`). 그대로 쓰면 게시글 작성자·멘션 후보로 **로그인 아이디가 공개**된다.
   - A) 공개 닉네임을 따로 둔다: 가입 때 닉네임 입력(2~20자, 중복 허용), 기존 회원은 `회원{id}` 로 채우고 내 정보에서 바꾼다. 멘션은 계정 id 에 연결. **(추천)**
   - B) 그대로 `display_name`(=아이디)을 보인다. 가장 작지만 로그인 아이디가 공개돼 비밀번호 대입 대상이 드러난다.
   - C) `회원#{id}` 만 보인다. 노출은 없지만 멘션할 사람을 찾기 어렵다.
   - 1단계 동안은 작성자를 `회원#{id}` 로 보이고(C), 2단계 멘션 전에 A 를 넣는 것으로 진행한다.
2. **데모 체험 계정.** A) 요청대로 회원 게시판·공지·승인 모임 소개 조회만 허용, 쓰기 403 **(추천)** · B) 데모에는 게시판 메뉴를 숨긴다.
3. **탈퇴 회원의 글·댓글.** A) 남기고 작성자를 `탈퇴한 회원` 으로 표시(기존 익명화와 같음), 탈퇴 회원 멘션도 `탈퇴한 회원` **(추천)** · B) 탈퇴 때 본인 글·댓글을 함께 물리 삭제(다른 사람의 답글·추천도 사라짐).
4. **생성자 계정 탈퇴 시 그 모임.** A) 다른 모임 운영자가 있으면 가장 먼저 임명된 운영자에게 생성자 이전, 없으면 모임 삭제 · B) 생성한 모임은 모두 삭제(회차 정책과 같음, 확인 화면에 안내) **(추천: 단순·일관)** · C) 생성 모임이 있으면 탈퇴를 막고 먼저 이전·삭제하게 한다.
5. **알림 폴링 주기.** 읽지 않은 수를 보이는 탭에서만 주기 조회 + 탭 복귀·재연결 때 1번(`useVisiblePolling`, 요청 겹침 방지). A) 30초 **(추천: 무료 서버 사용량)** · B) 15초 · C) 페이지 이동 때만.
6. **게시글 추천.** 요청에는 댓글·답글 추천만 있어 게시글 추천은 넣지 않는다. 필요하면 같은 구조로 추가.

참고 (결정 불필요): 공지 비회원 공개는 공지 목록·상세 `GET` 만 `permitAll` 로 열고 화면은 로그인 확인 없이 그린다. 읽기만이라 쿠키·CSRF·프록시 구조와 충돌하지 않는다.

## 무료 한도 영향

- Render Free: 인스턴스 1개 상시 750시간/월 안. 폴링은 로그인·보이는 탭에서만, 30초 간격.
- Neon Free: 저장 0.5GB. 게시글 본문 5000자·댓글 1000자 제한, 알림은 대상이 지워지면 함께 지워진다. 오래된 읽은 알림 정리는 필요하면 후속.
- 새 유료 리소스 없음.

## 최종 정리 (6단계, 2026-11-24)

### 적용한 기본 정책 (구현·테스트로 확인)

| 영역 | 정책 | 근거 테스트 |
|---|---|---|
| 권한 근거 | 서버가 판단하고 화면은 응답의 `canWrite`·`canEdit`·`canDelete`·`canJoin` 등을 따른다. 요청 본문의 역할·대상 값은 권한에 쓰지 않는다 | `ClubBoardApiTests` 위조 거부 |
| 회원 게시판·공지 | 회원 게시판은 로그인 회원(데모 읽기만), 공지는 비회원도 읽고 운영자만 쓴다. 수정은 작성자(공지는 운영자 공동), 삭제는 작성자·운영자 | `BoardPostApiTests` |
| 본문 | 원문 텍스트 저장, 화면은 텍스트 노드 + 줄바꿈만(HTML 해석 없음) | `board.spec.ts` |
| 댓글 | 한 단계 답글(답글의 답글 400, DB 트리거도 막음), 글·부모 삭제 시 답글·추천·멘션·알림 물리 삭제 | `CommentApiTests`·`CommentConcurrencyTests` |
| 추천 | 계정당 대상별 한 번(PK), `PUT`·`DELETE` 멱등 | 동시 추천 20회 |
| 공개 닉네임 (결정 1 A) | 로그인 아이디와 별개, 중복 허용, 멘션은 계정 id 연결 | `CommentApiTests` |
| 데모 (결정 2 A) | 읽기만(게시판·공지·승인 모임 소개), 쓰기 403 | 각 단계 비밀번호 없는 공개 점검 |
| 탈퇴 회원 글 (결정 3 A) | 글·댓글은 남고 작성자는 '탈퇴한 회원' | `community-flow.spec.ts` |
| 알림 | 승격·멘션·모임 결과를 원인 변경과 같은 트랜잭션에, `event_key` 유니크로 중복 없음, 자기 멘션 제외, 수정 시 새 멘션만, 열기 때 권한 재확인, 대상 삭제·탈퇴 시 정리 | `NotificationApiTests`·`PromotionRollbackTests` |
| 알림 폴링 (결정 5 A) | 보이는 탭에서만 30초, 탭 복귀·재연결 즉시, 겹친 요청 없음 | `notifications.spec.ts` |
| 모임 신청 | 대기·승인·반려(사유)·취소, 반려·취소 뒤 고치면 재신청, 승인 모임 수정은 재승인 없이 즉시 반영 | `ClubApiTests` |
| 승인 vs 수정 경합 | `version` 낙관적 잠금, 낡은 version 의 결정은 409 | `ClubConcurrencyTests` |
| 가입 | 즉시 가입, PK 로 중복 없음 | 동시 가입 20회 |
| 모임 역할 | 생성자만 임명·회수·이전, 생성자 운영자 권한은 회수 불가, 매 요청 가입·역할 재확인, 잠금 순서 모임 → 글 → 댓글 | `ClubBoardApiTests`·`ClubBoardConcurrencyTests`·`ClubPolicyReviewTests` |
| 모임 내부 | 회원만 조회·작성, 모임 공지는 모임 운영자, 서비스 운영자는 조회·삭제만, 모임 안 멘션은 회원만 | `ClubBoardApiTests` |
| 생성자 탈퇴 | 모임 탈퇴는 생성자 이전 뒤(이전 생성자는 모임 운영자로 남음). 계정 탈퇴는 만든 모임 삭제(결정 4 B), 한 트랜잭션 | `ClubApiTests`·`ClubPolicyReviewTests` |
| 삭제 | 모임 삭제는 글·댓글·추천·멘션·알림까지 FK CASCADE 한 문장, 중간 실패 시 전체 롤백 | `ClubPolicyReviewTests` 롤백 |
| 게시글 추천 (결정 6) | 넣지 않음(댓글·답글 추천만) | — |
| 화면 | 공통 스타일 재사용, 상태는 색+글자, `role=status` 안내, 180ms 전환·동작 줄이기, 불러오는 중·빈 목록·권한 없음(다시 시도 없음)·삭제됨·오류 뒤 다시 시도, 375px 가로 넘침 없음, 키보드 이동 | `community-flow.spec.ts` |

### 남은 미검증·한계

- **장애 재현:** 삭제 중 실패는 DB 트리거 한 가지로만 재현했다. 네트워크 끊김·서버 중단 중 실패는 같은 트랜잭션 원리에 기대며 따로 재현하지 않았다.
- **공개 환경 동시성:** 경합(이중 추천·가입·승인·이전, 삭제 vs 작성)은 로컬 실제 PostgreSQL 에서 각 20회 확인했다. 공개 환경에서는 동시 가입 2번·동시 추천 2번만 확인했다.
- **화면 공개 확인:** 공개 환경 점검은 API 흐름이다. 화면(375px·키보드·동작 줄이기·화면 상태)은 로컬 프로덕션 빌드 e2e 로 확인했고, 공개 환경에서는 화면 주소 응답만 본다.
- **스크린 리더:** `role=status`·`aria-current`·`aria-pressed`·버튼 이름은 e2e 로 확인했지만 실제 스크린 리더(VoiceOver 등)로 읽어 보지는 않았다.
- **알림 보관:** 목록은 최신 50건(쪽 나눔 없음), 읽은 알림 보관 기간 정리 없음(대상 삭제 시에만 지워짐).
- **결정 4 다른 선택지:** 계정 탈퇴 시 모임을 다른 운영자에게 자동 이전(A)은 구현하지 않았다(지금은 B: 삭제).
- **콜드 스타트:** Render Free 가 절전하면 첫 요청이 수십 초 걸린다(점검 스크립트는 출력 없이 기다린다).

