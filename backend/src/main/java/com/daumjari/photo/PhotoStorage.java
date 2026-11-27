package com.daumjari.photo;

import java.util.Optional;

/**
 * 사진 바이트 저장소. 지금은 DB(Neon, {@link DbPhotoStorage}), 나중에 Supabase Storage 같은 바깥 저장소로 바꿀 수 있게 나눈다.
 * put 은 사진 정보 행(club_photo)을 넣은 같은 트랜잭션 안에서 불린다. 바깥 저장소로 바꾸면 트랜잭션 밖이므로,
 * 실패·고아 파일은 작업 표로 다시 처리해야 한다(계획 문서 '삭제 정합성').
 */
public interface PhotoStorage {

	enum Kind {
		IMAGE, THUMB
	}

	void put(long photoId, Kind kind, byte[] jpeg);

	Optional<byte[]> get(long photoId, Kind kind);

	/** 사진 하나의 모든 바이트를 지운다. DB 저장소는 club_photo 삭제의 FK cascade 로도 지워진다. */
	void delete(long photoId);

}
