package com.daumjari.reservation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	long countByStudySessionIdAndStatus(Long studySessionId, ReservationStatus status);

	long countByStudySessionIdAndStatusAndIdLessThan(Long studySessionId, ReservationStatus status, Long id);

	/** 가장 먼저 신청한 대기자 (id 순 = 신청 순, V3 주석 참고). */
	Optional<Reservation> findFirstByStudySessionIdAndStatusOrderByIdAsc(Long studySessionId,
			ReservationStatus status);

	/** 대기 순번(1부터). 대기 상태가 아니면 null. */
	default Integer waitingPositionOf(Reservation reservation) {
		if (reservation.getStatus() != ReservationStatus.WAITING) {
			return null;
		}
		return Math.toIntExact(countByStudySessionIdAndStatusAndIdLessThan(reservation.getStudySession().getId(),
				ReservationStatus.WAITING, reservation.getId()) + 1);
	}

	@Query("""
			select r.studySession.id as sessionId, count(r) as total
			from Reservation r
			where r.status = :status and r.studySession.id in :sessionIds
			group by r.studySession.id
			""")
	List<SessionCount> countBySessionIds(@Param("sessionIds") Collection<Long> sessionIds,
			@Param("status") ReservationStatus status);

	List<Reservation> findByAccountIdAndStatusNot(Long accountId, ReservationStatus status);

	Optional<Reservation> findByStudySessionIdAndAccountIdAndStatusNot(Long studySessionId, Long accountId,
			ReservationStatus status);

	@Query("""
			select r from Reservation r join fetch r.account
			where r.studySession.id = :sessionId and r.status = :status
			order by r.createdAt, r.id
			""")
	List<Reservation> findWithAccountBySessionIdAndStatus(@Param("sessionId") Long sessionId,
			@Param("status") ReservationStatus status);

	/** 본인 신청의 회차 ID 만 조회 (엔티티를 영속성 컨텍스트에 올리지 않는다). */
	@Query("select r.studySession.id from Reservation r where r.id = :id and r.account.id = :accountId")
	Optional<Long> findSessionIdByIdAndAccountId(@Param("id") Long id, @Param("accountId") Long accountId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Reservation r join fetch r.studySession where r.id = :id and r.account.id = :accountId")
	Optional<Reservation> findByIdAndAccountIdForUpdate(@Param("id") Long id, @Param("accountId") Long accountId);

	@Query("""
			select r from Reservation r join fetch r.studySession
			where r.account.id = :accountId
			order by r.createdAt desc, r.id desc
			""")
	List<Reservation> findWithSessionByAccountId(@Param("accountId") Long accountId);

	interface SessionCount {

		Long getSessionId();

		Long getTotal();

	}

}
