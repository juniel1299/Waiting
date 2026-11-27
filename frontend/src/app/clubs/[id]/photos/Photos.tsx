"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useId, useState, type ChangeEvent } from "react";

import { AuthGate } from "@/components/AuthGate";
import { api, ApiError, errorMessage, formatSeoul } from "@/lib/api";
import { uploadPlan, type ClubPhoto, type PhotoPage } from "@/lib/photo";
import { useUi } from "@/lib/uiStore";

export function Photos() {
  const { id } = useParams<{ id: string }>();
  return <AuthGate>{() => <ClubPhotos clubId={id} />}</AuthGate>;
}

/** 4MB 넘는 사진은 브라우저에서 긴 변 2400px JPEG 로 줄여 보낸다(프록시 요청 한도). 최종 처리·EXIF 제거는 서버가 한다. */
async function shrink(file: File): Promise<Blob> {
  const bitmap = await createImageBitmap(file);
  const scale = Math.min(1, 2400 / Math.max(bitmap.width, bitmap.height));
  const canvas = document.createElement("canvas");
  canvas.width = Math.round(bitmap.width * scale);
  canvas.height = Math.round(bitmap.height * scale);
  canvas.getContext("2d")?.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  bitmap.close();
  return new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("줄이기 실패"))), "image/jpeg", 0.9));
}

/** 모임 사진첩: 썸네일 격자(눌러 원본 크기로 보기), 사진 올리기, 본인·모임 운영자 삭제, 더 보기. 회원이 아니면 안내. */
function ClubPhotos({ clubId }: { clubId: string }) {
  const id = useId();
  const toast = useUi((s) => s.toast);
  const [page, setPage] = useState<PhotoPage | null>(null);
  const [failure, setFailure] = useState<{ text: string; forbidden: boolean } | null>(null);
  const [notice, setNotice] = useState<{ kind: "status" | "alert"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const [version, setVersion] = useState(0);
  const load = useCallback(() => setVersion((v) => v + 1), []);

  useEffect(() => {
    let active = true;
    api<PhotoPage>(`/api/clubs/${clubId}/photos`)
      .then((res) => {
        if (!active) return;
        setPage(res.data);
        setFailure(null);
      })
      .catch((e: unknown) => {
        if (!active) return;
        const forbidden = e instanceof ApiError && (e.status === 403 || e.status === 404);
        setFailure({ text: forbidden ? "모임 회원만 사진첩을 볼 수 있어요." : errorMessage(e), forbidden });
      });
    return () => {
      active = false;
    };
  }, [clubId, version]);

  async function more() {
    if (!page?.nextBefore) return;
    try {
      const { data } = await api<PhotoPage>(`/api/clubs/${clubId}/photos?before=${page.nextBefore}`);
      setPage({ ...data, items: [...page.items, ...data.items] });
    } catch (e) {
      toast(errorMessage(e), "error");
    }
  }

  async function upload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    const plan = uploadPlan(file);
    if (!plan.ok) {
      setNotice({ kind: "alert", text: plan.error });
      return;
    }
    setBusy(true);
    setNotice({ kind: "status", text: plan.shrink ? "사진을 줄여서 올리는 중…" : "올리는 중…" });
    try {
      const form = new FormData();
      form.append("file", plan.shrink ? await shrink(file) : file, file.name);
      await api(`/api/clubs/${clubId}/photos`, { method: "POST", body: form });
      setNotice({ kind: "status", text: "사진을 올렸어요. 위치 정보는 지우고 저장했어요." });
      load();
    } catch (e) {
      setNotice({ kind: "alert", text: errorMessage(e) });
    }
    setBusy(false);
  }

  async function remove(photo: ClubPhoto) {
    try {
      await api(`/api/clubs/${clubId}/photos/${photo.id}`, { method: "DELETE" });
      toast("사진을 지웠어요.");
      load();
    } catch (e) {
      toast(errorMessage(e), "error");
    }
  }

  if (failure?.forbidden) {
    return (
      <>
        <p role="alert">{failure.text}</p>
        <Link href={`/clubs/${clubId}`} className="link">
          모임 소개로
        </Link>
      </>
    );
  }
  return (
    <>
      <Link href={`/clubs/${clubId}`} className="link">
        ← 모임 소개
      </Link>
      <h1>모임 사진첩</h1>
      <p className="meta">
        모임 회원만 볼 수 있어요. JPEG·PNG·WebP, 5MB까지. 올리면 긴 변 1600px 로 줄이고 촬영 위치 같은 정보(EXIF)는 지워요.
        {page && ` · ${page.count} / ${page.maxPerClub}장`}
      </p>
      <div className="photo-upload">
        <label htmlFor={`${id}-file`} className={busy ? "button-label disabled" : "button-label"}>
          사진 올리기
        </label>
        <input id={`${id}-file`} type="file" accept="image/jpeg,image/png,image/webp" onChange={upload} disabled={busy} className="visually-hidden" />
      </div>
      {notice && <p role={notice.kind}>{notice.text}</p>}
      {failure && <p role="alert">{failure.text}</p>}
      {!page ? (
        <p>불러오는 중…</p>
      ) : page.items.length === 0 ? (
        <p className="meta">아직 사진이 없어요. 첫 사진을 올려 보세요.</p>
      ) : (
        <>
          <ul className="photo-grid" aria-label="사진 목록">
            {page.items.map((p) => (
              <li key={p.id} className="photo-cell">
                <a href={p.imageUrl} target="_blank" rel="noopener noreferrer">
                  {/* eslint-disable-next-line @next/next/no-img-element -- 회원 확인이 필요한 사진이라 이미지 최적화 서버를 거치지 않는다 */}
                  <img src={p.thumbUrl} alt={`${p.uploader.label}님이 ${formatSeoul(p.createdAt)}에 올린 사진`} loading="lazy" />
                </a>
                <span className="meta">{p.uploader.label}</span>
                {p.canDelete && (
                  <button type="button" className="secondary" onClick={() => remove(p)}>
                    삭제
                  </button>
                )}
              </li>
            ))}
          </ul>
          {page.nextBefore && (
            <button type="button" className="secondary" onClick={more}>
              더 보기
            </button>
          )}
        </>
      )}
    </>
  );
}
