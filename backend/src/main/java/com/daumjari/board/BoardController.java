package com.daumjari.board;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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

/**
 * 회원 게시판(community)·서비스 공지(notice). 공지 목록·글 상세 GET 은 비회원에게 열려 있고(SecurityConfig),
 * 회원 게시판 글은 서비스가 로그인 여부를 다시 확인한다. 비회원이면 user 는 null.
 */
@RestController
public class BoardController {

	private final BoardService service;

	public BoardController(BoardService service) {
		this.service = service;
	}

	@GetMapping("/api/boards/{board}/posts")
	public PostPage list(@PathVariable String board, @RequestParam(required = false) String q,
			@RequestParam(required = false) String field, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size, @AuthenticationPrincipal SessionUser user) {
		return service.list(board, q, field, page, size, user);
	}

	@GetMapping("/api/posts/{id}")
	public PostResponse get(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return service.get(id, user);
	}

	@PostMapping("/api/boards/{board}/posts")
	@ResponseStatus(HttpStatus.CREATED)
	public PostResponse create(@PathVariable String board, @RequestBody PostRequest request,
			@AuthenticationPrincipal SessionUser user) {
		return service.create(board, request, user);
	}

	@PatchMapping("/api/posts/{id}")
	public PostResponse edit(@PathVariable Long id, @RequestBody PostRequest request,
			@AuthenticationPrincipal SessionUser user) {
		return service.edit(id, request, user);
	}

	@DeleteMapping("/api/posts/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		service.delete(id, user);
	}

}
