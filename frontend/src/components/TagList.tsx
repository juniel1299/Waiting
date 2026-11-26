/** 관심 태그 목록 (배지). */
export function TagList({ tags }: { tags: string[] }) {
  return (
    <ul className="tags" data-testid="profile-tags" aria-label="태그 목록">
      {tags.map((t) => (
        <li key={t} className="badge">
          {t}
        </li>
      ))}
    </ul>
  );
}
