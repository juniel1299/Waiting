package com.daumjari.board;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.board.BoardDtos.PostPage;
import com.daumjari.board.BoardDtos.PostRequest;
import com.daumjari.board.BoardDtos.PostResponse;

/** 모임 내부 게시판(board)·모임 공지(notice) 목록·작성. 상세·수정·삭제·댓글은 /api/posts 의 같은 API 를 쓴다. */
@RestController
public class ClubBoardController {

	private final BoardService service;

	public ClubBoardController(BoardService service) {
		this.service = service;
	}

	@GetMapping("/api/clubs/{clubId}/boards/{board}/posts")
	public PostPage list(@PathVariable Long clubId, @PathVariable String board, @RequestParam(required = false) String q,
			@RequestParam(required = false) String field, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size, @AuthenticationPrincipal SessionUser user) {
		return service.listClub(clubId, board, q, field, page, size, user);
	}

	@PostMapping("/api/clubs/{clubId}/boards/{board}/posts")
	@ResponseStatus(HttpStatus.CREATED)
	public PostResponse create(@PathVariable Long clubId, @PathVariable String board, @RequestBody PostRequest request,
			@AuthenticationPrincipal SessionUser user) {
		return service.createClub(clubId, board, request, user);
	}

}
