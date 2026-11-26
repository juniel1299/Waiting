package com.daumjari.feedback;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.feedback.FeedbackDtos.ChangeRequest;
import com.daumjari.feedback.FeedbackDtos.Detail;
import com.daumjari.feedback.FeedbackDtos.Page;
import com.daumjari.feedback.FeedbackDtos.SubmitRequest;

/** 개선사항 API. /api/dev/** 는 개발자 역할만(SecurityConfig). */
@RestController
public class FeedbackController {

	private final FeedbackService service;

	public FeedbackController(FeedbackService service) {
		this.service = service;
	}

	@PostMapping("/api/feedback")
	@ResponseStatus(HttpStatus.CREATED)
	public Detail submit(@RequestBody SubmitRequest body, @AuthenticationPrincipal SessionUser user, HttpServletRequest request) {
		return service.submit(body, user, request.getRemoteAddr());
	}

	@GetMapping("/api/me/feedback")
	public Page mine(@AuthenticationPrincipal SessionUser user) {
		return service.mine(user);
	}

	@GetMapping("/api/feedback/{id}")
	public Detail get(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return service.get(id, user);
	}

	@GetMapping("/api/dev/feedback")
	public Page search(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) String category, @RequestParam(defaultValue = "0") int page) {
		return service.search(q, status, category, page);
	}

	@PatchMapping("/api/dev/feedback/{id}")
	public Detail change(@PathVariable Long id, @RequestBody ChangeRequest body, @AuthenticationPrincipal SessionUser user) {
		return service.change(id, body, user);
	}

}
