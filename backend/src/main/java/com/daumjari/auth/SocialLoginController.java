package com.daumjari.auth;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.auth.SocialAccounts.Linked;
import com.daumjari.auth.SocialProviders.Provider;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/**
 * 카카오·네이버 계정 연결과 연결된 계정의 소셜 로그인. 소셜로 새로 가입하지는 않는다.
 * 브라우저가 이 주소들로 직접 이동하고(프록시 경유), 결과는 화면 주소로 302 를 돌려준다(?linked= / ?error=코드).
 * state 는 서버 세션에 일회용·10분. 공급자 코드·state·토큰은 로그에 남기지 않는다.
 */
@RestController
public class SocialLoginController {

	static final String STATES = "daumjari.oauth.states";

	static final Duration STATE_TTL = Duration.ofMinutes(10);

	static final int MAX_PENDING_STATES = 5;

	private static final Logger log = LoggerFactory.getLogger(SocialLoginController.class);

	private static final SecureRandom RANDOM = new SecureRandom();

	record UnlinkRequest(String password) {
	}

	record MySocial(List<Linked> items, Map<String, Boolean> providers) {
	}

	private final SocialProviders providers;

	private final SocialAccounts socialAccounts;

	private final AccountRepository accounts;

	private final SessionLogin sessionLogin;

	private final PasswordEncoder passwordEncoder;

	private final LoginThrottle throttle;

	private final Clock clock;

	public SocialLoginController(SocialProviders providers, SocialAccounts socialAccounts, AccountRepository accounts,
			SessionLogin sessionLogin, PasswordEncoder passwordEncoder, LoginThrottle throttle, Clock clock) {
		this.providers = providers;
		this.socialAccounts = socialAccounts;
		this.accounts = accounts;
		this.sessionLogin = sessionLogin;
		this.passwordEncoder = passwordEncoder;
		this.throttle = throttle;
		this.clock = clock;
	}

	@GetMapping("/api/auth/oauth/providers")
	public Map<String, Boolean> enabledProviders() {
		Map<String, Boolean> out = new LinkedHashMap<>();
		for (Provider p : Provider.values()) {
			out.put(p.path(), providers.enabled(p));
		}
		return out;
	}

	@GetMapping("/api/auth/oauth/{provider}/{mode}/start")
	public ResponseEntity<Void> start(@PathVariable String provider, @PathVariable String mode, @AuthenticationPrincipal SessionUser user,
			HttpServletRequest request) {
		Provider p = provider(provider);
		boolean link = switch (mode) {
			case "link" -> true;
			case "login" -> false;
			default -> throw new ApiException(ErrorCode.OAUTH_PROVIDER_NOT_FOUND);
		};
		String back = link ? "/me/accounts" : "/login";
		if (!providers.enabled(p)) {
			return redirect(back + "?error=OAUTH_PROVIDER_DISABLED");
		}
		if (link && user == null) {
			return redirect("/login?error=LOGIN_REQUIRED");
		}
		if (link && user.isDemo()) {
			return redirect("/me/accounts?error=SOCIAL_FORBIDDEN");
		}
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		HttpSession session = request.getSession(true);
		Map<String, OAuthState> pending = pending(session);
		pending.values().removeIf(s -> s.expiresAt().isBefore(Instant.now(clock)));
		while (pending.size() >= MAX_PENDING_STATES) {
			pending.remove(pending.keySet().iterator().next());
		}
		pending.put(state, new OAuthState(p.name(), mode, link ? user.id() : null, Instant.now(clock).plus(STATE_TTL)));
		session.setAttribute(STATES, pending);
		return redirect(providers.authorizeUrl(p, state));
	}

	@GetMapping("/api/auth/oauth/{provider}/callback")
	public ResponseEntity<Void> callback(@PathVariable String provider, @RequestParam(required = false) String code,
			@RequestParam(required = false) String state, @RequestParam(required = false) String error,
			@AuthenticationPrincipal SessionUser user, HttpServletRequest request, HttpServletResponse response) {
		Provider p = provider(provider);
		OAuthState saved = take(request.getSession(false), state);
		if (saved == null || !saved.provider().equals(p.name()) || saved.expiresAt().isBefore(Instant.now(clock))) {
			return redirect((user != null ? "/me/accounts" : "/login") + "?error=OAUTH_STATE_INVALID");
		}
		boolean link = "link".equals(saved.mode());
		String back = link ? "/me/accounts" : "/login";
		if (error != null) {
			return redirect(back + "?error=OAUTH_DENIED");
		}
		String providerUserId;
		try {
			providerUserId = providers.providerUserId(p, code == null ? "" : code, state);
		}
		catch (RuntimeException failure) {
			log.warn("{} 사용자 확인 실패: {}", p, failure.getClass().getSimpleName());
			return redirect(back + "?error=OAUTH_FAILED");
		}
		return link ? finishLink(p, providerUserId, saved, user) : finishLogin(p, providerUserId, request, response);
	}

	private ResponseEntity<Void> finishLink(Provider p, String providerUserId, OAuthState saved, SessionUser user) {
		if (user == null || !user.id().equals(saved.accountId())) {
			return redirect("/login?error=LOGIN_REQUIRED");
		}
		return switch (socialAccounts.link(user.id(), p, providerUserId)) {
			case LINKED -> redirect("/me/accounts?linked=" + p.path());
			case ALREADY_LINKED_TO_OTHER -> redirect("/me/accounts?error=SOCIAL_ALREADY_LINKED");
			case PROVIDER_ALREADY_LINKED -> redirect("/me/accounts?error=SOCIAL_PROVIDER_ALREADY_LINKED");
		};
	}

	private ResponseEntity<Void> finishLogin(Provider p, String providerUserId, HttpServletRequest request,
			HttpServletResponse response) {
		Account account = socialAccounts.accountFor(p, providerUserId).flatMap(accounts::findById)
			.filter(a -> !a.isDeleted())
			.orElse(null);
		if (account == null) {
			return redirect("/login?error=SOCIAL_NOT_LINKED");
		}
		if (account.isSuspended()) {
			return redirect("/login?error=ACCOUNT_SUSPENDED");
		}
		sessionLogin.establish(new AccountUserDetails(account).toSessionUser(), request, response);
		return redirect("/");
	}

	@GetMapping("/api/me/social")
	public MySocial mine(@AuthenticationPrincipal SessionUser user) {
		return new MySocial(socialAccounts.of(user.id()), enabledProviders());
	}

	/** 해제는 비밀번호 재확인(로그인과 같은 시도 제한). 비밀번호 로그인은 늘 남아 있어 로그인 수단이 없어지지 않는다. */
	@DeleteMapping("/api/me/social/{provider}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unlink(@PathVariable String provider, @RequestBody UnlinkRequest body, @AuthenticationPrincipal SessionUser user,
			HttpServletRequest request) {
		Provider p = provider(provider);
		if (user.isDemo()) {
			throw new ApiException(ErrorCode.SOCIAL_FORBIDDEN);
		}
		throttle.countAttempt(user.loginId(), request.getRemoteAddr());
		String hash = accounts.findById(user.id()).map(Account::getPasswordHash).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED));
		if (body.password() == null || !passwordEncoder.matches(body.password(), hash)) {
			throw new ApiException(ErrorCode.PASSWORD_MISMATCH);
		}
		if (!socialAccounts.unlink(user.id(), p)) {
			throw new ApiException(ErrorCode.SOCIAL_NOT_FOUND);
		}
	}

	private static Provider provider(String path) {
		Provider p = Provider.fromPath(path);
		if (p == null) {
			throw new ApiException(ErrorCode.OAUTH_PROVIDER_NOT_FOUND);
		}
		return p;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, OAuthState> pending(HttpSession session) {
		Object stored = session.getAttribute(STATES);
		return stored instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, OAuthState>) m) : new LinkedHashMap<>();
	}

	/** 꺼내면서 지운다(일회용). */
	private static OAuthState take(HttpSession session, String state) {
		if (session == null || state == null) {
			return null;
		}
		Map<String, OAuthState> pending = pending(session);
		OAuthState saved = pending.remove(state);
		session.setAttribute(STATES, pending);
		return saved;
	}

	private static ResponseEntity<Void> redirect(String location) {
		return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(location)).build();
	}

}
