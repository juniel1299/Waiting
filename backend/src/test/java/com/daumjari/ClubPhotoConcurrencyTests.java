package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Account;
import com.daumjari.account.AccountRepository;
import com.daumjari.account.Role;

/**
 * 전체 저장 용량 상한: 여러 회원이 동시에 올려도 저장된 합계가 상한을 넘지 않는다(실제 PostgreSQL, 5회).
 * 테스트에서는 상한을 작게(100KB) 두고 매 반복 시작 때 사진을 비운다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "daumjari.photo.total-max-bytes=100000")
@Testcontainers(disabledWithoutDocker = true)
class ClubPhotoConcurrencyTests {

	static final String PASSWORD = "photo-race-1!";

	static final int UPLOADERS = 8;

	@Value("${local.server.port}")
	int port;

	@Autowired
	AccountRepository accounts;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	com.daumjari.photo.PhotoProcessor processor;

	@RepeatedTest(5)
	void concurrentUploadsNeverExceedTheTotalStorageCap() throws Exception {
		jdbc.update("delete from club_photo");
		List<Long> ids = new ArrayList<>();
		List<HttpTestClient> clients = new ArrayList<>();
		for (int i = 0; i < UPLOADERS; i++) {
			Account a = accounts.save(Account.create("pc-" + UUID.randomUUID().toString().substring(0, 8), passwordEncoder.encode(PASSWORD),
					"pc", Role.PARTICIPANT, Instant.now()));
			ids.add(a.getId());
			clients.add(HttpTestClient.login("http://localhost:" + port, a.getLoginId(), PASSWORD));
		}
		long club = jdbc.queryForObject("""
				insert into club (name, intro, place, mode, status, creator_account_id, created_at, submitted_at, decided_at)
				values ('용량 경합', '소개', '온라인', 'ONLINE', 'APPROVED', ?, now(), now(), now()) returning id
				""", Long.class, ids.get(0));
		for (Long id : ids) {
			jdbc.update("insert into club_member (club_id, account_id, role, joined_at) values (?, ?, 'MEMBER', now())", club, id);
		}
		// 한 장의 저장 크기 S 를 같은 처리기로 재고, 미리 채워 둔 사진으로 남은 공간을 정확히 3.5장분으로 맞춘다
		byte[] photo = noisyJpeg();
		var processed = processor.process(photo);
		int one = processed.image().length + processed.thumb().length;
		int filler = 100000 - (3 * one + one / 2);
		long fillerId = jdbc.queryForObject("insert into club_photo (club_id, uploader_account_id, width, height, stored_bytes, created_at) "
				+ "values (?, ?, 1, 1, ?, now()) returning id", Long.class, club, ids.get(0), filler);
		jdbc.update("insert into photo_object (photo_id, kind, data) values (?, 'IMAGE', ?)", fillerId, new byte[filler]);
		ExecutorService pool = Executors.newFixedThreadPool(UPLOADERS);
		List<Future<HttpResponse<String>>> results = new ArrayList<>();
		for (HttpTestClient c : clients) {
			Callable<HttpResponse<String>> task = () -> c.upload("/api/clubs/" + club + "/photos", "n.jpg", "image/jpeg", photo);
			results.add(pool.submit(task));
		}
		List<Integer> codes = new ArrayList<>();
		for (Future<HttpResponse<String>> f : results) {
			codes.add(f.get().statusCode());
		}
		pool.shutdown();

		long total = jdbc.queryForObject("select coalesce(sum(stored_bytes), 0) from club_photo", Long.class);
		assertThat(total).isLessThanOrEqualTo(100000);
		assertThat(codes.stream().filter(c -> c == 201).count()).isEqualTo(3);
		assertThat(codes).allMatch(c -> c == 201 || c == 409);
		assertThat(jdbc.queryForObject("select coalesce(sum(length(data)), 0) from photo_object", Long.class)).isEqualTo(total);
	}

	private static byte[] noisyJpeg() throws Exception {
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(80, 60, java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.util.Random random = new java.util.Random(7);
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				img.setRGB(x, y, random.nextInt(0xFFFFFF));
			}
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(img, "jpg", out);
		return out.toByteArray();
	}

}
