package com.daumjari;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.reservation.Reservation;
import com.daumjari.reservation.ReservationRepository;
import com.daumjari.studysession.StudySession;
import com.daumjari.studysession.StudySessionRepository;

import jakarta.servlet.http.Cookie;

/** MockMvc API 테스트 공통: 계정 생성, 실제 CSRF 쿠키 흐름으로 로그인, 회차·예약 준비. */
abstract class ApiTestSupport {

	static final String PASSWORD = "test-pass-1!";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	AccountRepository accounts;

	@Autowired
	StudySessionRepository sessions;

	@Autowired
	ReservationRepository reservations;

	@Autowired
	PasswordEncoder passwordEncoder;

	/** 테스트마다 겹치지 않는 계정을 만든다. */
	Account newAccount(String prefix, Role role) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return accounts.save(Account.create(loginId, passwordEncoder.encode(PASSWORD), prefix, role, Instant.now()));
	}

	StudySession newSession(String title, Instant startsAt, int capacity) {
		return sessions.save(StudySession.create(title, startsAt, "테스트룸", capacity, Instant.now()));
	}

	Reservation confirmDirectly(StudySession session, Account account) {
		return reservations.save(Reservation.confirm(session, account, Instant.now()));
	}

	MockHttpSession login(Account account) throws Exception {
		MvcResult result = mockMvc.perform(withXsrf(post("/api/auth/login"), null)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"loginId":"%s","password":"%s"}
						""".formatted(account.getLoginId(), PASSWORD)))
			.andExpect(status().isOk())
			.andReturn();
		return (MockHttpSession) result.getRequest().getSession(false);
	}

	/** 상태 변경 요청에 XSRF 쿠키·헤더를 붙인다. */
	<B extends AbstractMockHttpServletRequestBuilder<B>> B withXsrf(B request, MockHttpSession session) throws Exception {
		MockHttpServletRequestBuilder csrfRequest = get("/api/auth/csrf");
		if (session != null) {
			csrfRequest.session(session);
			request.session(session);
		}
		Cookie xsrf = mockMvc.perform(csrfRequest).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return request.cookie(xsrf).header("X-XSRF-TOKEN", xsrf.getValue());
	}

}
