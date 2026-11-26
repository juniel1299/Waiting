package com.daumjari.errorlog;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * 다른 처리기(ApiExceptionHandler·스프링 기본 4xx 처리)가 처리하지 않은, 정말 예상하지 못한 오류만 여기로 온다(가장 마지막 순서).
 * 에러 로그에 남기고 추적 ID 를 담은 500 을 돌려준다. 메시지에 내부 내용은 넣지 않는다.
 */
@Component
public class UnexpectedErrorResolver implements HandlerExceptionResolver, Ordered {

	private static final Logger log = LoggerFactory.getLogger(UnexpectedErrorResolver.class);

	private final ErrorLog errorLog;

	public UnexpectedErrorResolver(ErrorLog errorLog) {
		this.errorLog = errorLog;
	}

	@Override
	public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException || response.isCommitted()) {
			return null;
		}
		String traceId = ErrorLog.newTraceId();
		log.error("처리하지 못한 오류 traceId={} {} {}", traceId, request.getMethod(), request.getRequestURI(), ex);
		errorLog.recordBackend(ex, request.getRequestURI(), traceId);
		try {
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			response.setHeader("X-Trace-Id", traceId);
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write("{\"code\":\"INTERNAL_ERROR\",\"message\":\"잠시 문제가 생겼어요. 다시 시도해 주세요.\",\"traceId\":\""
					+ traceId + "\"}");
		}
		catch (IOException e) {
			return null;
		}
		return new ModelAndView();
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

}
