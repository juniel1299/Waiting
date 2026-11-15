import { expect, request, type APIRequestContext, type Page } from "@playwright/test";

/** 로컬 시드 계정 (backend LocalSeedData, README "시드 계정"). */
export const SEED_PASSWORD = "daumjari-local-1!";

const baseURL = `http://localhost:${process.env.E2E_PORT ?? 3000}`;

/** 브라우저 밖에서 다른 사용자로 행동한다. 프런트 프록시(/api)를 그대로 거친다. */
export class ApiActor {
  private constructor(private readonly ctx: APIRequestContext) {}

  static async login(loginId: string): Promise<ApiActor> {
    const ctx = await request.newContext({ baseURL });
    const actor = new ApiActor(ctx);
    const res = await actor.post("/api/auth/login", { loginId, password: SEED_PASSWORD });
    expect(res.status(), `${loginId} 로그인`).toBe(200);
    return actor;
  }

  async post(path: string, data?: unknown) {
    const csrf = await (await this.ctx.get("/api/auth/csrf")).json();
    return this.ctx.post(path, { data, headers: { [csrf.headerName]: csrf.token } });
  }

  async createSession(title: string, capacity: number, startsAt?: Date): Promise<number> {
    const start = startsAt ?? new Date(Date.now() + 7 * 24 * 3600 * 1000);
    const res = await this.post("/api/operator/sessions", {
      title,
      startsAt: start.toISOString(),
      location: "E2E 스터디룸",
      capacity,
    });
    expect(res.status(), `회차 등록: ${await res.text()}`).toBe(201);
    return (await res.json()).id;
  }

  async reserve(sessionId: number) {
    return this.post(`/api/sessions/${sessionId}/reservations`);
  }

  async waitlist(sessionId: number) {
    return this.post(`/api/sessions/${sessionId}/waitlist`);
  }

  async cancel(reservationId: number) {
    return this.post(`/api/reservations/${reservationId}/cancel`);
  }

  async dispose() {
    await this.ctx.dispose();
  }
}

export async function loginInBrowser(page: Page, loginId: string) {
  await page.goto("/login");
  await page.getByLabel("아이디").fill(loginId);
  await page.getByLabel("비밀번호").fill(SEED_PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();
  await page.waitForURL((url) => url.pathname !== "/login");
}

/** 앱의 오류 안내. Next 가 넣는 경로 안내 영역(#__next-route-announcer__, role=alert)은 제외한다. */
export function appAlert(page: Page) {
  return page.locator('[role="alert"]:not(#__next-route-announcer__)');
}

export function uniqueTitle(prefix: string) {
  return `${prefix} ${Date.now().toString(36)}`;
}
