package com.daumjari.studysession;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

	List<StudySession> findAllByOrderByStartsAtAscIdAsc();

	/** 회차 단위로 예약·취소를 직렬화하기 위한 행 잠금 (SELECT ... FOR UPDATE). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from StudySession s where s.id = :id")
	Optional<StudySession> findByIdForUpdate(@Param("id") Long id);

}
