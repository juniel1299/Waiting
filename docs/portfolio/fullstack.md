# 다음자리 — 풀스택 포트폴리오

> 가입 없이 핵심 흐름(대기 신청 → 빈자리 발생 → 예약 확정)을 직접 눌러 볼 수 있게, 화면·API·DB·무료 배포를 한 흐름으로 설계한 예약·대기 서비스.
> 공개 데모: https://waiting-mauve.vercel.app (가입 없이 `데모 체험하기`) · 시연: [/tour](https://waiting-mauve.vercel.app/tour) (34초 영상 + 단계별 화면, 서버가 잠들어 있어도 열림)

> **커뮤니티 확장(2026-11-23~25):** 회원 게시판·공지, 댓글·한 단계 답글·추천·멘션, 알림함, 모임 신청·승인·가입, 모임 역할·내부 게시판을 같은 서비스에 더했다. 자세한 문제 해결은 아래 "커뮤니티 확장" 절.

## 지원서용 요약

**프로젝트 소개.** 다음자리는 정원이 찬 스터디 회차에 대기를 걸면 빈자리가 생길 때 순서대로 자동 확정되는 예약 서비스입니다. 가입 없이 핵심 흐름을 직접 눌러 볼 수 있는 방문자별 데모를 실제 예약 로직 위에 만들고, Next.js·Spring Boot·PostgreSQL 을 월 0원 무료 구성으로 배포해 서버 절전까지 화면에서 대응했습니다. 공개 데모 https://waiting-mauve.vercel.app (가입 없이 `데모 체험하기`) · 시연 [/tour](https://waiting-mauve.vercel.app/tour) (34초 영상 + 단계별 화면, 백엔드 없이 열림)

**핵심 구현·검증 결과**

1. **가입 없는 방문자별 데모**: 버튼 한 번에 방문자·샘플 참가자·마감 회차가 생기고, 다른 방문자의 회차는 404 로 숨긴다. 별도 브라우저 두 방문자가 서로 영향을 주지 않음을 e2e 로 확인. 근거: [`demo.spec.ts`](../../frontend/e2e/demo.spec.ts), [`DemoApiTests`](../../backend/src/test/java/com/daumjari/DemoApiTests.java)
2. **인증부터 예약까지**: 서버 세션 + 쿠키 CSRF, 회원가입·탈퇴, 회차 잠금 아래 예약·대기·취소·승격. 공개 배포에서 운영 회차(정원 1)에 A 탈퇴 → B 대기 1번이 4.9초 뒤 새로고침 없이 `예약 확정`(1회). 근거: [공개 배포 검증](../../README.md#공개-배포-검증-2026-11-21)
3. **무료 배포와 절전 대응**: Vercel → Render Free → Neon Free. 프록시 503 + 자동 재시도 + 기다리는 동안 볼 `/tour`. 약 55분 무요청 뒤 클릭부터 체험 화면까지 170.9초(1회, 측정 조건 명시). 근거: [측정 조건과 한계](../../README.md#절전-후-체험-시작-2분-51초-조건과-한계)

4. **커뮤니티 확장을 단계별로 (확장)**: 계획(데이터 모델·권한표·결정 필요 항목) → 6단계 PR, 단계마다 테스트 먼저·로컬 운영 이미지 리허설·공개 점검(비밀번호 없는 점검 + 사용자 실행 로그인 점검 + Neon SQL 0행). Render 배포가 한 번 실패했을 때는 GitHub 배포 기록으로 원인을 좁히고 점검 스크립트가 배포를 먼저 확인하게 고쳤다. 근거: [계획](../plans/community-plan.md), [전체 흐름 점검](../../loadtest/results/2026-11-24-public-community-flow.txt)

**[직접 작성]** 아래는 저장소 기록만으로 확인할 수 없어 비워 둔 항목이다.

- 개발 기간: [직접 작성: 예) 2026.11 ~ 2026.11, 주 n시간] (참고, 저장소 커밋 기준: **최초 개발 기간 2026.11.15~11.21**, **개편 기간 2026.11.22** 12:02~18:10 KST — 커밋 `64a0d601`(V8 생성자 권한) ~ `a7a26c29`(공개 점검 보강), PR #30~#35), **커뮤니티 확장 기간 2026.11.23 20:14 ~ 11.25 06:02 KST** — 첫 커밋 `e5dceee6`(커뮤니티·알림·모임 계획) ~ PR #48 병합 `78c98f46`, PR #39~#48
- 개인 담당 범위·팀 구성: [직접 작성: 예) 1인 개발 / 기획·설계·구현·배포 전부]
- AI 활용 방식: [직접 작성: 예) 어떤 도구로 무엇을 맡겼고, 무엇을 직접 판단·검증했는지]
- 지원 직무·회사에 맞춰 강조할 점: [직접 작성]

## 서비스 소개

- **사용자:** 참가자는 회차를 예약·취소하고, 마감된 회차에는 대기를 건다. 운영자는 회차를 만들고 정원을 바꾸고 확정 명단을 본다.
- **핵심:** 확정자가 취소하거나 정원이 늘면 **먼저 대기한 사람부터** 자동 확정되고, 그 사람의 화면이 새로고침 없이 바뀐다.

```
브라우저 ─▶ Vercel (Next.js 16, sin1) ─ /api Route Handler 프록시 ─▶ Render Free (Spring Boot 4, Singapore) ─▶ Neon Free (PostgreSQL 16)
```

## 주요 구현

### 1. 가입 없는 데모

- **문제:** 포트폴리오 방문자는 가입하지 않는다. 그런데 핵심 흐름을 보려면 "마감된 회차"와 "취소해 줄 다른 참가자"가 필요하다.
- **방식:** `데모 체험하기` 한 번에 체험 하나(`demo_trial`)가 만들어진다.
  - 내용: 방문자 계정, 샘플 참가자 2명, 정원 2 마감 회차, 샘플 확정 예약.
  - 바로 그 방문자로 세션을 연다.
  - `샘플 참가자 취소` 는 지정 샘플 예약을 기존 취소·승격 로직으로 취소한다. 그래서 내 대기가 같은 트랜잭션에서 확정된다.
  - 30분 뒤 정리 작업이 체험을 통째로 지운다.
  - 남용 방지: IP 당 15분 10회.
- **확인:** [`DemoApiTests`](../../backend/src/test/java/com/daumjari/DemoApiTests.java), [`demo.spec.ts`](../../frontend/e2e/demo.spec.ts). 공개 서비스에서 녹화한 [시연 영상](../../frontend/public/tour/).

### 2. 방문자 격리

- **방식:**
  - 체험에 속한 계정·회차는 `demo_trial_id` 로 묶인다.
  - 회차는 같은 체험의 계정에게만 보이고, 아니면 404 로 존재를 숨긴다.
  - 일반 계정은 일반 회차만 본다.
  - 샘플 취소 API 는 파라미터를 받지 않고 세션의 체험에서 대상을 찾아, 다른 체험을 가리킬 수 없다.
  - 데모 계정은 비밀번호 로그인이 막혀 있다.
- **확인:**
  - [`demo.spec.ts`](../../frontend/e2e/demo.spec.ts): 별도 브라우저 두 방문자가 서로의 체험에 영향을 주지 않고, B 가 A 의 회차 주소로 들어가면 "회차를 찾을 수 없습니다".
  - [`DemoApiTests`](../../backend/src/test/java/com/daumjari/DemoApiTests.java)

### 3. 인증부터 예약까지의 흐름

- **인증:**
  - 서버 세션(`JSESSIONID`, HttpOnly·SameSite=Lax·Secure) + 쿠키 이중 제출 CSRF(`XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더).
  - 두 쿠키 모두 프런트 주소의 같은 출처 쿠키다.
  - 운영 환경은 Spring Session JDBC 로 세션을 DB 에 둔다. 근거: [`ProdSessionTests`](../../backend/src/test/java/com/daumjari/ProdSessionTests.java), 로컬 운영 이미지 재시작(4초) 뒤 같은 쿠키로 세션 유지(2026-11-18 실측, [README](../../README.md#로컬에서-운영-이미지-확인-vm-대안과-같은-구성)). Render 절전 뒤 로그인 유지는 따로 측정하지 않았다.
- **가입:** 역할은 서버가 `PARTICIPANT` 로 고정한다. 아이디 규칙·중복(동시 가입 포함 409)·비밀번호 확인을 칸별로 안내한다.
- **예약·대기·취소:**
  - 회차 행 잠금 아래에서 처리한다.
  - 대기자가 있으면 빈자리가 보여도 신규 예약은 막아 대기자 몫으로 둔다.
  - 확정은 5초 폴링(보이는 탭만)과 탭 복귀·재연결 즉시 조회로 화면에 반영된다.
- **탈퇴:** 비밀번호를 다시 확인한 뒤, 시작 전 확정은 취소·승격하고 대기는 취소한다. 그다음 계정을 익명화하고 다른 기기 세션도 끊는다.
- **확인:**
  - 공개 배포 2026-11-21:
    - 가입 → 로그인, 칸별 오류(보내기 전, 요청 0회), 중복 409, 키보드만 가입.
    - 탈퇴 후 다른 세션 401, 같은 아이디 재가입.
    - 운영 회차(정원 1)에서 A 탈퇴 → B 대기 1번이 4.9초 뒤 새로고침 없이 `예약 확정`.
    - [기록](../../README.md#공개-배포-검증-2026-11-21)
  - 자동 테스트: 백엔드 306개·e2e 41개 통과 (직전 실행 결과: 2026-11-21 약 10:50 UTC, 로컬 Mac, 커밋 `548023aa` 기준. 그 뒤 커밋(`cfbf1830` 시연 영상 mp4, 문서)은 다시 돌리지 않음).

### 4. 무료 배포와 절전 대응

- **구성:**
  - Vercel Hobby(프런트·프록시, 함수 리전 sin1), Render Free(백엔드, Singapore, 15분 무요청 시 절전), Neon Free(PostgreSQL). 월 0원.
  - 백엔드는 공유 비밀 토큰이 있는 요청만 받고, 직접 호출은 403.
  - 클라이언트 IP 는 프록시가 실제 접속 주소로 다시 써서 보낸다. 공개 배포에서 IP 별 한도 집계를 확인했다: Mac 은 429, 같은 시각 휴대폰 모바일 데이터는 정상.
- **절전 대응:**
  - 첫 화면은 로그인 확인을 기다리지 않는다.
  - 프록시는 85초에 끊고 `503 + Retry-After` 로 답한다. 브라우저는 안내와 함께 최대 5분 자동 재시도한다.
  - 기다리는 동안 볼 `/tour` 를 새 탭으로 연다.
- **측정 (공개 배포, 1회):**
  - 조건: 2026-11-21 04:20 UTC, 약 55분 무요청 뒤, 새 컨텍스트, 데스크톱, 배포 커밋 `4ed83a75`.
  - 결과: 첫 `/api` 응답이 87.3초에 503 → 자동 재시도 → 클릭부터 체험 화면까지 **170.9초 (2분 51초)**.
  - 같은 날 서버가 깨어 있을 때는 탭부터 체험 화면까지 2.1초 (11:01 UTC, iPhone 13 에뮬레이션, 1회).
  - [조건과 한계](../../README.md#절전-후-체험-시작-2분-51초-조건과-한계)

### 5. 권한·관리·검색·운영자 기능 개편 (2026-11-22, 4단계로 나눠 PR)

1. 생성자 권한·회차 물리 삭제·누적 집계 기반 (V8) → 2. 등록·수정 화면 분리, 내가 만든 회차, 검색·필터·정렬 7종·서버 페이지네이션 (V9 인덱스) → 3. 운영자 계정 관리(영향 미리보기·사유·조치 기록)·사용량 대시보드 (V10) → 4. 공통 상호작용·모션·순번 변경 강조, 전체 흐름 e2e.
각 단계는 테스트를 먼저 써서 실패를 확인한 뒤 구현했고, 운영 반영 전 로컬 운영 이미지(JDBC 세션·프록시 토큰)로 공개 점검 스크립트를 리허설했다. 운영자 비밀번호가 필요한 점검은 실행할 때 입력받고 출력·저장하지 않는다.

### 6. 일반 회원의 회차 개설부터 삭제까지 이어지는 흐름

1. **개설:** 일반 회원이 `회차 만들기` 로 회차를 연다. 생성자는 서버가 로그인 세션에서 정한다(요청의 생성자 값은 무시).
2. **참가:** 다른 회원은 전체 회차에서 검색(제목·장소, 서울 날짜 범위, 모집 상태, 정렬 7종, 주소 유지)으로 찾아 예약하거나 대기한다. 취소로 자리가 나면 대기 순서대로 확정되고, 화면은 순번 변경·확정을 강조와 글로 알린다.
3. **관리:** 생성자는 `내가 만든 회차` 에서 수정·명단·정원 변경(늘린 만큼 대기자 승격)을 한다. 다른 회원이 관리 API 를 직접 불러도 403.
4. **삭제:** 생성자나 운영자가 삭제하면 회차와 그 신청(확정·대기·취소)이 한 트랜잭션에서 물리 삭제되고 승격은 없다. 삭제된 회차를 열면 "삭제되었거나 찾을 수 없는 회차입니다".
5. **운영:** 운영자는 사용량(현재 데이터 / 누적 사용량, 일반·데모 구분)·전체 회차·계정 관리(정지·해제·강제탈퇴, 영향 미리보기·사유·조치 기록)를 본다. 생성자가 탈퇴하거나 강제탈퇴되면 만든 회차와 그 신청도 함께 지운다.

이 흐름을 데스크톱·375px 에서 한 번에 지나가는 e2e(`full-flow.spec.ts`)와, 공개 배포에서 같은 흐름을 전용 테스트 계정으로 확인하고 정리하는 점검 스크립트(`public-stage1-check.mjs`, `public-stage3-check.mjs`, 사용자 실행 대기)를 두었다.

### 7. 커뮤니티 확장 (2026-11-23~25): 6단계로 나눈 기능 확장과 공개 검증

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

**단계 진행 (계획 → 구현 → 공개 검증):** [계획 문서](../plans/community-plan.md) 에 데이터 모델·권한표·API·결정 필요 항목(추천안으로 진행)을 먼저 적고, 1 게시판·공지 → 2 댓글·추천·멘션 → 3 알림함 → 4 모임 신청·승인·가입 → 5 모임 역할·내부 게시판 → 6 전체 흐름으로 나눠 PR 을 냈다. 공개 점검 중 Render 배포가 31초 만에 실패해 이전 백엔드가 남은 일이 있었고(2026-11-23), 같은 커밋의 운영 이미지를 로컬에서 띄워 앱 문제가 아님을 확인한 뒤 점검 스크립트가 가입 전에 새 API 배포를 먼저 확인하도록 고쳤다([기록](../../README.md#3단계-공개-검증)).

## 기술 선택의 이유

| 선택 | 이유 |
|---|---|
| Next.js + Spring Boot 분리 | 첫 화면·`/tour` 같은 화면은 백엔드가 잠들어 있어도 Vercel 에서 열리고, 정합성이 중요한 예약 로직은 트랜잭션·잠금이 확실한 JVM + PostgreSQL 에 둔다 |
| 같은 출처 프록시 | 세션·CSRF 쿠키를 같은 출처로 단순하게 쓰고, 클라이언트 IP 와 백엔드 보호 토큰을 서버에서 붙일 수 있다 |
| 데모를 실제 도메인 로직 위에 | 데모 전용 가짜 흐름이 아니라 진짜 예약·취소·승격 코드를 그대로 쓰니, 시연이 곧 검증이 된다 |
| 무료 구성 | 포트폴리오 공개에 비용이 들지 않게. 대신 절전을 UX(안내·재시도·시연 페이지)로 흡수했다 |

## 검증 결과 요약

| 항목 | 결과 | 환경 |
|---|---|---|
| 동시성·정합성 | 마지막 자리·취소/승격·정원 증설, 동시 요청 10~400 단계 모두 PASS, 예상 못한 오류 0, SQL 4종 0행 (예: 대기자 400명 상태 혼합 C2 203건 636 req/s, p95 288ms) | 로컬(Apple M4 Pro, PostgreSQL 16.16 docker, 부하 발생기·서버·DB 같은 기기) · [A·B](../../loadtest/results/2026-11-21-local.md) · [C](../../loadtest/results/2026-11-21-local-capacity.md) |
| 공개 정상 흐름 | 21요청 모두 정상, p95 514ms (서버가 깨어 있던 상태, 1회) | 공개 배포 · [기록](../../loadtest/results/2026-11-21-public-smoke.txt) |
| 절전 후 체험 시작 | 170.9초 (1회) | 공개 배포 · 위 조건 |
| 휴대폰 화면 | 첫 화면·`/tour`·데모 시작 통과 | iPhone 13 **에뮬레이션**(Playwright Chromium) |

로컬 환경: Apple M4 Pro(12코어, 24GB), macOS 27.2, JDK 17.0.13, Spring Boot 4.1.2, Hikari 풀 10, PostgreSQL 16.16(docker), Node 23.7.

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

- **절전 시간:** 무료 서버 기동이 대부분이라 날마다 다르다 (측정 1회). 유료 인스턴스나 예약 기동이 근본 해결이다.
- **공개 부하 미측정:** 공개 서버는 소수 요청만 확인했다. 운영 처리량은 모른다.
- **실기기 미확인:** 실제 iPhone Safari 영상 재생은 **확인 대기**, 공개 운영자 화면은 **미검증**이다.
- **실시간성:** 폴링이라 최대 5초 + 왕복 지연. 필요하면 SSE 로 확장한다.
- **후속 과제:** 예약 변경 이력 화면(누가 언제 확정·대기·취소했는지)은 이번 개편 범위에서 빼고 후속으로 남겼다.
- **실사용 지표 없음:** 실제 사용자 수나 사업 성과는 수집하지 않았다.
