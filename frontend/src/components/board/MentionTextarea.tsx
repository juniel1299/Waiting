"use client";

import { createContext, useContext, useEffect, useId, useRef, useState, type KeyboardEvent } from "react";

import { api } from "@/lib/api";
import { insertMention, mentionQueryAt } from "@/lib/mention";

/** 모임 글·댓글이면 그 모임: 멘션 후보를 모임 회원으로 좁힌다 (서버도 모임 회원에게만 연결). */
export const MentionClub = createContext<number | string | null>(null);

interface Candidate {
  id: number;
  nickname: string;
}

interface MentionTextareaProps {
  label: string;
  value: string;
  onChange: (value: string) => void;
  maxLength: number;
  rows?: number;
}

/**
 * `@` 를 치면 닉네임 후보를 보여 주고 고르면 `@[닉네임#id]` 토큰을 넣는 입력칸. 키보드: ↓↑ 이동, Enter 선택, Esc 닫기.
 * 후보 응답은 계정 id 와 공개 닉네임뿐이다.
 */
export function MentionTextarea({ label, value, onChange, maxLength, rows = 4 }: MentionTextareaProps) {
  const id = useId();
  const ref = useRef<HTMLTextAreaElement>(null);
  const [query, setQuery] = useState<{ query: string; start: number } | null>(null);
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [active, setActive] = useState(0);
  const pendingCursor = useRef<number | null>(null);
  const clubId = useContext(MentionClub);

  useEffect(() => {
    if (!query) return;
    let live = true;
    const timer = setTimeout(() => {
      api<Candidate[]>(`/api/mentions/candidates?q=${encodeURIComponent(query.query)}${clubId ? `&clubId=${clubId}` : ""}`)
        .then((res) => {
          if (!live) return;
          setCandidates(res.data);
          setActive(0);
        })
        .catch(() => live && setCandidates([]));
    }, 150);
    return () => {
      live = false;
      clearTimeout(timer);
    };
  }, [query, clubId]);

  useEffect(() => {
    if (pendingCursor.current !== null && ref.current) {
      ref.current.setSelectionRange(pendingCursor.current, pendingCursor.current);
      pendingCursor.current = null;
    }
  }, [value]);

  const open = query !== null && candidates.length > 0;

  function update(text: string, cursor: number) {
    onChange(text);
    const found = mentionQueryAt(text, cursor);
    setQuery(found);
    if (!found) setCandidates([]);
  }

  function choose(person: Candidate) {
    if (!query || !ref.current) return;
    const next = insertMention(value, query.start, ref.current.selectionStart, person);
    pendingCursor.current = next.cursor;
    onChange(next.text);
    setQuery(null);
    setCandidates([]);
    ref.current.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (!open) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      setActive((i) => (i + (event.key === "ArrowDown" ? 1 : candidates.length - 1)) % candidates.length);
    } else if (event.key === "Enter") {
      event.preventDefault();
      choose(candidates[active]);
    } else if (event.key === "Escape") {
      setQuery(null);
      setCandidates([]);
    }
  }

  return (
    <div className="field mention-field">
      <label htmlFor={`${id}-input`}>{label}</label>
      <textarea
        id={`${id}-input`}
        ref={ref}
        value={value}
        rows={rows}
        maxLength={maxLength}
        required
        role="combobox"
        aria-expanded={open}
        aria-autocomplete="list"
        aria-controls={`${id}-list`}
        aria-activedescendant={open ? `${id}-opt-${active}` : undefined}
        onChange={(e) => update(e.target.value, e.target.selectionStart)}
        onKeyDown={onKeyDown}
        onBlur={() => setTimeout(() => setCandidates([]), 150)}
      />
      <span className="meta">@ 를 치고 닉네임을 입력하면 회원을 멘션할 수 있어요. {value.length}/{maxLength}</span>
      {open && (
        <ul id={`${id}-list`} role="listbox" aria-label="멘션할 회원" className="mention-list">
          {candidates.map((c, i) => (
            <li
              key={c.id}
              id={`${id}-opt-${i}`}
              role="option"
              aria-selected={i === active}
              onMouseDown={(e) => {
                e.preventDefault();
                choose(c);
              }}
            >
              {c.nickname} <span className="meta">#{c.id}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
