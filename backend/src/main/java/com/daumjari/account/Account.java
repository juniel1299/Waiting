package com.daumjari.account;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "account")
public class Account {

	public static final String WITHDRAWN_DISPLAY_NAME = "탈퇴한 회원";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "login_id", nullable = false, unique = true, length = 50)
	private String loginId;

	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Column(name = "display_name", nullable = false, length = 50)
	private String displayName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/** 탈퇴 시각. 탈퇴하지 않았으면 null. */
	@Column(name = "deleted_at")
	private Instant deletedAt;

	/** 공개 닉네임 (V12). 비어 있으면 '회원{id}' 로 보인다. */
	@Column(name = "nickname", length = 20)
	private String nickname;

	/** 공개 소개 (V17). */
	@Column(name = "bio", length = 300)
	private String bio;

	/** 관심 태그 최대 5개 (V17). */
	@Column(name = "interest_tags", nullable = false)
	private String[] interestTags = new String[0];

	/** 이용 정지 시각. 정지가 아니면 null (V10). */
	@Column(name = "suspended_at")
	private Instant suspendedAt;

	/** 데모 체험 계정이면 그 체험 ID. 일반 계정은 null. */
	@Column(name = "demo_trial_id")
	private Long demoTrialId;

	protected Account() {
	}

	public static Account create(String loginId, String passwordHash, String displayName, Role role, Instant now) {
		Account account = new Account();
		account.loginId = loginId;
		account.passwordHash = passwordHash;
		account.displayName = displayName;
		account.role = role;
		account.createdAt = now;
		return account;
	}

	/** 데모 체험 계정. 비밀번호 로그인은 막혀 있다 (AccountUserDetailsService). */
	public static Account demo(String loginId, String displayName, Long demoTrialId, Instant now) {
		// 형식이 맞지 않는 해시라 어떤 비밀번호와도 일치하지 않는다
		Account account = create(loginId, "!demo-no-password", displayName, Role.PARTICIPANT, now);
		account.demoTrialId = demoTrialId;
		return account;
	}

	/**
	 * 탈퇴: 아이디·이름·비밀번호 해시를 익명 값으로 바꾸고 탈퇴 시각을 남긴다. 행은 신청 기록 때문에 지우지 않는다.
	 * 해시는 형식이 맞지 않는 값이라 어떤 비밀번호와도 일치하지 않는다.
	 */
	public void withdraw(Instant now) {
		loginId = "deleted-" + id;
		displayName = WITHDRAWN_DISPLAY_NAME;
		passwordHash = "!withdrawn-no-password";
		nickname = null;
		bio = null;
		interestTags = new String[0];
		deletedAt = now;
	}

	/** 이용 정지: 행·신청은 그대로 두고 로그인과 기존 세션만 막는다 (AuthController, WithdrawnAccountFilter). */
	public void suspend(Instant now) {
		suspendedAt = now;
	}

	public void unsuspend() {
		suspendedAt = null;
	}

	public boolean isSuspended() {
		return suspendedAt != null;
	}

	public Instant getSuspendedAt() {
		return suspendedAt;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public Long getDemoTrialId() {
		return demoTrialId;
	}

	public Long getId() {
		return id;
	}

	public String getLoginId() {
		return loginId;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getDisplayName() {
		return displayName;
	}

	public Role getRole() {
		return role;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
