package com.daumjari.studysession;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

	/** 일반 회차 (데모 체험 회차 제외). */
	List<StudySession> findByDemoTrialIdIsNullOrderByStartsAtAscIdAsc();

	/** 한 데모 체험의 회차. */
	List<StudySession> findByDemoTrialIdOrderByStartsAtAscIdAsc(Long demoTrialId);

	/** 회차 단위로 예약·취소를 직렬화하기 위한 행 잠금 (SELECT ... FOR UPDATE). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from StudySession s where s.id = :id")
	Optional<StudySession> findByIdForUpdate(@Param("id") Long id);

}
