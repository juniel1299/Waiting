package com.daumjari.board;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.auth.SessionUser;
import com.daumjari.board.CommentService.CommentRequest;
import com.daumjari.board.CommentService.CommentResponse;
import com.daumjari.board.CommentService.LikeResponse;

/** 댓글·답글(parentId)·추천. 댓글 목록 GET 은 공지 글이면 비회원도 (SecurityConfig), 그 밖의 규칙은 CommentService. */
@RestController
public class CommentController {

	private final CommentService service;

	public CommentController(CommentService service) {
		this.service = service;
	}

	@GetMapping("/api/posts/{postId}/comments")
	public List<CommentResponse> list(@PathVariable Long postId, @AuthenticationPrincipal SessionUser user) {
		return service.list(postId, user);
	}

	@PostMapping("/api/posts/{postId}/comments")
	@ResponseStatus(HttpStatus.CREATED)
	public CommentResponse create(@PathVariable Long postId, @RequestBody CommentRequest request,
			@AuthenticationPrincipal SessionUser user) {
		return service.create(postId, request, user);
	}

	@PatchMapping("/api/comments/{id}")
	public CommentResponse edit(@PathVariable Long id, @RequestBody CommentRequest request,
			@AuthenticationPrincipal SessionUser user) {
		return service.edit(id, request, user);
	}

	@DeleteMapping("/api/comments/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		service.delete(id, user);
	}

	@PutMapping("/api/comments/{id}/like")
	public LikeResponse like(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return service.like(id, true, user);
	}

	@DeleteMapping("/api/comments/{id}/like")
	public LikeResponse unlike(@PathVariable Long id, @AuthenticationPrincipal SessionUser user) {
		return service.like(id, false, user);
	}

}
