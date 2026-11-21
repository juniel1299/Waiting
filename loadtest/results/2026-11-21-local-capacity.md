# 로컬 정원 증설 동시성 결과 (2026-11-21, 시나리오 C)

시나리오 A(마지막 자리)·B(취소·승격)는 재실행하지 않았다: 기록은 [`2026-11-21-local.md`](2026-11-21-local.md) 그대로다. 이 파일은 빠져 있던 **정원 증설** 동시 요청만 새로 검증한 결과다.

- **구분:** 로컬 실제 PostgreSQL 결과다. 공개 무료 서버에는 부하를 주지 않았다 (공개 배포는 소수 요청 점검만: [`2026-11-21-public-smoke.txt`](2026-11-21-public-smoke.txt)).
- **명령:** `SCENARIOS=C loadtest/run.sh` (원자료 [`2026-11-21T11-12-55-650Z.json`](2026-11-21T11-12-55-650Z.json), SQL [`checks-20261121-201310.txt`](checks-20261121-201310.txt))
- **실행 환경:** Apple M4 Pro (12코어, 24GB), macOS 27.2 / JDK 17.0.13 (Zulu) / Spring Boot 4.1.2 local 프로필, Hikari 기본 풀 10 / PostgreSQL 16.16 (`postgres:16`, docker 27.3.1, Docker Desktop VM 12 CPU·8GB) / Node 23.7.0 내장 fetch. 부하 발생기·서버·DB 가 같은 Mac.
- **운영자 계정:** 부하 전용 DB 에 참가자로 가입한 계정을 SQL 로 `OPERATOR` 로 바꿔 썼다 (시드·운영 비밀번호를 쓰지 않음).

## 시나리오 C

1. 정원 1 회차, 확정자 1명.
2. **C1:** N명이 동시에 대기 신청.
3. **C2:** 다음을 한꺼번에 출발:
   - 운영자 정원 증설 1 → 6 (`PATCH /api/operator/sessions/{id}/capacity`)
   - 운영자 정원 축소 → 1 (처리 순서에 따라 `200`, 또는 확정자가 이미 늘어 `409 CAPACITY_BELOW_CONFIRMED`: 둘 다 예상된 응답)
   - 확정자 취소, 신규 예약 N/4, 신규 대기 N/4
4. **통과 조건:** 어느 순서든 최종 정원 6, 확정 6명 = 대기 순번 1~6 (id 순), 신규 예약은 대기자가 남아 모두 `409 SESSION_FULL`, 남은 대기 수 = N − 6 + N/4, 예상 못한 오류 0.

| 시나리오 | 동시 요청 | 실행 ms | 처리량 req/s | p50 ms | p95 ms | max ms | 응답 (예상된 응답) | 예상 못한 오류 | 판정 |
|---|---|---|---|---|---|---|---|---|---|
| C1 동시 대기 | 10 | 30 | 328 | 18 | 30 | 30 | waitlist 201 WAITING ×10 | 0 | PASS |
| C2 증설·축소·취소 혼합 | 7 | 37 | 188 | 11 | 37 | 37 | capacity+ 200 ×1<br>capacity- 409 CAPACITY_BELOW_CONFIRMED ×1<br>cancel 200 CANCELLED ×1<br>reserve 409 SESSION_FULL ×2<br>waitlist 201 WAITING ×2 | 0 | PASS |
| C1 동시 대기 | 50 | 112 | 447 | 66 | 107 | 110 | waitlist 201 WAITING ×50 | 0 | PASS |
| C2 증설·축소·취소 혼합 | 27 | 49 | 547 | 25 | 47 | 49 | capacity+ 200 ×1<br>capacity- 409 CAPACITY_BELOW_CONFIRMED ×1<br>cancel 200 CANCELLED ×1<br>reserve 409 SESSION_FULL ×12<br>waitlist 201 WAITING ×12 | 0 | PASS |
| C1 동시 대기 | 100 | 192 | 520 | 107 | 181 | 190 | waitlist 201 WAITING ×100 | 0 | PASS |
| C2 증설·축소·취소 혼합 | 53 | 95 | 557 | 58 | 91 | 95 | capacity+ 200 ×1<br>capacity- 409 CAPACITY_BELOW_CONFIRMED ×1<br>cancel 200 CANCELLED ×1<br>reserve 409 SESSION_FULL ×25<br>waitlist 201 WAITING ×25 | 0 | PASS |
| C1 동시 대기 | 200 | 388 | 516 | 215 | 369 | 386 | waitlist 201 WAITING ×200 | 0 | PASS |
| C2 증설·축소·취소 혼합 | 103 | 170 | 607 | 73 | 145 | 169 | capacity+ 200 ×1<br>capacity- 409 CAPACITY_BELOW_CONFIRMED ×1<br>cancel 200 CANCELLED ×1<br>reserve 409 SESSION_FULL ×50<br>waitlist 201 WAITING ×50 | 0 | PASS |
| C1 동시 대기 | 400 | 706 | 567 | 370 | 660 | 696 | waitlist 201 WAITING ×400 | 0 | PASS |
| C2 증설·축소·취소 혼합 | 203 | 319 | 636 | 135 | 288 | 319 | capacity+ 200 ×1<br>capacity- 200 ×1<br>cancel 200 CANCELLED ×1<br>reserve 409 SESSION_FULL ×100<br>waitlist 201 WAITING ×100 | 0 | PASS |

## 대기자 우선: 처리 순서와 최종 상태

| 대기 N | 최종 정원 | 확정된 대기자의 대기 순번 | 승격 = 대기 순번 1~6 | 남은 대기 | 정원 변경 처리 순서 |
|---|---|---|---|---|---|
| 10 | 6 | 1, 2, 3, 4, 5, 6 | true | 6 | 증설 먼저 → 축소 409 |
| 50 | 6 | 1, 2, 3, 4, 5, 6 | true | 56 | 증설 먼저 → 축소 409 |
| 100 | 6 | 1, 2, 3, 4, 5, 6 | true | 119 | 증설 먼저 → 축소 409 |
| 200 | 6 | 1, 2, 3, 4, 5, 6 | true | 244 | 증설 먼저 → 축소 409 |
| 400 | 6 | 1, 2, 3, 4, 5, 6 | true | 494 | 축소 먼저(200) → 증설 |

N=10 의 대기 신청 10건 (요청 출발 순서, DB 처리 순서 = id, 최종 상태):

| 출발 순서 | 출발 시각 (첫 요청 기준 ms) | 응답 ms | id (처리 순서) | 대기 순번 | 최종 |
|---|---|---|---|---|---|
| 2 | 0 | 8 | 4 | 1 | 확정 (승격) |
| 5 | 0 | 10 | 5 | 2 | 확정 (승격) |
| 1 | 0 | 13 | 6 | 3 | 확정 (승격) |
| 0 | 0 | 16 | 7 | 4 | 확정 (승격) |
| 4 | 0 | 18 | 8 | 5 | 확정 (승격) |
| 3 | 0 | 21 | 9 | 6 | 확정 (승격) |
| 9 | 1 | 23 | 10 | 7 | 대기 |
| 6 | 0 | 26 | 11 | 8 | 대기 |
| 8 | 1 | 28 | 12 | 9 | 대기 |
| 7 | 0 | 30 | 13 | 10 | 대기 |

- 같은 순간에 출발한 요청은 회차 행 잠금을 얻는 순서대로 처리된다. 그래서 id·대기 순번이 출발 순서와 다를 수 있다. 공정성의 기준은 **서버가 처리한 순서(id)** 이고, 승격은 그 순서를 지킨다.
- 신규 예약은 증설로 빈자리가 생긴 순간에도 대기자가 남아 있어 모두 `409 SESSION_FULL` 이었다 (대기자 우선).

## SQL 정합성 (`checks.sql`)

① 정원 초과 ② 활성 신청 중복 ③ 대기 추월(남은 대기자보다 늦은 id 의 확정) ④ 승격 누락: 4종 모두 **0행**.

## 검사가 위반을 잡는지 먼저 확인

- **SQL:** `loadtest/run.sh --selftest` 로 일부러 만든 위반을 ①③④ 각 1행 검출 (②는 unique 인덱스가 원천 차단).
- **시나리오 판정:** 기대 확정 대기자를 일부러 "순번 2~7" 로 틀리게 바꿔 N=10 을 돌리면 C2 가 `FAIL` 로 판정되는 것을 확인한 뒤 원래대로 되돌려 실행했다 (그 시험 실행의 결과 파일은 남기지 않음).
