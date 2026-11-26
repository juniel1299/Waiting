// 공개 배포의 확장 2단계(닉네임·프로필·즐겨찾기)를 새로 가입한 테스트 회원 2명으로 확인하고 정리한다.
// 회원 가입(계정 생성)이 있어 사람이 직접 실행한다: node loadtest/public-platform2-check.mjs
// 운영자 비밀번호는 필요 없다. 테스트 회원 비밀번호는 실행 중에 무작위로 만들고 출력·저장하지 않는다.
//
// 흐름: 새 백엔드 확인 → A·B 가입 → A 의 가입 닉네임이 무작위 한글+숫자 → B 가 A 닉네임(대문자로 바꿔)을 중복 확인·저장 → 불가·409
//      → A 소개·태그 저장(중복 태그 정리) → B 가 A 공개 프로필 조회(필드 bio·id·nickname·tags 뿐, 로그인 아이디 없음)
//      → B 가 공개 회차·승인 모임 즐겨찾기 두 번(멱등, 목록에 1개씩) → 해제
// 정리(finally): A·B 탈퇴(즐겨찾기도 지워짐). 끝에 Neon 확인 SQL 출력.
import { brief, Client, expect, log, sleep } from "./lib/public-client.mjs";

const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const a = new Client(`qa_pa2a${tag}`);
const b = new Client(`qa_pa2b${tag}`);
let failed = false;
const ids = [];

async function waitForBackend(who) {
  const until = Date.now() + 10 * 60 * 1000;
  for (;;) {
    const r = await who.req("GET", "/api/me/favorites");
    if (r.status === 200) return;
    expect(r.status === 404 && Date.now() < until, `즐겨찾기 API 가 준비되지 않음: ${r.status}. 배포(Render)가 끝난 뒤 다시 실행`);
    log("배포 대기: 즐겨찾기 API 가 아직 404. 30초 뒤 다시 확인, 최대 10분");
    await sleep(30 * 1000);
  }
}

try {
  log("A", a.loginId, await a.signupAndLogin());
  await waitForBackend(a);
  log("B", b.loginId, await b.signupAndLogin());
  const meA = (await a.req("GET", "/api/me/profile")).body;
  const bId = (await b.req("GET", "/api/me/profile")).body?.id;
  ids.push(meA?.id, bId);
  log(`A 가입 닉네임 "${meA?.nickname}"`);
  expect(/^[가-힣]+[0-9]{1,4}$/.test(meA?.nickname ?? ""), "가입 닉네임이 무작위 한글+숫자가 아니라 멈춤");

  const upper = meA.nickname.toUpperCase();
  const check = await b.req("GET", `/api/me/nickname/check?value=${encodeURIComponent(upper)}`);
  const take = await b.req("PATCH", "/api/me/profile", { nickname: upper });
  const banned = await b.req("PATCH", "/api/me/profile", { nickname: "운영자" });
  log(`B 가 A 닉네임 중복 확인 ${JSON.stringify(check.body)} | 저장 ${brief(take)} | 금지어 저장 ${brief(banned)}`);
  expect(check.body?.available === false && take.status === 409 && take.body?.code === "NICKNAME_TAKEN" && banned.status === 400,
    "닉네임 유일성·금지어가 예상과 달라 멈춤");

  const saved = await a.req("PATCH", "/api/me/profile", { bio: "[QA] 공개 점검 소개", tags: ["자바", " 스프링 ", "자바"] });
  const seen = await b.req("GET", `/api/profiles/${meA.id}`);
  const keys = Object.keys(seen.body ?? {}).sort().join(",");
  log(`A 소개·태그 저장 ${brief(saved)} tags=${JSON.stringify(saved.body?.tags)} | B 가 본 A 프로필 ${brief(seen)} [${keys}] 로그인 아이디 포함: ${JSON.stringify(seen.body).includes(a.loginId)}`);
  expect(saved.status === 200 && JSON.stringify(saved.body?.tags) === '["자바","스프링"]' && keys === "bio,id,nickname,tags"
    && !JSON.stringify(seen.body).includes(a.loginId), "프로필 저장·공개 항목이 예상과 달라 멈춤");

  const session = (await b.req("GET", "/api/sessions")).body?.[0];
  const club = (await b.req("GET", "/api/clubs")).body?.items?.[0];
  if (session) {
    const twice = [await b.req("PUT", `/api/sessions/${session.id}/favorite`), await b.req("PUT", `/api/sessions/${session.id}/favorite`)];
    const list = (await b.req("GET", "/api/me/favorites")).body;
    const count = list?.sessions?.filter((s) => s.id === session.id).length;
    const off = await b.req("DELETE", `/api/sessions/${session.id}/favorite`);
    log(`B 회차 ${session.id} 즐겨찾기 두 번 ${twice.map(brief).join("·")} → 목록 ${count}개 → 해제 ${brief(off)}`);
    expect(twice.every((r) => r.status === 200) && count === 1 && off.body?.favorite === false, "회차 즐겨찾기가 예상과 달라 멈춤");
  } else log("공개 회차가 없어 회차 즐겨찾기는 건너뜀");
  if (club) {
    const on = await b.req("PUT", `/api/clubs/${club.id}/favorite`);
    const list = (await b.req("GET", "/api/me/favorites")).body;
    log(`B 모임 ${club.id} 즐겨찾기 ${brief(on)} → 목록 ${list?.clubs?.length}개 (탈퇴 정리에서 지워짐)`);
    expect(on.status === 200 && list?.clubs?.some((c) => c.id === club.id), "모임 즐겨찾기가 예상과 달라 멈춤");
  } else log("승인된 모임이 없어 모임 즐겨찾기는 건너뜀");
  log("확장 2단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  for (const [name, who] of [["A", a], ["B", b]]) {
    if (who.loggedIn) log(`정리: ${name} 탈퇴`, brief(await who.req("POST", "/api/me/withdraw", { password: who.password })));
  }
  const known = ids.filter(Boolean);
  if (known.length) {
    log("Neon SQL 편집기에서 확인 (두 값 모두 0: 남은 즐겨찾기, 닉네임·소개가 남은 탈퇴 계정):");
    console.log(`select (select count(*) from favorite where account_id in (${known.join(", ")})) as favorites_left,
       (select count(*) from account where id in (${known.join(", ")}) and (nickname is not null or bio is not null)) as profile_left;`);
  }
  process.exitCode = failed ? 1 : 0;
}
