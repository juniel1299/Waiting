package com.daumjari.demo;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionLogin;
import com.daumjari.auth.SessionUser;
import com.daumjari.demo.DemoService.DemoStart;
import com.daumjari.reservation.ReservationDtos.ReservationResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/demo")
public class DemoController {

	public record DemoStartResponse(long sessionId, Instant expiresAt) {
	}

	private final DemoService service;

	private final SessionLogin sessionLogin;

	public DemoController(DemoService service, SessionLogin sessionLogin) {
		this.service = service;
		this.sessionLogin = sessionLogin;
	}

	/** 로그인 없이 체험 시작: 새 체험을 만들고 그 방문자로 세션을 연다 (기존 세션은 교체). */
	@PostMapping("/start")
	public ResponseEntity<DemoStartResponse> start(HttpServletRequest request, HttpServletResponse response) {
		DemoStart started = service.start(request.getRemoteAddr());
		sessionLogin.establish(started.visitor(), request, response);
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(new DemoStartResponse(started.sessionId(), started.expiresAt()));
	}

	/** 내 체험의 샘플 참가자 1명 예약을 취소한다 (대기 1번이면 기존 승격 로직으로 확정된다). */
	@PostMapping("/sample-cancel")
	public ReservationResponse cancelSample(@AuthenticationPrincipal SessionUser user) {
		return service.cancelSample(user);
	}

}
