package com.daumjari.auth;

import java.io.Serializable;

import com.daumjari.account.Role;

/** 세션에 저장되는 로그인 사용자. 비밀번호 해시는 담지 않는다. */
public record SessionUser(Long id, String loginId, String displayName, Role role) implements Serializable {
}
