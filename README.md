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

# frontend (http://localhost:3000)
cd frontend
npm install
npm run dev
npm run build
npm run lint
```

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

회차 응답: `{id, title, startsAt, location, capacity, confirmedCount, remainingSeats, status, myReservation}`

- `status`: `OPEN`(예약 가능) · `FULL`(잔여석 0) · `STARTED`(시작 시각 이후, 예약·취소 불가)
- `myReservation`: 조회한 본인의 활성 신청 `{id, status}`, 없으면 `null`. 다른 참가자 정보는 포함하지 않는다.
- 등록 시 `capacity` 는 1~1000, `startsAt` 은 현재 이후여야 한다 (아니면 400 `VALIDATION_FAILED`).

## 예약 API (PARTICIPANT 전용)

대상 계정은 항상 세션 사용자다. 운영자 계정은 403 `FORBIDDEN`.

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/sessions/{id}/reservations` | 예약. 새로 확정하면 201, 이미 활성 신청이 있으면 그 신청을 200 으로 반환 |
| POST | `/api/reservations/{id}/cancel` | 본인 예약 취소. 이미 취소된 건이면 변경 없이 200 |
| GET | `/api/me/reservations` | 내 신청 목록 (최근순, 취소 포함) |

예약 응답: `{id, sessionId, sessionTitle, startsAt, status, createdAt, cancelledAt}`

## CSRF 정책

- 쿠키 이중 제출 방식. 서버가 `XSRF-TOKEN` 쿠키(JS에서 읽기 가능)를 내려주고, 상태 변경 요청(POST/PUT/PATCH/DELETE)은 같은 값을 `X-XSRF-TOKEN` 헤더로 보내야 한다. 로그인 요청도 포함.
- 프런트 흐름: `GET /api/auth/csrf` → 로그인 → 로그인 응답이 토큰 쿠키를 만료시키므로 다음 상태 변경 전에 다시 `GET /api/auth/csrf`.
- 로그인 시 세션 ID를 교체한다(세션 고정 방지).
- 쿠키를 같은 출처로 쓰기 위해 프런트는 `/api` 를 백엔드로 프록시하는 구성을 전제로 한다 (프런트 연동 작업 때 설정).

## API 오류 규칙

오류 응답 본문은 항상 `{"code": "...", "message": "..."}` 이다. 화면은 `code` 로 분기하고 `message` 는 표시용이다.

| HTTP | code | 상황 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | 요청 형식·값 오류 |
| 401 | `UNAUTHENTICATED` | 로그인 필요 (세션 없음·만료) |
| 401 | `INVALID_CREDENTIALS` | 아이디 또는 비밀번호 불일치 (계정 존재 여부는 구분하지 않음) |
| 403 | `FORBIDDEN` | 권한 없음 (참가자의 운영자 API 접근 등) |
| 403 | `CSRF_INVALID` | CSRF 토큰 누락·불일치 |
| 404 | `SESSION_NOT_FOUND` | 회차 없음 |
| 404 | `RESERVATION_NOT_FOUND` | 예약 없음, 또는 다른 사람의 예약 (존재 여부를 구분하지 않음) |
| 409 | `SESSION_FULL` | 정원 마감 |
| 409 | `SESSION_STARTED` | 회차 시작 시각 이후의 예약·취소 |

## 마감 정책

- **시작 시각 기준 마감:** 회차 `startsAt` 이 되는 순간부터(`now >= startsAt`) 예약과 취소를 모두 거절한다 (409 `SESSION_STARTED`). 시각 비교는 서버 UTC `Clock` 기준.
- **정원:** 확정(`CONFIRMED`) 수가 정원과 같으면 새 예약은 409 `SESSION_FULL`. 잔여석 = 정원 − 확정 수로 매번 계산하므로 취소를 반복해도 좌석은 한 번만 돌아온다.
- **1인 1건:** 회차·계정별 활성 신청은 하나. 중복 요청은 기존 신청을 돌려주고, DB 부분 유니크 인덱스가 한 번 더 막는다. 취소 후 시작 전이고 자리가 있으면 다시 예약할 수 있다 (새 신청 행 생성).
- **동시성:** 예약·취소는 회차 행을 `SELECT ... FOR UPDATE` 로 잠근 트랜잭션 안에서 잔여석 판단과 상태 변경을 함께 한다. 잠금 순서는 회차 → 신청.

