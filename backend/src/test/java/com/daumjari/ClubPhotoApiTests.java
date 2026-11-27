package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * 확장 7단계: 모임 사진첩. 승인된 모임의 지금 회원만 보고 올린다(요청마다 재확인). JPEG·PNG·WebP 만(바이트 시그니처),
 * 원본 5MB, 긴 변 1600px·썸네일 400px 로 줄이고 EXIF(위치·방향) 를 지운 JPEG 로 저장, 방향은 반영한다.
 * 이미지 응답은 회원 확인 뒤 Cache-Control private·nosniff. 본인·모임 운영자 삭제, 모임 삭제 시 함께 삭제.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ClubPhotoApiTests extends ApiTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	MockHttpSession creator;

	MockHttpSession member;

	long club;

	@BeforeEach
	void setUp() throws Exception {
		creator = login(newAccount("ph-creator", Role.PARTICIPANT));
		member = login(newAccount("ph-member", Role.PARTICIPANT));
		club = ClubFixtures.approvedClub(this, creator, login(newAccount("ph-op", Role.OPERATOR)), "사진 모임");
		mockMvc.perform(withXsrf(post("/api/clubs/{id}/join", club), member)).andExpect(status().is2xxSuccessful());
	}

	@Test
	void membersUploadAndTheServerStoresAResizedJpegAndThumbnailServedPrivately() throws Exception {
		String body = body(upload(member, "big.jpg", "image/jpeg", PhotoFixtures.jpeg(2400, 1200)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.width").value(1600)).andExpect(jsonPath("$.height").value(800)).andExpect(jsonPath("$.mine").value(true)));
		long photo = ((Number) JsonPath.read(body, "$.id")).longValue();
		mockMvc.perform(get("/api/clubs/{id}/photos", club).session(creator)).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(photo))
			.andExpect(jsonPath("$.items[0].imageUrl").value("/api/clubs/" + club + "/photos/" + photo + "/image"))
			.andExpect(jsonPath("$.items[0].uploader.label").isNotEmpty());

		byte[] image = mockMvc.perform(get("/api/clubs/{id}/photos/{p}/image", club, photo).session(creator)).andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/jpeg"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("private")))
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(PhotoFixtures.size(image)).containsExactly(1600, 800);
		byte[] thumb = mockMvc.perform(get("/api/clubs/{id}/photos/{p}/thumb", club, photo).session(creator)).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(PhotoFixtures.size(thumb)).containsExactly(400, 200);
	}

	@Test
	void exifIsStrippedAndOrientationIsApplied() throws Exception {
		byte[] withExif = PhotoFixtures.jpegWithExif(300, 200, 6);
		assertThat(new String(withExif, StandardCharsets.ISO_8859_1)).contains(PhotoFixtures.SECRET);
		long photo = ((Number) JsonPath.read(body(upload(member, "phone.jpg", "image/jpeg", withExif).andExpect(status().isCreated())), "$.id")).longValue();
		byte[] stored = mockMvc.perform(get("/api/clubs/{id}/photos/{p}/image", club, photo).session(member))
			.andReturn().getResponse().getContentAsByteArray();
		assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(PhotoFixtures.SECRET);
		assertThat(PhotoFixtures.hasExif(stored)).isFalse();
		// 방향 6(시계 방향 90도) → 가로 300×200 이 세로 200×300 으로
		assertThat(PhotoFixtures.size(stored)).containsExactly(200, 300);
	}

	@Test
	void onlyJpegPngAndWebpBySignatureAreAccepted() throws Exception {
		upload(member, "fake.jpg", "image/jpeg", "<html>not an image</html>".getBytes(StandardCharsets.UTF_8))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTO_INVALID"));
		upload(member, "anim.jpg", "image/jpeg", PhotoFixtures.gif(20, 20)).andExpect(status().isBadRequest());
		byte[] truncated = Arrays.copyOf(PhotoFixtures.jpeg(200, 200), 40);
		upload(member, "broken.jpg", "image/jpeg", truncated).andExpect(status().isBadRequest());
		upload(member, "alpha.png", "image/png", PhotoFixtures.png(64, 48)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.width").value(64));
		upload(member, "tiny.webp", "image/webp", PhotoFixtures.WEBP_1X1).andExpect(status().isCreated());
		// 확장자·Content-Type 이 아니라 내용으로 판단: PNG 를 .jpg 로 보내도 PNG 로 처리된다
		upload(member, "really-png.jpg", "image/jpeg", PhotoFixtures.png(10, 10)).andExpect(status().isCreated());
	}

	@Test
	void originalsOverFiveMegabytesAreRejected() throws Exception {
		byte[] big = new byte[5 * 1024 * 1024 + 1];
		System.arraycopy(PhotoFixtures.jpeg(10, 10), 0, big, 0, 3);
		upload(member, "huge.jpg", "image/jpeg", big).andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.code").value("PHOTO_TOO_LARGE"));
	}

	@Test
	void onlyCurrentMembersSeeOrUploadAndOnlyAuthorsOrManagersDelete() throws Exception {
		long photo = ((Number) JsonPath.read(body(upload(member, "a.jpg", "image/jpeg", PhotoFixtures.jpeg(50, 50))), "$.id")).longValue();
		MockHttpSession outsider = login(newAccount("ph-out", Role.PARTICIPANT));
		mockMvc.perform(get("/api/clubs/{id}/photos", club).session(outsider)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/clubs/{id}/photos/{p}/image", club, photo).session(outsider)).andExpect(status().isForbidden());
		upload(outsider, "b.jpg", "image/jpeg", PhotoFixtures.jpeg(50, 50)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/clubs/{id}/photos/{p}/thumb", club, photo)).andExpect(status().isUnauthorized());

		long creatorPhoto = ((Number) JsonPath.read(body(upload(creator, "c.jpg", "image/jpeg", PhotoFixtures.jpeg(50, 50))), "$.id")).longValue();
		mockMvc.perform(withXsrf(delete("/api/clubs/{id}/photos/{p}", club, creatorPhoto), member)).andExpect(status().isForbidden());
		mockMvc.perform(withXsrf(delete("/api/clubs/{id}/photos/{p}", club, photo), creator)).andExpect(status().isNoContent());
		mockMvc.perform(get("/api/clubs/{id}/photos/{p}/image", club, photo).session(member)).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from photo_object where photo_id = ?", Long.class, photo)).isZero();

		mockMvc.perform(withXsrf(post("/api/clubs/{id}/leave", club), member)).andExpect(status().is2xxSuccessful());
		mockMvc.perform(get("/api/clubs/{id}/photos/{p}/thumb", club, creatorPhoto).session(member)).andExpect(status().isForbidden());

		mockMvc.perform(withXsrf(delete("/api/clubs/{id}", club), creator)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from club_photo where club_id = ?", Long.class, club)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from photo_object where photo_id = ?", Long.class, creatorPhoto)).isZero();
	}

	private ResultActions upload(MockHttpSession s, String name, String type, byte[] bytes) throws Exception {
		return mockMvc.perform(withXsrf(multipart("/api/clubs/{id}/photos", club).file(new MockMultipartFile("file", name, type, bytes)), s));
	}

	private static String body(ResultActions actions) throws Exception {
		return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

}
