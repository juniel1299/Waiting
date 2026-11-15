package com.daumjari.studysession;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StudySessionRepository extends JpaRepository<StudySession, Long> {

	List<StudySession> findAllByOrderByStartsAtAscIdAsc();

}
