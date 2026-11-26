package com.daumjari.feedback;

import java.time.Instant;
import java.util.List;

/** 개선사항 요청·응답. */
public final class FeedbackDtos {

	private FeedbackDtos() {
	}

	public enum Category {
		BUG, IDEA, OTHER
	}

	public enum Status {
		RECEIVED, IN_PROGRESS, VERIFY_REQUESTED, DONE, ON_HOLD;

		boolean needsReason() {
			return this == VERIFY_REQUESTED || this == ON_HOLD;
		}
	}

	public record SubmitRequest(String category, String title, String body, String pagePath) {
	}

	/** resolution·prUrl 이 null 이면 그대로, 빈 문자열이면 지운다. */
	public record ChangeRequest(String status, String reason, String resolution, String prUrl, Long version) {
	}

	public record Summary(Long id, Category category, String title, Status status, String author, Instant createdAt,
			Instant updatedAt) {
	}

	public record History(Status fromStatus, Status toStatus, String reason, Instant createdAt) {
	}

	public record Detail(Long id, Category category, String title, String body, String pagePath, Status status,
			String resolution, String prUrl, long version, String author, Instant createdAt, Instant updatedAt,
			List<History> history) {
	}

	public record Page(List<Summary> items, int page, int size, long totalElements) {
	}

}
