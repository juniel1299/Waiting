# 다음자리 — 면접 답변 초안

각 답변은 **선택 이유 → 구현 방식 → 검증 근거 → 한계** 순서다. 코드 위치는 `파일:줄` 이다 (2026-11-21 `main` 기준, PR #21 병합 `cda24d44`).

- **로컬 부하 환경:** Apple M4 Pro(12코어, 24GB), macOS 27.2, JDK 17.0.13, Spring Boot 4.1.2, Hikari 풀 10, PostgreSQL 16.16(docker). 부하 발생기·서버·DB 가 같은 기기다.
- **테스트 수:** 백엔드 306개·e2e 41개는 직전 실행 결과다 (2026-11-21, 커밋 `548023aa` 기준).

공개 데모: https://waiting-mauve.vercel.app · 시연: [/tour](https://waiting-mauve.vercel.app/tour)

---

## 1. 회차 단위 DB 잠금을 선택한 이유와 병목이 생길 조건

**선택 이유**

- 문제의 핵심은 "마지막 자리"처럼 같은 회차에서 충돌이 잦은 상황이다.
- 비관적 잠금은 충돌이 나도 재시도 없이 한 번에 정답(확정 또는 `409`)을 낸다.
- 잠금 단위를 회차로 잡으면 같은 회차 요청만 줄을 서고, 다른 회차끼리는 서로 막지 않는다.
- 낙관적 잠금은 충돌 때 재시도·실패 응답이 늘고, 분산 락은 무료 구성에 인프라가 하나 더 필요해 고르지 않았다.

**구현 방식**

- 회차 조회에 `PESSIMISTIC_WRITE` (`SELECT … FOR UPDATE`): [`StudySessionRepository.java:22`](../../backend/src/main/java/com/daumjari/studysession/StudySessionRepository.java).
- 예약은 계정 공유 잠금 → 회차 잠금 → 같은 트랜잭션에서 확정 수·대기 수 판단 순서다 ([`ReservationService.java:50-58`](../../backend/src/main/java/com/daumjari/reservation/ReservationService.java)).
- 잠금 순서는 항상 계정 → 회차 → 신청이라 교착이 생기지 않게 했다.

**검증 근거**

- [`ReservationConcurrencyTests`](../../backend/src/test/java/com/daumjari/ReservationConcurrencyTests.java): 두 계정이 마지막 1석에 동시 예약, 30회 반복, 매번 1명 확정.
- 로컬 부하 A: 정원 1 회차에 동시 예약을 단계적으로 늘린 결과 ([결과](../../loadtest/results/2026-11-21-local.md)). 모든 단계 확정 1건, 나머지 `409 SESSION_FULL`, 오류 0.

| 동시 예약 | 10 | 50 | 100 | 200 | 400 |
|---|---|---|---|---|---|
| p95 (ms) | 47 | 73 | 126 | 219 | 429 |
| 처리량 (req/s) | 211 | 662 | 742 | 858 | 870 |

**병목이 생길 조건**

- 같은 회차에 요청이 몰리면 잠금 대기로 줄을 서서 지연이 요청 수에 비례해 는다 (위 표의 p95 증가).
- 대기 신청 400건이 한 회차에 몰렸을 때 p95 는 749ms(B1)·660ms(C1)였다.
- **측정하지 않은 범위:**
  - 여러 회차에 동시에 퍼진 요청
  - 원격 DB(Neon) 왕복 지연이 잠금 보유 시간에 더해지는 경우
  - 운영 커넥션 풀 5개([`application-prod.yml:9`](../../backend/src/main/resources/application-prod.yml))에서 잠금 대기로 풀이 고갈되는 조건
  - 잠금 대기 타임아웃

**한계**

- 한 회차가 매우 인기면 그 회차 요청은 직렬화된다.
- 그 규모가 되면 회차별 대기열을 큐로 분리하거나 좌석 카운터를 원자적 갱신으로 바꾸는 방안을 검토할 수 있다 (현재 미구현·미측정).

## 2. 취소와 승격을 같은 트랜잭션에서 처리하는 이유

**선택 이유**

- 취소와 승격을 나누면 두 가지 틈이 생긴다:
  - 그 사이에 신규 예약이 빈자리를 가져가 대기자가 밀린다.
  - 승격만 실패해 "취소됐는데 아무도 확정 안 된" 좌석이 생긴다.
- 같은 트랜잭션이면 둘 다 일어나지 않는다.

**구현 방식**

- 취소는 회차 잠금 → 신청 잠금 → 취소 → 확정 좌석이 비었으면 첫 대기자(id 순) 승격 순서다 ([`ReservationService.java:86-98`](../../backend/src/main/java/com/daumjari/reservation/ReservationService.java)).
- `WaitlistPromoter.promoteNext` 는 `Propagation.MANDATORY` ([`WaitlistPromoter.java:22`](../../backend/src/main/java/com/daumjari/reservation/WaitlistPromoter.java)). 호출한 트랜잭션 밖에서는 예외가 나서, 승격이 따로 커밋될 길이 없다.
- 정원 증설도 같은 승격 객체로 늘어난 자리만큼 확정한다 ([`StudySessionService.java:109`](../../backend/src/main/java/com/daumjari/studysession/StudySessionService.java)).
- 대기자가 있으면 빈자리가 보여도 신규 예약은 `409` 다 (`ReservationService.java:58`).

**검증 근거**

- [`PromotionRollbackTests`](../../backend/src/test/java/com/daumjari/PromotionRollbackTests.java): 승격 직후 예외를 주입하면 취소도 롤백. 트랜잭션 밖 호출은 거부.
- [`WaitlistConcurrencyTests`](../../backend/src/test/java/com/daumjari/WaitlistConcurrencyTests.java): 취소 vs 신규 예약·신규 대기 경합 각 30회.
- 로컬 부하 B·C (B2: 확정자 5명 취소 + 신규 요청 혼합, C2: 정원 1→6 증설·축소·취소 혼합):
  - 승격된 사람 = 대기 순번 1~k, 모든 단계 일치.
  - SQL 검사 "대기 추월"·"승격 누락" 0행.
  - [B](../../loadtest/results/2026-11-21-local.md), [C](../../loadtest/results/2026-11-21-local-capacity.md)
- 공개 배포: 운영 회차(정원 1)에서 A 탈퇴(=취소) → B 대기 1번이 4.9초 뒤 화면에서 `예약 확정`, 1회 ([기록](../../README.md#공개-배포-검증-2026-11-21)).

**한계**

- 승격까지 같은 잠금 안에서 하므로 취소 요청의 잠금 보유 시간이 조금 길어진다.
- 승격 알림은 푸시가 아니라 상대 화면의 폴링으로 전달된다.
- 상태 변경 이력을 남기지 않아 사후 감사가 어렵다.

## 3. 같은 계정의 중복 예약 요청에 기존 예약을 반환하는 이유

**선택 이유**

- 더블 탭, 네트워크 재시도, 응답 유실 뒤 다시 누르기처럼 같은 사람이 같은 회차에 같은 요청을 다시 보내는 건 흔하다.
- 이를 오류(`409`)로 돌려주면 사용자는 "예약이 됐는지" 모른다. 기존 신청을 그대로 돌려주면 결과가 같아 멱등하게 다룰 수 있다.

**구현 방식**

- 회차 잠금 아래에서 같은 (회차, 계정)의 활성 신청이 있으면 새로 만들지 않고 그 신청을 반환한다 ([`ReservationService.java:54`](../../backend/src/main/java/com/daumjari/reservation/ReservationService.java), 대기는 `:74`).
- 컨트롤러는 새로 만들면 `201`, 기존이면 `200` ([`ReservationController.java:41-42`](../../backend/src/main/java/com/daumjari/reservation/ReservationController.java)).
- 마지막 방어선은 부분 unique 인덱스 `ux_reservation_active` ([`V2__core_schema.sql`](../../backend/src/main/resources/db/migration/V2__core_schema.sql)).

**검증 근거**

- [`ReservationApiTests`](../../backend/src/test/java/com/daumjari/ReservationApiTests.java) `duplicateReserveDoesNotAddActiveReservation`: 두 번째 요청 `200`, 활성 신청 1건.
- [`ReservationConcurrencyTests`](../../backend/src/test/java/com/daumjari/ReservationConcurrencyTests.java) `sameAccountDuplicateRaceKeepsOneActiveReservation`: 같은 계정 동시 2건 → `201` 과 `200`, 같은 id, 활성 1건 (30회 반복).
- [`SchemaConstraintTests`](../../backend/src/test/java/com/daumjari/SchemaConstraintTests.java): 같은 계정 두 번째 활성 신청을 DB 가 거부.
- 부하 뒤 SQL "활성 신청 중복" 0행.

**한계**

- 요청 단위 멱등 키(Idempotency-Key)가 아니라 "(회차, 계정)의 활성 신청" 기준이다.
- 취소한 뒤 다시 예약하면 새 신청이다 (의도, `canReserveAgainAfterCancel`). 대기를 취소했다가 다시 걸면 맨 뒤로 간다.
- 그래서 "취소 직후 늦게 도착한 옛 예약 요청"은 새 신청으로 처리될 수 있다. 클라이언트가 변경 요청을 자동 재시도하지 않는 이유 중 하나다 (7번).

## 4. 5초 폴링의 선택 이유와 SSE 전환을 검토할 기준

**선택 이유**

- 내 상태가 바뀌는 건 "다른 사람이 취소했을 때"뿐이라 드물고, 몇 초 늦어도 된다.
- 무료 서버는 15분 무요청이면 잠들고, 프런트 프록시(Vercel 함수)는 요청당 실행 시간 제한(`maxDuration` 90초)이 있다. 그래서 연결을 오래 붙잡는 방식보다 짧은 요청이 단순하다.
- 숨긴 탭은 요청 0건이라 낭비가 적다.

**구현 방식**

- [`useVisiblePolling.ts`](../../frontend/src/lib/useVisiblePolling.ts): 보이고 온라인일 때만 5초(`:6`) 조회. `visibilitychange`·`online` 때 즉시 한 번 조회하고 다시 시작(`:53`).
- 늦게 온 옛 응답은 요청 순번으로 버린다 ([`SessionDetail.tsx:56-58`](../../frontend/src/app/sessions/[id]/SessionDetail.tsx)).

**검증 근거**

- [`auto-refresh.spec.ts`](../../frontend/e2e/auto-refresh.spec.ts), 로컬, 각 10회 ([측정](../../README.md#회차-상세-자동-갱신)):
  - 다른 사람 취소 → 내 화면 확정 4799~4822ms
  - 탭 복귀 → 반영 25~30ms
  - 네트워크 복구 → 반영 24~28ms
- 계산(측정 아님): 상세를 보는 사람 1명당 0.2 요청/초. 100명이면 초당 20건.

**SSE 전환을 검토할 기준 (제안, 측정 근거 아님)**

- 반영 지연(최대 5초 + 왕복)이 요구사항과 맞지 않을 때. 예: 확정 뒤 짧은 시간 안에 결제·응답해야 하는 흐름이 생길 때.
- 동시 시청자 × 0.2 요청/초가 백엔드·DB 여력을 크게 차지할 때 (측정해서 판단해야 함).
- 백엔드가 상시 기동 인스턴스로 바뀌고, 프록시를 거치지 않거나 긴 연결을 허용하는 경로가 생길 때. 지금 구조에서는 Vercel 함수 제한 때문에 SSE 를 프록시로 오래 유지하기 어렵다.
- 전환하더라도 폴링은 재연결 실패 시 대체 경로로 남기는 것이 안전하다.

**한계**

- 즉시성이 없다.
- 폴링 부하는 계산만 했고 공개 환경에서 측정하지 않았다.

## 5. 프록시 전달 헤더 위조를 막는 방식

**선택 이유**

- 백엔드가 프록시 뒤에 있으면 모든 요청이 프록시 IP 로 보여 IP 기반 한도가 무의미해진다.
- 그렇다고 `X-Forwarded-For` 를 그대로 믿으면 클라이언트가 값을 바꿔 한도를 피할 수 있다.
- "누가 보낸 헤더를 믿을지"를 정해야 한다.

**구현 방식**

- **로컬 프런트 서버:** 클라이언트가 보낸 전달 헤더를 버리고 실제 접속 주소로 다시 쓴다 ([`server.mjs:39`](../../frontend/server.mjs)).
- **공개 배포:** Vercel 이 `x-forwarded-for` 를 자기가 본 주소로 덮어쓴다는 전제로 첫 값을 쓴다 ([`route.ts:27-28`](../../frontend/src/app/api/[...path]/route.ts)).
- **프록시 → 백엔드:**
  - 클라이언트 IP 를 `X-Daumjari-Client-IP` 로, 공유 비밀을 `X-Daumjari-Proxy-Token` 으로 보낸다.
  - 백엔드는 토큰을 상수 시간 비교해 맞을 때만 그 IP 를 쓰고, 아니면 `403 PROXY_REQUIRED` ([`ProxyTokenFilter.java:45-54`](../../backend/src/main/java/com/daumjari/config/ProxyTokenFilter.java)).
  - 운영은 `forward-headers-strategy: none` ([`application-prod.yml:16`](../../backend/src/main/resources/application-prod.yml)).
- **로컬 백엔드:** Tomcat RemoteIpValve 가 loopback 프록시에서 온 요청만 신뢰한다 ([`application.yml:28-31`](../../backend/src/main/resources/application.yml)).

**검증 근거**

- [`ProxyTokenTests`](../../backend/src/test/java/com/daumjari/ProxyTokenTests.java): 토큰 없음·틀림 403, 토큰 있을 때만 IP 헤더 사용, 위조 XFF 무시.
- [`ForwardedHeaderTrustTests`](../../backend/src/test/java/com/daumjari/ForwardedHeaderTrustTests.java): 위조 XFF 로 한도 키를 돌릴 수 없음.
- [`proxy.test.mjs`](../../frontend/test/proxy.test.mjs)
- 공개 배포 2026-11-20 실측: Mac IP 에서 데모 시작 11번째 `429` + `Retry-After`. 같은 시각 휴대폰 모바일 데이터에서는 정상 시작. 즉 IP 별로 따로 셌다.

**한계**

- 공개 배포의 IP 정확성은 Vercel 의 헤더 덮어쓰기 동작에 기댄다 (1회 실측).
- 같은 NAT·통신사 IP 를 쓰는 사용자들은 한도를 함께 쓴다.
- 공유 비밀은 수동 교체다.

## 6. 탈퇴와 예약·대기 처리의 정합성

**선택 이유**

- 탈퇴 도중에 같은 계정의 예약 요청이 끼면 "탈퇴한 계정에 활성 신청이 남는" 상태가 생길 수 있다.
- 탈퇴로 빈 자리는 대기자에게 넘어가야 한다.

**구현 방식**

- 탈퇴는 비밀번호 재확인(시도는 로그인 제한에 함께 셈, [`WithdrawalService.java:52`](../../backend/src/main/java/com/daumjari/auth/WithdrawalService.java)) 뒤 한 트랜잭션에서 처리한다:
  1. 계정 `FOR UPDATE` (`:54`)
  2. 활성 신청이 있는 회차를 id 순으로 잠금
  3. 시작 전 확정은 취소 + 승격, 대기는 취소, 시작된 회차의 확정은 기록으로 유지 ([`ReservationService.java:109`](../../backend/src/main/java/com/daumjari/reservation/ReservationService.java))
  4. 익명화
- 예약·대기는 계정 행을 `FOR SHARE` 로 먼저 잠근다 (`ReservationService.java:144`). 그래서 탈퇴와 겹치면 탈퇴 뒤에 처리돼 `401` 이 된다. 잠금 순서는 계정 → 회차로 통일했다.
- 다른 기기 세션은 요청마다 탈퇴 여부를 확인하는 필터가 끊는다 ([`WithdrawnAccountFilter.java:36`](../../backend/src/main/java/com/daumjari/auth/WithdrawnAccountFilter.java)).

**검증 근거**

- [`WithdrawalConcurrencyTests`](../../backend/src/test/java/com/daumjari/WithdrawalConcurrencyTests.java): 탈퇴 vs 예약 동시 20회 → 탈퇴 계정 활성 신청 0, 대기자 승격 1회.
- [`WithdrawalRollbackTests`](../../backend/src/test/java/com/daumjari/WithdrawalRollbackTests.java): 승격 실패 주입 시 익명화·취소 전부 롤백.
- [`WithdrawalApiTests`](../../backend/src/test/java/com/daumjari/WithdrawalApiTests.java)
- 공개 배포 2026-11-21: 탈퇴 뒤 다른 브라우저 세션 `401`, 같은 아이디 재가입, A 탈퇴 → B 승격 4.9초.

**한계**

- 요청마다 계정 상태를 DB 로 한 번 더 확인한다 (비용 측정 안 함).
- 계정 행은 FK 때문에 지우지 않고 익명화한다.
- 공개 운영자 화면의 "탈퇴한 회원" 표시는 **확인 대기**(운영자 비밀번호 미사용)다.

## 7. 무료 서버의 절전 대응과 재시도 가능한 요청의 기준

**선택 이유**

- Render Free 는 15분 무요청이면 잠들어 첫 요청이 수 분 걸린다.
- 처음에는 Vercel 함수 제한(90초)에 걸려 `504` 가 났고, 첫 화면 버튼도 48초 뒤에야 보였다 (2026-11-20 실측).
- 사용자가 무슨 일인지 알고 기다리거나 다시 시도할 수 있어야 한다.

**구현 방식**

- **첫 화면:** 로그인 확인을 기다리지 않는다.
- **프록시:** 85초(`maxDuration − 5`)에 업스트림을 끊고 `503 + Retry-After: 5` ([`route.ts:11`, `:62`](../../frontend/src/app/api/[...path]/route.ts)).
- **브라우저:** 502/503/504·연결 실패에 안내를 띄우고 `Retry-After` 간격으로 최대 5분 재시도한다 ([`api.ts:73-74`, `:107`](../../frontend/src/lib/api.ts)).
- **재시도 기준:**
  - 조회(GET)는 기본 재시도: `retryWhileWaking ?? !MUTATING.has(method)` ([`api.ts:198`](../../frontend/src/lib/api.ts)).
  - 변경 요청 중에는 데모 시작만 명시적으로 켠다 ([`DemoStartButton.tsx:44`](../../frontend/src/components/DemoStartButton.tsx)). 두 번 처리돼도 남는 체험은 아무도 쓰지 않고 30분 뒤 정리되며, IP 한도 1회만 더 쓴다.
  - 예약·대기·취소는 재시도하지 않는다. 서버는 같은 계정 중복을 기존 신청으로 돌려주지만(3번), 사용자가 그사이 취소했다면 늦은 재시도가 새 신청을 만들 수 있어서다. 오류를 보여 주고 사용자가 다시 판단하게 했다.
- 기다리는 동안 볼 `/tour`(백엔드 호출 0)를 새 탭으로 연결했다.

**검증 근거**

- [`proxy.test.mjs`](../../frontend/test/proxy.test.mjs): 제한 시간 초과 → 503 + Retry-After.
- [`cold-start.spec.ts`](../../frontend/e2e/cold-start.spec.ts): 504·502·연결 실패 재시도, 대기 신청 503 은 재시도 안 함.
- [`demo-button.spec.ts`](../../frontend/e2e/demo-button.spec.ts): 5분 상한 초과 뒤 `다시 시도하기` 복구.
- 공개 배포 2026-11-21 04:20 UTC, 약 55분 무요청 뒤, 1회:
  - 첫 `/api` 응답 87.3초에 503, 클릭부터 체험 화면까지 170.9초.
  - 중복 시작 요청 0.
  - [측정 조건과 한계](../../README.md#절전-후-체험-시작-2분-51초-조건과-한계)

**한계**

- 대기 시간 대부분은 Render 기동이라 코드로 줄일 수 없고, 측정은 1회라 날마다 다를 수 있다.
- 5분 상한 초과는 시뮬레이션으로만 확인했다.

## 8. 부하 테스트로 확인한 범위와 추가 검증이 필요한 부분

**확인한 범위** (로컬 실제 PostgreSQL, Node 내장 fetch 스크립트, [`loadtest/`](../../loadtest/README.md))

- **A 마지막 자리:** 정원 1 회차에 동시 예약 10·50·100·200·400.
- **B 취소·승격:** 대기 N명 + 확정자 5명 취소·신규 예약·신규 대기 혼합.
- **C 정원 증설:** 대기 N명 + 증설 1→6·축소·취소·신규 요청 혼합.
- **결과:** 모든 단계에서 예상된 응답만(`201`/`200`/`409 SESSION_FULL`/`409 CAPACITY_BELOW_CONFIRMED`), 예상 못한 오류 0.
  - SQL 4종(정원 초과·활성 중복·대기 추월·승격 누락) 0행.
  - 승격 = 대기 순번 순서.
  - 예: 대기자 400명 상태의 혼합 요청(C2, 203건) 636 req/s, p95 288ms.
  - [A·B 결과](../../loadtest/results/2026-11-21-local.md) · [C 결과](../../loadtest/results/2026-11-21-local-capacity.md)
- **검사식 자체 검증:** 일부러 만든 위반을 SQL 이 잡는지 셀프테스트, 기대 승격자를 틀리게 바꾸면 C 가 `FAIL` 하는지 먼저 확인했다.
- **공개 배포:** 부하 없이 소수 요청만 (21요청, p95 514ms, 서버가 깨어 있던 상태, [기록](../../loadtest/results/2026-11-21-public-smoke.txt)).

**추가 검증이 필요한 부분 (미측정)**

- 서버를 여러 대로 띄운 경우: 정합성은 DB 잠금이라 유지될 것으로 보지만 처리량·잠금 경합은 재지 않았다.
- 공개 환경(Render Free + Neon Free) 부하: 무료 서버라 의도적으로 하지 않았다. 원격 DB 왕복 지연과 운영 커넥션 풀 5개에서의 동작은 모른다.
- Neon 무료 커넥션 한도·풀러 경유 시 동작.
- 장시간(soak) 실행: 세션·로그인 시도 테이블 증가, 정리 작업 중 부하.
- 여러 회차에 퍼진 혼합 트래픽, 폴링 부하(시청자 수 × 0.2 요청/초)의 실측.
- 부하 발생기를 서버와 다른 기기에서 돌린 측정.
