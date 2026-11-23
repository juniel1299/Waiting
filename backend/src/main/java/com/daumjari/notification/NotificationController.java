package com.daumjari.notification;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.notification.NotificationService.NotificationPage;
import com.daumjari.notification.NotificationService.Opened;
import com.daumjari.notification.NotificationService.UnreadCount;

/** 내 알림함. 로그인한 사람만 (/api/me/** 는 인증 필요). */
@RestController
public class NotificationController {

	private final NotificationService notifications;

	public NotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	@GetMapping("/api/me/notifications")
	public NotificationPage list(@AuthenticationPrincipal SessionUser me) {
		return notifications.list(me);
	}

	@GetMapping("/api/me/notifications/unread-count")
	public UnreadCount unread(@AuthenticationPrincipal SessionUser me) {
		return notifications.unread(me);
	}

	@PostMapping("/api/me/notifications/{id}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void read(@PathVariable Long id, @AuthenticationPrincipal SessionUser me) {
		notifications.read(id, me);
	}

	@PostMapping("/api/me/notifications/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void readAll(@AuthenticationPrincipal SessionUser me) {
		notifications.readAll(me);
	}

	@PostMapping("/api/me/notifications/{id}/open")
	public Opened open(@PathVariable Long id, @AuthenticationPrincipal SessionUser me) {
		return notifications.open(id, me);
	}

}
