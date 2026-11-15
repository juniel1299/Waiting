package com.daumjari.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

	long countByStudySessionIdAndStatus(Long studySessionId, ReservationStatus status);

}
