package com.daumjari.photo;

import java.io.IOException;
import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.daumjari.auth.SessionUser;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;
import com.daumjari.photo.ClubPhotoService.Page;
import com.daumjari.photo.ClubPhotoService.Photo;

/** 모임 사진첩 API. 이미지 바이트도 회원 확인 뒤에만 주고, 브라우저 개인 캐시(5분)만 허용한다. */
@RestController
public class ClubPhotoController {

	private static final CacheControl PRIVATE_CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate();

	private final ClubPhotoService photos;

	public ClubPhotoController(ClubPhotoService photos) {
		this.photos = photos;
	}

	@GetMapping("/api/clubs/{id}/photos")
	public Page list(@PathVariable Long id, @RequestParam(required = false) Long before, @AuthenticationPrincipal SessionUser user) {
		return photos.list(id, before, user);
	}

	@PostMapping("/api/clubs/{id}/photos")
	@ResponseStatus(HttpStatus.CREATED)
	public Photo upload(@PathVariable Long id, @RequestParam("file") MultipartFile file, @AuthenticationPrincipal SessionUser user) {
		if (file.getSize() > ClubPhotoService.MAX_ORIGINAL_BYTES) {
			throw new ApiException(ErrorCode.PHOTO_TOO_LARGE);
		}
		try {
			return photos.upload(id, file.getBytes(), user);
		}
		catch (IOException e) {
			throw new ApiException(ErrorCode.PHOTO_INVALID);
		}
	}

	@GetMapping("/api/clubs/{id}/photos/{photoId}/image")
	public ResponseEntity<byte[]> image(@PathVariable Long id, @PathVariable Long photoId, @AuthenticationPrincipal SessionUser user) {
		return jpeg(photos.bytes(id, photoId, PhotoStorage.Kind.IMAGE, user));
	}

	@GetMapping("/api/clubs/{id}/photos/{photoId}/thumb")
	public ResponseEntity<byte[]> thumb(@PathVariable Long id, @PathVariable Long photoId, @AuthenticationPrincipal SessionUser user) {
		return jpeg(photos.bytes(id, photoId, PhotoStorage.Kind.THUMB, user));
	}

	@DeleteMapping("/api/clubs/{id}/photos/{photoId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id, @PathVariable Long photoId, @AuthenticationPrincipal SessionUser user) {
		photos.delete(id, photoId, user);
	}

	private static ResponseEntity<byte[]> jpeg(byte[] bytes) {
		return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(PRIVATE_CACHE).header("X-Content-Type-Options", "nosniff")
			.body(bytes);
	}

}
