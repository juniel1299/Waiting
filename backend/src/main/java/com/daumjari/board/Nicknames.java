package com.daumjari.board;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 닉네임 규칙: NFC 정규화 → 앞뒤 공백 제거 → 연속 공백 하나. 2~12자, 한글·영문·숫자·공백만.
 * 운영자·관리자·개발자·다음자리·탈퇴한 회원(포함)과 '회원+숫자'(기본 표시와 헷갈림)는 쓸 수 없다.
 * 유일성은 대소문자를 무시한 DB 유일 인덱스(ux_account_nickname)가 최종으로 지킨다.
 */
@Component
public class Nicknames {

	static final Pattern ALLOWED = Pattern.compile("[가-힣A-Za-z0-9 ]{2,12}");

	static final Pattern DEFAULT_LIKE = Pattern.compile("회원\\s*#?\\d+");

	static final List<String> RESERVED = List.of("운영자", "관리자", "개발자", "다음자리", "탈퇴한 회원", "admin", "operator");

	private static final String[] ADJECTIVES = { "다정한", "씩씩한", "느긋한", "반짝이는", "상냥한", "부지런한", "용감한", "포근한", "명랑한", "차분한" };

	private static final String[] NOUNS = { "다람쥐", "고래", "부엉이", "수달", "여우", "펭귄", "고양이", "토끼", "판다", "참새" };

	private static final SecureRandom RANDOM = new SecureRandom();

	private final NamedParameterJdbcTemplate jdbc;

	public Nicknames(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public static String normalize(String raw) {
		return normalizeText(raw).replaceAll("\\s+", " ");
	}

	/** NFC 정규화 + 앞뒤 공백 제거 (소개·태그). */
	public static String normalizeText(String raw) {
		return raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
	}

	/** 규칙에 맞지 않으면 그 이유, 맞으면 null. */
	public static String problem(String nickname) {
		if (!ALLOWED.matcher(nickname).matches()) {
			return "닉네임은 한글·영문·숫자·공백으로 2~12자여야 해요.";
		}
		String lower = nickname.toLowerCase(Locale.ROOT);
		if (RESERVED.stream().anyMatch(lower::contains) || DEFAULT_LIKE.matcher(nickname).matches()) {
			return "운영 역할이나 기본 이름과 헷갈릴 수 있어 쓸 수 없어요.";
		}
		return null;
	}

	/** 다른 계정이 이미 쓰는지 (대소문자 무시). */
	public boolean takenByOther(String nickname, Long accountId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from account where lower(nickname) = lower(:n) and id <> :id)",
				new MapSqlParameterSource("n", nickname).addValue("id", accountId), Boolean.class));
	}

	/** 가입한 계정에 무작위 닉네임을 준다. 유일 인덱스와 부딪히면 새로 만들어 다시 시도(최대 10번), 끝내 못 주면 기본 표시로 둔다. */
	public void assignRandom(Long accountId) {
		for (int attempt = 0; attempt < 10; attempt++) {
			String candidate = ADJECTIVES[RANDOM.nextInt(ADJECTIVES.length)] + NOUNS[RANDOM.nextInt(NOUNS.length)]
					+ (1 + RANDOM.nextInt(9999));
			try {
				jdbc.update("update account set nickname = :n where id = :id and nickname is null",
						new MapSqlParameterSource("n", candidate).addValue("id", accountId));
				return;
			}
			catch (DataIntegrityViolationException collided) {
				// 같은 무작위 닉네임이 이미 있다: 다른 조합으로 다시
			}
		}
	}

}
