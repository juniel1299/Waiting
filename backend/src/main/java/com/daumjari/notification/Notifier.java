package com.daumjari.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림을 남긴다. 언제나 알림을 일으킨 변경(승격·글·댓글 저장)과 같은 트랜잭션 안에서만 동작하므로,
 * 그 변경이 롤백되면 알림도 남지 않는다. 같은 사건은 event_key 유니크로 한 번만 남는다 (재시도·반복 요청에도).
 */
@Component
public class Notifier {

	private static final String INSERT = """
			insert into notification (recipient_account_id, type, actor_account_id, session_id, post_id, comment_id,
			                          event_key, created_at)
			values (:recipient, :type, :actor, :session, :post, :comment, :key, :now)
			on conflict (event_key) do nothing
			""";

	private final NamedParameterJdbcTemplate jdbc;

	private final Clock clock;

	public Notifier(NamedParameterJdbcTemplate jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	/** 대기자가 확정됐다 (취소·정원 증가). 신청 하나는 한 번만 승격되므로 신청 id 가 사건 열쇠다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void promoted(Long reservationId, Long accountId, Long sessionId) {
		jdbc.update(INSERT, params(accountId, "PROMOTED", null, "PROMOTED:" + reservationId).addValue("session", sessionId));
	}

	/**
	 * 글(commentId 가 null) 또는 댓글·답글에서 새로 멘션된 사람들. 자기 자신은 알리지 않는다.
	 * 멘션 동기화가 돌려준 '새로 추가된' 계정만 넘기므로 수정할 때 기존 멘션에게는 다시 알리지 않는다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void mentioned(Long postId, Long commentId, Collection<Long> recipients, Long actorId) {
		String source = commentId == null ? "post:" + postId : "comment:" + commentId;
		for (Long recipient : recipients) {
			if (recipient.equals(actorId)) {
				continue;
			}
			jdbc.update(INSERT, params(recipient, "MENTION", actorId, "MENTION:" + source + ":" + recipient)
				.addValue("post", postId)
				.addValue("comment", commentId));
		}
	}

	/** 탈퇴: 계정 행은 익명화되어 남으므로 받은 알림은 여기서 지운다. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void forgetRecipient(Long accountId) {
		jdbc.update("delete from notification where recipient_account_id = :id", new MapSqlParameterSource("id", accountId));
	}

	private MapSqlParameterSource params(Long recipient, String type, Long actor, String key) {
		return new MapSqlParameterSource("recipient", recipient).addValue("type", type)
			.addValue("actor", actor)
			.addValue("session", null)
			.addValue("post", null)
			.addValue("comment", null)
			.addValue("key", key)
			.addValue("now", Instant.now(clock).atOffset(ZoneOffset.UTC));
	}

}
