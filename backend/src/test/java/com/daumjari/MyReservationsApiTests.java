package com.daumjari;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.Role;
import com.daumjari.reservation.Reservation;
import com.daumjari.studysession.StudySession;

/**
 * 내 신청 모아보기 (GET /api/me/reservations): 세션 계정의 신청만, 회차 정보(제목·시작·장소)와 상태·대기 순번을 담는다.
 * 경로에 사용자 id 를 받지 않으므로 다른 계정의 신청을 가리킬 수 없다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MyReservationsApiTests extends ApiTestSupport {

	static final Instant STARTS_AT = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);

	@Test
	void listsOnlyMyActiveAndWaitingReservationsWithSessionDetails() throws Exception {
		Account me = newAccount("mine-me", Role.PARTICIPANT);
		Account other = newAccount("mine-other", Role.PARTICIPANT);
		StudySession open = newSession("내 확정 회차", STARTS_AT, 3);
		StudySession full = newSession("내 대기 회차", STARTS_AT.plus(1, ChronoUnit.DAYS), 1);
		StudySession othersOnly = newSession("남의 회차", STARTS_AT, 3);
		confirmDirectly(full, other);
		confirmDirectly(othersOnly, other);
		MockHttpSession session = login(me);

		mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", open.getId()), session))
			.andExpect(status().isCreated());
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", full.getId()), session))
			.andExpect(status().isCreated());

		mockMvc.perform(get("/api/me/reservations").session(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(2)))
			.andExpect(jsonPath("$[?(@.sessionId == %d)].status".formatted(open.getId())).value("CONFIRMED"))
			.andExpect(jsonPath("$[?(@.sessionId == %d)].location".formatted(open.getId())).value("테스트룸"))
			.andExpect(jsonPath("$[?(@.sessionId == %d)].startsAt".formatted(open.getId())).value(STARTS_AT.toString()))
			.andExpect(jsonPath("$[?(@.sessionId == %d)].status".formatted(full.getId())).value("WAITING"))
			.andExpect(jsonPath("$[?(@.sessionId == %d)].waitingPosition".formatted(full.getId())).value(1))
			.andExpect(jsonPath("$[?(@.sessionId == %d)]".formatted(othersOnly.getId())).isEmpty());
	}

	@Test
	void promotionShowsUpAsConfirmedForTheWaiter() throws Exception {
		Account waiter = newAccount("mine-waiter", Role.PARTICIPANT);
		Account holder = newAccount("mine-holder", Role.PARTICIPANT);
		StudySession full = newSession("승격 회차", STARTS_AT, 1);
		Reservation held = confirmDirectly(full, holder);
		MockHttpSession waiterSession = login(waiter);
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/waitlist", full.getId()), waiterSession))
			.andExpect(status().isCreated());

		mockMvc.perform(withXsrf(post("/api/reservations/{id}/cancel", held.getId()), login(holder)))
			.andExpect(status().isOk());

		mockMvc.perform(get("/api/me/reservations").session(waiterSession))
			.andExpect(jsonPath("$[0].status").value("CONFIRMED"))
			.andExpect(jsonPath("$[0].waitingPosition").doesNotExist());
	}

	@Test
	void requiresAParticipantSession() throws Exception {
		mockMvc.perform(get("/api/me/reservations")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/me/reservations").session(login(newAccount("mine-op", Role.OPERATOR))))
			.andExpect(status().isForbidden());
	}

	@Test
	void withdrawnAccountSessionCannotReadIt() throws Exception {
		Account member = newAccount("mine-gone", Role.PARTICIPANT);
		MockHttpSession phone = login(member);
		MockHttpSession laptop = login(member);
		mockMvc.perform(withXsrf(post("/api/me/withdraw"), phone).contentType(MediaType.APPLICATION_JSON)
			.content("{\"password\":\"%s\"}".formatted(PASSWORD))).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/me/reservations").session(laptop)).andExpect(status().isUnauthorized());
	}

}
