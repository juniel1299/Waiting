package com.daumjari.reservation;

import java.time.Instant;

import com.daumjari.account.Account;
import com.daumjari.studysession.StudySession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservation")
public class Reservation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "study_session_id", nullable = false)
	private StudySession studySession;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id", nullable = false)
	private Account account;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ReservationStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "cancelled_at")
	private Instant cancelledAt;

	protected Reservation() {
	}

	public static Reservation confirm(StudySession studySession, Account account, Instant now) {
		Reservation reservation = new Reservation();
		reservation.studySession = studySession;
		reservation.account = account;
		reservation.status = ReservationStatus.CONFIRMED;
		reservation.createdAt = now;
		return reservation;
	}

	/** 이미 취소된 신청이면 아무것도 바꾸지 않는다 (반복 취소에도 좌석은 한 번만 반환). */
	public void cancel(Instant now) {
		if (status == ReservationStatus.CANCELLED) {
			return;
		}
		status = ReservationStatus.CANCELLED;
		cancelledAt = now;
	}

	public boolean isActive() {
		return status != ReservationStatus.CANCELLED;
	}

	public Long getId() {
		return id;
	}

	public StudySession getStudySession() {
		return studySession;
	}

	public Account getAccount() {
		return account;
	}

	public ReservationStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getCancelledAt() {
		return cancelledAt;
	}

}
