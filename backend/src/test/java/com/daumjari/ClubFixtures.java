package com.daumjari;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

import com.jayway.jsonpath.JsonPath;

/** 승인된 모임 하나를 만든다(만든 사람 세션 + 운영자 세션). */
final class ClubFixtures {

	private ClubFixtures() {
	}

	static long approvedClub(ApiTestSupport t, MockHttpSession creator, MockHttpSession operator, String name) throws Exception {
		String created = t.mockMvc.perform(t.withXsrf(post("/api/clubs"), creator).contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"%s\",\"intro\":\"소개\",\"place\":\"온라인\",\"mode\":\"ONLINE\"}".formatted(name)))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
		long club = ((Number) JsonPath.read(created, "$.id")).longValue();
		t.mockMvc.perform(t.withXsrf(post("/api/operator/clubs/{id}/approve", club), operator).contentType(MediaType.APPLICATION_JSON)
			.content("{\"version\":" + JsonPath.read(created, "$.version") + "}")).andExpect(status().isOk());
		return club;
	}

}
