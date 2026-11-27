package com.daumjari;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.daumjari.account.Role;
import com.jayway.jsonpath.JsonPath;

/**
 * 사진 업로드 경계 사례(테스트 파일): 큰 원본(24MP)·세로·가로, EXIF 방향 3·8, CMYK JPEG·HEIC 는 이유가 분명한 안내로 거부,
 * 머리만 맞고 속이 깨진 파일 거부. 저장본은 늘 긴 변 1600px 이하 JPEG 이고 EXIF 가 없다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "daumjari.photo.max-per-window=100")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ClubPhotoEdgeTests extends ApiTestSupport {

	MockHttpSession member;

	long club;

	@BeforeEach
	void setUp() throws Exception {
		MockHttpSession creator = login(newAccount("pe-creator", Role.PARTICIPANT));
		member = creator;
		club = ClubFixtures.approvedClub(this, creator, login(newAccount("pe-op", Role.OPERATOR)), "경계 모임");
	}

	@Test
	void largeOriginalsAndBothOrientationsAreResizedToTheLongSide() throws Exception {
		byte[] large = PhotoFixtures.jpeg(6000, 4000);
		assertThat(large.length).isLessThan(5 * 1024 * 1024);
		upload("large.jpg", "image/jpeg", large).andExpect(status().isCreated())
			.andExpect(jsonPath("$.width").value(1600)).andExpect(jsonPath("$.height").value(1067));
		long portrait = id(upload("portrait.jpg", "image/jpeg", PhotoFixtures.jpeg(3000, 4000)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.width").value(1200)).andExpect(jsonPath("$.height").value(1600)));
		byte[] stored = image(portrait);
		assertThat(PhotoFixtures.size(stored)).containsExactly(1200, 1600);
		assertThat(PhotoFixtures.hasExif(stored)).isFalse();
	}

	@Test
	void exifOrientationsThreeAndEightAreApplied() throws Exception {
		upload("o8.jpg", "image/jpeg", PhotoFixtures.jpegWithExif(300, 200, 8)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.width").value(200)).andExpect(jsonPath("$.height").value(300));
		long upsideDown = id(upload("o3.jpg", "image/jpeg", PhotoFixtures.jpegWithExif(300, 200, 3)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.width").value(300)).andExpect(jsonPath("$.height").value(200)));
		// 원본은 왼쪽이 파랑·오른쪽이 노랑: 180도 돌리면 왼쪽이 노랑이 된다
		java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(image(upsideDown)));
		java.awt.Color left = new java.awt.Color(img.getRGB(20, 100));
		assertThat(left.getRed()).isGreaterThan(left.getBlue());
	}

	@Test
	void cmykJpegAndHeicAreRejectedWithClearGuidance() throws Exception {
		upload("print.jpg", "image/jpeg", PhotoFixtures.cmykJpeg(120, 80)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PHOTO_CMYK_UNSUPPORTED"));
		upload("iphone.heic", "image/heic", PhotoFixtures.heicHeader()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("PHOTO_HEIC_UNSUPPORTED"))
			.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("JPEG")));
	}

	@Test
	void filesWithAValidHeaderButBrokenBodyAreRejected() throws Exception {
		byte[] good = PhotoFixtures.jpeg(400, 300);
		byte[] broken = Arrays.copyOf(good, good.length);
		byte[] noise = new byte[good.length - 200];
		new Random(1).nextBytes(noise);
		System.arraycopy(noise, 0, broken, 200, noise.length);
		upload("broken.jpg", "image/jpeg", broken).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PHOTO_INVALID"));
		byte[] png = PhotoFixtures.png(100, 100);
		upload("cut.png", "image/png", Arrays.copyOf(png, png.length / 2)).andExpect(status().isBadRequest());
	}

	private ResultActions upload(String name, String type, byte[] bytes) throws Exception {
		return mockMvc.perform(withXsrf(multipart("/api/clubs/{id}/photos", club).file(new MockMultipartFile("file", name, type, bytes)), member));
	}

	private byte[] image(long photo) throws Exception {
		return mockMvc.perform(get("/api/clubs/{id}/photos/{p}/image", club, photo).session(member)).andExpect(status().isOk()).andReturn()
			.getResponse().getContentAsByteArray();
	}

	private static long id(ResultActions actions) throws Exception {
		return ((Number) JsonPath.read(actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8), "$.id")).longValue();
	}

}
