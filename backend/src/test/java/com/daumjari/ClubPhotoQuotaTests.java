package com.daumjari;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Role;

/** 상한: 계정별 업로드 속도(여기서는 창 안 2장), 모임당 장수(여기서는 3장). 넘으면 각각 429·409. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = { "daumjari.photo.max-per-window=2", "daumjari.photo.max-per-club=3" })
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ClubPhotoQuotaTests extends ApiTestSupport {

	@Test
	void uploadRateAndPhotosPerClubAreCapped() throws Exception {
		MockHttpSession creator = login(newAccount("pq-creator", Role.PARTICIPANT));
		MockHttpSession member = login(newAccount("pq-member", Role.PARTICIPANT));
		long club = ClubFixtures.approvedClub(this, creator, login(newAccount("pq-op", Role.OPERATOR)), "상한 모임");
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/join", club), member)).andExpect(status().is2xxSuccessful());

		upload(club, member).andExpect(status().isCreated());
		upload(club, member).andExpect(status().isCreated());
		upload(club, member).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("PHOTO_TOO_FAST"));
		upload(club, creator).andExpect(status().isCreated());
		upload(club, creator).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PHOTO_CLUB_FULL"));
	}

	private ResultActions upload(long club, MockHttpSession s) throws Exception {
		return mockMvc.perform(withXsrf(multipart("/api/clubs/{id}/photos", club)
			.file(new MockMultipartFile("file", "q.jpg", "image/jpeg", PhotoFixtures.jpeg(40, 40))), s));
	}

}
