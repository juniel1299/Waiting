package com.daumjari.photo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.daumjari.auth.SessionUser;
import com.daumjari.board.Mentions;
import com.daumjari.club.ClubRoles;
import com.daumjari.common.ApiException;
import com.daumjari.common.ErrorCode;

/**
 * 모임 사진첩. 승인된 모임의 지금 회원만(요청마다 ClubRoles 재확인) 보고 올린다. 본인·모임 운영자만 지운다.
 * 올리기: 회원·속도 확인 → 이미지 처리(트랜잭션 밖) → 트랜잭션 안에서 모임 행 공유 잠금 + 전체 용량 advisory 잠금으로
 * 장수·용량 상한을 확인하고 넣는다. 그래서 동시에 올려도 전체 용량 상한을 넘지 않는다.
 */
@Service
public class ClubPhotoService {

	public record Uploader(Long id, String label) {
	}

	public record Photo(Long id, int width, int height, Uploader uploader, boolean mine, boolean canDelete, Instant createdAt,
			String imageUrl, String thumbUrl) {
	}

	public record Page(List<Photo> items, Long nextBefore, long count, int maxPerClub) {
	}

	static final long MAX_ORIGINAL_BYTES = 5L * 1024 * 1024;

	static final int PAGE_SIZE = 30;

	/** 전체 저장 용량 advisory 잠금 키(채팅 6001 과 겹치지 않게). */
	private static final int LOCK_NAMESPACE = 7001;

	private static final String COLUMNS = "p.id, p.width, p.height, p.uploader_account_id, p.created_at, " + Mentions.labelSql("a") + " as label";

	private final NamedParameterJdbcTemplate jdbc;

	private final ClubRoles clubRoles;

	private final PhotoProcessor processor;

	private final PhotoStorage storage;

	private final TransactionTemplate transaction;

	private final Clock clock;

	private final int maxPerClub;

	private final long totalMaxBytes;

	private final int maxPerWindow;

	private final Duration window;

	public ClubPhotoService(NamedParameterJdbcTemplate jdbc, ClubRoles clubRoles, PhotoProcessor processor, PhotoStorage storage,
			PlatformTransactionManager transactionManager, Clock clock, @Value("${daumjari.photo.max-per-club:200}") int maxPerClub,
			@Value("${daumjari.photo.total-max-bytes:314572800}") long totalMaxBytes,
			@Value("${daumjari.photo.max-per-window:10}") int maxPerWindow, @Value("${daumjari.photo.window:10m}") Duration window) {
		this.jdbc = jdbc;
		this.clubRoles = clubRoles;
		this.processor = processor;
		this.storage = storage;
		this.transaction = new TransactionTemplate(transactionManager);
		this.clock = clock;
		this.maxPerClub = maxPerClub;
		this.totalMaxBytes = totalMaxBytes;
		this.maxPerWindow = maxPerWindow;
		this.window = window;
	}

	@Transactional(readOnly = true)
	public Page list(Long clubId, Long before, SessionUser user) {
		String role = member(clubId, user, false);
		MapSqlParameterSource params = new MapSqlParameterSource("club", clubId).addValue("before", before == null ? Long.MAX_VALUE : before);
		List<Photo> items = jdbc.query("select " + COLUMNS + " from club_photo p join account a on a.id = p.uploader_account_id "
				+ "where p.club_id = :club and p.id < :before order by p.id desc limit " + (PAGE_SIZE + 1), params,
				(rs, n) -> photo(rs, clubId, user, role));
		boolean more = items.size() > PAGE_SIZE;
		List<Photo> page = more ? items.subList(0, PAGE_SIZE) : items;
		long count = jdbc.queryForObject("select count(*) from club_photo where club_id = :club", params, Long.class);
		return new Page(page, more ? page.get(page.size() - 1).id() : null, count, maxPerClub);
	}

	public Photo upload(Long clubId, byte[] original, SessionUser user) {
		if (original == null || original.length == 0) {
			throw new ApiException(ErrorCode.PHOTO_INVALID);
		}
		if (original.length > MAX_ORIGINAL_BYTES) {
			throw new ApiException(ErrorCode.PHOTO_TOO_LARGE);
		}
		member(clubId, user, false);
		Instant now = Instant.now(clock);
		// shortcut: 동시에 여러 장을 보내면 속도 한도를 조금 넘을 수 있다(DB 개수 확인), 용량·장수 상한은 잠금 안에서 정확히 지킨다
		Long recent = jdbc.queryForObject("select count(*) from club_photo where uploader_account_id = :me and created_at > :since",
				new MapSqlParameterSource("me", user.id()).addValue("since", now.minus(window).atOffset(ZoneOffset.UTC)), Long.class);
		if (recent != null && recent >= maxPerWindow) {
			throw new ApiException(ErrorCode.PHOTO_TOO_FAST);
		}
		PhotoProcessor.Processed processed;
		try {
			processed = processor.process(original);
		}
		catch (PhotoProcessor.Unsupported unsupported) {
			throw new ApiException(unsupported.kind() == PhotoProcessor.Unsupported.Kind.HEIC ? ErrorCode.PHOTO_HEIC_UNSUPPORTED
					: ErrorCode.PHOTO_CMYK_UNSUPPORTED);
		}
		catch (IllegalArgumentException invalid) {
			throw new ApiException(ErrorCode.PHOTO_INVALID);
		}
		int stored = processed.image().length + processed.thumb().length;
		return transaction.execute(status -> {
			String role = member(clubId, user, true);
			jdbc.queryForList("select pg_advisory_xact_lock(:ns, 0)", new MapSqlParameterSource("ns", LOCK_NAMESPACE));
			MapSqlParameterSource p = new MapSqlParameterSource("club", clubId).addValue("me", user.id())
				.addValue("w", processed.width()).addValue("h", processed.height()).addValue("bytes", stored)
				.addValue("now", now.atOffset(ZoneOffset.UTC));
			if (jdbc.queryForObject("select count(*) from club_photo where club_id = :club", p, Long.class) >= maxPerClub) {
				throw new ApiException(ErrorCode.PHOTO_CLUB_FULL);
			}
			if (jdbc.queryForObject("select coalesce(sum(stored_bytes), 0) from club_photo", p, Long.class) + stored > totalMaxBytes) {
				throw new ApiException(ErrorCode.PHOTO_STORAGE_FULL);
			}
			Long id = jdbc.queryForObject("""
					insert into club_photo (club_id, uploader_account_id, width, height, stored_bytes, created_at)
					values (:club, :me, :w, :h, :bytes, :now) returning id
					""", p, Long.class);
			storage.put(id, PhotoStorage.Kind.IMAGE, processed.image());
			storage.put(id, PhotoStorage.Kind.THUMB, processed.thumb());
			return one(clubId, id, user, role);
		});
	}

	@Transactional(readOnly = true)
	public byte[] bytes(Long clubId, Long photoId, PhotoStorage.Kind kind, SessionUser user) {
		member(clubId, user, false);
		if (jdbc.queryForList("select id from club_photo where id = :id and club_id = :club",
				new MapSqlParameterSource("id", photoId).addValue("club", clubId), Long.class).isEmpty()) {
			throw new ApiException(ErrorCode.PHOTO_NOT_FOUND);
		}
		return storage.get(photoId, kind).orElseThrow(() -> new ApiException(ErrorCode.PHOTO_NOT_FOUND));
	}

	@Transactional
	public void delete(Long clubId, Long photoId, SessionUser user) {
		String role = member(clubId, user, true);
		Map<String, Object> row = jdbc.queryForList("select uploader_account_id from club_photo where id = :id and club_id = :club for update",
				new MapSqlParameterSource("id", photoId).addValue("club", clubId)).stream().findFirst()
			.orElseThrow(() -> new ApiException(ErrorCode.PHOTO_NOT_FOUND));
		boolean mine = user.id().equals(((Number) row.get("uploader_account_id")).longValue());
		if (!mine && !ClubRoles.MANAGER.equals(role)) {
			throw new ApiException(ErrorCode.PHOTO_DELETE_FORBIDDEN);
		}
		storage.delete(photoId);
		jdbc.update("delete from club_photo where id = :id", new MapSqlParameterSource("id", photoId));
	}

	private String member(Long clubId, SessionUser user, boolean lock) {
		String role = clubRoles.role(clubId, user.id(), lock);
		if (role == null) {
			throw new ApiException(ErrorCode.CLUB_MEMBERS_ONLY);
		}
		return role;
	}

	private Photo one(Long clubId, Long id, SessionUser user, String role) {
		return jdbc.queryForObject("select " + COLUMNS + " from club_photo p join account a on a.id = p.uploader_account_id where p.id = :id",
				new MapSqlParameterSource("id", id), (rs, n) -> photo(rs, clubId, user, role));
	}

	private static Photo photo(ResultSet rs, Long clubId, SessionUser user, String role) throws SQLException {
		long id = rs.getLong("id");
		boolean mine = user.id().equals(rs.getLong("uploader_account_id"));
		String base = "/api/clubs/" + clubId + "/photos/" + id;
		return new Photo(id, rs.getInt("width"), rs.getInt("height"), new Uploader(rs.getLong("uploader_account_id"), rs.getString("label")),
				mine, mine || ClubRoles.MANAGER.equals(role), rs.getTimestamp("created_at").toInstant(), base + "/image", base + "/thumb");
	}

}
