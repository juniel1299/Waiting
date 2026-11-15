package com.daumjari.reservation;

/** CONFIRMED·WAITING 은 활성 신청, CANCELLED 는 종료. 회차·계정별 활성 신청은 하나. */
public enum ReservationStatus {
	CONFIRMED,
	WAITING,
	CANCELLED
}
