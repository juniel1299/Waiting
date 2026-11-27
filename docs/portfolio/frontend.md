# 다음자리 — 프런트엔드 포트폴리오

> 다른 사람의 취소로 내 대기가 확정되는 순간을 **새로고침 없이** 보여주고, 무료 서버가 잠들어 있어도 사용자가 길을 잃지 않게 만든 예약·대기 화면.
> 공개 데모: https://waiting-mauve.vercel.app (가입 없이 `데모 체험하기`) · 시연: [/tour](https://waiting-mauve.vercel.app/tour) (예약 흐름·모임 영상 + 단계별·기능 화면)

> **커뮤니티 확장(2026-11-23~25):** 회원 게시판·공지, 댓글·한 단계 답글·추천·멘션, 알림함, 모임 신청·승인·가입, 모임 역할·내부 게시판을 같은 서비스에 더했다. 자세한 문제 해결은 아래 "커뮤니티 확장" 절.

## 지원서용 요약

**프로젝트 소개.** 다음자리는 정원이 찬 스터디 회차에 대기를 걸어 두면, 취소로 빈자리가 생길 때 대기 순서대로 자동 확정되는 예약 서비스입니다. 프런트엔드에서는 다른 사용자의 취소로 바뀐 내 상태를 새로고침 없이 보여 주고, 무료 서버가 잠들어 있어도 사용자가 진행 상황을 알고 기다리거나 다시 시도할 수 있게 만들었습니다. 공개 데모 https://waiting-mauve.vercel.app (가입 없이 `데모 체험하기`) · 시연 [/tour](https://waiting-mauve.vercel.app/tour) (예약 흐름·모임 영상 + 단계별·기능 화면, 백엔드 없이 열림)

**핵심 구현·검증 결과**

1. **보이는 탭에서만 5초 폴링 + 탭 복귀·재연결 즉시 조회**: 다른 사람이 취소하면 내 화면이 4.8초 안팎에 `예약 확정` 으로 바뀌고, 탭 복귀 뒤 25~30ms 에 반영됐다 (로컬, 각 10회). 근거: [`auto-refresh.spec.ts`](../../frontend/e2e/auto-refresh.spec.ts), [측정](../../README.md#회차-상세-자동-갱신)
2. **잠든 서버 대응**: 프록시가 85초에 `503 + Retry-After` 로 답하고, 조회와 데모 시작만 최대 5분 자동 재시도한다. 공개 배포에서 약 55분 무요청 뒤 클릭부터 체험 화면까지 170.9초였고(1회), 그동안 버튼은 `체험 준비 중…`·포커스 유지, 중복 시작 요청 0. 근거: [`cold-start.spec.ts`](../../frontend/e2e/cold-start.spec.ts), [`demo-button.spec.ts`](../../frontend/e2e/demo-button.spec.ts), [측정](../../README.md#절전-후-첫-접속-2026-11-21-0420-utc)
3. **접근성·모바일과 상태 버그 수정**: `aria-busy`·`aria-disabled` 로 포커스를 지키고, 칸별 오류는 `aria-describedby` 로만 연결했다. 떠난 화면에 남은 진행 상태 때문에 로그아웃 뒤 재로그인이 막히던 버그를 e2e 로 잡아 고쳤다. e2e 41개 통과(직전 실행), iPhone 13 **에뮬레이션** 통과(실제 iPhone Safari 는 확인 대기). 근거: [`auth.spec.ts`](../../frontend/e2e/auth.spec.ts), [`signup.spec.ts`](../../frontend/e2e/signup.spec.ts)

4. **커뮤니티 화면 (확장)**: 서버가 준 권한 플래그로만 버튼을 보이고, 권한 없음(403)은 다시 시도 없이 안내·돌아갈 링크, 읽음 상태·진행 단계·역할은 색과 글자를 함께, 알림 버튼은 읽지 않은 수를 이름에 글자로 넣는다. 375px 10개 화면 가로 넘침 0, 키보드로 알림까지, 동작 줄이기 전환 0.02초 미만. 근거: [`community-flow.spec.ts`](../../frontend/e2e/community-flow.spec.ts), [`NotificationBell`](../../frontend/src/components/NotificationBell.tsx)

아래에서 **[직접 작성]** 표시는 저장소 기록만으로 확인할 수 없어 비워 둔 항목이다.

- 개발 기간: [직접 작성: 예) 2026.11 ~ 2026.11, 주 n시간] (참고, 저장소 커밋 기준: **최초 개발 기간 2026.11.15~11.21**, **개편 기간 2026.11.22** 12:02~18:10 KST — 커밋 `64a0d601`(V8 생성자 권한) ~ `a7a26c29`(공개 점검 보강), PR #30~#35), **커뮤니티 확장 기간 2026.11.23 20:14 ~ 11.25 06:02 KST** — 첫 커밋 `e5dceee6`(커뮤니티·알림·모임 계획) ~ PR #48 병합 `78c98f46`, PR #39~#48
- 개인 담당 범위·팀 구성: 개인 프로젝트
- AI 협업 방식: 기획·문서는 Codex, 개발·테스트는 Claude가 맡고, 요구사항 결정·PR 병합·로그인이 필요한 공개 검증 실행은 본인이 했다
- 지원 직무·회사에 맞춰 강조할 점: [직접 작성]

## 서비스 소개

- **화면:** 정원이 있는 스터디 회차를 예약하고, 마감된 회차에는 대기를 건다. 확정자가 취소하면 첫 대기자가 자동 확정되고, 그 사람의 화면은 몇 초 안에 `예약 확정` 으로 바뀐다.
- **구성:** Next.js 16(App Router, `cacheComponents`) + TypeScript.
- **API 호출:** 같은 출처의 `/api` Route Handler 프록시로만 백엔드를 부른다.

## 주요 구현

### 1. 상태 동기화: 보이는 동안만 5초 폴링

- **문제:** 다른 사람의 취소로 내 상태가 바뀌어도 화면은 그대로다.
- **방식:** `useVisiblePolling` 이 상세 화면이 **보이고 온라인일 때만** 5초마다 조회한다.
  - 늦게 도착한 옛 응답은 버린다 (마지막에 시작한 조회만 반영).
  - 대기 → 확정이 된 순간에만 `role="status"` 로 한 번 알린다.
- **확인:** [`auto-refresh.spec.ts`](../../frontend/e2e/auto-refresh.spec.ts). 두 브라우저에서 B 가 취소하면 A 는 새로고침 없이 확정을 본다.
  - 반영 4799~4822ms (10회, 폴링 간격 5초).
  - 측정 환경: 로컬 M4 Pro, Next `build && start`, Spring Boot local, PostgreSQL 16. [측정](../../README.md#회차-상세-자동-갱신)

### 2. 탭 복귀·재연결

- **방식:**
  - 숨긴 탭(`visibilitychange`)과 오프라인(`offline`)에서는 요청을 0건으로 멈춘다.
  - 탭이 다시 보이거나 `online` 이 되면 즉시 한 번 조회하고 폴링을 다시 시작한다.
  - 오프라인 안내는 한 번만 띄운다.
- **확인:** 같은 스펙. 탭 복귀 → 반영 25~30ms, 네트워크 복구 → 반영 24~28ms (각 10회, 로컬).

### 3. 로딩·오류 복구: 잠든 무료 서버

- **문제:** Render Free 는 15분 무요청이면 잠든다. 첫 요청이 Vercel 함수 제한(90초)에 걸려 504 가 났고, 첫 화면 버튼은 로그인 확인을 기다리느라 48초 뒤에야 보였다.
- **방식:**
  - 첫 화면은 로그인 확인을 기다리지 않고 바로 그린다.
  - 프록시는 85초에 업스트림을 끊고 `503 + Retry-After` 로 답한다.
  - 클라이언트(`sendWhileWaking`)는 502/503/504·연결 실패에 "서버를 깨우는 중" 안내를 띄우고, `Retry-After` 간격으로 최대 5분 재시도한다.
  - 재시도 대상은 조회(GET)와, 두 번 처리돼도 무해한 데모 시작뿐이다. 대기 신청 같은 변경은 다시 보내지 않는다.
  - 데모 버튼:
    - 누르면 `체험 준비 중…` 으로 바뀐다.
    - `disabled` 대신 `aria-disabled`·`aria-busy` 를 써서 키보드 포커스를 유지한다. 연속 클릭은 ref 로 즉시 막는다.
    - 상한을 넘기면 `다시 시도하기` 로 되살린다.
  - 기다리는 동안 볼 시연(`/tour`, 백엔드 호출 0)을 새 탭 링크로 연다. 원래 탭의 재시도는 끊기지 않는다.
- **확인:**
  - e2e:
    - [`cold-start.spec.ts`](../../frontend/e2e/cold-start.spec.ts): 504·502·연결 실패 재시도, 변경 요청 재시도 금지.
    - [`demo-button.spec.ts`](../../frontend/e2e/demo-button.spec.ts): 로딩 문구·포커스·중복 클릭, 상한 초과 복구, 429.
    - [`tour.spec.ts`](../../frontend/e2e/tour.spec.ts): `/api` 전부 막아도 열림, 새 탭 링크.
    - 프록시 단위 [`proxy.test.mjs`](../../frontend/test/proxy.test.mjs).
  - 공개 배포 실측 (2026-11-21 04:20 UTC, 약 55분 무요청 뒤, 새 컨텍스트):
    - 버튼 1.6초 노출, 클릭 직후 `체험 준비 중…`·`aria-busy=true`·포커스 유지.
    - 추가 클릭 2회 + Enter 에도 시작 요청 1회, 안내 4.7초.
    - 체험 화면까지 **170.9초**. [기록](../../README.md#절전-후-첫-접속-2026-11-21-0420-utc)

### 4. 떠난 화면에 남은 상태 (실제 버그 수정)

- **문제:** `cacheComponents` 는 떠난 화면을 숨긴 채 상태를 남긴다. 그래서 로그아웃 후 돌아온 로그인 화면의 버튼이 이전 로그인의 `busy` 상태로 잠겨 있었다 (당시 `main` 과 공개 배포에 있던 버그, 2026-11-21 로컬 e2e 에서 발견).
- **방식:** 화면이 숨겨질 때(effect 정리) 진행 중 표시와 비밀번호 입력을 비운다.
- **확인:** [`auth.spec.ts`](../../frontend/e2e/auth.spec.ts) "로그아웃한 뒤 같은 화면에서 다시 로그인할 수 있다", 데모 화면에서 뒤로 가기 복귀 테스트.

### 5. 모바일·접근성

- **방식:**
  - 모바일 탭 강조(`-webkit-tap-highlight-color`)가 눌린 모양으로 남지 않게 했다.
  - 가입 칸별 오류는 `label` 밖에 두고 `aria-describedby` 로만 연결해, 오류 문구가 칸 이름에 섞이지 않게 했다.
  - 오류 시 첫 오류 칸으로 포커스를 옮긴다.
  - 결과는 `role="status"`, 오류는 `role="alert"`.
  - 첫 화면 3단계 흐름은 480px 이하에서 세로로 쌓인다.
- **확인:**
  - [`signup.spec.ts`](../../frontend/e2e/signup.spec.ts) (`toHaveAccessibleDescription`), [`tour.spec.ts`](../../frontend/e2e/tour.spec.ts) (375px 가로 넘침 없음).
  - 키보드만으로 데모 시작(Tab → Space)·탈퇴(Tab → Enter → 비밀번호 → Enter)를 실제 Chromium 에서 확인했다.
  - 화면 확인은 Playwright **iPhone 13 에뮬레이션**이다 (아래 한계 참고).

### 6. 개편 뒤 화면 구조와 상호작용 (2026-11-22, 2~4단계)

- **조건은 주소에 둔다:** 회차 검색(전체·내가 만든 회차·운영자 전체 회차 공통 부품)은 검색어·범위·날짜·상태·정렬 7종·쪽을 주소에 남겨, 상세에 갔다 오거나 새로고침해도 같은 결과로 돌아온다. 조건이 바뀌면 1쪽부터.
- **카드 클릭 영역:** 제목 링크의 덮개(`::after`)가 카드 전체를 상세 이동 영역으로 만들고, 카드 안 `수정`·`관리` 링크는 그 위에 올려 상세 이동과 겹치지 않게 했다 (e2e 로 둘 다 확인).
- **상태가 구분되는 버튼·메뉴·입력:** 기본(채움·포인터)·hover·키보드 포커스 링·눌림·진행 중·비활성을 공통 스타일로 정리. 주 메뉴는 `aria-current` 로 현재 위치를 굵게·밑줄로 보인다. 모든 전환은 180ms, `prefers-reduced-motion` 이면 이동·확대·애니메이션을 끈다 (e2e 에서 `reducedMotion` 으로 확인).
- **변화는 강조 + 글로:** 다른 사람의 취소로 대기 순번이 앞당겨지거나 확정되면 해당 칸을 1.6초 강조하고 `role=status` 로 "대기 순번이 2번에서 1번으로 앞당겨졌어요." 를 알린다. 강조만으로 전달하지 않는다. 처음 본 상태·본인 행동은 알리지 않는다(단위 테스트).
- **전체 흐름 e2e:** 가입 → 회차 만들기 → 검색 → 대기 신청 → 앞사람 취소로 순번 변경 → 확정 → 운영자 화면을 데스크톱·모바일(375px) 뷰포트에서 실제 버튼으로 진행한다. 키보드만으로 로그인·검색·상세·예약·취소하는 흐름도 따로 검증.

### 7. 등록·수정 화면 분리와 반응형

- 회차 목록에 섞여 있던 등록 폼을 `회차 만들기`(`/sessions/new`) 와 `회차 수정`(`/sessions/{id}/edit`, 제목·시작·장소만) 전용 화면으로 나눴다. 정원 변경은 대기자 승격이 따르므로 회차 관리 화면에만 둔다. 같은 폼 컴포넌트(`SessionForm`)를 만들기·수정에 함께 쓴다.
- 검색 칸은 넓은 화면에서 한 줄 두 칸·네 칸, 375px 에서는 칸이 줄바꿈되고 표는 표 안에서만 가로로 넘긴다. 가로 스크롤 없음은 e2e(로컬)와 공개 점검(375px)에서 확인했다.
- `cacheComponents` 가 떠난 화면을 숨겨 DOM 에 남기는 탓에 같은 이름표가 두 번 잡히는 문제를 만나, 검색 칸 이름표를 `label htmlFor` 로 바꾸고 e2e 는 보이는 요소만 찾게 했다.

### 8. 커뮤니티 확장 (2026-11-23~25): 권한별 화면·알림·상태

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

**프런트엔드에서 한 일 (위 사례의 화면 쪽):**

- 1·2단계 게시판 화면(`BoardList`·`PostDetail`·`PostForm`·`Comments`)을 모임 게시판·공지에 그대로 재사용하고, 멘션 후보만 React 컨텍스트로 모임 회원으로 좁혔다([`MentionTextarea`](../../frontend/src/components/board/MentionTextarea.tsx)).
- 권한 없음(403)·삭제됨(404)·오류(다시 시도)를 구분해 보인다. 403 은 다시 해도 같으므로 검색칸·다시 시도를 감추고 안내와 모임 소개 링크만 둔다([`BoardList`](../../frontend/src/components/board/BoardList.tsx)).
- 알림을 열면 서버가 준 주소로 이동하고 `#comment-{id}` 면 목록을 받은 뒤 그 댓글로 옮겨 초점을 둔다.
- 화면 상태·375px·키보드·동작 줄이기를 한 e2e 파일에서 확인한다([`community-flow.spec.ts`](../../frontend/e2e/community-flow.spec.ts)).

## 기술 선택의 이유

| 선택 | 이유 | 대안과 비교 |
|---|---|---|
| 폴링(5초) | 상태 변화가 "가끔, 몇 초 늦어도 되는" 성격이고, 무료 서버가 잠들 수 있어 연결을 오래 붙잡는 방식이 맞지 않는다. 숨긴 탭은 0건 | SSE·WebSocket 은 즉시성이 좋지만 절전·재연결·프록시 타임아웃 처리가 더 복잡하다 |
| 같은 출처 `/api` 프록시 | 쿠키(세션·CSRF)를 같은 출처로 쓰고, 클라이언트 IP·공유 토큰을 서버에서 붙인다 | 브라우저가 백엔드를 직접 부르면 CORS·서드파티 쿠키 문제가 생긴다 |
| `aria-disabled` + ref 가드 | `disabled` 는 포커스를 잃게 해 키보드 사용자가 위치를 잃는다 | |
| 재시도 범위 제한 | 대기 신청·취소를 다시 보내면 의도치 않은 상태 변경이 생길 수 있다 | |

## 검증 결과 요약

| 항목 | 결과 | 환경 · 근거 |
|---|---|---|
| e2e | 41개 통과, 실패·건너뜀 0 | Playwright Chromium + 실제 Spring Boot·PostgreSQL, 로컬. 직전 실행 결과: 2026-11-21 약 10:50 UTC, 로컬 Mac, 커밋 `548023aa` 기준. 그 뒤 커밋(`cfbf1830` 시연 영상 mp4, 문서)은 다시 돌리지 않음 |
| 프록시 단위 | 2개 통과 | `node --test`. 직전 실행 결과: 2026-11-21 약 10:50 UTC, 로컬 Mac, 커밋 `548023aa` 기준. 그 뒤 커밋(`cfbf1830` 시연 영상 mp4, 문서)은 다시 돌리지 않음 |
| 공개 정상 흐름 | 데모 흐름 3회 순차 + 데모 시작 동시 3건, 21요청 모두 200·201, p50 221ms · p95 514ms (서버가 깨어 있던 상태, 서울의 Mac 에서 1회 측정) | 공개 배포 2026-11-21 10:46 UTC · [기록](../../loadtest/results/2026-11-21-public-smoke.txt) |
| 공개 휴대폰 화면 | 첫 화면 흐름·가로 넘침 없음, `/tour` `/api` 0건·mp4 재생 가능(readyState 4, Chromium 기준)·이미지 4/4, 데모 시작 2.1초 | **iPhone 13 에뮬레이션**(Playwright Chromium), 2026-11-21 11:01 UTC |

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

- **실기기 미확인:** 휴대폰 화면 검증은 Playwright Chromium 의 iPhone 13 에뮬레이션이다. 실제 iPhone Safari 의 영상(H.264 mp4) 재생과 탭·스크롤 감각은 **확인 대기**다.
- **폴링 지연:** 회차 상세는 반영까지 최대 5초 + 왕복(모임 채팅은 3초). 현재 인증·프록시 구조와 무료 배포의 운영 복잡도를 고려한 선택이며, 동시 접속 증가에 따른 조회 부하와 갱신 지연을 측정해 SSE·WebSocket 전환을 검토한다.
- **절전 대기:** 2분 51초는 대부분 Render 인스턴스 기동 시간이라 프런트로 줄일 수 없다. `/tour` 로 기다림을 덜 지루하게 했을 뿐이다. 측정은 1회다.
- **후속 과제:** 예약 변경 이력 화면(누가 언제 확정·대기·취소했는지)은 이번 개편 범위에서 빼고 후속으로 남겼다.
- **실사용 지표 없음:** 실제 사용자 수·전환율 같은 지표는 수집하지 않았다.
