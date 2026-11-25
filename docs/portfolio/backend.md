# 다음자리 — 백엔드 포트폴리오

> 정원이 있는 스터디 회차의 **예약·취소 대기**에서, 동시에 몰리는 요청에도 정원을 넘기지 않고 대기 순서대로 확정되게 만든 서비스.
> 공개 데모: https://waiting-mauve.vercel.app (가입 없이 `데모 체험하기`) · 시연: [/tour](https://waiting-mauve.vercel.app/tour)

> **커뮤니티 확장(2026-11-23~25):** 회원 게시판·공지, 댓글·한 단계 답글·추천·멘션, 알림함, 모임 신청·승인·가입, 모임 역할·내부 게시판을 같은 서비스에 더했다. 자세한 문제 해결은 아래 "커뮤니티 확장" 절.

## 지원서용 요약

**프로젝트 소개.** 다음자리는 정원이 있는 스터디 회차의 예약·취소 대기 서비스입니다. 백엔드에서는 마지막 자리에 요청이 몰려도 정원을 넘기지 않고, 취소나 정원 증설로 생긴 자리를 먼저 대기한 사람부터 확정하도록 잠금·트랜잭션·DB 제약을 설계했습니다. 공개 데모 https://waiting-mauve.vercel.app (가입 없이 `데모 체험하기`) · 시연 [/tour](https://waiting-mauve.vercel.app/tour) (34초 영상 + 단계별 화면, 백엔드 없이 열림)

**핵심 구현·검증 결과**

1. **회차 행 잠금(`SELECT … FOR UPDATE`)**: 정원 1 회차에 동시 예약 10~400건을 보낸 모든 단계에서 확정 정확히 1건, 나머지 `409 SESSION_FULL`, 예상 못한 오류 0 (로컬 M4 Pro·PostgreSQL 16.16 docker). 근거: [`ReservationConcurrencyTests`](../../backend/src/test/java/com/daumjari/ReservationConcurrencyTests.java), [결과](../../loadtest/results/2026-11-21-local.md)
2. **취소·승격·정원 증설의 원자성**: 같은 트랜잭션·같은 회차 잠금에서 첫 대기자를 확정한다. 대기자 400명 상태의 혼합 요청(C2, 203건)에서 확정 = 대기 순번 1~6, 636 req/s, p95 288ms (로컬). 승격 실패 주입 시 취소까지 롤백. 근거: [`PromotionRollbackTests`](../../backend/src/test/java/com/daumjari/PromotionRollbackTests.java), [결과](../../loadtest/results/2026-11-21-local-capacity.md)
3. **로그인 제한·프록시 신뢰**: 시도를 비밀번호 확인 전에 PostgreSQL upsert 로 원자적으로 세어, 동시 실패 10건 중 정확히 5건만 인증까지 간다. 프록시 공유 토큰이 맞을 때만 전달된 클라이언트 IP 를 믿어 위조 `X-Forwarded-For` 로 한도를 피할 수 없다. 근거: [`LoginThrottleConcurrencyTests`](../../backend/src/test/java/com/daumjari/LoginThrottleConcurrencyTests.java), [`ProxyTokenTests`](../../backend/src/test/java/com/daumjari/ProxyTokenTests.java)

4. **커뮤니티 권한·정합성 (확장)**: 모임 역할을 요청마다 DB 에서 다시 읽고 모임 → 글 → 댓글 순서로 잠가, 권한 회수·모임 탈퇴가 기존 세션에도 즉시 반영되고 이전·삭제·작성 경합에서 생성자 1명·고아 0(각 20회)을 지킨다. 알림은 원인 변경과 같은 트랜잭션·사건 키 유니크. 근거: [`ClubBoardConcurrencyTests`](../../backend/src/test/java/com/daumjari/ClubBoardConcurrencyTests.java), [`ClubPolicyReviewTests`](../../backend/src/test/java/com/daumjari/ClubPolicyReviewTests.java), [공개 점검](../../loadtest/results/2026-11-24-public-community-flow.txt)

아래에서 **[직접 작성]** 표시는 저장소 기록만으로 확인할 수 없어 비워 둔 항목이다.

- 개발 기간: [직접 작성: 예) 2026.11 ~ 2026.11, 주 n시간] (참고, 저장소 커밋 기준: **최초 개발 기간 2026.11.15~11.21**, **개편 기간 2026.11.22** 12:02~18:10 KST — 커밋 `64a0d601`(V8 생성자 권한) ~ `a7a26c29`(공개 점검 보강), PR #30~#35), **커뮤니티 확장 기간 2026.11.23 20:14 ~ 11.25 06:02 KST** — 첫 커밋 `e5dceee6`(커뮤니티·알림·모임 계획) ~ PR #48 병합 `78c98f46`, PR #39~#48
- 개인 담당 범위·팀 구성: 개인 프로젝트
- AI 협업 방식: 기획·문서는 Codex, 개발·테스트는 Claude가 맡고, 요구사항 결정·PR 병합·로그인이 필요한 공개 검증 실행은 본인이 했다
- 지원 직무·회사에 맞춰 강조할 점: [직접 작성]

## 서비스 소개

- **역할:** 참가자는 회차를 예약하고, 마감된 회차에는 대기를 건다. 확정자가 취소하거나 운영자가 정원을 늘리면 **먼저 대기한 사람부터** 자동 확정된다.
- **구성:** Spring Boot 4.1(Java 17) + PostgreSQL 16(Flyway), 서버 세션 인증(Spring Session JDBC).
- **배포:** Next.js 프런트의 `/api` 프록시 뒤에 둔다.

## 주요 구현

### 1. 회차 행 잠금으로 마지막 자리 직렬화

- **문제:** "잔여석 확인 → 저장" 사이에 다른 요청이 끼면 정원을 넘긴다.
- **방식:**
  - 예약·대기·취소·정원 변경이 모두 회차 행을 `SELECT … FOR UPDATE` 로 먼저 잠근다.
  - 같은 트랜잭션에서 확정 수·대기 수를 세고 상태를 바꾼다.
  - 같은 회차의 요청만 줄을 서고, 다른 회차끼리는 서로 막지 않는다.
  - 잠금 순서는 항상 회차 → 신청이라 교착을 만들지 않는다.
- **확인:**
  - [`ReservationConcurrencyTests`](../../backend/src/test/java/com/daumjari/ReservationConcurrencyTests.java): 실제 포트·PostgreSQL 에서 두 계정이 마지막 1석에 동시에 예약하는 경합을 30회 반복해 매번 정확히 1명 확정.
  - 로컬 부하 시나리오 A: 정원 1 회차에 동시 10~400건 → 모든 단계 확정 1건, 나머지 `409 SESSION_FULL`, 예상 못한 오류 0. [결과](../../loadtest/results/2026-11-21-local.md)

### 2. 취소·승격의 원자성

- **문제:** 취소와 승격 사이에 신규 예약이 끼면 대기자가 밀린다. 승격만 실패하면 좌석이 사라진다.
- **방식:**
  - 취소와 "가장 먼저 대기한 사람(id 순) 확정"을 같은 트랜잭션·같은 회차 잠금 아래에서 처리한다.
  - `WaitlistPromoter.promoteNext` 는 `Propagation.MANDATORY` 라 호출한 트랜잭션 밖에서는 아예 돌지 않는다.
  - 대기자가 있으면 빈자리가 보여도 신규 예약은 `409` 로 막아 대기자 몫으로 남긴다.
  - 정원 증설도 같은 승격 객체로, 늘어난 자리만큼 순서대로 확정한다.
- **확인:**
  - [`PromotionRollbackTests`](../../backend/src/test/java/com/daumjari/PromotionRollbackTests.java): 승격 직후 실패를 주입하면 취소까지 롤백된다.
  - [`WaitlistConcurrencyTests`](../../backend/src/test/java/com/daumjari/WaitlistConcurrencyTests.java), [`CapacityConcurrencyTests`](../../backend/src/test/java/com/daumjari/CapacityConcurrencyTests.java): 취소·증설과 신규 예약 경합을 각 30회 반복.
  - 로컬 부하 B·C에서 승격된 사람 = 대기 순번 1~k 를 모든 단계에서 확인:
    - B: 확정자 5명 취소와 신규 요청 혼합. [결과](../../loadtest/results/2026-11-21-local.md)
    - C: 정원 1→6 증설·축소·취소·신규 요청 혼합, 처리 순서 표 포함. [결과](../../loadtest/results/2026-11-21-local-capacity.md)

### 3. DB 제약을 마지막 방어선으로

- **방식:**
  - 활성 신청(취소 아님)은 부분 unique 인덱스 `ux_reservation_active (study_session_id, account_id) where status <> 'CANCELLED'` 로 한 건만 허용.
  - `check` 제약: 상태 값, 취소 시각 일관성, 정원 > 0.
  - 아이디는 `login_id unique`. 동시 가입의 제약 위반도 `409 LOGIN_ID_TAKEN` 으로 바꾼다.
- **확인:**
  - [`SchemaConstraintTests`](../../backend/src/test/java/com/daumjari/SchemaConstraintTests.java)
  - [`SignupConcurrencyTests`](../../backend/src/test/java/com/daumjari/SignupConcurrencyTests.java): 같은 아이디 8건 동시 → 201 하나, 나머지 409.
  - 부하 뒤 SQL 검사 [`checks.sql`](../../loadtest/checks.sql) 4종(정원 초과·활성 중복·대기 추월·승격 누락) 0행. 검사식이 일부러 만든 위반을 잡는지는 셀프테스트로 먼저 확인했다.

### 4. 로그인 시도 제한

- **문제:** 비밀번호 대입은 막되, 남의 아이디로 일부러 틀려 그 사람을 잠그게 하면 안 된다.
- **방식:**
  - (정규화한 아이디 + 클라이언트 IP) 15분 5회, IP 15분 50회.
  - 비밀번호 확인 **전에** PostgreSQL `INSERT … ON CONFLICT DO UPDATE … RETURNING` 으로 원자적으로 센다. 키는 SHA-256.
  - 탈퇴 비밀번호 재확인도 같은 한도에 센다.
  - 가입(15분 5회)·데모 시작(15분 10회)은 IP 한도.
- **확인:**
  - [`LoginThrottleConcurrencyTests`](../../backend/src/test/java/com/daumjari/LoginThrottleConcurrencyTests.java): 실패 요청 10건 동시 → 정확히 5건 401, 5건 429 (10회 반복).
  - [`LoginThrottleApiTests`](../../backend/src/test/java/com/daumjari/LoginThrottleApiTests.java)

### 5. 프록시 뒤의 클라이언트 IP 신뢰

- **문제:** 프록시 뒤에서는 모든 요청이 프록시 IP 로 보여 IP 한도가 무의미하다. 클라이언트가 `X-Forwarded-For` 를 위조할 수도 있다.
- **방식:**
  - 프런트 프록시가 클라이언트가 보낸 전달 헤더를 버리고 실제 접속 IP 를 `X-Daumjari-Client-IP` 로 보낸다.
  - 함께 공유 비밀 `X-Daumjari-Proxy-Token` 을 붙인다.
  - 백엔드(`ProxyTokenFilter`)는 토큰이 맞을 때만 그 IP 를 믿고, 토큰 없는 직접 호출은 `403 PROXY_REQUIRED`.
  - 로컬은 Tomcat RemoteIpValve 가 loopback 프록시만 신뢰한다.
- **확인:**
  - [`ProxyTokenTests`](../../backend/src/test/java/com/daumjari/ProxyTokenTests.java), [`ForwardedHeaderTrustTests`](../../backend/src/test/java/com/daumjari/ForwardedHeaderTrustTests.java): 위조 XFF 로 한도 키를 바꿀 수 없음.
  - 공개 배포 실측(2026-11-20): Mac IP 로 데모 시작 한도(15분 10회)를 채워 11번째가 `429` + `Retry-After`. 같은 시각 휴대폰 모바일 데이터에서는 정상 시작.

### 6. 회원탈퇴의 일관성

- **방식:**
  - 계정 행 `FOR UPDATE` → 활성 신청이 있는 회차를 id 순으로 잠그고 정리(시작 전 확정은 취소 + 승격, 대기는 취소) → 익명화.
  - 예약·대기는 계정 행을 `FOR SHARE` 로 먼저 잠가 탈퇴와 직렬화한다 (잠금 순서 계정 → 회차).
  - 남은 다른 기기 세션은 요청마다 탈퇴 여부를 보는 필터가 끊는다.
- **확인:**
  - [`WithdrawalConcurrencyTests`](../../backend/src/test/java/com/daumjari/WithdrawalConcurrencyTests.java): 탈퇴와 예약 동시 20회 → 탈퇴 계정의 활성 신청 0.
  - [`WithdrawalRollbackTests`](../../backend/src/test/java/com/daumjari/WithdrawalRollbackTests.java)
  - [`WithdrawalApiTests`](../../backend/src/test/java/com/daumjari/WithdrawalApiTests.java)

### 7. 권한·물리 삭제·운영 조치·누적 집계 (2026-11-22, 1~3단계)

- **생성자 권한:** 생성자는 요청 본문이 아니라 로그인 세션에서 정하고, 수정·정원·명단·삭제는 회차 행을 잠근 뒤 서버가 확인한다. 기존 회차는 마이그레이션(V8)으로 가장 먼저 만든 운영자에게 연결했다.
- **물리 삭제:** 회차 행 `FOR UPDATE` 뒤 한 문장으로 지우고 신청은 FK `ON DELETE CASCADE` 로 같은 트랜잭션에서 지운다. 삭제와 예약·대기·정원 증설을 각각 20회 경합시켜 고아 신청 0.
- **운영 조치:** 이용 정지·해제·강제탈퇴는 활성 운영자 행(id 순) → 대상 계정 → 회차(id 순) 순서로 잠근다. 운영자 둘이 서로를 동시에 정지해도 하나만 성공해 운영자가 0명이 되지 않고(20회), 강제탈퇴와 대상의 예약 경합에서도 활성 신청이 남지 않는다(20회). 정지·탈퇴는 요청마다 계정 상태를 보는 필터가 기존 세션까지 끊는다. 조치 기록은 계정 id 만 남겨 탈퇴하면 익명이 된다.
- **누적 집계:** 성공한 동작만 같은 트랜잭션에서 `usage_daily(day, metric, demo, count)` 에 더해, 롤백·중복 요청에는 늘지 않고 원본을 지워도 줄지 않는다. 현재 데이터(지금 남은 행)와 나눠 보이고 집계 시작 시각을 표시해, 확인할 수 없는 과거 누적치는 만들지 않는다.
- **찾아낸 버그:** 회차를 만든 회원의 탈퇴에서 네이티브 삭제(`clearAutomatically`)가 영속성 컨텍스트를 비워 계정 익명화가 저장되지 않았다. 회귀 테스트로 재현한 뒤 정리 후 계정을 다시 읽어 익명화하도록 고쳤다.

### 8. 커뮤니티 확장 (2026-11-23~25): 권한·경합·알림

#### 사례 1. 권한 설계: 다섯 종류 사용자와 "지금" 권한

- **문제:** 커뮤니티가 붙으면서 권한 주체가 서비스 운영자·모임 생성자·모임 운영자·모임 회원·데모 사용자(그리고 비회원)로 늘었다. 화면이 버튼을 숨기는 것만으로는 직접 API 호출을 못 막고, 권한을 회수하거나 모임을 탈퇴한 사람이 이미 로그인한 세션으로 계속 쓰면 안 된다.
- **방식:** 모든 판단을 서버에 두고 응답에 `canWrite`·`canEdit`·`canDelete`·`canJoin` 을 실어 화면이 따른다. 모임 역할은 세션에 저장하지 않고 요청마다 `club_member` 에서 다시 읽는다([`ClubRoles`](../../backend/src/main/java/com/daumjari/club/ClubRoles.java)). 쓰기·수정·삭제는 모임 행·회원 행을 `FOR SHARE` 로 잠근 뒤 판단해, 임명·회수·이전·삭제(모임 행 `FOR UPDATE`)와 줄을 선다([`BoardService.lockClubOf`](../../backend/src/main/java/com/daumjari/board/BoardService.java)). 역할·대상은 요청 본문이 아니라 세션 계정과 DB 의 현재 값으로만 정한다. 서비스 운영자는 모임 안에서 조회·삭제만, 데모는 읽기만.
- **확인:** [`ClubBoardApiTests.onlyTheCreatorAppointsAndRevokesAndForgedRequestsAreRefused`](../../backend/src/test/java/com/daumjari/ClubBoardApiTests.java)(본문에 생성자 id 를 넣은 위조 거부), [`ClubPolicyReviewTests.revokedManagerIsRefusedNoticeWriteEditAndDeleteInTheSameSession`](../../backend/src/test/java/com/daumjari/ClubPolicyReviewTests.java)·[`leavingBlocksBoardsNoticesMembersAndEveryDirectApiAtOnce`](../../backend/src/test/java/com/daumjari/ClubPolicyReviewTests.java)(다시 로그인하지 않은 세션에서 바로 403), [`ClubBoardConcurrencyTests.revokingAManagerWhileItDeletesIsAllOrNothing`](../../backend/src/test/java/com/daumjari/ClubBoardConcurrencyTests.java)(회수와 그 운영자의 삭제 경합 20회). 공개 환경: [5단계 로그인 점검](../../loadtest/results/2026-11-24-public-community5.txt)(회수 뒤 같은 세션 작성·수정·삭제 403, 밖 회원 403, 서비스 운영자 쓰기 403).

#### 사례 2. 동시성과 삭제 정합성: 이전·삭제·작성이 겹칠 때

- **문제:** 생성자 이전 두 건, 모임 삭제와 내부 글·댓글 작성, 글 삭제와 댓글·추천이 동시에 오면 생성자가 둘이 되거나 지워진 모임의 글·댓글·알림이 고아로 남을 수 있다. 삭제 도중 실패하면 일부만 지워질 수도 있다.
- **방식:** 잠금 순서를 모임 → 글 → 댓글로 통일했다(모임 삭제의 CASCADE 순서와 같아 교착이 없다). 연관 데이터는 FK `ON DELETE CASCADE` 로 한 문장·한 트랜잭션에서 물리 삭제한다([V12](../../backend/src/main/resources/db/migration/V12__comments_likes_mentions.sql)·[V13](../../backend/src/main/resources/db/migration/V13__notification.sql)·[V14](../../backend/src/main/resources/db/migration/V14__club.sql)·[V15](../../backend/src/main/resources/db/migration/V15__club_board.sql)). 경합 테스트에서 찾은 버그: 계정과 조인한 쿼리에 `FOR UPDATE` 를 걸면 앞선 이전이 생성자를 바꾼 뒤 재확인에서 행이 사라진 것처럼 보여(404) 모임 행만 단독으로 잠근 뒤 읽게 고쳤다([`ClubService.lock`](../../backend/src/main/java/com/daumjari/club/ClubService.java)).
- **확인:** [`ClubBoardConcurrencyTests`](../../backend/src/test/java/com/daumjari/ClubBoardConcurrencyTests.java) `twoTransfersRacingLeaveExactlyOneCreator`(200·403 하나씩, 이전 생성자는 모임 운영자로 남음)·`deletingAClubWhileWritingInsideLeavesNoPosts`·`deletingAClubWhileCommentingLeavesNoOrphans`, [`CommentConcurrencyTests.deletingAParentWhileReplyingOrLikingLeavesNoOrphans`](../../backend/src/test/java/com/daumjari/CommentConcurrencyTests.java)(각 20회, 실제 PostgreSQL), [`ClubPolicyReviewTests.aFailureWhileDeletingTheClubRollsBackTheClubAndEverythingInIt`](../../backend/src/test/java/com/daumjari/ClubPolicyReviewTests.java)(테스트에서 댓글 삭제를 실패시키는 트리거를 걸면 모임·회원·글·댓글·알림 행 수가 그대로). 공개 환경: 삭제 뒤 API 404 와 별도로 Neon SQL 로 행 0건 확인([전체 흐름](../../loadtest/results/2026-11-24-public-community-flow.txt)).

#### 사례 3. 알림과 사용자 경험: 같은 트랜잭션, 한 번만, 지금 볼 수 있을 때만

- **문제:** 예약 승격·멘션·모임 승인/반려 알림이 원인 변경과 어긋나면(승격은 롤백됐는데 알림만 남음, 재시도로 두 번) 신뢰를 잃는다. 알림을 누른 순간 대상이 지워졌거나 권한이 바뀌었을 수 있다.
- **방식:** 알림은 원인 변경과 같은 트랜잭션에서만 쓴다([`Notifier`](../../backend/src/main/java/com/daumjari/notification/Notifier.java), `Propagation.MANDATORY`). 사건 열쇠 `event_key` 유니크 + `on conflict do nothing` 으로 반복·재시도에도 한 번. 자기 멘션은 제외하고 수정 때는 새로 추가된 사람만. 열기(`POST /api/me/notifications/{id}/open`)는 그 순간 대상 조회 권한을 다시 확인한 뒤 주소를 준다([`NotificationService.open`](../../backend/src/main/java/com/daumjari/notification/NotificationService.java)). 대상이 지워지면 알림도 CASCADE 로 사라진다. 화면은 읽지 않은 수를 버튼 이름에 글자로 넣고, 보이는 탭에서만 30초·탭 복귀 즉시 갱신하며 요청을 겹치지 않는다([`NotificationBell`](../../frontend/src/components/NotificationBell.tsx)).
- **확인:** [`PromotionRollbackTests.failureAfterPromotingRollsBackTheCancellationToo`](../../backend/src/test/java/com/daumjari/PromotionRollbackTests.java)(롤백 시 알림 0·성공 시 1), [`NotificationApiTests`](../../backend/src/test/java/com/daumjari/NotificationApiTests.java) `editingKeepsExistingMentionsAndNotifiesOnlyNewlyAddedPeople`·`commentAndReplyMentionsNotifyAndDeletingTheTargetRemovesTheNotification`, [`ClubPolicyReviewTests.notificationLinksRecheckCurrentAccessAfterRevokeAndDeletion`](../../backend/src/test/java/com/daumjari/ClubPolicyReviewTests.java), e2e [`notifications.spec.ts`](../../frontend/e2e/notifications.spec.ts)·[`community-flow.spec.ts`](../../frontend/e2e/community-flow.spec.ts). 공개 환경: [3단계 로그인 점검](../../loadtest/results/2026-11-24-public-community3.txt)(자기 멘션 제외, 취소 두 번에도 승격 알림 1건, 글 삭제 뒤 알림 0건).

## 기술 선택의 이유

| 선택 | 이유 | 대안과 비교 |
|---|---|---|
| 비관적 잠금(`FOR UPDATE`) | 마감 직전 경합이 핵심이라 충돌이 잦다. 재시도 없이 한 번에 정답을 낸다 | 낙관적 잠금은 충돌 시 재시도·실패 응답이 늘고, 분산 락(Redis)은 인프라가 하나 더 필요하다 |
| 승격을 같은 트랜잭션에 | "취소됐는데 승격 안 됨" 상태가 생기지 않는다 | 비동기 이벤트 승격은 순서·중복 처리를 따로 보장해야 한다 |
| DB 제약 병행 | 애플리케이션 버그가 있어도 활성 중복은 DB 가 막는다 | 애플리케이션 검사만으로는 동시성 버그를 못 막는다 |
| 서버 세션 + 쿠키 CSRF | 같은 출처 프록시라 쿠키가 자연스럽고, 세션 무효화(탈퇴·로그아웃)가 즉시 된다 | JWT 는 탈퇴 즉시 차단에 블랙리스트가 필요하다 |
| PostgreSQL upsert 로 시도 집계 | 별도 저장소 없이 원자적 카운트 | Redis 는 무료 배포 구성에 서비스가 하나 늘어난다 |

## 검증 결과 요약

| 항목 | 결과 | 환경 · 근거 |
|---|---|---|
| 자동 테스트 | 백엔드 306개 통과, 실패·건너뜀 0 | JUnit + Testcontainers PostgreSQL. 직전 실행 결과: 2026-11-21 약 10:50 UTC, 로컬 Mac, 커밋 `548023aa` 기준. 그 뒤 커밋(`cfbf1830` 시연 영상 mp4, 문서)은 다시 돌리지 않음 |
| 마지막 자리 (A) | 정원 1 회차에 동시 예약 400건: 확정 1, 409 ×399, 예상 못한 오류 0, 870 req/s, p95 429ms | 로컬(Apple M4 Pro, PostgreSQL 16.16 docker, 부하 발생기·서버·DB 같은 기기) · [결과](../../loadtest/results/2026-11-21-local.md) |
| 취소·승격 (B) | 대기자 400명 상태의 혼합 요청(B2, 205건: 확정자 취소 5 + 신규 예약 100 + 신규 대기 100): 승격 = 대기 순번 1~5, 639 req/s, p95 303ms | 로컬(Apple M4 Pro, PostgreSQL 16.16 docker, 부하 발생기·서버·DB 같은 기기) · [결과](../../loadtest/results/2026-11-21-local.md) |
| 정원 증설 (C) | 대기자 400명 상태의 혼합 요청(C2, 203건: 증설·축소·취소 각 1 + 신규 예약 100 + 신규 대기 100): 최종 정원 6, 승격 = 순번 1~6, 636 req/s, p95 288ms, 축소 요청은 처리 순서에 따라 200/409 | 로컬(Apple M4 Pro, PostgreSQL 16.16 docker, 부하 발생기·서버·DB 같은 기기) · [결과](../../loadtest/results/2026-11-21-local-capacity.md) |
| SQL 정합성 | 4종 0행 (A·B 실행 뒤, C 실행 뒤 각각) | 로컬(Apple M4 Pro, PostgreSQL 16.16 docker, 부하 발생기·서버·DB 같은 기기) · [`checks.sql`](../../loadtest/checks.sql) |

로컬 환경: Apple M4 Pro(12코어, 24GB), macOS 27.2, JDK 17.0.13, Spring Boot 4.1.2, Hikari 풀 10, PostgreSQL 16.16(docker), Node 23.7 부하 발생기. 부하 발생기·서버·DB 가 같은 기기라 절대 수치보다 "단계를 올려도 정합성이 유지되는지"가 요점이다.

## 개편 검증 결과 (2026-11-22, 로컬·공개·미검증 구분)

| 구분 | 결과 | 실행 커밋 · 환경 |
|---|---|---|
| 로컬 · 백엔드 전체 | 439개 통과, 실패 0 (개편으로 추가: 검색 9, 계정 관리 5, 경합 40, 대시보드 3 등) | `504bbc2f` · Apple M4 Pro macOS 27.2, JDK 17.0.13(Zulu), Testcontainers PostgreSQL 16 |
| 로컬 · e2e 전체 | 79개 통과 | `504bbc2f` · Playwright 1.64.1 Chromium(headless) + Spring Boot `bootRun`(local 프로필) + `next start`, docker compose PostgreSQL 16 |
| 로컬 · e2e 일부 재실행 | 디자인·전체 흐름 9개 통과 | `6f7d938b`(터치 hover CSS 수정) · 같은 환경 |
| 로컬 · 프런트 단위 | 12개 통과, `tsc`·`eslint` 오류 0 | `504bbc2f` · Node 23.7.0 `node --test` |
| 로컬 · 공개 점검 리허설 | 1단계·3단계 스크립트 모두 통과, 리허설 DB 의 삭제 회차·신청 0행, V10 이력 `10 / account moderation / true` | `a7a26c29` · 로컬 운영 이미지(`deploy/docker-compose.prod.yml`, prod 프로필·JDBC 세션·프록시 토큰), [기록](../../loadtest/results/2026-11-22-rehearsal-stage1-stage3.txt) |
| 공개 · 비밀번호 없는 4단계 점검 | 23/23 성공 (검색 정렬 7종 200, 비로그인 운영자 API 401, 메뉴 현재 위치·카드 클릭 영역·확정 강조와 안내·동작 줄이기, 데스크톱·375px) | 프런트 `5942cf15`·백엔드 `a9d77d4c` 배포본, 2026-11-22 08:54 UTC, 데모 체험 계정만 · [기록](../../loadtest/results/2026-11-22-public-stage4.txt) |
| 공개 · 1단계 점검 (일반 회원 3명) | 통과: 회차 생성·수정·내가 만든 회차, 다른 회원의 관리 API 4종 403, 정원 증설 승격, 삭제 뒤 신청 행 0건(물리 삭제)·상세 404, 정리 완료 | 사용자 실행 2026-11-22 09:38 UTC, 백엔드 `a9d77d4c` 배포본 · [기록](../../loadtest/results/2026-11-22-public-stage1.txt) |
| 공개 · 3단계 점검 (운영자) | 통과: 운영자가 다른 회원 회차 삭제(신청 행 0건), 대시보드 현재 회차·확정 -1·누적 유지, 정지(기존 세션 401·재로그인 403)·해제·강제탈퇴, 조치 기록 익명 표시, 운영자 화면 3개와 API 일치, 정리 완료 | 사용자 실행 2026-11-22 09:54 UTC, 백엔드 `a9d77d4c` 배포본 · [기록](../../loadtest/results/2026-11-22-public-stage3.txt) |
| 공개 · DB 확인 | 3단계에서 지운 회차 61~63 의 `study_session`·`reservation` 행 0건(물리 삭제, DB 직접 확인. 1단계 회차 60 은 API 로만 확인), V10 이력 `10 / account moderation / true` (installed 08:28 UTC, Render 배포 시간과 일치) | 사용자 Neon SQL 조회 2026-11-22 09:56 UTC · [기록](../../loadtest/results/2026-11-22-neon-checks.txt) |
| 미검증 · 후속 | 실제 iPhone Safari(Chromium 기기 에뮬레이션만), 공개 환경의 대기 순번 앞당김 강조(데모로 재현 불가, 로컬 e2e 만) · 예약 변경 이력 화면은 후속 과제 | 실기기 없음 · 일반 계정 셋 필요 |


## 커뮤니티 확장 검증 결과 (2026-11-23~25, 로컬·공개 API·Neon SQL·미검증 구분)

| 구분 | 결과 | 실행 커밋 · 환경 · 근거 |
|---|---|---|
| 로컬 · 백엔드 전체 | 705개 통과, 실패·건너뜀 0 (커뮤니티 확장 테스트 포함) | `78c98f46`(PR #48 병합) · 2026-11-25 10:48 UTC 실행 · Apple M4 Pro macOS 27.2, JDK 17.0.13(Zulu), Testcontainers PostgreSQL 16 |
| 로컬 · e2e 전체 | 102개 통과 (커뮤니티 `community-flow.spec.ts` 3·`/tour` 모임 시연 1 포함) | `78c98f46` + 이 문서의 /tour 변경 · 2026-11-25 10:59 UTC · Playwright 1.64.1 Chromium(headless) + Spring Boot `bootRun`(local) + `next start`, docker PostgreSQL 16 |
| 로컬 · 프런트 단위 | 29개 통과, `tsc`·`eslint` 오류 0 | 같은 날 · Node 23.7.0 `node --test` |
| 로컬 · 경합 (실제 PostgreSQL, 각 20회) | 이중 추천 → 행 1, 이중 가입 → 행 1, 이중 승인 → 결정·알림·운영자 행 1, 승인 vs 수정 → 본 적 없는 내용 승인 0, 이전 두 건 → 생성자 1, 삭제 vs 글·댓글·답글·추천·가입 → 고아 0 | `CommentConcurrencyTests`·`ClubConcurrencyTests`·`ClubBoardConcurrencyTests` |
| 공개 · API 확인 | 1~5단계 각 단계와 전체 흐름(가입 → 글·멘션 댓글·추천 → 알림 열기 → 모임 신청·승인 → 가입 → 임명·공지 → 회수·거절 → 이전·탈퇴 → 모임 삭제·알림 정리 → 계정 탈퇴) 모두 통과, 정리 API 204 | 사용자 실행, 운영자 비밀번호는 실행 중 숨김 입력 · [전체 흐름](../../loadtest/results/2026-11-24-public-community-flow.txt), [5단계](../../loadtest/results/2026-11-24-public-community5.txt), [4단계](../../loadtest/results/2026-11-24-public-community4.txt), [3단계](../../loadtest/results/2026-11-24-public-community3.txt) |
| 공개 · Neon SQL 확인 (물리 삭제) | 각 점검이 만든 글·댓글·추천·멘션·알림·모임·회원 행 0 (API 의 404 와 별도로 DB 를 직접 셈) | 사용자 Neon SQL 편집기 조회, 대상 id 는 각 기록 파일에 명시 |
| 공개 · 비밀번호 없는 점검 | 1~5단계·전체 흐름 모두 성공 (비회원 401, 데모 읽기 200·쓰기 403, 없는 대상 404) | 데모 체험 계정만 · [전체 흐름](../../loadtest/results/2026-11-24-public-community-flow-open.txt) |
| 미검증 | 실제 iPhone Safari(Chromium 기기 에뮬레이션만) · 실제 스크린 리더(VoiceOver 등) · 공개 환경 경합(동시 가입·추천 2번만, 나머지는 로컬) · 공개 화면 단위 점검(공개는 API 흐름과 화면 주소 응답, 화면은 로컬 프로덕션 빌드 e2e) · 삭제 중 실패는 DB 트리거 한 가지로만 재현 | — |

성능 수치(636 req/s·p95 288ms 등)는 예약·정원 변경의 로컬 혼합 요청 결과이며, 커뮤니티 기능의 성능은 따로 측정하지 않았다.

## 한계와 개선 방향

- **후속 과제:** 예약 변경 이력(누가 언제 확정·대기·취소했는지)은 이번 개편 범위에서 빼고 후속으로 남겼다. 지금 누적 집계는 일부러 횟수만 남겨 이력으로 쓸 수 없다.
- **잠금 범위:** 회차 단위 잠금이라 한 회차에 요청이 아주 몰리면 그 회차 요청은 줄을 선다 (N=400 대기 p95 660~749ms). 인기 회차가 많아지면 대기열을 큐로 분리하는 방안을 검토할 수 있다.
- **부하 측정 환경:** 같은 기기에서 돌렸고, 공개 무료 서버에는 부하를 주지 않았다. 운영 수준 처리량은 측정하지 않았다.
- **시도 제한:** 고정 창이라 경계 앞뒤로 최대 2배까지 몰아 시도할 수 있고, 여러 IP 로 한 계정을 나눠 대는 분산 시도는 (아이디+IP) 키로 막지 못한다.
- **변경 이력:** 상태 변경 이력을 남기지 않아 "직접 확정"과 "늦은 승격"을 사후에 구분할 수 없다. 이력 테이블이나 감사 로그가 다음 단계다.
- **인스턴스:** 단일 인스턴스 배포다. 수평 확장 시에도 DB 잠금이라 정합성은 유지되지만 처리량은 검증하지 않았다.
