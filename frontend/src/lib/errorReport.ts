// 화면 오류 보고: 메시지·경로(질의·조각 없이)·배포 버전만 보낸다. 저장 전 가림은 서버가 다시 한다. 다른 모듈에 기대지 않는다(node --test).

export interface ErrorReport {
  message: string;
  path: string;
  release: string;
}

const MESSAGE_MAX = 500;

export function reportPayload(error: unknown, location: string, release: string | undefined): ErrorReport {
  let message = "알 수 없는 오류";
  if (error instanceof Error) message = `${error.name}: ${error.message}`;
  else if (typeof error === "string" && error.trim()) message = error;
  return {
    message: message.slice(0, MESSAGE_MAX),
    path: location.replace(/[?#].*$/, "") || "/",
    release: release || "local",
  };
}

/** 같은 메시지는 한 번만, 화면을 새로 불러올 때까지 최대 limit 건만 send 로 넘긴다. */
export function createReporter(send: (report: ErrorReport) => void, limit = 5) {
  const seen = new Set<string>();
  return (error: unknown, location: string, release: string | undefined) => {
    const payload = reportPayload(error, location, release);
    if (seen.size >= limit || seen.has(payload.message)) return;
    seen.add(payload.message);
    send(payload);
  };
}
