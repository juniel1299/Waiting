// 공개 배포의 확장 7단계(모임 사진첩)를 전용 테스트 계정으로 확인하고 정리한다.
// 모임 승인에 운영자 권한이 필요해 사람이 직접 실행한다: node loadtest/public-platform7-check.mjs
// 운영자 아이디·비밀번호는 실행 중에 입력받는다(화면에 보이지 않고 저장·출력하지 않음).
// 테스트 회원은 2명만 가입한다(같은 IP 가입 제한 15분 5회 안). 비밀번호는 무작위·출력 없음.
//
// 흐름: A·B 가입 → A 모임 신청 → 운영자 승인 → B 가입 → B 가입 전 403 확인
//      → B 가 확장자만 .jpg 인 글자 파일 → 400 PHOTO_INVALID → B 가 위치 표지가 든 EXIF(방향 6) JPEG 올림 → 201(방향 반영 20×30)
//      → A 가 원본 보기: image/jpeg·Cache-Control private·nosniff, 표지·EXIF 없음 → 썸네일 → 비회원 401
//      → B 본인 사진 삭제 → 원본 404
// 정리(finally): A 가 모임 삭제(사진 함께), A·B 탈퇴, 운영자 로그아웃. 끝에 Neon 확인 SQL.
import { askOperator } from "./lib/operator-prompt.mjs";
import { brief, Client, expect, log, sleep } from "./lib/public-client.mjs";

// 30×20 JPEG(서버 쪽 Java ImageIO 로 만든 바이트). 여기에 EXIF(APP1: 방향 6, 설명=SECRET)를 끼워 올린다
const JPEG_30X20 = Buffer.from("/9j/4AAQSkZJRgABAgAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAUAB4DASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDIq1pv/IUtP+uyf+hCqtWtN/5Clp/12T/0IV9Riv4E/R/kfNUP4sfVHodFFFfiB+knmVWtN/5Clp/12T/0IUUV+34r+BP0f5H5tQ/ix9Ueh0UUV+IH6Sf/2Q==", "base64");
const SECRET = "GPS-37.5665N-126.9780E-SECRET";

function withExif(jpeg, orientation) {
  const text = Buffer.from(SECRET + "\0", "ascii");
  const tiff = Buffer.alloc(8 + 2 + 12 * 2 + 4 + text.length);
  tiff.write("II", 0, "ascii");
  tiff.writeUInt16LE(42, 2);
  tiff.writeUInt32LE(8, 4);
  tiff.writeUInt16LE(2, 8);
  tiff.writeUInt16LE(0x010e, 10); tiff.writeUInt16LE(2, 12); tiff.writeUInt32LE(text.length, 14); tiff.writeUInt32LE(8 + 2 + 24 + 4, 18);
  tiff.writeUInt16LE(0x0112, 22); tiff.writeUInt16LE(3, 24); tiff.writeUInt32LE(1, 26); tiff.writeUInt16LE(orientation, 30);
  tiff.writeUInt32LE(0, 34);
  text.copy(tiff, 38);
  const exif = Buffer.concat([Buffer.from("Exif\0\0", "ascii"), tiff]);
  const length = exif.length + 2;
  return Buffer.concat([jpeg.subarray(0, 2), Buffer.from([0xff, 0xe1, length >> 8, length & 0xff]), exif, jpeg.subarray(2)]);
}

function file(bytes, type, name) {
  const form = new FormData();
  form.append("file", new Blob([bytes], { type }), name);
  return form;
}

const tag = new Date().toISOString().slice(5, 16).replace(/\D/g, "");
const op = new Client(...(await askOperator()));
const a = new Client(`qa_pa7a${tag}`);
const b = new Client(`qa_pa7b${tag}`);
let failed = false;
let club = null;
let clubDeleted = false;
const ids = [];
const photos = [];

async function waitForBackend(who) {
  const until = Date.now() + 10 * 60 * 1000;
  for (;;) {
    const r = await who.req("GET", "/api/clubs/999999999/photos");
    if (r.status === 404 && r.body?.code === "CLUB_NOT_FOUND") return;
    expect(Date.now() < until, `사진첩 API 가 준비되지 않음: ${brief(r)}. 배포(Render)가 끝난 뒤 다시 실행`);
    log(`배포 대기: 사진첩 API ${brief(r)}. 30초 뒤 다시 확인, 최대 10분`);
    await sleep(30 * 1000);
  }
}

try {
  const ol = await op.login();
  op.password = null;
  if (ol.status === 401) throw new Error("운영자 로그인 실패 401: 아이디·비밀번호가 맞지 않음. 같은 아이디로 15분에 5번 넘게 틀리면 잠기니(429) 확인 뒤 다시 실행");
  expect(ol.status === 200 && ol.body?.role === "OPERATOR", `운영자 로그인 실패 ${brief(ol)}`);
  log("운영자 로그인 200");
  await waitForBackend(op);
  log("A", a.loginId, await a.signupAndLogin());
  ids.push((await a.req("GET", "/api/auth/me")).body?.id);
  log("B", b.loginId, await b.signupAndLogin());
  ids.push((await b.req("GET", "/api/auth/me")).body?.id);

  const created = await a.req("POST", "/api/clubs", { name: `[QA] 사진첩 점검 ${tag}`, intro: "공개 점검용 모임(점검 뒤 삭제)", place: "온라인", mode: "ONLINE" });
  club = created.body?.id;
  const approved = await op.req("POST", `/api/operator/clubs/${club}/approve`, { version: created.body?.version });
  const before = await b.req("GET", `/api/clubs/${club}/photos`);
  const joined = await b.req("POST", `/api/clubs/${club}/join`);
  log(`모임 신청 ${brief(created)} → 승인 ${brief(approved)} → B 가입 전 사진첩 ${brief(before)} → B 가입 ${brief(joined)}`);
  expect(created.status === 201 && approved.status === 200 && before.status === 403 && joined.status < 300, "모임 준비·가입 전 차단이 예상과 달라 멈춤");

  const fake = await b.req("POST", `/api/clubs/${club}/photos`, file(Buffer.from("<html>not an image</html>"), "image/jpeg", "fake.jpg"));
  const exifJpeg = withExif(JPEG_30X20, 6);
  const up = await b.req("POST", `/api/clubs/${club}/photos`, file(exifJpeg, "image/jpeg", "phone.jpg"));
  if (up.body?.id) photos.push(up.body.id);
  log(`확장자만 .jpg 인 글자 파일 ${brief(fake)} | EXIF(표지 포함 ${exifJpeg.includes(SECRET)}) JPEG ${brief(up)} 크기 ${up.body?.width}×${up.body?.height}`);
  expect(fake.status === 400 && fake.body?.code === "PHOTO_INVALID" && up.status === 201 && up.body.width === 20 && up.body.height === 30,
    "형식 확인·방향 반영이 예상과 달라 멈춤");

  const image = await a.raw(up.body.imageUrl);
  const thumb = await a.raw(up.body.thumbUrl);
  const anonImage = await new Client().raw(up.body.imageUrl);
  const stored = image.bytes.toString("latin1");
  log(`A 원본 보기 ${image.status} ${image.headers.get("content-type")} | ${image.headers.get("cache-control")} | ${image.headers.get("x-content-type-options")} | 표지 남음 ${stored.includes(SECRET)}, EXIF 남음 ${stored.includes("Exif\0\0")} | 썸네일 ${thumb.status} | 비회원 ${anonImage.status}`);
  expect(image.status === 200 && image.headers.get("content-type") === "image/jpeg" && (image.headers.get("cache-control") ?? "").includes("private")
    && image.headers.get("x-content-type-options") === "nosniff" && !stored.includes(SECRET) && !stored.includes("Exif\0\0")
    && thumb.status === 200 && anonImage.status === 401, "이미지 응답·EXIF 제거가 예상과 달라 멈춤");

  const removed = await b.req("DELETE", `/api/clubs/${club}/photos/${up.body.id}`);
  const gone = await a.raw(up.body.imageUrl);
  log(`B 본인 사진 삭제 ${brief(removed)} → 원본 ${gone.status}`);
  expect(removed.status === 204 && gone.status === 404, "삭제가 예상과 달라 멈춤");
  log("확장 7단계 공개 점검 통과");
} catch (e) {
  failed = true;
  log("중단:", e.message);
} finally {
  if (club && a.loggedIn) {
    const del = await a.req("DELETE", `/api/clubs/${club}`);
    clubDeleted = del.status === 204;
    log("정리: 모임 삭제(사진 함께)", brief(del));
  }
  if (club && !clubDeleted && op.loggedIn) log("정리: 운영자가 모임 삭제", brief(await op.req("DELETE", `/api/clubs/${club}`)));
  for (const [name, who] of [["A", a], ["B", b]]) {
    if (who.loggedIn) log(`정리: ${name} 탈퇴`, brief(await who.req("POST", "/api/me/withdraw", { password: who.password })));
  }
  if (op.loggedIn) log("정리: 운영자 로그아웃", brief(await op.req("POST", "/api/auth/logout")));
  const known = ids.filter(Boolean).join(", ") || "0";
  const photoIds = photos.join(", ") || "0";
  log("Neon SQL 편집기에서 확인 (기대값: 0 | 0 | 0 | 2 — 남은 모임, 남은 사진, 남은 사진 바이트, 탈퇴한 점검 계정):");
  console.log(`select (select count(*) from club where id = ${club ?? 0}) as club_left,
       (select count(*) from club_photo where club_id = ${club ?? 0}) as photos_left,
       (select count(*) from photo_object where photo_id in (${photoIds})) as bytes_left,
       (select count(*) from account where id in (${known}) and deleted_at is not null) as withdrawn;`);
  process.exitCode = failed ? 1 : 0;
}
