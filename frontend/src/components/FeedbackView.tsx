import { formatSeoul } from "@/lib/api";
import { CATEGORY_LABEL, STATUS_LABEL, type FeedbackDetail } from "@/lib/feedback";

/** 개선 요청 본문·상태·처리 내용·기록 (작성자 화면과 개발자 화면이 함께 쓴다). 내용은 글자로만 넣는다. */
export function FeedbackView({ item, heading = "h1" }: { item: FeedbackDetail; heading?: "h1" | "h2" }) {
  const Title = heading;
  return (
    <article className="feedback">
      <Title>{item.title}</Title>
      <p className="meta">
        {CATEGORY_LABEL[item.category]} · {item.author} · {formatSeoul(item.createdAt)}
        {item.pagePath && ` · 보낸 화면 ${item.pagePath}`}
      </p>
      <p>
        <span className={`badge fb-${item.status.toLowerCase()}`} data-testid="feedback-status">
          {STATUS_LABEL[item.status]}
        </span>
      </p>
      <p className="prewrap">{item.body}</p>
      {(item.resolution || item.prUrl) && (
        <section className="card">
          <h3>처리 내용</h3>
          {item.resolution && <p className="prewrap">{item.resolution}</p>}
          {item.prUrl && (
            <a href={item.prUrl} target="_blank" rel="noopener noreferrer" className="link">
              관련 PR
            </a>
          )}
        </section>
      )}
      {item.history.length > 0 && (
        <section>
          <h3>상태 기록</h3>
          <ol className="history">
            {item.history.map((h, i) => (
              <li key={i}>
                {STATUS_LABEL[h.fromStatus]} → {STATUS_LABEL[h.toStatus]} · {formatSeoul(h.createdAt)}
                {h.reason && <span className="meta"> · 사유: {h.reason}</span>}
              </li>
            ))}
          </ol>
        </section>
      )}
    </article>
  );
}
