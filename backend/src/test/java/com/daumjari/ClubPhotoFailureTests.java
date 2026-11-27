package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.io.OutputStream;
import java.net.Socket;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;
import com.daumjari.photo.DbPhotoStorage;
import com.daumjari.photo.PhotoStorage;

/**
 * 실패·중간에 끊긴 업로드는 사진 행·바이트·용량 집계를 남기지 않는다(실제 PostgreSQL).
 * 1) 저장소가 썸네일을 쓰다 실패하면 같은 트랜잭션의 사진 행·원본 바이트도 함께 되돌린다.
 * 2) 브라우저가 본문을 다 보내기 전에 연결을 끊으면(취소·네트워크 끊김) 아무것도 남지 않는다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class ClubPhotoFailureTests {

	static final String PASSWORD = "photo-fail-1!";

	@Value("${local.server.port}")
	int port;

	@Autowired
	AccountRepository accounts;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	DbPhotoStorage storage;

	@AfterEach
	void restore() {
		reset(storage);
	}

	@Test
	void aStorageFailureMidUploadRollsBackThePhotoRowAndBytes() throws Exception {
		Setup s = setup();
		doThrow(new IllegalStateException("썸네일 저장 실패 주입")).when(storage).put(anyLong(), eq(PhotoStorage.Kind.THUMB), any());
		HttpResponse<String> r = s.client.upload("/api/clubs/" + s.club + "/photos", "a.jpg", "image/jpeg", PhotoFixtures.jpeg(300, 200));
		assertThat(r.statusCode()).isEqualTo(500);
		assertThat(count("select count(*) from club_photo where club_id = ?", s.club)).isZero();
		assertThat(count("select count(*) from photo_object o join club_photo p on p.id = o.photo_id where p.club_id = ?", s.club)).isZero();
		assertThat(count("select count(*) from photo_object where photo_id not in (select id from club_photo)")).isZero();

		reset(storage);
		HttpResponse<String> ok = s.client.upload("/api/clubs/" + s.club + "/photos", "a.jpg", "image/jpeg", PhotoFixtures.jpeg(300, 200));
		assertThat(ok.statusCode()).isEqualTo(201);
		assertThat(count("select count(*) from club_photo where club_id = ?", s.club)).isEqualTo(1);
	}

	@Test
	void aConnectionCutBeforeTheBodyEndsLeavesNothingBehind() throws Exception {
		Setup s = setup();
		long before = count("select coalesce(sum(stored_bytes), 0) from club_photo");
		byte[] photo = PhotoFixtures.jpeg(800, 600);
		String boundary = "----cut" + System.nanoTime();
		byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cut.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
			.getBytes(StandardCharsets.UTF_8);
		int declared = head.length + photo.length + ("\r\n--" + boundary + "--\r\n").length();
		try (Socket socket = new Socket("localhost", port)) {
			OutputStream out = socket.getOutputStream();
			out.write(("POST /api/clubs/" + s.club + "/photos HTTP/1.1\r\nHost: localhost:" + port + "\r\nContent-Type: multipart/form-data; boundary="
					+ boundary + "\r\nContent-Length: " + declared + "\r\nCookie: " + s.client.cookieHeader() + "\r\nX-XSRF-TOKEN: " + s.client.xsrf()
					+ "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			out.write(head);
			out.write(photo, 0, photo.length / 3);
			out.flush();
		}
		Thread.sleep(1500);
		assertThat(count("select count(*) from club_photo where club_id = ?", s.club)).isZero();
		assertThat(count("select coalesce(sum(stored_bytes), 0) from club_photo")).isEqualTo(before);
	}

	private record Setup(HttpTestClient client, long club) {
	}

	private Setup setup() throws Exception {
		Account a = accounts.save(Account.create("pf-" + UUID.randomUUID().toString().substring(0, 8), passwordEncoder.encode(PASSWORD), "pf",
				Role.PARTICIPANT, Instant.now()));
		HttpTestClient client = HttpTestClient.login("http://localhost:" + port, a.getLoginId(), PASSWORD);
		long club = jdbc.queryForObject("""
				insert into club (name, intro, place, mode, status, creator_account_id, created_at, submitted_at, decided_at)
				values ('실패 확인', '소개', '온라인', 'ONLINE', 'APPROVED', ?, now(), now(), now()) returning id
				""", Long.class, a.getId());
		jdbc.update("insert into club_member (club_id, account_id, role, joined_at) values (?, ?, 'MANAGER', now())", club, a.getId());
		return new Setup(client, club);
	}

	private long count(String sql, Object... args) {
		return jdbc.queryForObject(sql, Long.class, args);
	}

}
