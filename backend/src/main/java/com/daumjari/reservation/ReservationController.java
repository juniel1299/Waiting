package com.daumjari.reservation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.reservation.ReservationDtos.ReservationResponse;
import com.daumjari.reservation.ReservationDtos.ReserveResult;

/** 참가자 전용 (SecurityConfig). 대상 계정은 항상 세션 사용자다. */
@RestController
public class ReservationController {

	private final ReservationService service;

	public ReservationController(ReservationService service) {
		this.service = service;
	}

	/** 새로 확정하면 201, 이미 활성 신청이 있으면 그 신청을 200 으로 돌려준다. */
	@PostMapping("/api/sessions/{sessionId}/reservations")
	public ResponseEntity<ReservationResponse> reserve(@PathVariable Long sessionId,
			@AuthenticationPrincipal SessionUser user) {
		ReserveResult result = service.reserve(sessionId, user.id());
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.body(result.reservation());
	}

	@PostMapping("/api/reservations/{reservationId}/cancel")
	public ReservationResponse cancel(@PathVariable Long reservationId, @AuthenticationPrincipal SessionUser user) {
		return service.cancel(reservationId, user.id());
	}

	@GetMapping("/api/me/reservations")
	public List<ReservationResponse> mine(@AuthenticationPrincipal SessionUser user) {
		return service.mine(user.id());
	}

}
