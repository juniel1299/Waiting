package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.daumjari.reservation.Reservation;
import com.daumjari.reservation.ReservationStatus;
import com.daumjari.studysession.StudySession;

/**
 * 회원탈퇴: 비밀번호 재확인, 계정 익명화, 로그인·기존 세션 차단, 시작 전 신청 취소와 대기자 승격,
 * 시작된 회차의 확정 기록 보존, 운영자·데모 계정 거부, 같은 아이디 재가입.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class WithdrawalApiTests extends ApiTestSupport {

	@Test
	void withdrawalAnonymizesTheAccountAndEndsTheCurrentSession() throws Exception {
		Account member = newAccount("wd-member", Role.PARTICIPANT);
		String originalLoginId = member.getLoginId();
		MockHttpSession session = login(member);

		withdraw(session, PASSWORD).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
		Account after = accounts.findById(member.getId()).orElseThrow();
		assertThat(after.isDeleted()).isTrue();
		assertThat(after.getLoginId()).isEqualTo("deleted-" + member.getId());
		assertThat(after.getDisplayName()).isEqualTo("탈퇴한 회원");
		assertThat(after.getPasswordHash()).doesNotStartWith("{bcrypt}");
		assertThat(accounts.findByLoginId(originalLoginId)).isEmpty();
		// 예전 아이디·비밀번호로 로그인할 수 없다
		mockMvc.perform(withXsrf(post("/api/auth/login"), null).contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"loginId":"%s","password":"%s"}
					""".formatted(originalLoginId, PASSWORD)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void otherSessionsOfTheSameAccountAreBlocked() throws Exception {
		Account member = newAccount("wd-multi", Role.PARTICIPANT);
		MockHttpSession phone = login(member);
		MockHttpSession laptop = login(member);
		StudySession open = newSession("다른 기기 회차", tomorrow(), 3);

		withdraw(phone, PASSWORD).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/auth/me").session(laptop)).andExpect(status().isUnauthorized());
		mockMvc.perform(withXsrf(post("/api/sessions/" + open.getId() + "/reservations"), laptop))
			.andExpect(status().isUnauthorized());
		assertThat(activeOf(member)).isZero();
	}

	@Test
	void confirmedReservationBeforeStartIsCancelledAndTheFirstWaiterIsPromoted() throws Exception {
		Account member = newAccount("wd-confirmed", Role.PARTICIPANT);
		Account waiter = newAccount("wd-waiter", Role.PARTICIPANT);
		StudySession full = newSession("마감 회차", tomorrow(), 1);
		Reservation mine = confirmDirectly(full, member);
		Reservation waiting = reservations.save(Reservation.waitlist(full, waiter, Instant.now()));

		withdraw(login(member), PASSWORD).andExpect(status().isNoContent());

		assertThat(reservations.findById(mine.getId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
		assertThat(reservations.findById(waiting.getId()).orElseThrow().getStatus())
			.isEqualTo(ReservationStatus.CONFIRMED);
	}

	@Test
	void waitingReservationsAreCancelled() throws Exception {
		Account member = newAccount("wd-waiting", Role.PARTICIPANT);
		Account holder = newAccount("wd-holder", Role.PARTICIPANT);
		StudySession full = newSession("대기 회차", tomorrow(), 1);
		Reservation held = confirmDirectly(full, holder);
		Reservation waiting = reservations.save(Reservation.waitlist(full, member, Instant.now()));

		withdraw(login(member), PASSWORD).andExpect(status().isNoContent());

		assertThat(reservations.findById(waiting.getId()).orElseThrow().getStatus())
			.isEqualTo(ReservationStatus.CANCELLED);
		assertThat(reservations.findById(held.getId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
	}

	@Test
	void startedSessionKeepsTheConfirmedRecordButDropsWaiting() throws Exception {
		Account member = newAccount("wd-started", Role.PARTICIPANT);
		Account other = newAccount("wd-started-other", Role.PARTICIPANT);
		StudySession attended = newSession("이미 시작된 회차", Instant.now().minus(1, ChronoUnit.HOURS), 1);
		StudySession startedFull = newSession("시작된 마감 회차", Instant.now().minus(1, ChronoUnit.HOURS), 1);
		Reservation record = confirmDirectly(attended, member);
		confirmDirectly(startedFull, other);
		Reservation lateWaiting = reservations.save(Reservation.waitlist(startedFull, member, Instant.now()));

		withdraw(login(member), PASSWORD).andExpect(status().isNoContent());

		assertThat(reservations.findById(record.getId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		assertThat(reservations.findById(lateWaiting.getId()).orElseThrow().getStatus())
			.isEqualTo(ReservationStatus.CANCELLED);
		// 운영자 명단에는 익명으로 보인다
		MockHttpSession operator = login(newAccount("wd-op", Role.OPERATOR));
		mockMvc.perform(get("/api/operator/sessions/" + attended.getId() + "/roster").session(operator))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].displayName").value("탈퇴한 회원"))
			.andExpect(jsonPath("$[0].loginId").value("deleted-" + member.getId()));
	}

	@Test
	void wrongPasswordChangesNothingAndCountsTowardTheLoginLimit() throws Exception {
		Account member = newAccount("wd-wrong", Role.PARTICIPANT);
		StudySession open = newSession("유지 회차", tomorrow(), 3);
		Reservation mine = confirmDirectly(open, member);
		MockHttpSession session = login(member);

		withdraw(session, "wrong-password").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PASSWORD_MISMATCH"));
		assertThat(accounts.findById(member.getId()).orElseThrow().isDeleted()).isFalse();
		assertThat(reservations.findById(mine.getId()).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
		mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());

		for (int i = 0; i < 4; i++) {
			withdraw(session, "wrong-password").andExpect(status().isBadRequest());
		}
		withdraw(session, PASSWORD).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
	}

	@Test
	void operatorsAndDemoVisitorsCannotWithdraw() throws Exception {
		Account operator = newAccount("wd-operator", Role.OPERATOR);
		withdraw(login(operator), PASSWORD).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_NOT_ALLOWED"));
		assertThat(accounts.findById(operator.getId()).orElseThrow().isDeleted()).isFalse();

		MvcResult demo = mockMvc.perform(withXsrf(post("/api/demo/start"), null).with(request -> {
			request.setRemoteAddr(SignupApiTests.randomIp());
			return request;
		})).andExpect(status().isCreated()).andReturn();
		withdraw((MockHttpSession) demo.getRequest().getSession(false), "anything").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WITHDRAWAL_NOT_ALLOWED"));
	}

	@Test
	void theSameLoginIdCanSignUpAgainAsANewAccount() throws Exception {
		String loginId = SignupApiTests.uniqueId();
		String ip = SignupApiTests.randomIp();
		signup(loginId, ip).andExpect(status().isCreated());
		Account first = accounts.findByLoginId(loginId).orElseThrow();
		withdraw(login(first), PASSWORD).andExpect(status().isNoContent());

		signup(loginId, ip).andExpect(status().isCreated());
		Account second = accounts.findByLoginId(loginId).orElseThrow();
		assertThat(second.getId()).isNotEqualTo(first.getId());
		mockMvc.perform(get("/api/auth/me").session(login(second))).andExpect(status().isOk());
	}

	@Test
	void withdrawalRequiresLoginAndCsrf() throws Exception {
		mockMvc.perform(withXsrf(post("/api/me/withdraw"), null).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"x\"}")).andExpect(status().isUnauthorized());
		MockHttpSession session = login(newAccount("wd-csrf", Role.PARTICIPANT));
		mockMvc.perform(post("/api/me/withdraw").session(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isForbidden());
	}

	private ResultActions withdraw(MockHttpSession session, String password) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/me/withdraw"), session).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(password)));
	}

	private ResultActions signup(String loginId, String ip) throws Exception {
		return mockMvc.perform(withXsrf(post("/api/auth/signup"), null).with(request -> {
			request.setRemoteAddr(ip);
			return request;
		}).contentType(MediaType.APPLICATION_JSON).content("""
				{"loginId":"%s","password":"%s","passwordConfirm":"%s"}
				""".formatted(loginId, PASSWORD, PASSWORD)));
	}

	private long activeOf(Account account) {
		return reservations.findByAccountIdAndStatusNot(account.getId(), ReservationStatus.CANCELLED).size();
	}

	private static Instant tomorrow() {
		return Instant.now().plus(1, ChronoUnit.DAYS).plusSeconds(ThreadLocalRandom.current().nextInt(3600));
	}

}
