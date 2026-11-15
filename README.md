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

TODO

## 인증 (서버 세션)

회원가입 없이 시드 계정으로 로그인한다. 서버가 세션(`JSESSIONID`, HttpOnly, SameSite=Lax, 30분)에서 계정·역할을 확인하며, 요청 본문의 사용자 ID·역할은 신뢰하지 않는다.

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| GET | `/api/auth/csrf` | 불필요 | CSRF 토큰 발급 (`XSRF-TOKEN` 쿠키 + 본문 `{headerName, token}`) |
| POST | `/api/auth/login` | 불필요 | 본문 `{"loginId","password"}`, 성공 시 `{id, loginId, displayName, role}` |
| POST | `/api/auth/logout` | 필요 | 세션 무효화, 204 |
| GET | `/api/auth/me` | 필요 | 현재 사용자 |
| * | `/api/operator/**` | OPERATOR | 운영자 API (참가자는 403) |

### CSRF 정책

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

TODO: 정원 마감, 회차 시작 후 요청 등 예약 오류 코드는 예약 API 구현 때 추가.

## 마감 정책

TODO

