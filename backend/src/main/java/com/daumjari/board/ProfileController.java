package com.daumjari.board;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.board.Mentions.MentionView;
import com.daumjari.club.ClubRoles;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/**
 * 공개 닉네임과 멘션 후보. 닉네임은 로그인 아이디와 별개이고 중복을 허용한다(멘션은 계정 id 로 연결).
 * 2~20자, 멘션 토큰 기호(@ [ ] #)와 다른 사람처럼 보일 이름('운영자', '탈퇴한 회원', '회원{숫자}')은 쓸 수 없다.
 */
@RestController
public class ProfileController {

	record Profile(String nickname) {
	}

	record ProfileRequest(String nickname) {
	}

	private static final Pattern DEFAULT_LIKE = Pattern.compile("회원\\s*#?\\d+");

	private static final List<String> RESERVED = List.of("운영자", "탈퇴한 회원");

	private final NamedParameterJdbcTemplate jdbc;

	private final Mentions mentions;

	private final ClubRoles clubRoles;

	public ProfileController(NamedParameterJdbcTemplate jdbc, Mentions mentions, ClubRoles clubRoles) {
		this.jdbc = jdbc;
		this.mentions = mentions;
		this.clubRoles = clubRoles;
	}

	@GetMapping("/api/me/profile")
	@Transactional(readOnly = true)
	public Profile profile(@AuthenticationPrincipal SessionUser user) {
		String nickname = jdbc.queryForObject("select coalesce(nickname, '회원' || id) from account where id = :id",
				new MapSqlParameterSource("id", user.id()), String.class);
		return new Profile(nickname);
	}

	@PatchMapping("/api/me/profile")
	@Transactional
	public Profile change(@RequestBody ProfileRequest request, @AuthenticationPrincipal SessionUser user) {
		if (user.isDemo()) {
			throw new ApiException(ErrorCode.PROFILE_FORBIDDEN);
		}
		String nickname = request.nickname() == null ? "" : request.nickname().strip();
		if (nickname.length() < 2 || nickname.length() > 20 || nickname.matches(".*[@\\[\\]#\\n].*")
				|| RESERVED.contains(nickname) || DEFAULT_LIKE.matcher(nickname).matches()) {
			throw new ApiException(ErrorCode.VALIDATION_FAILED);
		}
		jdbc.update("update account set nickname = :nickname where id = :id",
				new MapSqlParameterSource("nickname", nickname).addValue("id", user.id()));
		return new Profile(nickname);
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

}
