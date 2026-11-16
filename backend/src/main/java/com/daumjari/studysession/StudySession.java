package com.daumjari.studysession;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "study_session")
public class StudySession {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(name = "starts_at", nullable = false)
	private Instant startsAt;

	@Column(nullable = false, length = 200)
	private String location;

	@Column(nullable = false)
	private int capacity;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/** 데모 체험 회차면 그 체험 ID. 일반 회차는 null. */
	@Column(name = "demo_trial_id")
	private Long demoTrialId;

	protected StudySession() {
	}

	public static StudySession create(String title, Instant startsAt, String location, int capacity, Instant now) {
		if (capacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive: " + capacity);
		}
		StudySession session = new StudySession();
		session.title = title;
		session.startsAt = startsAt;
		session.location = location;
		session.capacity = capacity;
		session.createdAt = now;
		return session;
	}

	public static StudySession createDemo(String title, Instant startsAt, String location, int capacity,
			Long demoTrialId, Instant now) {
		StudySession session = create(title, startsAt, location, capacity, now);
		session.demoTrialId = demoTrialId;
		return session;
	}

	/** 일반 회차는 일반 계정(체험 없음)에게만, 체험 회차는 같은 체험의 계정에게만 보인다. */
	public boolean isVisibleTo(Long viewerDemoTrialId) {
		return java.util.Objects.equals(demoTrialId, viewerDemoTrialId);
	}

	public boolean isDemo() {
		return demoTrialId != null;
	}

	public Long getDemoTrialId() {
		return demoTrialId;
	}

	public void changeCapacity(int newCapacity) {
		if (newCapacity <= 0) {
			throw new IllegalArgumentException("capacity must be positive: " + newCapacity);
		}
		capacity = newCapacity;
	}

	/** 시작 시각부터 예약·취소를 받지 않는다. */
	public boolean hasStarted(Instant now) {
		return !now.isBefore(startsAt);
	}

	public Long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public Instant getStartsAt() {
		return startsAt;
	}

	public String getLocation() {
		return location;
	}

	public int getCapacity() {
		return capacity;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
