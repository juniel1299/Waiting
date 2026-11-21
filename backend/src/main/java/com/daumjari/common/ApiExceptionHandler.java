package com.daumjari.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.daumjari.auth.LoginThrottledException;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ApiError> apiException(ApiException e) {
		ErrorCode code = e.errorCode();
		return ResponseEntity.status(code.status()).body(new ApiError(code.name(), code.message()));
	}

	/** 로그인·데모 시작 시도 제한. Retry-After(초) 헤더와 본문 retryAfterSeconds. */
	@ExceptionHandler(LoginThrottledException.class)
	ResponseEntity<ThrottledError> throttled(LoginThrottledException e) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
			.body(new ThrottledError("TOO_MANY_ATTEMPTS", "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
					e.retryAfterSeconds()));
	}

	/** 입력 칸별 오류. fields 는 칸 이름 → 표시용 안내. */
	@ExceptionHandler(FieldValidationException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ValidationError fieldValidation(FieldValidationException e) {
		return new ValidationError(ErrorCode.VALIDATION_FAILED.name(), ErrorCode.VALIDATION_FAILED.message(),
				e.fields());
	}

	public record ValidationError(String code, String message, java.util.Map<String, String> fields) {
	}

	public record ThrottledError(String code, String message, long retryAfterSeconds) {
	}

	@ExceptionHandler(AuthenticationException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	ApiError invalidCredentials() {
		return new ApiError("INVALID_CREDENTIALS", "아이디 또는 비밀번호가 올바르지 않습니다.");
	}

	@ExceptionHandler({ MethodArgumentNotValidException.class, HttpMessageNotReadableException.class })
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ApiError invalidRequest() {
		return new ApiError("VALIDATION_FAILED", "요청 값이 올바르지 않습니다.");
	}

}
