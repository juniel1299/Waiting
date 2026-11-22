package com.daumjari.studysession;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

	/** 일반 회차 (데모 체험 회차 제외). */
	List<StudySession> findByDemoTrialIdIsNullOrderByStartsAtAscIdAsc();

	/** 한 데모 체험의 회차. */
	List<StudySession> findByDemoTrialIdOrderByStartsAtAscIdAsc(Long demoTrialId);

	/** 내가 만든 회차. */
	List<StudySession> findByCreatorAccountIdOrderByStartsAtAscIdAsc(Long creatorAccountId);

	@Query("select s.id from StudySession s where s.creatorAccountId = :accountId order by s.id")
	List<Long> findIdsByCreatorAccountId(@Param("accountId") Long accountId);

	/** 물리 삭제. 신청은 FK on delete cascade 로 같은 문장에서 함께 지워진다 (V8). */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "delete from study_session where id = :id", nativeQuery = true)
	int deleteRowById(@Param("id") Long id);

	/** 회차 단위로 예약·취소를 직렬화하기 위한 행 잠금 (SELECT ... FOR UPDATE). */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from StudySession s where s.id = :id")
	Optional<StudySession> findByIdForUpdate(@Param("id") Long id);

}
