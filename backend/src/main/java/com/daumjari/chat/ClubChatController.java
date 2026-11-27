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
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.chat.ClubChatService.Message;

/** 모임 채팅 API. 화면은 GET ?after=cursor 를 몇 초마다 불러 이어 받는다(README·계획 문서 "6단계 전송 방식"). */
@RestController
public class ClubChatController {

	record SendRequest(String body) {
	}

	private final ClubChatService chat;

	public ClubChatController(ClubChatService chat) {
		this.chat = chat;
	}

	/** 처음(인자 없음)·이어 받기(after=seq)·이전 기록(before=메시지 id, limit 최대 100). after 와 before 를 함께 주면 400. */
	@GetMapping("/api/clubs/{id}/chat")
	public Object read(@PathVariable Long id, @RequestParam(required = false) Long after, @RequestParam(required = false) Long before,
			@RequestParam(required = false) Integer limit, @AuthenticationPrincipal SessionUser user) {
		if (before != null) {
			if (after != null) {
				throw new ApiException(ErrorCode.VALIDATION_FAILED);
			}
			return chat.history(id, before, limit, user);
		}
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
