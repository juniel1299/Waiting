// 공개 점검 스크립트 공통: 운영자 아이디·비밀번호를 실행 중에 묻는다. 비밀번호는 화면에 쓰지 않고 저장·출력하지 않는다.
// 실제 터미널에서는 비밀번호를 원시 입력(raw mode)으로 받는다(readline 이 안내 문구를 지우는 문제, 9b3dab52).
import { createInterface } from "node:readline";

/** 운영자 아이디·비밀번호를 묻는다. 비밀번호 입력 글자는 화면에 쓰지 않는다. */
export async function askOperator() {
  if (!process.stdin.isTTY) {
    // 파이프 입력(로컬 리허설): 첫 줄 아이디, 둘째 줄 비밀번호. 아무것도 화면에 쓰지 않는다
    const lines = [];
    for await (const line of createInterface({ input: process.stdin })) {
      lines.push(line);
      if (lines.length === 2) break;
    }
    return [lines[0]?.trim() ?? "", lines[1] ?? ""];
  }
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const loginId = await new Promise((resolve) => rl.question("운영자 아이디: ", resolve));
  rl.close();
  // 빈 값이면 로그인을 시도하지 않고 다시 묻는다 (최대 3번)
  for (let attempt = 1; attempt <= 3; attempt++) {
    const password = await readSecret("운영자 비밀번호(입력해도 화면에 보이지 않음, 다 치고 Enter): ");
    if (password) return [loginId.trim(), password];
    console.log("비밀번호가 비어 있어요. 다시 입력해 주세요.");
  }
  throw new Error("비밀번호를 3번 비워 둬서 멈춤 (로그인 시도 없음)");
}

/**
 * 비밀번호를 원시 입력(raw mode)으로 받는다. readline 은 줄을 다시 그리며 안내 문구까지 지워 버려서 쓰지 않는다.
 * 입력 글자는 화면에 아무것도 쓰지 않고, Backspace 는 지우고, Ctrl+C 는 종료한다.
 */
function readSecret(prompt) {
  process.stdout.write(prompt);
  return new Promise((resolve) => {
    let value = "";
    const onData = (chunk) => {
      for (const ch of chunk) {
        if (ch === "\r" || ch === "\n") {
          process.stdin.off("data", onData);
          process.stdin.setRawMode(false);
          process.stdin.pause();
          process.stdout.write("\n");
          resolve(value);
          return;
        }
        if (ch === "\u0003") {
          process.stdin.setRawMode(false);
          process.stdout.write("\n");
          process.exit(130);
        }
        if (ch === "\u007f" || ch === "\b") value = value.slice(0, -1);
        else value += ch;
      }
    };
    process.stdin.setRawMode(true);
    process.stdin.setEncoding("utf8");
    process.stdin.resume();
    process.stdin.on("data", onData);
  });
}
