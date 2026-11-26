package com.daumjari;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 테스트 전용: 예상하지 못한 서버 오류를 일부러 낸다(에러 로그 수집 확인). 운영 코드에는 없다. */
@RestController
class BoomTestController {

	@GetMapping("/api/test/boom/{id}")
	String boom(@PathVariable String id) {
		throw new IllegalStateException("boom " + id + " password=hunter2 mail user@example.com token "
				+ "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijklmnop Bearer abcdef0123456789abcdef");
	}

}
