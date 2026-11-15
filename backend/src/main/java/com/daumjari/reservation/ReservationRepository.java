package com.daumjari.reservation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	long countByStudySessionIdAndStatus(Long studySessionId, ReservationStatus status);

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

	interface SessionCount {

		Long getSessionId();

		Long getTotal();

	}

}
