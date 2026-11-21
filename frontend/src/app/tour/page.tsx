import Image from "next/image";
import Link from "next/link";

export const metadata = { title: "다음자리 시연" };

const STEPS = [
  {
    src: "/tour/step-1-home.png",
    title: "1. 데모 체험 시작",
    text: "가입 없이 ‘데모 체험하기’ 를 누르면 나만의 체험 회차가 만들어져요. 다른 방문자와 섞이지 않아요.",
  },
  {
    src: "/tour/step-2-full-session.png",
    title: "2. 마감된 회차에 대기 신청",
    text: "샘플 참가자 2명이 자리를 채운 마감 회차예요. ‘대기 신청’ 을 누르면 대기 1번이 돼요.",
  },
  {
    src: "/tour/step-3-waiting.png",
    title: "3. 빈자리 발생",
    text: "‘샘플 참가자 취소’ 로 확정자 한 명이 취소하면 자리가 하나 비어요.",
  },
  {
    src: "/tour/step-4-confirmed.png",
    title: "4. 대기 순서대로 예약 확정",
    text: "같은 트랜잭션에서 첫 대기자가 확정되고, 화면은 새로고침 없이 ‘예약 확정’ 으로 바뀌어요.",
  },
];

/** 백엔드 호출 없이 열리는 정적 시연 자료. 서버가 깨는 동안에도 볼 수 있다. */
export default function TourPage() {
  return (
    <main className="container narrow">
      <h1>다음자리 시연 (34초)</h1>
      <p className="meta">공개 서비스에서 녹화한 핵심 흐름이에요: 대기 신청 → 빈자리 발생 → 예약 확정.</p>
      <video
        className="tour-video"
        src="/tour/demo-flow.webm"
        poster="/tour/poster.png"
        controls
        muted
        playsInline
        preload="metadata"
        width={390}
        height={844}
      >
        이 브라우저는 영상을 재생할 수 없어요. 아래 단계별 화면을 봐 주세요.
      </video>
      <ol className="tour-steps">
        {STEPS.map((step) => (
          <li key={step.src}>
            <h2>{step.title}</h2>
            <p>{step.text}</p>
            <Image src={step.src} alt={step.title} width={705} height={1200} sizes="(max-width: 400px) 100vw, 360px" />
          </li>
        ))}
      </ol>
      <Link href="/" className="link">
        직접 체험하러 가기
      </Link>
    </main>
  );
}
