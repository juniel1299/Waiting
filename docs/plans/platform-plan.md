# 다음자리 확장 계획: 메뉴·디자인·권한·모임 활동·개발자 도구 (2026-11-25)

요청: 계층형 메뉴와 디자인 개편 → 서비스·모임 권한 관리 → 프로필·랜덤 닉네임·즐겨찾기 → 개선사항·에러 로그·모니터링 → 대시보드 그리드·차트 → 카카오·네이버 로그인·계정 연결 → 모임 채팅 → 모임 사진첩. 단계마다 테스트 먼저(권한·계정 연결·중복 방지·삭제 정합성은 실제 PostgreSQL), PR 분리, 완료·검증·미검증 구분 기록. **월 0원 유지(카드 등록이 필요한 서비스는 쓰지 않는다).**

진행: **1단계 완료·공개 검증 통과, 2단계 배포·비밀번호 없는 점검 통과(로그인 점검 대기), 3단계 배포·비밀번호 없는 점검 통과(로그인 점검 대기), 4단계 배포(PR #53), 5단계 배포(꺼진 상태 점검 통과), 6단계 배포(비밀번호 없는 점검 통과), 7단계 배포(비밀번호 없는 점검 통과). 7단계 모두 배포·공개 검증. 남은 것: 5단계 실제 카카오·네이버 계정으로 연결·로그인 확인(사용자, 공급자는 07:50 UTC 켜짐). 요약: [정리](../platform-expansion-summary.md)** (아래 "상태 기록").

## 지금 구조 (2026-11-25 `c3505151` 확인)

| 영역 | 지금 있는 것 | 이번에 넓히는 곳 |
|---|---|---|
| 인증 | 서버 세션(Spring Session JDBC)·쿠키 CSRF·Vercel `/api` 프록시·프록시 공유 토큰, `SessionUser(id, loginId, displayName, role, demoTrialId)` | 1단계: 서비스 역할 목록을 세션에, 5단계: 소셜 로그인도 같은 세션 |
| 요청마다 계정 확인 | `WithdrawnAccountFilter` 가 요청마다 탈퇴·정지를 보고 세션을 끊는다 | 1단계: 역할도 요청마다 다시 읽어 세션 권한을 바꾼다(회수 즉시 반영) |
| 서비스 역할 | `account.role` ∈ `PARTICIPANT`·`OPERATOR` 하나, 데모는 `PARTICIPANT` + `demo_trial_id` | 1단계: 여러 역할 |
| 모임 역할 | `club_member.role` ∈ `MEMBER`·`MANAGER` + `club.creator_account_id`, `ClubRoles`(요청마다 재확인), 임명·회수·이전 API | 1단계: 모임 안 권한 관리 탭 |
| 운영 조치 | 정지·해제·강제탈퇴(활성 운영자 행 id 순 잠금, 조치 기록 `account_moderation`) | 1단계: 역할 부여·회수도 같은 잠금·기록 방식 |
| 화면 | `AuthGate`·`useMe`·머리글 가로 메뉴, 공통 버튼·카드·배지 CSS(180ms·동작 줄이기), `useVisiblePolling`, 검색 조건은 주소에, `api()` 요청 함수 | 1단계: 사이드바·서랍, 공통 컴포넌트는 쓰이는 단계에서 |
| 의존성 | Next 16·React 19, 상태 관리·차트·그리드 라이브러리 **없음** | 1단계: Zustand 추가, 4단계: 차트 방식 결정 |
| 테스트 | JUnit + Testcontainers PostgreSQL(705), Playwright e2e(102, 실제 백엔드), `node --test`(29) | 단계마다 추가 |
| 배포 | Vercel Hobby → Render Free → Neon Free | 3·7단계에서 외부 서비스 결정 |

## 단계와 의존성

| 단계 | 범위 | 먼저 필요 | 스키마 (예정) |
|---|---|---|---|
| 1 | 계층형 메뉴·디자인 규칙, 서비스 역할(여러 개)·서비스 권한 관리 화면, 모임 안 권한 관리 탭, Zustand(메뉴·토스트)·공통 요청 모듈 | — | V16 `account_role`, `account_role_change` |
| 2 | 프로필(소개·관심 태그)·공개 프로필, 랜덤 한글 닉네임·유일성, 즐겨찾기(모임·회차) | 1(메뉴 자리) | V17 닉네임 정규화 키 유일·기존 회원 이관, `profile`, `favorite` |
| 3 | 개선사항 접수·관리, 에러 로그(자체 요약 + Sentry 링크), 마스킹·보관·샘플링 | 1(개발자 역할) | V18 `feedback`·`feedback_history`, `error_group`(개별 `error_event` 는 두지 않음), 알림 종류 2개 |
| 4 | 대시보드 그리드·차트 전환(같은 데이터·기간·필터) | 1(관리 메뉴) | 없음(`usage_daily` 재사용) |
| 5 | 카카오·네이버 로그인·계정 연결·해제·소셜 전용 회원 탈퇴 | 1(요청마다 재확인), 2(새 계정 랜덤 닉네임) | V19 `account_identity` |
| 6 | 모임 단체 채팅(모임당 1방, 텍스트) | 1(모임 탭), 실시간 방식 실험 | V20 `chat_message`, `chat_read` |
| 7 | 모임 사진첩 | 6(모임 활동 메뉴), **저장소 결정(사용자)** | V21 `club_photo`, `storage_job` |

공통 컴포넌트·Zustand·`fetch` 공통 모듈은 그 기능이 실제로 쓰는 단계에서 함께 넣는다(1단계: 메뉴 store·토스트·요청 모듈·페이지 머리).

## 기본 정책

- **역할은 여러 개:** 한 계정이 운영자 + 개발자일 수 있다. 회원은 역할 없는 기본값.
- **개발자 역할만으로는** 강제탈퇴·정지·서비스 권한 관리·모임 승인·대시보드·비공개 콘텐츠(모임 내부) 전체 열람이 **안 된다**. 개선사항·에러 로그만 더해진다.
- **마지막 운영자 제거 거부(409), 자기 권한 상승 거부:** 서비스 권한 관리 API 는 운영자만. 운영자도 자기 자신의 역할은 바꾸지 못한다(다른 운영자가 한다).
- **즉시 반영:** 역할 회수·모임 탈퇴·정지는 이미 로그인한 세션의 다음 요청부터 반영된다(요청마다 DB 재확인).
- **개선사항 접수:** 일반 회원만. 데모·비회원 불가. 기존 남용 방지(로그인 시도 제한)와 같은 방식으로 계정·IP 당 횟수 제한.
- **소셜 계정:** 제공자별로 내부 계정당 하나, 다른 계정에 연결된 소셜은 거부, 이메일·닉네임 일치로 자동 통합하지 않음. 로그인 수단은 최소 하나 남김. 두 내부 계정 병합은 정책안만 쓰고 구현 보류(두 계정 모두 인증, 예약·모임 소유권은 옮기지 않음).
- **삭제 정합성:** 새 연관 데이터(즐겨찾기·채팅·사진 기록·역할 기록)도 FK CASCADE·한 트랜잭션. 외부 저장소 파일은 작업 표로 재처리.

## 1단계: 메뉴·디자인·서비스/모임 권한

### 데이터 모델·마이그레이션 (V16)

- `account_role(account_id, role)` PK, `role` ∈ `OPERATOR`·`DEVELOPER`. 계정 삭제 시 CASCADE.
- `account_role_change(id, target_account_id, role, action GRANT|REVOKE, reason, actor_account_id, created_at)`. 처리자·대상은 계정 id 로만(탈퇴하면 화면에 '탈퇴한 회원').
- **기존 데이터 (구현에서 바꾼 점):** 운영자 여부는 기존 `account.role` 을 그대로 기준으로 쓰고(이관 없음, 기존 운영 계정·예약 규칙 `hasRole("PARTICIPANT")`·테스트 유지), `account_role` 에는 더해지는 역할(`DEVELOPER`)만 둔다. 운영자 부여·회수는 `account.role` 을 바꾼다. 데모 계정은 역할 없음(지금처럼 `PARTICIPANT` + `demo_trial_id`).

### 권한표

| 기능 | 비회원 | 데모 | 회원 | 운영자 | 개발자 |
|---|---|---|---|---|---|
| 게시판·모임·회차 읽기 | 공지만 | 읽기 | ○ | ○ | 회원과 같음 |
| 글·댓글·모임 신청·예약 | ✗ | ✗ | ○ | 기존 정책 | 회원과 같음 |
| 대시보드·계정 관리(정지·해제·강제탈퇴)·모임 승인 | ✗ | ✗ | ✗ | ○ | ✗ |
| 서비스 권한 관리(역할 조회·부여·회수) | ✗ | ✗ | ✗ | ○(자기 자신 제외) | ✗ |
| 개선사항·에러 로그(3단계) | ✗ | ✗ | ✗ | ✗(운영자만이면) | ○ |

### API

- `GET /api/operator/roles?q&page` 계정별 역할, `GET /api/operator/roles/changes` 변경 기록, `PUT`·`DELETE /api/operator/roles/{accountId}/{role}` `{reason}` (사유 1~200자 필수).
- `GET /api/auth/me` 에 `roles` 를 더한다(메뉴 노출용, 판단은 서버).

### 화면

- 사이드바(≥ 900px, 접기·펼치기) / 서랍(모바일): 게시판·모임·회차·내 활동(내 신청·알림)·관리(운영자: 대시보드·계정 관리·모임 승인·서비스 권한 관리). 아직 없는 화면(즐겨찾기·프로필·로그인 계정 연결·개선사항·에러 로그·채팅·사진첩)은 넣지 않고 해당 단계에서 추가.
- 서비스 권한 관리(`/operator/roles`): 역할별 기능 권한표, 계정 검색·역할 배지·부여·회수(사유 입력·확인), 변경 기록(처리자·대상·역할·동작·사유·시각).
- 모임 안 메뉴: 게시판·공지·회원·권한 관리. 권한 관리 탭은 임명·회수·생성자 이전과 역할별 할 수 있는 일 표.
- 디자인 규칙: [`docs/design-system.md`](../design-system.md).

### 테스트 (먼저)

- 백엔드(실제 PostgreSQL): 개발자 역할만으로 강제탈퇴·정지·역할 관리·모임 승인·대시보드·모임 내부 열람 403, 자기 역할 변경 403·회원의 역할 API 403, 마지막 운영자 회수 409, 운영자 두 명이 서로 동시 회수 → 한 명은 남음(20회), 회수 직후 같은 세션 403, 부여 직후 같은 세션 200, 기록에 사유·처리자·시각, 마이그레이션 뒤 기존 운영자 역할 유지.
- e2e: 역할별 메뉴 노출(회원·운영자·개발자·데모), 직접 URL 권한 없음 안내·API 403, 모바일 서랍 열고 닫은 뒤 초점 복귀(Esc·메뉴 선택), 데스크톱 사이드바 접기·현재 위치 강조, 375px 가로 넘침 0.

## 2단계 이후 (해당 단계 시작 때 상세화, 테스트 먼저)

- **2 닉네임·프로필·즐겨찾기:** 닉네임 2~12자, NFC 정규화·앞뒤 공백 제거·연속 공백 하나, 한글·영문·숫자·공백만, 예약어 금지. 유일성은 정규화 키 유일 인덱스(최종) + 저장 전 확인 API. 가입 시 "형용사+명사+숫자" 무작위, 충돌 시 재생성. 기존 회원 이관: 중복이면 숫자 접미사, 비어 있으면 무작위. 글·멘션은 이미 계정 id 연결. 공개 프로필은 닉네임·소개·태그만. 즐겨찾기 `favorite(account_id, target_type, target_id)` 유일·`PUT`/`DELETE` 멱등·대상 삭제 시 CASCADE(트리거 또는 대상별 FK)·목록은 지금 볼 수 있는 것만.
- **3 개선사항·에러 로그:** 상태 접수·진행 중·검증 요청·완료·보류(검증 요청·보류는 사유 필수), 처리 내용·PR 링크, 작성자는 본인 것만, 개발자는 전체 검색·필터. 오류는 자체 DB 에 요약(지문·횟수·화면·경로·배포 버전·추적 ID·처음/마지막 시각)만 두고 Sentry 상세 링크. 비밀번호·쿠키·토큰·이메일·채팅 본문은 수집 전 제거, 보관 30일, 같은 오류 알림 1시간 1회, 수집 실패는 기존 요청에 영향 없음.
- **4 대시보드:** 기존 정의(현재 데이터 / 누적, 일반 / 데모) 유지, 같은 API 결과를 표 ↔ 차트(날짜 추이는 선·막대, 현재는 요약 카드·표), 차트 수치는 표에도, 로딩·빈·오류 상태. 라이브러리는 4단계에서 의존성 없이 SVG 와 작은 라이브러리를 비교해 결정.
- **5 소셜:** `account_identity(provider, provider_user_id)` 유일, `(account_id, provider)` 유일. OAuth `state` 를 세션에 저장·1회용·목적(로그인/연결) 구분, 콜백 재사용 거부. 처음 소셜 로그인은 "기존 계정에 연결(기존 비밀번호로 인증) / 새 계정". 연결 해제는 로그인 수단 최소 하나. 소셜 전용 회원 탈퇴는 소셜 재인증. 정지·강제탈퇴는 소셜 세션에도(요청마다 재확인).
- **6 채팅:** 6단계 시작 전에 Vercel 프록시 + Render Free 에서 **폴링·SSE·WebSocket 을 직접 실험**해 고른다(Vercel 함수는 연결 유지 시간 제한, 브라우저가 Render 로 직접 붙으면 Vercel 도메인 세션 쿠키가 가지 않음). 메시지 클라이언트 id 유일(재시도 중복 없음), 커서 조회(재연결 누락 보충), 요청마다 모임 회원 재확인, 모임 삭제 시 CASCADE.
- **7 사진첩:** 파일 저장소는 아래 결정 뒤. 모임 회원 확인 뒤 백엔드가 짧은 서명 URL 또는 프록시로 준다(외부 URL 로 비회원 조회 불가). 크기·개수 제한, 매직 바이트 형식 확인, EXIF 제거, `storage_job` 으로 업로드 실패·사진 삭제·모임 삭제의 남은 파일 재처리.

## 외부 서비스 무료 한도 (2026-11-25 확인)

| 서비스 | 무료 한도 | 초과·카드 | 결정 |
|---|---|---|---|
| Sentry Developer | 사용자 1명, 오류 5,000건/월, 이메일 알림, API 접근 불가, 보관 30일 ([요금](https://sentry.io/pricing/)) | 가입 때 카드 필요 여부는 **사용자 확인 필요** | 자체 DB 에 오류 요약만 저장 + Sentry 상세 링크(기본안). 카드가 필요하면 Sentry 없이 자체 수집만 |
| Supabase Storage (Free) | 1GB, 비공개 버킷, 1주 비활성 시 프로젝트 일시정지 | 무료 플랜은 초과 과금 없이 알림·유예·제한 ([사용량](https://supabase.com/docs/guides/platform/manage-your-usage/storage-size), [비용 관리](https://supabase.com/docs/guides/platform/spend-cap)). 카드 요구 여부는 공식 페이지에 없음 → 가입 때 확인 | **추천(카드 없이 가입되면)** |
| Backblaze B2 | 10GB | 초과 과금, 결제 수단(카드·청구서) | 카드 필요 → 제외 |
| Cloudflare R2 | 10GB·Class A 100만·B 1,000만/월, 송신 무료 ([요금](https://developers.cloudflare.com/r2/pricing/)) | R2 구독 결제 절차 필요, 초과 과금 | 카드 필요 → 제외 |
| Neon `bytea` | DB 0.5GB 를 같이 씀 | 기존 무료 | Supabase 가 안 되면 대안(사진 크기·개수를 작게 제한) |
| 카카오·네이버 로그인 | 무료 | — | 5단계 |

**7단계 전 사용자 결정:** 사진 저장소 = Supabase Storage(카드 없이 가입 가능하면, 추천) 또는 Neon `bytea`(용량 작게). 비활성 일시정지는 사진 조회 실패로 이어질 수 있어 화면에 안내하고 재처리로 복구.

## 사용자가 할 일 (해당 단계에서 다시 안내)

- 3단계: Sentry 가입(카드 요구 여부 확인), 프로젝트 2개(프런트·백엔드), 알림 이메일, DSN 을 Vercel·Render 환경변수로(저장소에 넣지 않음).
- 5단계: 카카오·네이버 개발자 앱 등록, 콜백 URL(`https://waiting-mauve.vercel.app/api/auth/oauth/{provider}/callback` 예정), 동의 항목(제공자 사용자 식별자만, 이메일·실명 선택 안 함), 클라이언트 ID·비밀키를 Render 환경변수로.
- 7단계: 저장소 결정, Supabase 비공개 버킷·서비스 키를 Render 환경변수로.

## 2단계 설계·구현 (2026-11-26)

- **닉네임 규칙(`board/Nicknames`):** NFC → 앞뒤 공백 제거 → 연속 공백 하나. 한글·영문·숫자·공백 2~12자. 운영자·관리자·개발자·다음자리·탈퇴한 회원·admin·operator 를 **포함**하거나 '회원+숫자' 모양이면 400(이유 문구). 기존 2~20자·기호 허용 규칙에서 좁혔다(기존 닉네임은 그대로 두고, 바꿀 때만 새 규칙).
- **유일성:** `ux_account_nickname`(대소문자 무시 `lower(nickname)` 유일 인덱스)가 최종. `GET /api/me/nickname/check?value=` 는 안내용(`{available, reason}`). 동시에 같은 이름 저장 → 하나 200·하나 409 `NICKNAME_TAKEN`(20회). 탈퇴하면 닉네임·소개·태그를 지워 이름이 다시 풀린다.
- **가입:** 가입 트랜잭션 커밋 뒤 "형용사+명사+1~9999" 를 문장 하나로 넣고, 유일 인덱스에 걸리면 새 조합으로 최대 10번(못 주면 기본 '회원{id}' 표시).
- **V17 이관(기존 데이터):** 닉네임 NFC·공백 정리 → 대소문자만 다른 중복은 가장 먼저 만든 계정만 두고 나머지는 앞 10자+계정 id → 닉네임 없는 일반 회원(탈퇴·데모 제외)에 형용사+명사+계정 id → 유일 인덱스 생성. `NicknameMigrationTests` 가 V16 DB 에 기존 회원을 넣고 V17 을 적용해 확인.
- **프로필:** `account.bio varchar(300)`, `interest_tags varchar(20)[]`(최대 5, DB check). `PATCH /api/me/profile {nickname?, bio?, tags?}` 는 보낸 항목만 바꾸고 태그는 공백 제거·중복 제거. `GET /api/profiles/{id}` 는 `{id, nickname, bio, tags}` 만(로그인 아이디·역할 없음, 비회원 401, 탈퇴 404). 글·멘션은 계정 id 로 이어져 이름을 바꾸면 새 이름으로 보인다.
- **즐겨찾기:** `favorite(account_id, club_id | session_id)` 중 하나만(check), `(account_id, club_id)`·`(account_id, session_id)` 유일, 계정·모임·회차 FK `on delete cascade`. `PUT`/`DELETE /api/{sessions|clubs}/{id}/favorite` 멱등(`on conflict do nothing`, 대상 행 `for share` 확인). 담을 수 있는 것과 목록 모두 "지금 볼 수 있는 것"(승인된 모임, 데모가 아닌 회차). 탈퇴(본인·강제)하면 그 계정 즐겨찾기 삭제. 데모 403, 비회원 401.
- **화면:** 내 정보 > 프로필(중복 확인 버튼·소개 글자 수·태그), 공개 프로필 `/profiles/{id}`, 회차·모임 상세의 ☆ 즐겨찾기(`aria-pressed`), 내 활동 > 즐겨찾기(해제 버튼, 빈 목록 다음 행동 링크).

## 3단계 설계·구현 (2026-11-27)

- **권한:** `/api/dev/**` 는 개발자 역할만(`hasRole("DEVELOPER")`, 운영자만으로는 403). 개선 요청 접수는 로그인 회원(데모 403·비회원 401), 계정당 5건·IP 당 30건/로그인 제한 창(15분). 작성자는 자기 요청만, 남의 요청은 404(있는지 알리지 않음).
- **개선사항(`feedback`):** 종류(오류 신고·기능 제안·기타)·제목 100자·내용 2000자·보낸 화면 경로(질의·조각 버림). 상태 접수 → 진행 중·검증 요청·완료·보류, 검증 요청·보류는 사유 필수(앱 + DB check). 처리 내용 2000자, PR 링크는 `https://github.com/` 만. 상태 변경은 행 잠금 + `version` 비교(낡으면 409 `FEEDBACK_CHANGED`), 바뀌면 `feedback_history` 기록과 작성자 알림(`FEEDBACK_STATUS`). 작성자가 탈퇴해도 요청은 남고 '탈퇴한 회원'으로 보인다.
- **에러 로그(`error_group`):** 다른 처리기가 처리하지 않은 서버 예외(마지막 순서의 `UnexpectedErrorResolver`)와 화면 오류 보고(`POST /api/errors`, 누구나, IP 당 60건/창)를 지문(출처 + 숫자를 지운 메시지 + 경로 틀 SHA-256)별 한 행으로 모아 횟수·처음/마지막 시각·배포 버전(`RENDER_GIT_COMMIT`, 화면은 `NEXT_PUBLIC_VERCEL_GIT_COMMIT_SHA`)·마지막 추적 ID 만 둔다. 4xx(사용자 실수)는 모으지 않는다. 서버 500 응답은 `{code: INTERNAL_ERROR, traceId}` + `X-Trace-Id` 헤더(이전에는 스프링 기본 오류 응답).
- **가림(`ErrorMasking`, 저장·전송 전):** 비밀번호·비밀·토큰·쿠키·세션 이름 뒤 값, Bearer, JWT, 이메일, 휴대전화, 24자 이상 영숫자 덩어리(UUID 포함). 메시지는 한 줄 300자. 경로는 질의·조각 제거, 숫자·UUID 조각을 `:id` 로. 요청·응답 본문(글·채팅)은 받지 않는다.
- **알림·샘플링:** 새 오류이거나 마지막 알림 1시간이 지난 오류만 개발자에게 알림(`ERROR_ALERT`) + Sentry 전송. DB 횟수는 매번 센다(묶음 갱신이라 추가 샘플링 없음). 화면은 같은 오류 1번·화면 적재당 최대 5건만 보낸다.
- **보관:** 마지막으로 본 지 30일이 지난 묶음은 1시간마다 지운다(알림은 FK cascade).
- **Sentry:** `SENTRY_DSN` 이 있을 때만 envelope API 로 가린 요약 1건을 비동기 전송(SDK·새 의존성 없음, 실패해도 무시). 에러 로그 화면 링크는 `SENTRY_ISSUE_URL_TEMPLATE`(`{eventId}` 자리 채움)이 있을 때만. 둘 다 비어 있으면 자체 DB 요약만(지금 상태, 월 0원).
- **화면:** 내 활동 > 개선 요청(보내기·내 요청·상세와 상태 기록), 개발 묶음(개발자만): 개선사항 관리(검색·상태·종류 필터, 펼쳐서 상태·사유·처리 내용·PR 링크), 에러 로그(검색어·출처). 화면 오류 자동 보고(`ErrorReporter`).

## 4단계 설계·구현 (2026-11-27)

- **지표 정의는 그대로:** API(`GET /api/operator/usage`)와 현재 데이터(지금 남은 행)·누적 사용량(성공 횟수, `usage_daily`) 정의, 일반·데모 체험 구분을 바꾸지 않았다. 백엔드 변경 없음, 화면만 바꿨다.
- **현재 수치:** 항목별 카드(큰 수 일반, 작은 수 데모 체험) + 같은 값의 표.
- **누적 사용량 표 ↔ 차트:** `표`·`차트` 토글 버튼(`aria-pressed`), 고른 보기는 이 브라우저에 기억(localStorage, 못 쓰면 표). 표 보기는 기존 합계·날짜별 표. 차트 보기는 고른 지표의 일별 추이 선 차트(기록 없는 날 0으로 채움) + 지표별 합계 가로 막대 차트.
- **차트 방식 결정:** 의존성에 차트 라이브러리가 없어(Next·React·Zustand 만) 새로 들이지 않고 SVG 로 직접 그렸다. 근거: 번들 0KB, 서버·클라이언트 렌더 차이 없음, 접근성 요소를 직접 정함. Recharts 는 수십 KB 이상 추가되고 SVG 의 이름·설명을 따로 붙여야 하며, Chart.js 는 canvas 라 스크린 리더가 읽을 대체 수단을 따로 만들어야 한다.
- **접근성:** 차트는 `role="img"` 이고 이름에 기간·일반 합계·가장 많은 날·데모 합계를 글자로 담는다. 일반은 실선·원·채운 막대, 데모 체험은 점선·네모·빗금 막대로 색 말고도 구분. 차트 바로 아래 합계와 날짜별 수치 표(펼치기), 막대 차트 아래 합계 표가 같은 수치를 보인다. 좁은 화면에서는 차트 최소 너비 560px, 차트 상자 안에서만 가로 스크롤(화면 전체 가로 넘침 0).
- **상태:** 불러오는 중(`role=status`), 빈 기간("이 기간에는 기록이 없어요."), 오류(안내 + 다시 시도).

## 5단계 설계·구현 (2026-11-27)

- **정책:** 아이디·비밀번호로 로그인한 상태에서만 소셜 계정을 연결한다(소셜로 새 가입 없음). 소셜 로그인은 이미 연결된 계정에만, 정지 계정은 거부. 한 소셜 계정은 한 회원(`unique(provider, provider_user_id)`), 회원당 공급자 하나(`unique(account_id, provider)`). 해제는 비밀번호 재확인(로그인과 같은 시도 제한). 비밀번호 로그인은 늘 남아 로그인 수단이 없어지지 않는다. 데모는 연결 불가.
- **스키마(V19):** `social_account(account_id FK cascade, provider KAKAO|NAVER, provider_user_id, linked_at)`. 이메일·닉네임·액세스 토큰 저장 안 함. 탈퇴(본인·강제)는 계정 행을 익명화해 남기므로 연결 행을 탈퇴 처리에서 지운다.
- **흐름:** 브라우저가 `/api/auth/oauth/{kakao|naver}/{link|login}/start` 로 이동 → 서버가 32바이트 무작위 state 를 세션(운영 JDBC 세션, 직렬화 가능)에 10분·최대 5개 보관하고 공급자 인가 주소로 302(동의 항목 scope 없음) → 공급자가 `/api/auth/oauth/{provider}/callback` 으로 돌려보냄 → state 를 꺼내며 지움(일회용), 없음·만료·공급자 불일치면 `OAUTH_STATE_INVALID` → 서버 간 코드 → 토큰 → 사용자 id(토큰은 그 호출 안에서만) → 연결 또는 로그인 → 화면 주소로 302(`?linked=` / `?error=코드`). 코드·state·토큰은 로그에 남기지 않는다.
- **콜백 주소:** 조정 메모의 'Render 백엔드 주소 기준' 대신 **Vercel 주소**(`https://waiting-mauve.vercel.app/api/auth/oauth/{provider}/callback`)로 정했다. 세션 쿠키가 Vercel 도메인에 있어 Render 로 바로 오면 state 를 확인할 수 없기 때문. 프록시는 이미 `redirect: "manual"` 이라 302 를 그대로 전달한다(로컬 실제 브라우저로 확인).
- **설정:** 공급자마다 클라이언트 id(네이버는 시크릿도)와 `OAUTH_REDIRECT_BASE` 가 있을 때만 켜진다. 없으면 목록 false, 화면 '준비 중', 로그인 화면 버튼 없음. 앱 등록·환경 변수는 [설정 안내](../social-login-setup.md).
- **화면:** 내 정보 > 로그인 계정 연결(연결됨·연결일, 연결하기 링크, 비밀번호 확인 해제, 준비 중), 로그인 화면 '카카오로 로그인'·'네이버로 로그인'(켜진 공급자만), 콜백 결과 안내.

## 6단계 설계·구현 (2026-11-27)

- **권한:** 승인된 모임의 지금 회원만 읽고 쓴다. 요청마다 `ClubRoles` 로 가입·역할을 DB 에서 다시 읽어 모임 탈퇴·내보내기(회원 행 삭제) 직후 다음 요청부터 403 `CLUB_MEMBERS_ONLY`. 서비스 운영자도 회원이 아니면 못 본다(채팅은 회원끼리의 대화라 게시판의 운영자 열람 정책을 넓히지 않았다). 내보내기(강퇴) 화면·API 는 아직 없어 테스트는 회원 행 삭제로 대신했다.
- **메시지:** 1000자(앞뒤 공백 제거 뒤 1자 이상), 계정별 전송 속도 제한 10초 5건(모든 모임 합산, `CHAT_MAX_PER_WINDOW`·`CHAT_WINDOW`) 넘으면 429 `CHAT_TOO_FAST`. 본인 또는 모임 운영자가 지우면 내용을 비우고 삭제 표시만 남긴다(다시 지우면 그대로 성공). 모임이 지워지면 FK cascade 로 함께 지워진다.
- **스키마(V20):** `club_chat_message(club_id FK cascade, author_account_id, body null=삭제, seq, created_at, deleted_at, deleted_by)` + 시퀀스 `club_chat_seq`. `seq` 는 넣을 때와 지울 때 새로 받는 변경 순번.
- **전송 방식 결정: 커서 기반 증분 폴링(3초, 화면이 보일 때만).** 근거:
  - Vercel 프록시(`/api/[...path]`)는 응답 본문을 다 받은 뒤 넘기고 함수 실행 시간 한도(90초)가 있어, SSE·롱폴링 같은 긴 연결은 프록시를 고치거나 끊김 처리를 따로 해야 한다.
  - Render 무료 인스턴스는 잠들고 다시 시작하면 연결이 모두 끊긴다. 짧은 요청은 기존 '깨우는 중' 재시도(503·Retry-After)를 그대로 탄다.
  - 새 서비스·의존성 없이 기존 `useVisiblePolling`(탭 숨김 시 멈춤, 복귀·온라인 시 즉시 조회)을 재사용한다.
  - 비용: 화면을 연 사람마다 3초에 1회 가벼운 조회(인덱스 `(club_id, seq)`). 모임 채팅 규모에서는 무료 한도 안.
- **순서·누락 없음:** 표시 순서는 id. 이어 받기는 `GET ?after=cursor` 가 seq 가 더 큰 변경(새 메시지·삭제)을 seq 순으로 최대 200건 주고 다음 cursor 를 준다(더 있으면 `hasMore`). 같은 모임의 쓰기(보내기·지우기)는 모임 행 공유 잠금 뒤 모임 단위 advisory 잠금으로 한 줄로 서서, 커밋 순서 = seq 순서다. 그래서 어떤 seq 를 본 뒤 더 작은 seq 가 늦게 커밋되어 빠지는 일이 없다. 처음 조회는 최근 50건과 cursor 를 한 스냅숏(REPEATABLE READ)에서 읽는다. 재접속·탭 복귀는 마지막 cursor 로 이어 받는다.
- **화면:** 모임 안 메뉴 '채팅'(회원에게만), `role="log"` 목록(새 메시지 알림은 polite), 맨 아래 근처일 때만 따라 내려가기, Enter 보내기·Shift+Enter 줄바꿈·한글 조합 중 보내지 않음, 1000자 표시, 삭제 버튼(본인·모임 운영자), 회원이 아니게 되면 안내로 바뀜.
- **미구현(범위 밖):** 이전 메시지 더 불러오기(최근 50건만), 읽음 표시, 첨부, 알림.

## 7단계 설계·구현 (2026-11-27)

- **저장소:** 사용자가 아직 고르지 않아 추천안대로 **Neon DB(bytea) 저장**으로 진행했다. 바이트는 `PhotoStorage` 인터페이스(`put`·`get`·`delete`) 뒤에 두고 지금 구현은 `DbPhotoStorage`(`photo_object` 표) 하나다. Supabase Storage 등으로 바꾸면 이 구현만 바꾸면 된다(그때는 저장이 트랜잭션 밖이라 실패·고아 파일 재처리 작업 표가 필요하다). 병합 전 사용자 선택에 따라 바뀔 수 있다.
- **권한:** 승인된 모임의 지금 회원만 목록·이미지 보기·올리기(요청마다 `ClubRoles` 재확인, 탈퇴 직후 403). 본인 또는 모임 운영자 삭제. 모임 삭제 시 `club_photo`·`photo_object` FK cascade.
- **형식·크기:** 확장자·Content-Type 이 아니라 **바이트 시그니처**로 JPEG(FFD8FF)·PNG(89 50 4E 47 …)·WebP(RIFF…WEBP)만. 원본 5MB(multipart 한도와 서비스 둘 다), 디코드 전에 픽셀 수 4천만 상한(압축 폭탄 방지), 깨진 파일 400 `PHOTO_INVALID`.
- **저장본:** EXIF 방향(1~8)을 픽셀에 반영한 뒤 긴 변 1600px JPEG(품질 0.85) + 썸네일 400px(0.8)를 **새로 인코딩**한다. 새 JPEG 에는 EXIF(위치 포함)가 없다. 투명 PNG 는 흰 바탕. 원본은 저장하지 않는다.
- **상한:** 계정별 업로드 10분 10장(`PHOTO_MAX_PER_WINDOW`·`PHOTO_WINDOW`, 429), 모임당 200장(409 `PHOTO_CLUB_FULL`), 전체 저장 용량 300MB(Neon 무료 0.5GB 안, 409 `PHOTO_STORAGE_FULL`). 장수·용량 확인과 넣기는 모임 행 공유 잠금 + 전체 용량 advisory 잠금 안에서 해 동시 업로드에도 상한을 넘지 않는다(이미지 처리는 잠금 밖).
- **응답:** 이미지는 회원 확인 뒤 `Cache-Control: private, max-age=300`, `X-Content-Type-Options: nosniff`, `image/jpeg`.
- **의존성:** JDK 17 ImageIO 에는 WebP 가 없어 `com.twelvemonkeys.imageio:imageio-webp:3.12.0`(읽기만, 쓰기는 모두 JDK JPEG)을 더했다. 함께 들어오는 jar 6개 합계 약 564KB(imageio-webp 80KB, imageio-core 120KB, imageio-metadata 84KB, common-lang 122KB, common-io 92KB, common-image 80KB). 라이선스 BSD-3.
- **프록시 한도:** Vercel 함수 요청 본문 한도가 4.5MB 라, 화면은 4MB 넘는 사진을 브라우저에서 긴 변 2400px JPEG 로 줄여 보낸다(최종 처리·EXIF 제거는 서버). `api()` 에 FormData 전송을 더했다.
- **화면:** 모임 안 메뉴 '사진첩'(회원에게만), 썸네일 격자(눌러 원본), 장수 표시, 사진 올리기(키보드로도 고를 수 있는 파일 입력), 형식 안내, 삭제, 더 보기(30장씩), 회원이 아니면 안내.
- **알려진 한계:** CMYK JPEG 는 JDK 기본 리더가 못 읽어 `PHOTO_INVALID` 로 거부된다(필요하면 TwelveMonkeys imageio-jpeg 추가). HEIC(아이폰 기본)는 받지 않는다(화면이 안내).

## 상태 기록

| 단계 | 상태 | PR | 로컬 검증 | 공개 검증 | 미검증 |
|---|---|---|---|---|---|
| 1 | 완료·공개 검증 통과 | PR #50 | 백엔드 731(새 `ServiceRoleApiTests` 6·`ServiceRoleConcurrencyTests` 20), e2e 106(새 `menu.spec.ts` 4, 메뉴 개편으로 기존 e2e 24개 이동 경로 수정), 프런트 단위 33, `tsc`·`eslint` 0 (2026-11-26, 로컬) | PR #50 병합·배포 `1ee25983`(Render 11:52 UTC). 비밀번호 없는 점검 5/5 ([기록](../../loadtest/results/2026-11-26-public-platform1-open.txt)). 로그인 점검 통과(사용자 실행 11:56 UTC: 자기 상승 403·개발자 부여 뒤 같은 세션 반영·운영 API 403·자기 변경 403·회수 즉시 반영, [기록](../../loadtest/results/2026-11-26-public-platform1.txt)), Neon SQL 0·1·2 확인 | 실제 iPhone Safari·스크린 리더, 공개 환경 역할 경합 |
| 2 | 배포 완료, 공개 검증 완료(비밀번호 없는 6/6, 로그인 재실행 통과, Neon SQL 0·0) | PR #51 | 백엔드 781(새 `ProfileNicknameApiTests` 5·`FavoriteApiTests` 4·`NicknameFavoriteConcurrencyTests` 40·`NicknameMigrationTests` 1, 금지어에 '개발자'가 들어가 `CommentApiTests` 닉네임 예시 1곳 변경), e2e 108(새 `profile-favorite.spec.ts` 2, 메뉴 '닉네임'→'프로필'), 프런트 단위 34, `tsc`·`eslint` 0. 공개 점검 스크립트 로컬 리허설 6/6·통과, 정리 SQL 0·0 ([기록](../../loadtest/results/2026-11-26-rehearsal-platform2.txt)) | PR #51 병합·배포 `69ed7e6d`(Render 18:37 UTC, V17 적용). 비밀번호 없는 점검 6/6 ([기록](../../loadtest/results/2026-11-26-public-platform2-open.txt)). 운영 DB V17 이관 확인(사용자 Neon SQL 실행 19:15 UTC): 닉네임 없는 일반 회원 0·대소문자 무시 중복 0·정규화 안 된 닉네임 0·`flyway_schema_history` 17 성공 true. 로그인 점검: 05:39 UTC 실행은 가입 제한 429 로 중단(A 탈퇴 204), 06:29 UTC 재실행 통과(닉네임 중복 409·금지어 400, 공개 프로필에 로그인 아이디 없음, 즐겨찾기 멱등·해제, 탈퇴 정리, [기록](../../loadtest/results/2026-11-27-public-platform2.txt)), Neon SQL 0·0 확인 | 실제 iPhone Safari·스크린 리더 |
| 3 | 배포 완료, 공개 점검 통과(비밀번호 없는 6/6, 로그인 통과) | PR #52 | 백엔드 793(새 `FeedbackApiTests` 4·`ErrorLogApiTests` 4·`ErrorMaskingTests` 3·`SentryForwardingTests` 1, 구현 전 12개 실패 확인. 예상하지 못한 오류가 500 JSON 으로 바뀌어 `CapacityRollbackTests`·`UsageCountTests` 의 예외 기대를 500 기대로 수정), e2e 110(새 `feedback-errors.spec.ts` 2), 프런트 단위 38, `tsc`·`eslint` 0. 공개 점검 스크립트 로컬 리허설 6/6·통과, 확인 SQL 0·2·가림 ([기록](../../loadtest/results/2026-11-26-rehearsal-platform3.txt)) | PR #52 병합·배포 `4e7d0427`(Render 19:19 UTC, V18 적용). 비밀번호 없는 점검 6/6 ([기록](../../loadtest/results/2026-11-26-public-platform3-open.txt), QA 오류 보고 1건은 30일 뒤 자동 삭제 또는 기록의 SQL 로 삭제). 로그인 점검 통과(사용자 재실행 2026-11-27 05:39 UTC: 운영자 혼자 개발 API 403, 남의 요청 404, 사유 없는 보류 400·사유 있는 보류 200, 작성자 화면·알림, 오류 메시지 가림·경로 틀, 정리 204, [기록](../../loadtest/results/2026-11-27-public-platform3.txt)). 05:59 UTC 추가 실행은 가입 제한으로 중단(A 탈퇴, 그 실행의 SQL 0·1 정상). 통과 실행(274·275)의 Neon SQL 은 사용자가 실행 완료를 알림(값 미첨부) | 실제 Sentry 전송(DSN 미설정, 로컬 가짜 서버로만 확인), 실제 iPhone Safari·스크린 리더, 공개 환경 알림 1시간 1회 |
| 4 | 구현·로컬 검증 완료, PR·공개 검증 대기 | (PR 대기) | 백엔드 변경 없음(793 그대로). 프런트 단위 43(새 `dashboard.test.mjs` 5, 구현 전 실패 확인), e2e 112(새 `dashboard.spec.ts` 2: 카드=표 값, 차트 전환·이름의 합계=표 합계·새로 고침 뒤 기억, 빈 기간, 오류 뒤 다시 시도), `tsc`·`eslint` 0. 스크린샷: 표·차트(데스크톱), 차트(모바일 어두운 테마, 가로 넘침 0) | 배포 뒤 공개 화면 확인(운영자 로그인 필요, 사용자) | 실제 스크린 리더로 차트 이름 읽기, 실제 iPhone Safari |
| 5 | 배포 완료, 공급자 켬(2026-11-27 07:50 UTC), 켜진 상태 공개 점검 8/8, 실제 계정 연결·로그인 확인은 사용자 대기 | PR #54 | 백엔드 821(새 `SocialAccountApiTests` 7·`SocialProviderDisabledTests` 1·`SocialLinkConcurrencyTests` 20, 가짜 공급자 서버, 구현 전 28개 실패 확인), e2e 114(새 `social.spec.ts` 2), 프런트 단위 45, `tsc`·`eslint` 0. 로컬 실제 브라우저로 프록시 경유 연결·소셜 로그인·해제 확인, 공개 점검 리허설 8/8 ([기록](../../loadtest/results/2026-11-27-rehearsal-platform5.txt)) | PR #54 병합·배포 `0a483e40`(Render 01:56 UTC, V19 적용). 꺼진 상태 점검 8/8 ([기록](../../loadtest/results/2026-11-27-public-platform5-open.txt)). 사용자가 환경 변수를 넣은 뒤(07:49 UTC) 재실행 8/8: 로그인 시작이 kauth.kakao.com·nid.naver.com 으로, state 있음·scope 없음, redirect_uri 가 Vercel 콜백 ([기록](../../loadtest/results/2026-11-27-public-platform5-on.txt)). 실제 계정 연결·소셜 로그인·해제는 사용자 확인 대기 | 실제 카카오·네이버 인가·토큰·사용자 API(가짜 서버로만 확인), 공급자 카드 등록 필요 여부(미확인), 네이버 검수 전 멤버 제한, 실제 iPhone Safari |
| 6 | 배포 완료, 공개 검증 완료(비밀번호 없는 5/5, 로그인 재실행 통과, Neon SQL 0·0·2) | PR #55 | 백엔드 831(새 `ClubChatApiTests` 5: 주고받기·이어 받기, 비회원·탈퇴 직후·내보내기 직후 403, 길이·속도 제한, 본인·운영자 삭제 표시, 모임 삭제 cascade / `ClubChatConcurrencyTests` 5회: 4명 동시 60건 보내는 동안 cursor 이어 받기가 모두 받음·id 순, 구현 전 10개 실패 확인), e2e 116(새 `club-chat.spec.ts` 2), 프런트 단위 47, `tsc`·`eslint` 0. 공개 점검 리허설 5/5·통과, 정리 SQL 0·0·2 ([기록](../../loadtest/results/2026-11-27-rehearsal-platform6.txt)) | PR #55 병합·배포 `cf7d8b2c`(Render 03:20 UTC, V20 적용). 비밀번호 없는 점검 5/5 ([기록](../../loadtest/results/2026-11-27-public-platform6-open.txt)). 로그인 점검 `public-platform6-check.mjs`: 05:27 UTC 실행은 운영자 로그인 401(비밀번호 입력 오류로 보임)로 시작 전 중단. 재실행 통과, Neon SQL 0·0·2 확인(사용자 보고 05:59 UTC, 재실행 출력은 미수신, [기록](../../loadtest/results/2026-11-27-public-platform6.txt)) | 공개 환경에서 여러 사람 동시 채팅·Render 재시작 중 이어 받기, 실제 iPhone Safari·스크린 리더 |
| 7 | 배포 완료(Neon 저장), 공개 검증 완료(비밀번호 없는 5/5, 로그인 통과, Neon SQL 0·0·0·2) | PR #56 | 백엔드 842(새 `ClubPhotoApiTests` 5: 줄인 JPEG·썸네일·private 캐시·nosniff, EXIF 위치 표지 제거·방향 반영, 시그니처(가짜 .jpg·GIF·깨진 파일 거부, PNG·WebP 허용), 5MB 초과 413, 권한·삭제·탈퇴 직후 403·모임 삭제 cascade / `ClubPhotoQuotaTests` 1: 속도 429·모임당 장수 409 / `ClubPhotoConcurrencyTests` 5회: 8명 동시 업로드에서 정확히 남은 3장만 저장·용량 상한 이하, 구현 전 11개 실패 확인), e2e 118(새 `club-photos.spec.ts` 2, 업로드가 Next 프록시 경유), 프런트 단위 48, `tsc`·`eslint` 0. 공개 점검 리허설 5/5·통과, 정리 SQL 0·0·0·2 ([기록](../../loadtest/results/2026-11-27-rehearsal-platform7.txt)) | PR #56 병합·배포 `63135600`(Render 04:59 UTC, V21 적용). 비밀번호 없는 점검 5/5 ([기록](../../loadtest/results/2026-11-27-public-platform7-open.txt)). 로그인 점검 통과(사용자 실행 05:28 UTC: 가입 전 403, 가짜 .jpg 400, EXIF JPEG 201·20×30, private·nosniff, 표지·EXIF 없음, 비회원 401, 삭제 뒤 404, 정리 204, [기록](../../loadtest/results/2026-11-27-public-platform7.txt)), Neon SQL 0·0·0·2 확인 | 실제 휴대폰 사진(큰 원본·HEIC·CMYK), Vercel 4.5MB 근처 업로드, 공개 환경 용량 경합, 실제 iPhone Safari·스크린 리더 |
