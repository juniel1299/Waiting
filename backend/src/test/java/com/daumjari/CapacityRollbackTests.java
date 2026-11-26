package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Role;
import com.daumjari.reservation.WaitlistPromoter;
import com.daumjari.studysession.StudySession;

/**
 * 증설로 여러 명을 승격하다 중간에 실패하면 정원 변경과 이미 처리한 승격이 함께 롤백되는지.
 * 실패는 기존 승격 협력 객체(WaitlistPromoter) 스파이로 두 번째 승격 직후에 주입하고, 결과는 SQL 로 확인한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CapacityRollbackTests extends ApiTestSupport {

	@MockitoSpyBean
	WaitlistPromoter promoter;

	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void restoreRealPromoter() {
		reset(spyBehindProxy());
	}

	@Test
	void failureOnTheSecondPromotionRollsBackCapacityAndTheFirstPromotion() throws Exception {
		StudySession session = newSession("증설 롤백", Instant.now().plus(1, ChronoUnit.DAYS), 1);
		reserveAsNewParticipant(session);
		List<Long> waiters = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			waiters.add(waitlistAsNewParticipant(session));
		}
		MockHttpSession operator = login(newAccount("op", Role.OPERATOR));
		AtomicInteger calls = new AtomicInteger();
		doAnswer(invocation -> {
			Object promoted = invocation.callRealMethod();
			if (calls.incrementAndGet() == 2) {
				throw new IllegalStateException("두 번째 승격 실패 주입");
			}
			return promoted;
		}).when(spyBehindProxy()).promoteNext(anyLong());

		// 예상하지 못한 오류는 에러 로그에 남고 500 INTERNAL_ERROR 로 응답한다(확장 3단계)
		mockMvc.perform(withXsrf(patch("/api/operator/sessions/{id}/capacity", session.getId()), operator)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"capacity\":4}"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

		assertThat(calls.get()).isEqualTo(2);
		assertThat(capacityOf(session)).isEqualTo(1);
		assertThat(statuses(waiters)).containsExactly("WAITING", "WAITING", "WAITING");

		reset(spyBehindProxy());
		changeCapacity(operator, session, 4);
		assertThat(capacityOf(session)).isEqualTo(4);
		assertThat(statuses(waiters)).containsExactly("CONFIRMED", "CONFIRMED", "CONFIRMED");
	}

	private void changeCapacity(MockHttpSession operator, StudySession session, int capacity) throws Exception {
		mockMvc.perform(withXsrf(patch("/api/operator/sessions/{id}/capacity", session.getId()), operator)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"capacity\":" + capacity + "}"))
			.andExpect(status().isOk());
	}

	private void reserveAsNewParticipant(StudySession session) throws Exception {
		mockMvc.perform(withXsrf(post("/api/sessions/{id}/reservations", session.getId()),
				login(newAccount("p", Role.PARTICIPANT))))
			.andExpect(status().isCreated());
	}

	private Long waitlistAsNewParticipant(StudySession session) throws Exception {
		String json = mockMvc
			.perform(withXsrf(post("/api/sessions/{id}/waitlist", session.getId()),
					login(newAccount("p", Role.PARTICIPANT))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("WAITING"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return Long.valueOf(json.replaceAll("^\\{\"id\":(\\d+).*", "$1").trim());
	}

	private int capacityOf(StudySession session) {
		return jdbc.queryForObject("select capacity from study_session where id = ?", Integer.class, session.getId());
	}

	private List<String> statuses(List<Long> reservationIds) {
		return reservationIds.stream()
			.map(id -> jdbc.queryForObject("select status from reservation where id = ?", String.class, id))
			.toList();
	}

	/** 스파이는 트랜잭션 프록시 안쪽에 있다. 스텁은 프록시(MANDATORY 검사)를 거치지 않고 등록한다. */
	private WaitlistPromoter spyBehindProxy() {
		return AopTestUtils.getUltimateTargetObject(promoter);
	}

}
