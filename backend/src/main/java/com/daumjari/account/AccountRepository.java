package com.daumjari.account;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, Long> {

	Optional<Account> findByLoginId(String loginId);

	boolean existsByLoginIdIgnoreCase(String loginId);

	boolean existsByIdAndDeletedAtIsNotNull(Long id);

	/** 탈퇴: 계정 행을 먼저 잠근다 (잠금 순서: 계정 → 회차). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Account a where a.id = :id")
	Optional<Account> findByIdForUpdate(@Param("id") Long id);

	/** 예약·대기: 계정 행을 공유 잠금해 진행 중인 탈퇴와 직렬화한다 (FOR SHARE, 잠금 순서: 계정 → 회차). */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select a from Account a where a.id = :id")
	Optional<Account> findByIdForShare(@Param("id") Long id);

}
