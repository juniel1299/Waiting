# 실시간 예약 대기열

**다음자리** — 오프라인 개발 스터디 회차 예약·취소 대기 서비스.

## 구조

```
.
├── frontend/           # Next.js (App Router) + TypeScript + ESLint
├── backend/            # Java 17, Spring Boot 4.1, Gradle (Kotlin DSL)
│   └── src/main/resources/db/migration/   # Flyway 마이그레이션
├── docker-compose.yml  # 로컬 PostgreSQL 16
└── .env.example
```

## 사전 요구사항

- Node.js 20.9+ / npm
- JDK 17
- Docker (Docker Desktop) + Docker Compose v2

## 실행

```bash
# DB 기동 / 종료 (데이터는 볼륨 daumjari-pgdata 에 유지)
docker compose up -d
docker compose down
docker compose down -v   # 볼륨까지 삭제

# backend (http://localhost:8080, 헬스체크 /actuator/health)
cd backend
./gradlew bootRun
./gradlew test           # Testcontainers 사용: Docker 실행 중이어야 함 (없으면 스킵)
./gradlew build

# frontend (http://localhost:3000, /api 는 BACKEND_URL 로 프록시)
cd frontend
npm install
npm run dev                                   # node server.mjs --dev, BACKEND_URL 기본값 http://localhost:8080
BACKEND_URL=http://localhost:8080 npm run build && npm run start   # node server.mjs (전달 헤더 정리, 아래 "클라이언트 IP 판별")
npm run lint

# 브라우저 e2e (Playwright, 실제 백엔드 + 프런트 프로덕션 빌드)
docker compose up -d                          # 루트에서 먼저
cd frontend
npm run test:e2e                              # bootRun·next start 를 자동으로 띄움
```

### 로컬에서 화면 확인 순서

1. 루트: `docker compose up -d`
2. `backend`: `./gradlew bootRun` (local 프로필, 시드 계정 생성)
3. `frontend`: `npm install` 후 `npm run dev`
4. http://localhost:3000 → 시드 계정으로 로그인 (참가자 `alice`, 운영자 `operator`)

e2e 는 3000·8080 포트에 이미 뜬 서버가 있으면 그 서버를 재사용한다 (CI 환경변수가 있으면 새로 띄움). 개발 서버를 띄워 둔 채 최신 코드로 검증하려면 다른 포트를 쓴다:

```bash
E2E_PORT=3100 E2E_BACKEND_PORT=18080 npm run test:e2e
```

## 화면

| 경로 | 대상 | 내용 |
|---|---|---|
| `/login` | 모두 | 시드 계정 로그인. 성공 시 참가자 `/sessions`, 운영자 `/operator` |
| `/sessions` | 로그인 | 회차 목록, 잔여석·상태·내 예약 표시 |
| `/sessions/{id}` | 로그인 | 회차 상세, 예약하기·예약 취소, 마감 시 대기 신청·대기 순번·대기 취소. 요청 뒤 서버 상태를 다시 조회 |
| `/operator` | OPERATOR | 회차 등록(시작 일시는 서울 기준 입력), 회차 목록 |
| `/operator/sessions/{id}` | OPERATOR | 확정 명단, 정원·확정·대기 인원, 정원 변경(시작 전). 참가자가 직접 열면 서버 403 → 명단 없이 "권한이 없습니다." |

화면의 역할 분기는 표시용이고, 명단 비노출의 근거는 백엔드 403 이다 (e2e 에서 응답 코드까지 확인).

예약 안내 문구:

| 결과 | 안내 |
|---|---|
| 201 | 예약이 확정됐어요. |
| 200 (이미 활성 예약) | 이미 예약된 회차예요. 기존 예약을 보여드릴게요. |
| 409 `SESSION_FULL` | 정원이 마감됐어요. 다른 회차를 확인해 주세요. |
| 409 `SESSION_STARTED` | 이미 시작된 회차라 예약·취소할 수 없어요. |
| 대기 201 | 대기 N번으로 신청했어요. |
| 대기 200 (이미 대기 중) | 이미 대기 중이에요 (대기 N번). |
| 409 `SEATS_AVAILABLE` | 빈자리가 생겼어요. 대기 대신 바로 예약해 주세요. |

### 회차 상세 자동 갱신

- 화면이 보이고 온라인인 동안 `GET /api/sessions/{id}` 를 **5초**마다 조회한다 (`NEXT_PUBLIC_SESSION_POLL_MS`, 빌드 시점 값).
- 탭이 숨겨지거나(`visibilitychange`) 오프라인이 되면(`offline`) 폴링을 멈춘다. 탭으로 돌아오거나 네트워크가 복구되면(`online`) 즉시 한 번 조회하고 폴링을 다시 시작한다.
- 겹친 조회는 마지막에 시작한 것만 화면에 반영한다 (늦게 온 옛 응답이 새 상태를 덮지 않음).
- 대기 → 확정으로 바뀐 순간에만 `role="status"` 로 "대기 순번이 되어 예약이 확정됐어요." 를 한 번 안내한다. 폴링마다 반복하지 않는다.
- 조회 실패·오프라인은 오류 경고를 반복하지 않고 "연결이 끊겼어요…" 한 줄만 보여주며, 다음 성공 때 사라진다.

측정 (로컬, 실제 프런트·백엔드, Playwright 두 브라우저 컨텍스트, 각 10회):

| 경우 | 반영 시간 (최소 / 중앙 / 최대) |
|---|---|
| B 화면 취소 응답 → A 화면 "확정" (폴링) | 4799 / 4810 / 4822 ms |
| 탭 복귀 → 반영 | 25 / 28 / 30 ms |
| 네트워크 복구 → 반영 | 24 / 26.5 / 28 ms |

폴링 반영 시간은 이론상 0 ~ 5초 + 요청 왕복이다. 위 값이 4.8초 근처로 몰린 건 테스트 흐름상 취소가 늘 폴링 직후 같은 위치에 오기 때문이다.

2026-11-15 재측정 (폴링 간격 5000ms, B 취소 성공 응답 → A 화면 `확정`, A 는 새로고침·조작 없음):

| 회차 | 반영 시간 |
|---|---|
| 1 | 4813 ms |
| 2 | 4813 ms |
| 3 | 4810 ms |

실행 환경: Apple M4 Pro (Mac16,8, 12코어, 24GB), macOS 27.2, Node 23.7.0, OpenJDK 17.0.13 (Zulu), Playwright 1.64.1 / Chromium 156.0.8078.4 (headless), Next 16.4.6 `next build && next start -p 3100`, Spring Boot `bootRun --server.port=18080` (local 프로필), PostgreSQL 16.16 (docker compose). A·B 는 서로 다른 Chromium 프로세스(브라우저 인스턴스)로 각자 로그인했다.

```bash
cd frontend
E2E_PORT=3100 E2E_BACKEND_PORT=18080 npx playwright test e2e/auto-refresh.spec.ts --grep "새로고침 없이" --repeat-each=3
```

부하 메모: 상세 화면 1개당 5초에 요청 1번(초당 0.2건), 요청 1번에 SQL 약 4~5개(회차, 확정 수, 대기 수, 내 신청, 내 순번). 동시에 상세를 보고 있는 사람이 N명이면 약 0.2 × N 건/초 (100명이면 초당 20건). 숨긴 탭과 오프라인 탭은 0건이다.

결과는 `role="status"`, 오류는 `role="alert"` 로 보조 기술에 전달한다.

DB 접속 기본값: `localhost:5432`, DB/계정/비밀번호 모두 `daumjari` (로컬 전용). `.env` 로 바꿀 수 있다.
시각은 UTC로 저장한다 (Hibernate `jdbc.time_zone=UTC`, 시각 컬럼은 `timestamptz` 예정).

## 시드 계정

`./gradlew bootRun` 은 기본으로 `local` 프로필로 뜨고, DB에 계정이 하나도 없을 때만 아래 시드를 넣는다 (로컬 전용, 다른 프로필에서는 동작하지 않음). 초기화하려면 `docker compose down -v` 후 다시 기동한다.

| 아이디 | 비밀번호 | 역할 | 이름 |
|---|---|---|---|
| `operator` | `daumjari-local-1!` | OPERATOR | 운영자 |
| `alice` | `daumjari-local-1!` | PARTICIPANT | 앨리스 |
| `bob` | `daumjari-local-1!` | PARTICIPANT | 밥 |
| `carol` | `daumjari-local-1!` | PARTICIPANT | 캐롤 |

| 회차 | 시작 (서울) | 정원 | 시드 확정 | 용도 |
|---|---|---|---|---|
| Spring 트랜잭션 스터디 | 시드 시점 +7일 19:00 | 5 | alice | 빈자리 있는 회차 |
| PostgreSQL 잠금 스터디 | 시드 시점 +3일 19:00 | 2 | carol | 마지막 1석 회차 |

## 인증 (서버 세션)

회원가입 없이 시드 계정으로 로그인한다. 서버가 세션(`JSESSIONID`, HttpOnly, SameSite=Lax, 30분)에서 계정·역할을 확인하며, 요청 본문의 사용자 ID·역할은 신뢰하지 않는다.

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/api/auth/csrf` | 불필요 | CSRF 토큰 발급 (`XSRF-TOKEN` 쿠키 + 본문 `{headerName, token}`) |
| POST | `/api/auth/login` | 불필요 | 본문 `{"loginId","password"}`, 성공 시 `{id, loginId, displayName, role}` |
| POST | `/api/auth/logout` | 필요 | 세션 무효화, 204 |
| GET | `/api/auth/me` | 필요 | 현재 사용자 |
| * | `/api/operator/**` | OPERATOR | 운영자 API (참가자는 403) |

## 회차 API

시각은 ISO-8601 UTC(`2026-11-22T10:00:00Z`)로 주고받는다. 화면에서 서울 시간으로 바꿔 표시한다.

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/sessions` | 로그인 | 회차 목록 (시작 시각순) |
| GET | `/api/sessions/{id}` | 로그인 | 회차 상세 |
| POST | `/api/operator/sessions` | OPERATOR | 회차 등록 `{title, startsAt, location, capacity}`, 201 |
| GET | `/api/operator/sessions/{id}/roster` | OPERATOR | 확정 명단 `[{reservationId, accountId, loginId, displayName, reservedAt}]` |
| PATCH | `/api/operator/sessions/{id}/capacity` | OPERATOR | 정원 변경 `{capacity}` (1~1000 정수). 응답 `{session, promotedCount}` — 변경 후 회차와 늘어난 자리로 확정된 대기 인원 |

회차 응답: `{id, title, startsAt, location, capacity, confirmedCount, remainingSeats, waitingCount, status, myReservation}`

- `status`: `OPEN`(예약 가능) · `FULL`(잔여석 0) · `STARTED`(시작 시각 이후, 예약·취소 불가)
- `status` 는 대기자가 있으면 빈자리가 보여도 `FULL` 이다 (대기자 우선).
- `waitingCount`: 대기 인원.
- `myReservation`: 조회한 본인의 활성 신청 `{id, status, waitingPosition}`, 없으면 `null`. `waitingPosition` 은 `WAITING` 일 때만 1부터. 다른 참가자 정보는 포함하지 않는다.
- 등록 시 `capacity` 는 1~1000, `startsAt` 은 현재 이후여야 한다 (아니면 400 `VALIDATION_FAILED`).

## 예약 API (PARTICIPANT 전용)

대상 계정은 항상 세션 사용자다. 운영자 계정은 403 `FORBIDDEN`.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/sessions/{id}/reservations` | 예약. 새로 확정하면 201, 이미 활성 신청이 있으면 그 신청을 200 으로 반환 |
| POST | `/api/reservations/{id}/cancel` | 본인 예약 취소. 이미 취소된 건이면 변경 없이 200 |
| GET | `/api/me/reservations` | 내 신청 목록 (최근순, 취소 포함) |

| POST | `/api/sessions/{id}/waitlist` | 마감 회차 대기 신청. 새로 대기하면 201, 이미 활성 신청(확정·대기)이 있으면 그 신청을 200. 빈자리가 있으면 409 `SEATS_AVAILABLE` |

예약 응답: `{id, sessionId, sessionTitle, startsAt, status, waitingPosition, createdAt, cancelledAt}` (`status`: `CONFIRMED`·`WAITING`·`CANCELLED`, `waitingPosition` 은 대기일 때만)

- 예약 API(`/reservations`)의 계약은 그대로다. 마감이면 409 `SESSION_FULL` 을 주고, 대기는 별도 API 로 명시적으로 신청한다. 사용자가 원하지 않는 대기에 자동으로 들어가지 않게 하고, 기존 화면·테스트의 `SESSION_FULL` 처리와 충돌하지 않게 하려는 선택이다.
- 대기 취소도 `POST /api/reservations/{id}/cancel` 을 쓴다.

## 로그인 시도 제한

| 항목 | 기본값 | 설정 (환경변수) |
|---|---|---|
| 같은 아이디 + 같은 클라이언트 IP | 15분 창에 5회 | `daumjari.login-throttle.max-per-account-ip` (`LOGIN_MAX_PER_ACCOUNT_IP`) |
| 같은 클라이언트 IP (아이디 무관 실패) | 15분 창에 50회 | `daumjari.login-throttle.max-per-ip` (`LOGIN_MAX_PER_IP`) |
| 창 | 15분 (고정 창, 첫 시도부터) | `daumjari.login-throttle.window` (`LOGIN_THROTTLE_WINDOW`) |

- **응답:** 한도 안에서는 기존과 같은 401 `INVALID_CREDENTIALS`. 한도를 넘으면 429 `TOO_MANY_ATTEMPTS` 와 `Retry-After`(창이 끝날 때까지 남은 초). 제한 중에는 올바른 비밀번호여도 비밀번호를 확인하기 전에 429 다.
- **존재하지 않는 아이디:** 같은 키 규칙·같은 401·같은 429 본문. 계정 존재 여부를 드러내지 않는다.
- **키 설계:** 계정 단독으로 잠그지 않는다. 계정만으로 세면 누구나 남의 아이디로 5번 틀려 그 사람을 막을 수 있기 때문이다. 대신 (정규화한 아이디 + 클라이언트 IP) 로 세서 다른 곳의 정상 사용자는 영향이 없고, IP 단독 상한(50)으로 한 IP 가 아이디를 바꿔 가며 대는 시도를 늦춘다.
- **성공 시:** 그 (아이디+IP) 카운터를 지우고, IP 카운터에서는 성공한 시도 1회만 뺀다 (IP 카운터는 실패만 남는다).
- **집계 방식:** 시도를 비밀번호 확인 전에 PostgreSQL `INSERT ... ON CONFLICT DO UPDATE` 로 원자적으로 센다. 동시에 실패 요청이 몰려도 누락되거나 한도를 넘겨 통과하지 않는다 (10개 동시 요청 → 정확히 5개만 401, 5개 429). 키는 원문 대신 SHA-256 으로 저장한다 (아이디 칸에 비밀번호를 잘못 넣는 경우 대비).
- **근거:** OWASP Authentication Cheat Sheet 와 NIST SP 800-63B 는 온라인 추측 공격에 시도 제한을 두되, 계정 잠금이 서비스 거부로 악용되지 않게 하라고 권한다. 5회/15분은 사람이 비밀번호를 몇 번 틀리는 경우는 막지 않으면서 자동 대입을 하루 수백 회 수준으로 줄이는 값으로 골랐다.
- **재시작·다중 인스턴스:** 집계가 DB 테이블(`login_attempt`, V4)에 있으므로 백엔드를 재시작해도 유지되고, 같은 DB 를 쓰는 여러 인스턴스가 함께 센다. 창이 지난 행은 10분마다 지운다 (여러 인스턴스가 같이 지워도 결과는 같음).
- **알려진 한계:** 고정 창이라 창 경계 앞뒤로 최대 2배까지 몰아서 시도할 수 있다. 여러 IP 에서 한 계정을 나눠 대는 분산 시도는 (아이디+IP) 키만으로는 막지 못한다 (후속 작업).

## 클라이언트 IP 판별

제한 키의 IP 는 백엔드 `HttpServletRequest#getRemoteAddr()` 이고, Tomcat RemoteIpValve(`server.forward-headers-strategy: native`)가 정한다. **접속한 쪽이 신뢰 프록시일 때만** `X-Forwarded-For` 를 오른쪽부터 읽어 신뢰 프록시가 아닌 첫 주소를 클라이언트 IP 로 쓴다. 그 밖에는 실제 접속 주소다.

Next `/api` 프록시 실측 (Next 16.4.6, 에코 서버로 캡처):

| 브라우저가 보낸 헤더 | `next start` 그대로일 때 백엔드가 받은 XFF | `server.mjs` 를 거칠 때 |
|---|---|---|
| 없음 | 없음 (접속 주소만 `::1`) | `::1` (실제 접속 주소) |
| `X-Forwarded-For: 6.6.6.6` | `6.6.6.6` (그대로 통과) | `::1` |
| `X-Forwarded-For: 1.1.1.1, 2.2.2.2`, `Forwarded: for=9.9.9.9` | 둘 다 그대로 통과 | `::1`, `Forwarded`·`X-Forwarded-Proto` 제거 |

Next rewrite 프록시는 XFF 를 붙이지 않고 클라이언트 값을 그대로 넘긴다. 그래서 `frontend/server.mjs` 로 Next 를 감싸 클라이언트가 보낸 전달 헤더를 버리고 실제 접속 주소로 다시 쓴다 (`npm run dev`·`npm run start` 가 이 서버를 쓴다). `next dev`·`next start` 를 직접 실행하면 이 보호가 빠진다.

| 환경 | 프런트 (`server.mjs`) | 백엔드 (`TRUSTED_PROXIES`, Tomcat internal-proxies) |
|---|---|---|
| 로컬 (브라우저 → Next → 백엔드, 모두 localhost) | `FRONT_TRUSTED_PROXIES` 비움: XFF = 실제 접속 주소 | 기본값 loopback(`127.0.0.1`, `::1`)만 신뢰 → Next 가 넘긴 XFF 사용 |
| 배포 (LB → Next → 백엔드) | `FRONT_TRUSTED_PROXIES` = LB 주소: LB 가 붙인 XFF 의 맨 오른쪽 비신뢰 값 사용 | `TRUSTED_PROXIES` = Next 서버 주소만 (정규식). 백엔드는 공개 인터넷에서 직접 닿지 않게 둔다 |

백엔드에 직접 접속한 클라이언트(신뢰 프록시가 아님)가 보낸 `X-Forwarded-For` 는 무시된다 (`ForwardedHeaderTrustTests`). e2e 는 제한 창을 10초로 줄여 띄운다 (`E2E_THROTTLE_WINDOW_SECONDS`).

## CSRF 정책

- 쿠키 이중 제출 방식. 서버가 `XSRF-TOKEN` 쿠키(JS에서 읽기 가능)를 내려주고, 상태 변경 요청(POST/PUT/PATCH/DELETE)은 같은 값을 `X-XSRF-TOKEN` 헤더로 보내야 한다. 로그인 요청도 포함.
- 프런트 흐름: `GET /api/auth/csrf` → 로그인 → 로그인 응답이 토큰 쿠키를 만료시키므로 다음 상태 변경 전에 다시 `GET /api/auth/csrf`.
- 로그인 시 세션 ID를 교체한다(세션 고정 방지).
- 쿠키를 같은 출처로 쓰기 위해 프런트는 Next rewrites 로 `/api/*` 를 `BACKEND_URL` 로 프록시한다 (`frontend/next.config.ts`). 브라우저는 백엔드 주소를 직접 부르지 않으므로 CORS 설정이 필요 없다.

## API 오류 규칙

오류 응답 본문은 항상 `{"code": "...", "message": "..."}` 이다. 화면은 `code` 로 분기하고 `message` 는 표시용이다.

| HTTP | code | 상황 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 요청 형식·값 오류 |
| 401 | `UNAUTHENTICATED` | 로그인 필요 (세션 없음·만료) |
| 401 | `INVALID_CREDENTIALS` | 아이디 또는 비밀번호 불일치 (계정 존재 여부는 구분하지 않음) |
| 403 | `FORBIDDEN` | 권한 없음 (참가자의 운영자 API 접근 등) |
| 403 | `CSRF_INVALID` | CSRF 토큰 누락·불일치 |
| 429 | `TOO_MANY_ATTEMPTS` | 로그인 시도 제한. `Retry-After`(초) 헤더와 본문 `retryAfterSeconds` (아래 "로그인 시도 제한") |
| 404 | `SESSION_NOT_FOUND` | 회차 없음 |
| 404 | `RESERVATION_NOT_FOUND` | 예약 없음, 또는 다른 사람의 예약 (존재 여부를 구분하지 않음) |
| 409 | `SESSION_FULL` | 정원 마감 |
| 409 | `SESSION_STARTED` | 회차 시작 시각 이후의 예약·대기·취소 |
| 409 | `SEATS_AVAILABLE` | 빈자리가 있는 회차에 대기 신청 (바로 예약하면 됨) |
| 409 | `CAPACITY_BELOW_CONFIRMED` | 현재 확정 인원보다 적게 정원 변경 |
| 404 | `DEMO_NOT_FOUND` | 데모 체험이 없는 계정의 샘플 취소 |
| 410 | `DEMO_EXPIRED` | 데모 체험 시간이 지난 방문자의 요청 |

정원 변경의 `VALIDATION_FAILED` 는 0·음수·1000 초과·소수(`2.5`)·문자·`null` 을 모두 포함한다. 정수 필드에 소수가 와도 잘라 받지 않는다 (`spring.jackson.deserialization.accept-float-as-int: false`). 시작 시각 이후 변경은 `SESSION_STARTED`, 참가자 요청은 `FORBIDDEN` 이며, 거절되면 정원과 신청 상태는 바뀌지 않는다.

## 마감 정책

- **시작 시각 기준 마감:** 회차 `startsAt` 이 되는 순간부터(`now >= startsAt`) 예약과 취소를 모두 거절한다 (409 `SESSION_STARTED`). 시각 비교는 서버 UTC `Clock` 기준.
- **정원:** 확정(`CONFIRMED`) 수가 정원과 같으면 새 예약은 409 `SESSION_FULL`. 잔여석 = 정원 − 확정 수로 매번 계산하므로 취소를 반복해도 좌석은 한 번만 돌아온다.
- **1인 1건:** 회차·계정별 활성 신청(확정·대기)은 하나. 중복 요청은 기존 신청을 돌려주고, DB 부분 유니크 인덱스가 한 번 더 막는다. 취소 후 시작 전이고 자리가 있으면 다시 예약할 수 있다 (새 신청 행 생성).
- **대기 순번:** 저장하지 않고 같은 회차 `WAITING` 행 중 id 가 더 작은 수 + 1 로 계산한다. 신청은 회차 행 잠금 아래 하나씩 들어오므로 id 순서가 곧 신청 순서다 (같은 시각이어도 순서가 갈림). 대기를 취소하고 다시 신청하면 새 행이라 맨 뒤로 간다.
- **자동 승격:** 확정자가 취소하면 같은 트랜잭션·같은 회차 잠금 아래에서 가장 앞 대기자를 확정한다. 승격이 실패하면 취소도 함께 롤백된다. 대기 취소나 이미 취소된 건의 재취소는 승격하지 않는다. 시작 시각 이후에는 취소·승격 모두 일어나지 않는다.
- **대기자 우선:** 대기자가 있으면 빈자리가 보여도 새 예약은 409 `SESSION_FULL` 이고 대기로만 들어올 수 있다.
- **정원 변경:** 시작 전에만, 운영자만, 현재 확정 인원 이상으로만 바꿀 수 있다. 회차 행 잠금 아래 한 트랜잭션에서 정원을 바꾸고, 늘어난 자리만큼 기존 승격 로직(`WaitlistPromoter`)을 대기 순서대로 반복해 확정한다. 예: 정원 3·확정 3·대기 3 에서 5 로 늘리면 대기 1·2번 확정, 3번은 대기 1번. 대기자를 모두 확정하고도 자리가 남으면 일반 예약이 열린다(`OPEN`). 승격 중 실패하면 정원 변경과 앞선 승격이 함께 롤백된다. 줄일 때 대기자는 그대로 둔다.
- **동시성:** 예약·대기·취소는 회차 행을 `SELECT ... FOR UPDATE` 로 잠근 트랜잭션 안에서 잔여석·대기열 판단과 상태 변경을 함께 한다. 잠금 순서는 회차 → 신청.


## 공개 데모 (가입 없는 체험)

| 단계 | 경로 |
|---|---|
| 첫 화면 | `/` (비로그인) → `데모 체험하기`. `/login` 에도 같은 버튼 |
| 체험 시작 | `POST /api/demo/start` (CSRF 필요, 로그인 불필요) → 201 `{sessionId, expiresAt}`, 방문자 세션 발급 → `/sessions/{sessionId}` |
| 대기 | 체험 회차 상세에서 `대기 신청` → `대기 1번` |
| 샘플 취소 | `샘플 참가자 취소` → `POST /api/demo/sample-cancel` → 기존 취소·승격 로직 → 새로고침 없이 `예약 확정` |
| 다시 체험 | `처음부터 다시 체험` 또는 첫 화면 버튼 → 새 체험 (세션 ID 재발급, 이전 체험은 만료 때 삭제) |

- **체험 하나:** `demo_trial` 행, 방문자 계정, 샘플 참가자 계정 2개, 정원 2 체험 회차(샘플 2명 확정, 시작은 7일 뒤), 지정 샘플 예약 1건. 체험에 속한 계정·회차는 `demo_trial_id` 로 묶인다.
- **격리 (서버 강제):** 회차는 `demo_trial_id` 가 같은 계정에게만 보인다. 일반 계정은 일반 회차만, 방문자는 자기 체험 회차만 목록·상세·예약·대기할 수 있고, 아니면 404 (존재 비노출). 남의 예약 취소는 기존대로 404. 샘플 취소는 파라미터를 받지 않고 세션의 체험에서 지정 샘플을 찾으므로 다른 체험을 가리킬 수 없다 (일반 계정은 404 `DEMO_NOT_FOUND`). 운영자 목록·명단·정원 변경에서 체험 회차는 보이지 않고(404), 방문자는 운영자 API 에 403. 데모 계정은 비밀번호 로그인이 막혀 있다.
- **만료·정리:** 기본 30분(`DEMO_TTL`). 지나면 방문자의 API 요청은 410 `DEMO_EXPIRED`(체험 시작·인증 API 제외)이고 화면은 새 체험 시작 버튼을 보여준다. 10분마다 만료 체험의 예약 → 회차 → 계정 → 체험 순으로 지운다 (여러 인스턴스가 함께 돌아도 결과가 같다).
- **남용 제한:** 체험 시작은 클라이언트 IP 당 15분에 10회 (`DEMO_MAX_STARTS_PER_IP`, 로그인 제한과 같은 집계·IP 판별). 넘으면 429 와 남은 시간 안내.

## 배포

호스팅은 아직 정하지 않았다. 아래는 어느 호스팅이든 필요한 설정과 시작 명령이다.

구성: `HTTPS 종단(LB·리버스 프록시) → 프런트(server.mjs, Next) → 백엔드(Spring Boot) → PostgreSQL 16`. 백엔드는 프런트에서만 닿게 둔다.

```bash
# 백엔드 (프로필 없이 실행: local 시드가 들어가지 않음)
cd backend && ./gradlew bootJar
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar

# 프런트 (BACKEND_URL 은 빌드 시점에 rewrites 로 고정된다)
cd frontend && npm ci
BACKEND_URL=http://<백엔드 내부 주소>:8080 npm run build
PORT=3000 FRONT_TRUSTED_PROXIES=<LB 주소> npm run start
```

| 환경변수 | 쓰는 곳 | 배포 값 |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | 백엔드 | 운영 PostgreSQL 16 (Flyway 가 V1~V5 적용) |
| `SESSION_COOKIE_SECURE` | 백엔드 | `true` (세션·CSRF 쿠키 Secure. HttpOnly·SameSite=Lax 는 기본) |
| `TRUSTED_PROXIES` | 백엔드 | 프런트 서버 주소만 (Tomcat internal-proxies 정규식) |
| `LOGIN_MAX_PER_ACCOUNT_IP`, `LOGIN_MAX_PER_IP`, `LOGIN_THROTTLE_WINDOW` | 백엔드 | 기본 5 / 50 / 15m |
| `DEMO_TTL`, `DEMO_MAX_STARTS_PER_IP` | 백엔드 | 기본 30m / 10 |
| `BACKEND_URL` | 프런트 빌드·시작 | 백엔드 내부 주소 |
| `FRONT_TRUSTED_PROXIES` | 프런트 (`server.mjs`) | 앞단 LB 주소 (쉼표 구분). 비우면 전달 헤더를 믿지 않음 |
| `PORT` | 프런트 | 프런트 서버 포트 |
| `NEXT_PUBLIC_SESSION_POLL_MS` | 프런트 빌드 | 기본 5000 |

배포 체크리스트:

- [ ] 모바일 브라우저와 시크릿 창에서 공개 URL 첫 화면 → `데모 체험하기` → `대기 1번` → `샘플 참가자 취소` → `예약 확정` 까지 새로고침 없이 되는지
- [ ] 개발자 도구에서 `JSESSIONID`·`XSRF-TOKEN` 쿠키가 `Secure` 이고 `JSESSIONID` 가 `HttpOnly`, `SameSite=Lax` 인지 (HTTP 로는 로그인이 유지되지 않아야 함)
- [ ] 프록시 경유 IP 집계: 서로 다른 네트워크의 두 기기에서 같은 아이디로 6번 틀릴 때 한쪽만 429 인지, 위조 `X-Forwarded-For` 를 붙여도 한도가 그대로인지 (`curl -H 'X-Forwarded-For: 1.2.3.4' https://<도메인>/api/auth/login ...`)
- [ ] 백엔드 직접 접근 차단: 외부에서 백엔드 포트(예: 8080)로 접속이 안 되는지, `TRUSTED_PROXIES` 가 프런트 주소만인지
- [ ] `/actuator/health` 는 내부에서만 확인 (외부 노출 여부 결정)

남은 결정:

- 호스팅 방식 (VM 1대 + Caddy/nginx TLS, 컨테이너 PaaS 등)과 도메인, 비용
- PostgreSQL 관리형 여부와 백업
- 운영자 계정 만드는 방법 (지금은 local 프로필 시드뿐이라 운영 DB 에는 운영자가 없다)
- 백엔드 여러 대로 늘릴 때 세션 공유 (지금은 서버 메모리 세션. 로그인·데모 시작 집계는 DB 라 이미 공유됨)

개발 서버를 하나 더 띄우려면 빌드 폴더를 나눈다: `NEXT_DIST_DIR=.next-dev2 BACKEND_URL=... npm run dev -- -p 3300` (Next 16 은 같은 폴더의 두 번째 dev 서버를 거부한다).

## 후속 작업

| 작업 | 시점 | 메모 |
|---|---|---|
| 분산 로그인 시도 대응 | 공개 배포 전 검토 | 여러 IP 에서 한 계정을 나눠 대는 시도: 계정 단위 지연·추가 확인(CAPTCHA)·알림 |
| HTTPS 배포 | 호스팅 결정 후 | 위 "배포" 설정·체크리스트대로 공개 URL 배포, 배포 후 동시 요청·p95·오류율 기록 |
| 실시간 푸시 | 필요 시 | 지금은 5초 폴링. 동시 접속이 커지면 SSE 등 서버 푸시와 부하 측정(p95, 오류율) 검토 |
