package com.daumjari.board;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/** 게시판 API 요청·응답과 검색 조건. 작성자는 공개 라벨만 내보낸다 (로그인 아이디·계정 id 는 응답에 없음). */
public final class BoardDtos {

	private BoardDtos() {
	}

	/** 주소의 게시판 이름(community·notice)과 DB 값. 모임 게시판은 5단계에서 더한다. */
	public enum Board {

		COMMUNITY("community"), NOTICE("notice");

		private final String path;

		Board(String path) {
			this.path = path;
		}

		static Board fromPath(String path) {
			for (Board board : values()) {
				if (board.path.equals(path)) {
					return board;
				}
			}
			throw new ApiException(ErrorCode.BOARD_NOT_FOUND);
		}

	}

	public enum Field {
		ALL, TITLE, BODY
	}

	/** 작성자 표시: 운영자는 '운영자', 탈퇴한 계정은 '탈퇴한 회원', 그 밖에는 '회원#{id}' (공개 닉네임은 2단계 결정 뒤). */
	public record Author(String label) {
	}

	/** 목록에서는 body 가 null 이다 (응답 크기를 줄이고 미리보기는 제목으로). */
	public record PostResponse(Long id, Board board, String title, String body, Author author, Instant createdAt,
			Instant updatedAt, boolean edited, long commentCount, boolean canEdit, boolean canDelete) {
	}

	public record PostPage(List<PostResponse> items, int page, int size, long totalElements, int totalPages,
			boolean canWrite) {
	}

	/** 제목 1~100자, 본문 1~5000자 (앞뒤 공백만이면 안 됨). 제목은 앞뒤 공백을 지우고 본문은 원문 그대로 저장한다. */
	public record PostRequest(String title, String body) {

		PostRequest validated() {
			if (title == null || title.isBlank() || title.strip().length() > 100 || body == null || body.isBlank()
					|| body.length() > 5000) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			return new PostRequest(title.strip(), body);
		}

	}

	/** 검색 조건. page 는 1부터, size 기본 20(최대 50). 틀리면 400. */
	record Search(String keyword, Field field, int page, int size) {

		static Search parse(String q, String field, String page, String size) {
			String keyword = q == null ? "" : q.strip();
			if (keyword.length() > 100) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			Field f;
			try {
				f = field == null || field.isBlank() ? Field.ALL : Field.valueOf(field.strip().toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException e) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			return new Search(keyword, f, number(page, 1, 10_000), number(size, 20, 50));
		}

		long offset() {
			return (long) (page - 1) * size;
		}

		private static int number(String raw, int fallback, int max) {
			if (raw == null || raw.isBlank()) {
				return fallback;
			}
			int value;
			try {
				value = Integer.parseInt(raw.strip());
			}
			catch (NumberFormatException e) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			if (value < 1 || value > max) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			return value;
		}

	}

}
