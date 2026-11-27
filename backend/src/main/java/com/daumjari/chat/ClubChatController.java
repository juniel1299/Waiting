package com.daumjari.chat;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.chat.ClubChatService.Message;
import com.daumjari.chat.ClubChatService.Page;

/** 모임 채팅 API. 화면은 GET ?after=cursor 를 몇 초마다 불러 이어 받는다(README·계획 문서 "6단계 전송 방식"). */
@RestController
public class ClubChatController {

	record SendRequest(String body) {
	}

	private final ClubChatService chat;

	public ClubChatController(ClubChatService chat) {
		this.chat = chat;
	}

	@GetMapping("/api/clubs/{id}/chat")
	public Page read(@PathVariable Long id, @RequestParam(required = false) Long after, @AuthenticationPrincipal SessionUser user) {
		return chat.read(id, after, user);
	}

	@PostMapping("/api/clubs/{id}/chat")
	@ResponseStatus(HttpStatus.CREATED)
	public Message send(@PathVariable Long id, @RequestBody SendRequest body, @AuthenticationPrincipal SessionUser user) {
		return chat.send(id, body.body(), user);
	}

	@DeleteMapping("/api/clubs/{id}/chat/{messageId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @PathVariable Long messageId, @AuthenticationPrincipal SessionUser user) {
		chat.delete(id, messageId, user);
	}

}
