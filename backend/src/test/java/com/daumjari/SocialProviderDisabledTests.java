package com.daumjari;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Role;

/** 공급자 환경 변수(KAKAO_CLIENT_ID 등)가 없으면 꺼진다: 목록은 false, 시작은 '준비 중' 오류로 돌아간다. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SocialProviderDisabledTests extends ApiTestSupport {

	@Test
	void providersWithoutConfigurationAreOff() throws Exception {
		mockMvc.perform(get("/api/auth/oauth/providers")).andExpect(status().isOk())
			.andExpect(jsonPath("$.kakao").value(false)).andExpect(jsonPath("$.naver").value(false));
		mockMvc.perform(get("/api/auth/oauth/kakao/link/start").session(login(newAccount("sd-me", Role.PARTICIPANT))))
			.andExpect(status().isFound()).andExpect(redirectedUrl("/me/accounts?error=OAUTH_PROVIDER_DISABLED"));
		mockMvc.perform(get("/api/auth/oauth/naver/login/start")).andExpect(status().isFound())
			.andExpect(redirectedUrl("/login?error=OAUTH_PROVIDER_DISABLED"));
	}

}
