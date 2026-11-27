# 카카오·네이버 계정 연결 설정 (확장 5단계)

2026-11-27 07:50 UTC 부터 운영에서 **켜져 있다**(카카오·네이버 모두 사용자 실제 계정으로 연결·로그인·해제 확인). 환경 변수를 지우면 다시 꺼진다(화면에 '준비 중', 로그인 화면에 소셜 버튼 없음). 비용: 두 서비스 모두 개발자 앱 등록·로그인 API 를 무료로 쓸 수 있는 것으로 알고 있지만, **카드 등록이 필요한지는 미확인**이다(가입 화면에서 직접 확인).

## 정책 요약

- 아이디·비밀번호로 로그인한 회원만 내 정보 > 로그인 계정 연결에서 연결한다. 소셜로 새로 가입하지 않는다. 소셜 로그인은 이미 연결된 계정에만 된다.
- 공급자에게서는 **사용자 식별 번호(id)만** 받는다. 동의 항목(이메일·전화번호·프로필 등)을 추가로 켜지 않는다. 액세스 토큰은 저장하지 않는다.
- 한 소셜 계정은 한 회원에만, 한 회원은 공급자마다 하나. 해제는 비밀번호 재확인. 탈퇴하면 연결이 지워진다.

## 리다이렉트(콜백) URI

```
https://waiting-mauve.vercel.app/api/auth/oauth/kakao/callback
https://waiting-mauve.vercel.app/api/auth/oauth/naver/callback
```

Render 백엔드 주소가 아니라 **프런트(Vercel) 주소**를 쓴다. 로그인 세션 쿠키가 Vercel 도메인에 있고 브라우저는 `/api` 를 Vercel 프록시로 부르기 때문에, 콜백도 같은 도메인으로 와야 서버가 세션에 둔 state 를 확인할 수 있다. 콜백은 프록시를 거쳐 Render 로 전달된다.

## 카카오 Developers

1. https://developers.kakao.com 에 카카오 계정으로 로그인 → 내 애플리케이션 → 애플리케이션 추가(앱 이름 '다음자리').
2. 앱 설정 > 플랫폼 > Web: 사이트 도메인 `https://waiting-mauve.vercel.app`.
3. 제품 설정 > 카카오 로그인: 활성화 ON, Redirect URI 에 위 카카오 콜백 주소 등록.
4. 동의항목: 추가로 켜지 않는다(닉네임 등도 필수로 바꾸지 않음. 서버는 id 만 쓴다).
5. (선택) 보안 > Client Secret: 코드 발급 후 '사용함'으로 켜면 `KAKAO_CLIENT_SECRET` 에도 넣는다. 켜지 않으면 비워 둔다.
6. 앱 키 > **REST API 키**가 `KAKAO_CLIENT_ID` 다.

### 실제 설정에서 겪은 것 (2026-11-27)

- **KOE006:** 리다이렉트 URI 를 **로그아웃 리다이렉트 URI** 칸에 넣어서 났다(칸 안내문에 '로그아웃'이라고 적혀 있다). 로그인용 칸(앱 → 플랫폼 키 → REST API 키 → 카카오 로그인 리다이렉트 URI, 또는 예전 화면의 카카오 로그인 → Redirect URI)에 넣어 해결.
- **"공급자에서 계정을 확인하지 못했어요" (OAUTH_FAILED):** 동의 뒤 토큰 단계 실패. 새 카카오 콘솔은 REST API 키의 **클라이언트 시크릿이 기본 사용함**이라 Render `KAKAO_CLIENT_SECRET` 이 필요하다. 시크릿 설정을 맞춘 뒤 연결·로그인·해제 성공.

### 문제 해결: KOE006 "앱 관리자 설정 오류"

카카오 로그인 화면에서 KOE006 이 나오면 **Redirect URI 가 등록돼 있지 않거나 글자가 다르다**는 뜻이다(2026-11-27 실제로 겪음).
- 카카오 Developers → 내 애플리케이션 → 다음자리 → 카카오 로그인(또는 앱 키 > REST API 키) 의 **Redirect URI** 에 아래를 글자 그대로 등록한다.
  `https://waiting-mauve.vercel.app/api/auth/oauth/kakao/callback`
  (https, 끝에 `/` 없음, `waiting-mauve.vercel.app` 철자 확인. Render 주소가 아니다.)
- 카카오 로그인 **활성화 ON** 인지 확인한다.
- `KAKAO_CLIENT_ID` 가 같은 앱의 **REST API 키**인지 확인한다(JavaScript 키·Admin 키가 아님).
- 저장하면 바로 반영된다(Render 재배포 필요 없음).

## 네이버 Developers

1. https://developers.naver.com 에 네이버 계정으로 로그인 → Application > 애플리케이션 등록.
2. 사용 API: **네이버 로그인**. 제공 정보는 필수 항목을 늘리지 않는다(서버는 id 만 쓴다).
3. 로그인 오픈 API 서비스 환경: PC 웹, 서비스 URL `https://waiting-mauve.vercel.app`, 네이버 로그인 Callback URL 에 위 네이버 콜백 주소.
4. 등록 후 **Client ID** → `NAVER_CLIENT_ID`, **Client Secret** → `NAVER_CLIENT_SECRET`.
5. 앱이 '개발 중' 상태면 멤버관리에 등록한 네이버 계정만 로그인된다. 모두가 쓰려면 검수 요청이 필요하다.

## Render 환경 변수 (백엔드 서비스)

| 이름 | 값 |
|---|---|
| `OAUTH_REDIRECT_BASE` | `https://waiting-mauve.vercel.app` (끝에 `/` 없이) |
| `KAKAO_CLIENT_ID` | 카카오 REST API 키 |
| `KAKAO_CLIENT_SECRET` | 카카오 클라이언트 시크릿(새 콘솔은 기본 사용함이라 대부분 필요) |
| `NAVER_CLIENT_ID` | 네이버 Client ID |
| `NAVER_CLIENT_SECRET` | 네이버 Client Secret (네이버는 필수) |

- 값은 Render 대시보드에만 넣고 저장소·문서·채팅에 붙이지 않는다. Vercel 에는 넣을 것이 없다.
- 공급자마다 따로 켜진다: 카카오 값만 넣으면 카카오만 켜진다. `OAUTH_REDIRECT_BASE` 가 비어 있으면 둘 다 꺼진다.
- 넣고 나면 Render 가 다시 배포한다. 끝난 뒤 `node loadtest/public-platform5-open-check.mjs` 로 켜졌는지 확인한다(로그인 시작이 `kauth.kakao.com`·`nid.naver.com` 으로 이동하는지, state 가 있고 추가 동의 요청이 없는지).

## 직접 확인 (켠 뒤)

1. 아이디로 로그인 → 내 정보 > 로그인 계정 연결 → 카카오 연결하기 → 카카오 동의 화면 → '카카오 계정을 연결했어요.'
2. 로그아웃 → 로그인 화면의 '카카오로 로그인' → 같은 계정으로 로그인되는지.
3. 내 정보 > 로그인 계정 연결 → 연결 해제(비밀번호 확인).
4. Neon 에서 남은 연결 확인: `select provider, linked_at from social_account where account_id = <내 계정 id>;` (해제 뒤 0행)
