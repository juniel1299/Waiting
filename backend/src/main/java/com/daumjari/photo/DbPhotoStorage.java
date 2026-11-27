package com.daumjari.photo;

import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** photo_object(bytea) 에 둔다. 사진·모임이 지워지면 FK cascade 로 함께 지워진다. */
@Component
public class DbPhotoStorage implements PhotoStorage {

	private final NamedParameterJdbcTemplate jdbc;

	public DbPhotoStorage(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void put(long photoId, Kind kind, byte[] jpeg) {
		jdbc.update("insert into photo_object (photo_id, kind, data) values (:id, :kind, :data)",
				new MapSqlParameterSource("id", photoId).addValue("kind", kind.name()).addValue("data", jpeg));
	}

	@Override
	public Optional<byte[]> get(long photoId, Kind kind) {
		return jdbc.query("select data from photo_object where photo_id = :id and kind = :kind",
				new MapSqlParameterSource("id", photoId).addValue("kind", kind.name()), (rs, n) -> rs.getBytes(1)).stream().findFirst();
	}

	@Override
	public void delete(long photoId) {
		jdbc.update("delete from photo_object where photo_id = :id", new MapSqlParameterSource("id", photoId));
	}

}
