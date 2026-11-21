import Link from "next/link";

/** 서버가 깨는 동안 볼 시연 자료. 새 탭으로 열어 원래 탭의 자동 재시도가 끊기지 않게 한다. */
export function TourLink() {
  return (
    <Link href="/tour" target="_blank" rel="noopener" className="link">
      기다리는 동안 시연 보기
    </Link>
  );
}
