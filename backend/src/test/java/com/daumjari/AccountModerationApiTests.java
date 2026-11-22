package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.daumjari.reservation.Reservation;
import com.daumjari.studysession.StudySession;

/**
 * 운영자 계정 관리: 검색, 영향 미리보기, 이용 정지·해제·강제탈퇴(사유 필수), 기존 세션 차단, 정지 계정 로그인 안내,
 * 운영자 본인 보호, 조치 기록(처리자·대상 id·사유·시각, 탈퇴자는 익명 표시).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class AccountModerationApiTests extends ApiTestSupport {

	static final Instant LATER = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);

	@Autowired
	JdbcTemplate jdbc;

	Account operator;

	MockHttpSession operatorSession;

	@BeforeEach
	void setUp() throws Exception {
		operator = newAccount("mod-op", Role.OPERATOR);
		operatorSession = login(operator);
	}

	@Test
	void operatorSearchesAccountsByLoginIdOrNameWithStatusFilterAndPages() throws Exception {
		String token = "acct" + UUID.randomUUID().toString().substring(0, 6);
		Account a = newAccount(token + "-a", Role.PARTICIPANT);
		Account b = newAccount(token + "-b", Role.PARTICIPANT);
		newAccount(token + "-c", Role.PARTICIPANT);
		moderate(b, "suspend", "도배").andExpect(status().isOk());

		mockMvc.perform(get("/api/operator/accounts").param("q", token.toUpperCase()).session(operatorSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.items[0].id").value(a.getId()))
			.andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
			.andExpect(jsonPath("$.items[0].role").value("PARTICIPANT"))
			.andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
		mockMvc.perform(get("/api/operator/accounts").param("q", token).param("status", "SUSPENDED")
			.session(operatorSession))
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.items[0].id").value(b.getId()))
			.andExpect(jsonPath("$.items[0].suspendedAt").isNotEmpty());
		mockMvc.perform(get("/api/operator/accounts").param("q", token).param("size", "2").param("page", "2")
			.session(operatorSession))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.totalPages").value(2));
		mockMvc.perform(get("/api/operator/accounts").param("status", "NOPE").session(operatorSession))
			.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/operator/accounts").session(login(a))).andExpect(status().isForbidden());
	}

	@Test
	void suspensionNeedsAReasonBlocksTheExistingSessionAndLoginUntilLifted() throws Exception {
		Account member = newAccount("mod-member", Role.PARTICIPANT);
		MockHttpSession memberSession = login(member);
		mockMvc.perform(get("/api/me/reservations").session(memberSession)).andExpect(status().isOk());

		moderate(member, "suspend", "   ").andExpect(status().isBadRequest());
		moderate(member, "suspend", "가".repeat(501)).andExpect(status().isBadRequest());
		moderate(member, "suspend", "반복 노쇼").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUSPENDED"));
		moderate(member, "suspend", "다시").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_STATE_CONFLICT"));

		// 이미 로그인한 세션도 다음 요청에서 끊긴다
		mockMvc.perform(get("/api/me/reservations").session(memberSession)).andExpect(status().isUnauthorized());
		// 비밀번호가 맞을 때만 정지 사실을 알린다 (틀리면 일반 로그인 실패와 같다)
		loginAttempt(member, PASSWORD).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
		loginAttempt(member, "wrong-pass-1!").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

		moderate(member, "unsuspend", "소명 확인").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));
		loginAttempt(member, PASSWORD).andExpect(status().isOk());
	}

	@Test
	void impactPreviewThenForcedWithdrawalReusesTheWithdrawalRules() throws Exception {
		Account target = newAccount("mod-target", Role.PARTICIPANT);
		MockHttpSession targetSession = login(target);
		Account other = newAccount("mod-other", Role.PARTICIPANT);
		Account waiter = newAccount("mod-waiter", Role.PARTICIPANT);
		// 다른 사람 회차: 시작 전 확정(대기자 있음 → 승격 1), 시작 전 대기, 시작된 회차 확정(남김)
		StudySession full = newSession("남의 마감 회차", LATER, 1);
		confirmDirectly(full, target);
		Reservation queued = reservations.save(Reservation.waitlist(full, waiter, Instant.now()));
		StudySession waitingOn = newSession("남의 대기 회차", LATER, 1);
		confirmDirectly(waitingOn, other);
		reservations.save(Reservation.waitlist(waitingOn, target, Instant.now()));
		StudySession started = newSession("시작된 회차", LATER, 3);
		Reservation kept = confirmDirectly(started, target);
		jdbc.update("update study_session set starts_at = now() - interval '1 hour' where id = ?", started.getId());
		// 대상이 만든 회차 2개와 그 신청 (함께 삭제)
		StudySession own1 = ownedBy(target, "대상의 회차 1");
		confirmDirectly(own1, other);
		StudySession own2 = ownedBy(target, "대상의 회차 2");
		reservations.save(Reservation.waitlist(own2, waiter, Instant.now()));

		mockMvc.perform(get("/api/operator/accounts/{id}/impact", target.getId()).session(operatorSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.upcomingConfirmed").value(1))
			.andExpect(jsonPath("$.waiting").value(1))
			.andExpect(jsonPath("$.startedConfirmedKept").value(1))
			.andExpect(jsonPath("$.promotions").value(1))
			.andExpect(jsonPath("$.ownedSessions").value(2))
			.andExpect(jsonPath("$.ownedSessionReservations").value(2))
			.andExpect(jsonPath("$.canWithdraw").value(true));

		moderate(target, "withdraw", "운영 정책 위반").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("WITHDRAWN"))
			.andExpect(jsonPath("$.loginId").doesNotExist());

		assertThat(statusOf(queued)).isEqualTo("CONFIRMED");
		assertThat(statusOf(kept)).isEqualTo("CONFIRMED");
		assertThat(count("select count(*) from reservation where account_id = ? and status = 'WAITING'", target))
			.isZero();
		assertThat(jdbc.queryForObject("select count(*) from study_session where id in (?, ?)", Long.class,
				own1.getId(), own2.getId())).isZero();
		assertThat(jdbc.queryForObject("select login_id from account where id = ?", String.class, target.getId()))
			.isEqualTo("deleted-" + target.getId());
		mockMvc.perform(get("/api/me/reservations").session(targetSession)).andExpect(status().isUnauthorized());
		moderate(target, "suspend", "이미 탈퇴").andExpect(status().isConflict());

		// 조치 기록: 처리자·대상 id·조치·사유·시각·처리 건수. 탈퇴자는 익명 표시
		mockMvc.perform(get("/api/operator/actions").param("targetId", String.valueOf(target.getId()))
			.session(operatorSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].action").value("FORCE_WITHDRAW"))
			.andExpect(jsonPath("$.items[0].reason").value("운영 정책 위반"))
			.andExpect(jsonPath("$.items[0].operatorId").value(operator.getId()))
			.andExpect(jsonPath("$.items[0].targetId").value(target.getId()))
			.andExpect(jsonPath("$.items[0].targetLabel").value("탈퇴한 회원 #" + target.getId()))
			.andExpect(jsonPath("$.items[0].cancelledReservations").value(2))
			.andExpect(jsonPath("$.items[0].deletedSessions").value(2))
			.andExpect(jsonPath("$.items[0].createdAt").isNotEmpty());
	}

	@Test
	void operatorCannotModerateThemselvesOrMissingAccounts() throws Exception {
		moderate(operator, "suspend", "실수").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("SELF_MODERATION_NOT_ALLOWED"));
		moderate(operator, "withdraw", "실수").andExpect(status().isConflict());
		mockMvc.perform(get("/api/operator/accounts/{id}/impact", operator.getId()).session(operatorSession))
			.andExpect(jsonPath("$.canSuspend").value(false))
			.andExpect(jsonPath("$.canWithdraw").value(false))
			.andExpect(jsonPath("$.blockedReason").isNotEmpty());
		mockMvc.perform(withXsrf(post("/api/operator/accounts/{id}/suspend", 999_999_999L), operatorSession)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"없음\"}")).andExpect(status().isNotFound());
		assertThat(count("select count(*) from moderation_action where operator_account_id = ?", operator)).isZero();
	}

	@Test
	void anotherOperatorCanBeSuspendedAndIsCutOffImmediately() throws Exception {
		Account second = newAccount("mod-op2", Role.OPERATOR);
		MockHttpSession secondSession = login(second);
		moderate(second, "suspend", "계정 공유 의심").andExpect(status().isOk());
		mockMvc.perform(get("/api/operator/accounts").session(secondSession)).andExpect(status().isUnauthorized());
	}

	private ResultActions moderate(Account target, String action, String reason) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/operator/accounts/{id}/" + action, target.getId()), operatorSession)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"%s\"}".formatted(reason)));
	}

	private ResultActions loginAttempt(Account account, String password) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/auth/login"), null).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"loginId":"%s","password":"%s"}
					""".formatted(account.getLoginId(), password)));
	}

	private StudySession ownedBy(Account account, String title) {
		StudySession session = StudySession.create(title, LATER, "테스트룸", 2, Instant.now());
		session.assignCreator(account.getId());
		return sessions.save(session);
	}

	private String statusOf(Reservation reservation) {
		return jdbc.queryForObject("select status from reservation where id = ?", String.class, reservation.getId());
	}

	private long count(String sql, Account account) {
		return jdbc.queryForObject(sql, Long.class, account.getId());
	}

}
