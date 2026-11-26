package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.daumjari.errorlog.ErrorMasking;

/** 에러 로그에 들어가기 전 지우는 값들과 경로 틀. */
class ErrorMaskingTests {

	@Test
	void secretsAndPersonalDataAreRemovedBeforeStoring() {
		String masked = ErrorMasking.message("fail password=hunter2 pwd: abc secret=\"s3\" mail Me.Name+x@Example.co.kr "
				+ "Authorization: Bearer abc.def-123456789012345678 cookie: JSESSIONID=ABCDEF0123 XSRF-TOKEN=zz "
				+ "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig_part-123 key 0123456789abcdef0123456789abcdef 전화 010-1234-5678");
		assertThat(masked).doesNotContain("hunter2", "abc ", "s3", "Example.co.kr", "abc.def", "ABCDEF0123", "zz", "eyJ",
				"0123456789abcdef0123456789abcdef", "1234-5678");
		assertThat(masked).contains("fail", "[가림]");
	}

	@Test
	void messagesAreTrimmedToOneLineAndCapped() {
		assertThat(ErrorMasking.message("a\nb\tc   d")).isEqualTo("a b c d");
		assertThat(ErrorMasking.message("가".repeat(1000))).hasSize(300);
		assertThat(ErrorMasking.message(null)).isEqualTo("(메시지 없음)");
	}

	@Test
	void pathsDropQueryAndFragmentAndTemplateIds() {
		assertThat(ErrorMasking.path("/sessions/123/manage?token=abc#x")).isEqualTo("/sessions/:id/manage");
		assertThat(ErrorMasking.path("https://waiting.example/clubs/9/board/posts/77")).isEqualTo("/clubs/:id/board/posts/:id");
		assertThat(ErrorMasking.path("/profiles/3f2a9c1e-aaaa-bbbb-cccc-1234567890ab")).isEqualTo("/profiles/:id");
		assertThat(ErrorMasking.path(null)).isEqualTo("/");
		assertThat(ErrorMasking.path("/" + "a".repeat(500))).hasSizeLessThanOrEqualTo(200);
	}

}
