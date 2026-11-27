// 공개 배포의 확장 5단계(카카오·네이버 계정 연결)를 비밀번호 없이 확인한다: 비회원만. 데이터를 바꾸지 않는다.
// 공급자 환경 변수가 없으면 '꺼짐'이 정상이고, 넣은 뒤에는 로그인 시작이 공급자 인가 주소로 302 하는지 본다.
// 실행: node loadtest/public-platform5-open-check.mjs
import { BASE, Client } from "./lib/public-client.mjs";

const results = [];
const record = (name, ok, detail) => {
  results.push(ok);
  console.log(`${ok ? "성공" : "실패"} | ${name} | ${detail}`);
};

console.log(`시작 ${new Date().toISOString()}`);
const html = await fetch(BASE + "/me/accounts").then((r) => r.status);
record("화면 주소 200", html === 200, `/me/accounts ${html}`);

const anon = new Client();
const providers = await anon.req("GET", "/api/auth/oauth/providers");
const on = providers.body ?? {};
record("새 백엔드(V19): 공급자 목록 200", providers.status === 200 && typeof on.kakao === "boolean" && typeof on.naver === "boolean",
  `${providers.status} ${JSON.stringify(on)}`);

const mine = await anon.req("GET", "/api/me/social");
record("비회원 내 연결 목록 401", mine.status === 401, `${mine.status}`);

for (const p of ["kakao", "naver"]) {
  const link = await fetch(`${BASE}/api/auth/oauth/${p}/link/start`, { redirect: "manual" });
  const linkTo = link.headers.get("location") ?? "";
  const expectedLink = on[p] ? "/login?error=LOGIN_REQUIRED" : "/me/accounts?error=OAUTH_PROVIDER_DISABLED";
  record(`${p} 비회원 연결 시작 → ${expectedLink}`, link.status === 302 && linkTo.endsWith(expectedLink), `${link.status} ${linkTo}`);

  const login = await fetch(`${BASE}/api/auth/oauth/${p}/login/start`, { redirect: "manual" });
  const loginTo = login.headers.get("location") ?? "";
  if (on[p]) {
    const host = p === "kakao" ? "kauth.kakao.com" : "nid.naver.com";
    const u = new URL(loginTo);
    const ok = login.status === 302 && u.host === host && u.searchParams.get("state")?.length >= 32 && !u.searchParams.has("scope")
      && (u.searchParams.get("redirect_uri") ?? "").endsWith(`/api/auth/oauth/${p}/callback`);
    record(`${p} 로그인 시작 → 공급자 인가 주소(state 있음, 추가 동의 없음)`, ok, `${login.status} ${u.host} redirect_uri=${u.searchParams.get("redirect_uri")}`);
  } else {
    record(`${p} 꺼짐: 로그인 시작 → 준비 중 안내`, login.status === 302 && loginTo.endsWith("/login?error=OAUTH_PROVIDER_DISABLED"), `${login.status} ${loginTo}`);
  }
}

const forged = await fetch(`${BASE}/api/auth/oauth/kakao/callback?code=x&state=forged`, { redirect: "manual" });
record("위조 state 콜백 거부", forged.status === 302 && (forged.headers.get("location") ?? "").endsWith("/login?error=OAUTH_STATE_INVALID"),
  `${forged.status} ${forged.headers.get("location")}`);

const failed = results.filter((ok) => !ok).length;
console.log(`결과: 성공 ${results.length - failed} / 실패 ${failed}`);
process.exitCode = failed ? 1 : 0;
