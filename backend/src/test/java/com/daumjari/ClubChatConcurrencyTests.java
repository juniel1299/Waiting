package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.jayway.jsonpath.JsonPath;

/**
 * 여러 회원이 동시에 보내는 동안 다른 회원이 cursor 로 이어 받아도 빠지는 메시지가 없다 (실제 PostgreSQL, 5회).
 * 같은 모임의 쓰기는 모임 단위 잠금으로 한 줄로 서서 커밋 순서 = 순번 순서가 된다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "daumjari.chat.max-per-window=1000")
@Testcontainers(disabledWithoutDocker = true)
class ClubChatConcurrencyTests {

	static final String PASSWORD = "chat-race-1!";

	static final int SENDERS = 4;

	static final int PER_SENDER = 15;

	@Value("${local.server.port}")
	int port;

	@Autowired
	AccountRepository accounts;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@RepeatedTest(5)
	void concurrentSendsAreAllDeliveredInIdOrderToACursorPoller() throws Exception {
		List<Long> ids = new ArrayList<>();
		List<HttpTestClient> senders = new ArrayList<>();
		for (int i = 0; i < SENDERS + 1; i++) {
			Account a = accounts.save(Account.create("cc-" + UUID.randomUUID().toString().substring(0, 8),
					passwordEncoder.encode(PASSWORD), "cc", Role.PARTICIPANT, Instant.now()));
			ids.add(a.getId());
			senders.add(HttpTestClient.login("http://localhost:" + port, a.getLoginId(), PASSWORD));
		}
		long club = jdbc.queryForObject("""
				insert into club (name, intro, place, mode, status, creator_account_id, created_at, submitted_at, decided_at)
				values ('경합 채팅', '소개', '온라인', 'ONLINE', 'APPROVED', ?, now(), now(), now()) returning id
				""", Long.class, ids.get(0));
		for (Long id : ids) {
			jdbc.update("insert into club_member (club_id, account_id, role, joined_at) values (?, ?, 'MEMBER', now())", club, id);
		}
		HttpTestClient poller = senders.remove(SENDERS);

		Set<Long> seen = new TreeSet<>();
		AtomicBoolean done = new AtomicBoolean(false);
		ExecutorService pool = Executors.newFixedThreadPool(SENDERS + 1);
		Future<?> polling = pool.submit(() -> {
			long cursor = 0;
			while (true) {
				boolean last = done.get();
				HttpResponse<String> r = poller.send("GET", "/api/clubs/" + club + "/chat?after=" + cursor, null);
				assertThat(r.statusCode()).isEqualTo(200);
				List<Number> got = JsonPath.read(r.body(), "$.items[*].id");
				List<Long> batch = got.stream().map(Number::longValue).toList();
				assertThat(batch).isSorted();
				seen.addAll(batch);
				cursor = ((Number) JsonPath.read(r.body(), "$.cursor")).longValue();
				if (last) {
					return null;
				}
			}
		});
		List<Future<?>> sends = new ArrayList<>();
		for (HttpTestClient s : senders) {
			sends.add(pool.submit(() -> {
				for (int i = 0; i < PER_SENDER; i++) {
					assertThat(s.send("POST", "/api/clubs/" + club + "/chat", "{\"body\":\"m" + i + "\"}").statusCode()).isEqualTo(201);
				}
				return null;
			}));
		}
		for (Future<?> f : sends) {
			f.get();
		}
		done.set(true);
		polling.get();
		pool.shutdown();

		List<Long> all = jdbc.queryForList("select id from club_chat_message where club_id = ? order by id", Long.class, club);
		assertThat(all).hasSize(SENDERS * PER_SENDER);
		assertThat(seen).containsExactlyElementsOf(all);
	}


	/** 과거 120건을 거꾸로 읽는 동안 3명이 30건을 동시에 보내도, 처음 조회 + 과거 페이지 + 이어 받기의 합이 전체와 같다(3회). */
	@RepeatedTest(3)
	void pagingBackwardsWhileOthersSendLosesNothing() throws Exception {
		List<Long> ids = new ArrayList<>();
		List<HttpTestClient> clients = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Account a = accounts.save(Account.create("ch-" + UUID.randomUUID().toString().substring(0, 8),
					passwordEncoder.encode(PASSWORD), "ch", Role.PARTICIPANT, Instant.now()));
			ids.add(a.getId());
			clients.add(HttpTestClient.login("http://localhost:" + port, a.getLoginId(), PASSWORD));
		}
		long club = jdbc.queryForObject("""
				insert into club (name, intro, place, mode, status, creator_account_id, created_at, submitted_at, decided_at)
				values ('기록 경합', '소개', '온라인', 'ONLINE', 'APPROVED', ?, now(), now(), now()) returning id
				""", Long.class, ids.get(0));
		for (Long id : ids) {
			jdbc.update("insert into club_member (club_id, account_id, role, joined_at) values (?, ?, 'MEMBER', now())", club, id);
		}
		for (int i = 0; i < 120; i++) {
			jdbc.update("insert into club_chat_message (club_id, author_account_id, body, seq, created_at) values (?, ?, ?, nextval('club_chat_seq'), now())",
					club, ids.get(0), "옛 " + i);
		}
		HttpTestClient reader = clients.remove(3);
		ExecutorService pool = Executors.newFixedThreadPool(3);
		List<Future<?>> sends = new ArrayList<>();
		for (HttpTestClient c : clients) {
			sends.add(pool.submit(() -> {
				for (int i = 0; i < 10; i++) {
					assertThat(c.send("POST", "/api/clubs/" + club + "/chat", "{\"body\":\"새 " + i + "\"}").statusCode()).isEqualTo(201);
				}
				return null;
			}));
		}
		Set<Long> seen = new TreeSet<>();
		HttpResponse<String> first = reader.send("GET", "/api/clubs/" + club + "/chat", null);
		List<Number> firstIds = JsonPath.read(first.body(), "$.items[*].id");
		firstIds.forEach(n -> seen.add(n.longValue()));
		long cursor = ((Number) JsonPath.read(first.body(), "$.cursor")).longValue();
		long before = firstIds.get(0).longValue();
		boolean more = true;
		while (more) {
			HttpResponse<String> older = reader.send("GET", "/api/clubs/" + club + "/chat?before=" + before + "&limit=20", null);
			List<Number> olderIds = JsonPath.read(older.body(), "$.items[*].id");
			olderIds.forEach(n -> seen.add(n.longValue()));
			more = JsonPath.read(older.body(), "$.hasMore");
			if (!olderIds.isEmpty()) {
				before = olderIds.get(0).longValue();
			}
			HttpResponse<String> changes = reader.send("GET", "/api/clubs/" + club + "/chat?after=" + cursor, null);
			((List<Number>) JsonPath.read(changes.body(), "$.items[*].id")).forEach(n -> seen.add(n.longValue()));
			cursor = ((Number) JsonPath.read(changes.body(), "$.cursor")).longValue();
		}
		for (Future<?> f : sends) {
			f.get();
		}
		pool.shutdown();
		HttpResponse<String> last = reader.send("GET", "/api/clubs/" + club + "/chat?after=" + cursor, null);
		((List<Number>) JsonPath.read(last.body(), "$.items[*].id")).forEach(n -> seen.add(n.longValue()));

		List<Long> all = jdbc.queryForList("select id from club_chat_message where club_id = ? order by id", Long.class, club);
		assertThat(all).hasSize(150);
		assertThat(seen).containsExactlyElementsOf(all);
	}

}
