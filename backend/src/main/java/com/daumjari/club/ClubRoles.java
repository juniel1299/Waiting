package com.daumjari.club;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/**
 * 모임 안의 역할 조회. 매 요청마다 지금 가입·역할을 다시 읽는다 (화면이 보낸 역할은 믿지 않는다).
 * lock 이면 승인된 모임 행과 회원 행을 공유 잠금한다: 임명·회수·이전·삭제(모임 행 FOR UPDATE)와 모임 탈퇴(회원 행 삭제)가
 * 이 요청이 끝날 때까지 기다리므로, 권한 확인과 그 권한으로 하는 쓰기·삭제 사이에 역할이 바뀌지 않는다.
 * 잠금 순서: 모임 → 글 → 댓글 (모임 삭제가 같은 순서로 지우므로 교착이 없다).
 */
@Component
public class ClubRoles {

	public static final String MANAGER = "MANAGER";

	private final NamedParameterJdbcTemplate jdbc;

	public ClubRoles(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** 승인된 모임에서 이 계정의 역할(MANAGER·MEMBER), 회원이 아니면 null. 모임이 없거나 승인 전이면 404. */
	public String role(Long clubId, Long accountId, boolean lock) {
		MapSqlParameterSource params = new MapSqlParameterSource("club", clubId).addValue("account", accountId);
		if (jdbc.queryForList("select id from club where id = :club and status = 'APPROVED'" + (lock ? " for share" : ""), params,
				Long.class).isEmpty()) {
			throw new ApiException(ErrorCode.CLUB_NOT_FOUND);
		}
		if (accountId == null) {
			return null;
		}
		return jdbc
			.queryForList("select role from club_member where club_id = :club and account_id = :account"
					+ (lock ? " for share" : ""), params, String.class)
			.stream()
			.findFirst()
			.orElse(null);
	}

}
