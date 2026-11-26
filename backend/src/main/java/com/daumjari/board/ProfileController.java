package com.daumjari.board;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.board.Mentions.MentionView;
import com.daumjari.club.ClubRoles;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.common.FieldValidationException;

/**
 * 프로필: 공개 닉네임(규칙·유일성은 {@link Nicknames}), 소개 300자, 관심 태그 최대 5개와 멘션 후보.
 * 공개 프로필은 닉네임·소개·태그만 내보낸다(로그인 아이디·역할·연락처 없음). 멘션은 계정 id 로 이어져 이름을 바꿔도 끊기지 않는다.
 */
@RestController
public class ProfileController {

	record Profile(Long id, String nickname, String bio, List<String> tags) {
	}

	record ProfileRequest(String nickname, String bio, List<String> tags) {
	}

	record NicknameCheck(boolean available, String reason) {
	}

	static final int BIO_MAX = 300;

	static final int TAGS_MAX = 5;

	static final int TAG_MAX_LENGTH = 20;

	private final NamedParameterJdbcTemplate jdbc;

	private final Mentions mentions;

	private final ClubRoles clubRoles;

	private final Nicknames nicknames;

	public ProfileController(NamedParameterJdbcTemplate jdbc, Mentions mentions, ClubRoles clubRoles, Nicknames nicknames) {
		this.jdbc = jdbc;
		this.mentions = mentions;
		this.clubRoles = clubRoles;
		this.nicknames = nicknames;
	}

	@GetMapping("/api/me/profile")
	@Transactional(readOnly = true)
	public Profile profile(@AuthenticationPrincipal SessionUser user) {
		return load(user.id()).orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND));
	}

	/** 탈퇴한 계정은 없는 것으로(404). 로그인한 회원만 본다(비회원 401). */
	@GetMapping("/api/profiles/{id}")
	@Transactional(readOnly = true)
	public Profile publicProfile(@PathVariable Long id) {
		return load(id).orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND));
	}

	@GetMapping("/api/me/nickname/check")
	@Transactional(readOnly = true)
	public NicknameCheck check(@RequestParam String value, @AuthenticationPrincipal SessionUser user) {
		String nickname = Nicknames.normalize(value);
		String problem = Nicknames.problem(nickname);
		if (problem != null) {
			return new NicknameCheck(false, problem);
		}
		return nicknames.takenByOther(nickname, user.id()) ? new NicknameCheck(false, "이미 다른 회원이 쓰는 닉네임이에요.")
				: new NicknameCheck(true, null);
	}

	/** 보낸 항목만 바꾼다. 닉네임이 겹치면 DB 유일 인덱스가 막고 409 NICKNAME_TAKEN. */
	@PatchMapping("/api/me/profile")
	@Transactional
	public Profile change(@RequestBody ProfileRequest request, @AuthenticationPrincipal SessionUser user) {
		if (user.isDemo()) {
			throw new ApiException(ErrorCode.PROFILE_FORBIDDEN);
		}
		MapSqlParameterSource params = new MapSqlParameterSource("id", user.id());
		List<String> sets = new ArrayList<>();
		if (request.nickname() != null) {
			String nickname = Nicknames.normalize(request.nickname());
			String problem = Nicknames.problem(nickname);
			if (problem != null) {
				throw new FieldValidationException(Map.of("nickname", problem));
			}
			sets.add("nickname = :nickname");
			params.addValue("nickname", nickname);
		}
		if (request.bio() != null) {
			String bio = Nicknames.normalizeText(request.bio());
			if (bio.length() > BIO_MAX) {
				throw new FieldValidationException(Map.of("bio", "소개는 300자 이하로 써 주세요."));
			}
			sets.add("bio = :bio");
			params.addValue("bio", bio.isEmpty() ? null : bio);
		}
		if (request.tags() != null) {
			sets.add("interest_tags = cast(:tags as varchar(20)[])");
			params.addValue("tags", tags(request.tags()));
		}
		if (!sets.isEmpty()) {
			try {
				jdbc.update("update account set " + String.join(", ", sets) + " where id = :id", params);
			}
			catch (DuplicateKeyException taken) {
				throw new ApiException(ErrorCode.NICKNAME_TAKEN);
			}
		}
		return profile(user);
	}

	/** 로그인한 회원만 (비회원 401). 응답은 계정 id·공개 라벨뿐. */
	@GetMapping("/api/mentions/candidates")
	@Transactional(readOnly = true)
	public List<MentionView> candidates(@RequestParam(required = false) String q,
			@RequestParam(required = false) Long clubId, @AuthenticationPrincipal SessionUser user) {
		// 모임 안 멘션 후보는 그 모임 회원만, 묻는 사람도 지금 회원이어야 한다
		if (clubId != null && clubRoles.role(clubId, user.id(), false) == null) {
			throw new ApiException(ErrorCode.CLUB_MEMBERS_ONLY);
		}
		return mentions.candidates(q, clubId);
	}

	/** 앞뒤 공백 제거·중복 제거(순서 유지), 빈 태그는 버린다. 정리한 뒤 5개를 넘거나 20자를 넘으면 400. */
	private static String tags(List<String> raw) {
		Set<String> cleaned = new LinkedHashSet<>();
		for (String tag : raw) {
			String t = Nicknames.normalizeText(tag);
			if (t.length() > TAG_MAX_LENGTH) {
				throw new FieldValidationException(Map.of("tags", "태그는 20자 이하로 써 주세요."));
			}
			if (!t.isEmpty()) {
				cleaned.add(t);
			}
		}
		if (cleaned.size() > TAGS_MAX) {
			throw new FieldValidationException(Map.of("tags", "관심 태그는 5개까지예요."));
		}
		// 배열 리터럴 하나를 바인딩한다 (이름 붙은 파라미터는 자바 배열을 IN 목록으로 펼친다)
		return "{" + String.join(",", cleaned.stream()
			.map(t -> "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").toList()) + "}";
	}

	private java.util.Optional<Profile> load(Long id) {
		return jdbc.query("select id, coalesce(nickname, '회원' || id) nickname, bio, interest_tags from account "
				+ "where id = :id and deleted_at is null", new MapSqlParameterSource("id", id),
				(rs, n) -> new Profile(rs.getLong("id"), rs.getString("nickname"), rs.getString("bio"),
						tagList(rs.getArray("interest_tags"))))
			.stream().findFirst();
	}

	private static List<String> tagList(Array array) throws SQLException {
		return array == null ? List.of() : List.of((String[]) array.getArray());
	}

}
