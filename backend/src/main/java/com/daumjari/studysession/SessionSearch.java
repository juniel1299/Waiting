package com.daumjari.studysession;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/**
 * 회차 검색 조건 (전체 회차·내가 만든 회차 공통). 쿼리 문자열을 그대로 받아 검증하고, 틀리면 400 VALIDATION_FAILED.
 * 날짜는 서울 기준 날짜이고 종료일을 포함한다. page 는 1부터.
 */
public record SessionSearch(Scope scope, String keyword, Field field, LocalDate from, LocalDate to,
		StatusFilter status, Sort sort, int page, int size) {

	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	static final int DEFAULT_SIZE = 10;

	static final int MAX_SIZE = 50;

	static final int MAX_PAGE = 10_000;

	static final int MAX_KEYWORD = 100;

	public enum Scope {
		ALL, MINE
	}

	public enum Field {
		ALL, TITLE, LOCATION
	}

	/** UPCOMING: 시작 전 전체, OPEN: 바로 예약 가능, WAITLIST: 대기 신청 가능(마감·대기자 있음), ENDED: 시작 시각이 지남. */
	public enum StatusFilter {
		UPCOMING, ALL, OPEN, WAITLIST, ENDED
	}

	/**
	 * 시작 임박순(앞으로 시작할 회차를 가까운 순, 시작된 회차는 그 뒤에 최근 시작부터) · 시작일 최신순 · 시작일 오래된순 · 최근 등록순 ·
	 * 확정 참가자 많은순 · 확정 참가자 적은순 · 잔여석 많은순. 보조 정렬은 항상 id 오름차순.
	 */
	public enum Sort {
		SOON, LATEST_START, EARLIEST_START, NEWEST, MOST_CONFIRMED, FEWEST_CONFIRMED, MOST_SEATS
	}

	public static SessionSearch parse(String scope, String q, String field, String from, String to, String status,
			String sort, String page, String size) {
		String keyword = q == null ? "" : q.strip();
		if (keyword.length() > MAX_KEYWORD) {
			throw invalid();
		}
		LocalDate fromDate = date(from);
		LocalDate toDate = date(to);
		if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
			throw invalid();
		}
		return new SessionSearch(option(Scope.class, scope, Scope.ALL), keyword, option(Field.class, field, Field.ALL),
				fromDate, toDate, option(StatusFilter.class, status, StatusFilter.UPCOMING),
				option(Sort.class, sort, Sort.SOON), number(page, 1, 1, MAX_PAGE),
				number(size, DEFAULT_SIZE, 1, MAX_SIZE));
	}

	/** 시작 시각 하한 (포함). */
	Instant startsFrom() {
		return from == null ? null : from.atStartOfDay(SEOUL).toInstant();
	}

	/** 시작 시각 상한 (미포함) = 종료일 다음 날 0시. */
	Instant startsBefore() {
		return to == null ? null : to.plusDays(1).atStartOfDay(SEOUL).toInstant();
	}

	private static <E extends Enum<E>> E option(Class<E> type, String raw, E fallback) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			throw invalid();
		}
	}

	private static LocalDate date(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return LocalDate.parse(raw.strip());
		}
		catch (DateTimeParseException e) {
			throw invalid();
		}
	}

	private static int number(String raw, int fallback, int min, int max) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		int value;
		try {
			value = Integer.parseInt(raw.strip());
		}
		catch (NumberFormatException e) {
			throw invalid();
		}
		if (value < min || value > max) {
			throw invalid();
		}
		return value;
	}

	private static ApiException invalid() {
		return new ApiException(ErrorCode.VALIDATION_FAILED);
	}

}
