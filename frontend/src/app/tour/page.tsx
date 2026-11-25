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

const ROLE_STEPS = [
  {
    src: "/tour/community/role-1-members.png",
    title: "모임 회원 목록 (만든 사람 화면)",
    text: "역할은 색과 글자로 보여요. 만든 사람에게만 운영자 임명·회수와 생성자 넘기기 버튼이 있어요.",
  },
  {
    src: "/tour/community/role-2-appointed.png",
    title: "운영자 임명",
    text: "하람 님을 모임 운영자로 임명하면 그때부터 모임 공지를 쓸 수 있어요. 결과는 화면 읽기 프로그램에도 알려요.",
  },
  {
    src: "/tour/community/role-3-revoked.png",
    title: "운영자 회수",
    text: "회수하면 이미 로그인한 화면에서도 다음 요청부터 공지 작성·수정·삭제가 거절돼요. 서버가 요청마다 지금 역할을 다시 확인해요.",
  },
  {
    src: "/tour/community/role-4-transferred.png",
    title: "생성자 넘기기",
    text: "확인창을 거쳐 하람 님에게 생성자를 넘기면, 이전 생성자는 모임 운영자로 남고 이제 모임을 탈퇴할 수 있어요.",
  },
];

/** 백엔드 호출 없이 열리는 정적 시연 자료. 서버가 깨는 동안에도 볼 수 있다. */
export default function TourPage() {
  return (
    <main className="container narrow">
      <h1>다음자리 시연</h1>
      <h2>예약·자동 승격 (34초)</h2>
      <p className="meta">공개 서비스에서 녹화한 핵심 흐름이에요: 대기 신청 → 빈자리 발생 → 예약 확정.</p>
      <video
        className="tour-video"
        poster="/tour/poster.png"
        controls
        muted
        playsInline
        preload="metadata"
        width={390}
        height={844}
      >
        {/* iOS Safari 는 webm 을 못 틀 수 있어 H.264 mp4 를 먼저 둔다 */}
        <source src="/tour/demo-flow.mp4" type="video/mp4" />
        <source src="/tour/demo-flow.webm" type="video/webm" />
        이 브라우저는 영상을 재생할 수 없어요. 아래 단계별 화면을 봐 주세요.
      </video>
      <ol className="tour-steps">
        {STEPS.map((step) => (
          <li key={step.src}>
            <h3>{step.title}</h3>
            <p>{step.text}</p>
            <Image src={step.src} alt={step.title} width={705} height={1200} sizes="(max-width: 400px) 100vw, 360px" />
          </li>
        ))}
      </ol>
      <section aria-labelledby="community-tour" className="tour-section">
        <h2 id="community-tour">모임·커뮤니티 (15초)</h2>
        <p className="meta">
          로컬 프로덕션 빌드에서 촬영용 가명 계정(새별·하람·모임장)으로 녹화했어요. 운영 서비스 화면이 아니며, 촬영 데이터는 녹화 뒤 지웠어요.
          흐름: 모임 찾기 → 가입 → 모임 게시판 글에 댓글·답글 → 다른 회원의 멘션 알림 → 알림을 열어 그 댓글로 이동.
        </p>
        <video
          className="tour-video"
          poster="/tour/community/community-poster.png"
          controls
          muted
          playsInline
          preload="metadata"
          width={390}
          height={844}
          aria-label="모임·커뮤니티 시연 영상"
        >
          <source src="/tour/community/community-flow.mp4" type="video/mp4" />
          <source src="/tour/community/community-flow.webm" type="video/webm" />
          이 브라우저는 영상을 재생할 수 없어요. 아래 화면 자료를 봐 주세요.
        </video>
        <h3>모임 역할 관리 화면 자료</h3>
        <p className="meta">운영자 임명·회수와 생성자 넘기기는 만든 사람 화면의 회원 목록에서 해요. 같은 촬영에서 찍은 화면이에요.</p>
        <ol className="tour-steps">
          {ROLE_STEPS.map((step) => (
            <li key={step.src}>
              <h4>{step.title}</h4>
              <p>{step.text}</p>
              <Image src={step.src} alt={`${step.title}: ${step.text}`} width={780} height={1688} sizes="(max-width: 400px) 100vw, 360px" />
            </li>
          ))}
        </ol>
      </section>
      <Link href="/" className="link">
        직접 체험하러 가기
      </Link>
    </main>
  );
}
