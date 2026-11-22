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

	/** 탈퇴했거나 이용 정지된 계정이면 true. 남은 세션을 요청마다 끊는 데 쓴다. */
	@Query("select count(a) > 0 from Account a where a.id = :id and (a.deletedAt is not null or a.suspendedAt is not null)")
	boolean isBlocked(@Param("id") Long id);

	/**
	 * 운영 조치: 활성 운영자 행을 id 순으로 잠근다. 운영자끼리 동시에 서로를 정지·탈퇴시켜도 이 잠금에서 줄을 서고,
	 * 앞선 조치로 정지된 운영자는 다음 조회에서 빠지므로 운영자가 0명이 되지 않는다.
	 */
	@Query(value = """
			select id from account
			where role = 'OPERATOR' and deleted_at is null and suspended_at is null and demo_trial_id is null
			order by id for update
			""", nativeQuery = true)
	java.util.List<Long> lockActiveOperatorIds();

	/** 탈퇴: 계정 행을 먼저 잠근다 (잠금 순서: 계정 → 회차). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select a from Account a where a.id = :id")
	Optional<Account> findByIdForUpdate(@Param("id") Long id);

	/** 예약·대기: 계정 행을 공유 잠금해 진행 중인 탈퇴와 직렬화한다 (FOR SHARE, 잠금 순서: 계정 → 회차). */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select a from Account a where a.id = :id")
	Optional<Account> findByIdForShare(@Param("id") Long id);

}
