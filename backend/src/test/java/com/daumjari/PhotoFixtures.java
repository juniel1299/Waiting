package com.daumjari;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.imageio.ImageIO;

/** 사진첩 테스트용 이미지 바이트(테스트 안에서 만든다). */
final class PhotoFixtures {

	/** 위치 정보처럼 보이는 표지: 저장본에 남으면 EXIF 가 지워지지 않은 것이다. */
	static final String SECRET = "GPS-37.5665N-126.9780E-SECRET";

	/** 1×1 무손실 WebP. */
	static final byte[] WEBP_1X1 = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");

	private PhotoFixtures() {
	}

	static byte[] jpeg(int width, int height) throws IOException {
		return encode(image(width, height, BufferedImage.TYPE_INT_RGB), "jpg");
	}

	static byte[] png(int width, int height) throws IOException {
		return encode(image(width, height, BufferedImage.TYPE_INT_ARGB), "png");
	}

	static byte[] gif(int width, int height) throws IOException {
		return encode(image(width, height, BufferedImage.TYPE_INT_RGB), "gif");
	}

	/** JPEG 의 SOI 바로 뒤에 EXIF(APP1) 를 끼운다: 방향(0x0112)=orientation, 설명(0x010E)=SECRET. */
	static byte[] jpegWithExif(int width, int height, int orientation) throws IOException {
		byte[] plain = jpeg(width, height);
		byte[] text = (SECRET + "\0").getBytes(StandardCharsets.US_ASCII);
		ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 * 2 + 4 + text.length).order(ByteOrder.LITTLE_ENDIAN);
		tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
		tiff.putShort((short) 2);
		tiff.putShort((short) 0x010E).putShort((short) 2).putInt(text.length).putInt(8 + 2 + 12 * 2 + 4);
		tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
		tiff.putInt(0);
		tiff.put(text);
		byte[] exif = ByteBuffer.allocate(6 + tiff.capacity()).put("Exif\0\0".getBytes(StandardCharsets.US_ASCII)).put(tiff.array()).array();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(plain, 0, 2);
		out.write(0xFF);
		out.write(0xE1);
		int length = exif.length + 2;
		out.write(length >> 8);
		out.write(length & 0xFF);
		out.write(exif);
		out.write(plain, 2, plain.length - 2);
		return out.toByteArray();
	}

	/** 4채널(CMYK 계열) JPEG: JDK 쓰기 도구가 4밴드 래스터를 그대로 쓴다(인쇄용 파일과 같은 구성). */
	static byte[] cmykJpeg(int width, int height) throws IOException {
		java.awt.image.WritableRaster raster = java.awt.image.Raster.createInterleavedRaster(java.awt.image.DataBuffer.TYPE_BYTE, width, height, 4, null);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				raster.setPixel(x, y, new int[] { x % 256, y % 256, 80, 20 });
			}
		}
		javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
			writer.setOutput(ios);
			writer.write(null, new javax.imageio.IIOImage(raster, null, null), writer.getDefaultWriteParam());
		}
		finally {
			writer.dispose();
		}
		return out.toByteArray();
	}

	/** HEIC 파일 머리(ftyp heic): 내용은 없지만 형식 판별에는 충분하다. */
	static byte[] heicHeader() {
		byte[] b = new byte[64];
		byte[] head = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0, 'm', 'i', 'f', '1', 'h', 'e', 'i', 'c' };
		System.arraycopy(head, 0, b, 0, head.length);
		return b;
	}

	static int[] size(byte[] bytes) throws IOException {
		BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
		return new int[] { img.getWidth(), img.getHeight() };
	}

	static boolean hasExif(byte[] jpeg) {
		return new String(jpeg, StandardCharsets.ISO_8859_1).contains("Exif\0\0");
	}

	private static BufferedImage image(int width, int height, int type) {
		BufferedImage img = new BufferedImage(width, height, type);
		Graphics2D g = img.createGraphics();
		g.setColor(new Color(40, 120, 200));
		g.fillRect(0, 0, width / 2, height);
		g.setColor(new Color(240, 180, 40, type == BufferedImage.TYPE_INT_ARGB ? 128 : 255));
		g.fillRect(width / 2, 0, width - width / 2, height);
		g.dispose();
		return img;
	}

	private static byte[] encode(BufferedImage img, String format) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!ImageIO.write(img, format, out)) {
			throw new IOException("인코더 없음: " + format);
		}
		return out.toByteArray();
	}

}
