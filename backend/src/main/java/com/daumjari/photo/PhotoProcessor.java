package com.daumjari.photo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import org.springframework.stereotype.Component;

/**
 * 올린 사진을 저장본으로 바꾼다: 바이트 시그니처로 JPEG·PNG·WebP 만 받고, 픽셀 수 상한(압축 폭탄 방지)을 디코드 전에 보고,
 * EXIF 방향을 반영한 뒤 긴 변 1600px(썸네일 400px)로 줄여 새 JPEG 로 쓴다. 새로 쓰는 JPEG 에는 EXIF(위치 포함)가 없다.
 * 투명 PNG 는 흰 바탕에 얹는다. WebP 읽기는 TwelveMonkeys imageio-webp(JDK ImageIO 에 없음).
 */
@Component
public class PhotoProcessor {

	public record Processed(byte[] image, byte[] thumb, int width, int height) {
	}

	/** 받지 않는 형식인데 이유를 따로 알려 줄 수 있는 경우(인쇄용 CMYK JPEG, 아이폰 HEIC). */
	public static final class Unsupported extends IllegalArgumentException {

		public enum Kind {
			CMYK, HEIC
		}

		private final Kind kind;

		Unsupported(Kind kind) {
			super(kind.name());
			this.kind = kind;
		}

		public Kind kind() {
			return kind;
		}

	}

	static final int MAX_SIDE = 1600;

	static final int THUMB_SIDE = 400;

	static final long MAX_PIXELS = 40_000_000L;

	private static final float QUALITY = 0.85f;

	private static final float THUMB_QUALITY = 0.8f;

	public PhotoProcessor() {
		// 실행 jar 에서 플러그인(WebP)을 확실히 찾도록 한 번 다시 훑는다
		ImageIO.scanForPlugins();
	}

	/** 형식이 아니거나 깨졌거나 너무 크면 IllegalArgumentException. */
	public Processed process(byte[] original) {
		String format = format(original);
		if (format == null) {
			if (isHeic(original)) {
				throw new Unsupported(Unsupported.Kind.HEIC);
			}
			throw new IllegalArgumentException("허용하지 않는 형식");
		}
		if ("jpeg".equals(format) && jpegComponents(original) == 4) {
			// JDK 기본 JPEG 리더는 CMYK·YCCK 를 읽지 못한다: 깨진 파일과 구분해 RGB 로 저장해 달라고 안내한다
			throw new Unsupported(Unsupported.Kind.CMYK);
		}
		BufferedImage decoded = decode(original, format);
		BufferedImage upright = orient(decoded, "jpeg".equals(format) ? ExifOrientation.read(original) : 1);
		BufferedImage image = fit(upright, MAX_SIDE);
		BufferedImage thumb = fit(image, THUMB_SIDE);
		return new Processed(jpeg(image, QUALITY), jpeg(thumb, THUMB_QUALITY), image.getWidth(), image.getHeight());
	}

	/** 확장자·Content-Type 이 아니라 첫 바이트로 판단. */
	static String format(byte[] b) {
		if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
			return "jpeg";
		}
		if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A
				&& b[6] == 0x1A && b[7] == 0x0A) {
			return "png";
		}
		if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B'
				&& b[11] == 'P') {
			return "webp";
		}
		return null;
	}

	/** ISO BMFF ftyp 상자의 브랜드가 HEIF 계열(heic·heix·hevc·heim·heis·mif1·msf1)인지. */
	static boolean isHeic(byte[] b) {
		if (b.length < 12 || b[4] != 'f' || b[5] != 't' || b[6] != 'y' || b[7] != 'p') {
			return false;
		}
		String brand = new String(b, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
		return java.util.List.of("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1").contains(brand);
	}

	/** JPEG 의 첫 SOF(시작 프레임) 표지에 적힌 색 성분 수. 못 찾으면 0. */
	static int jpegComponents(byte[] b) {
		int i = 2;
		while (i + 9 < b.length && (b[i] & 0xFF) == 0xFF) {
			int marker = b[i + 1] & 0xFF;
			int length = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
			if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
				return b[i + 9] & 0xFF;
			}
			if (marker == 0xDA || length < 2) {
				return 0;
			}
			i += 2 + length;
		}
		return 0;
	}

	private static BufferedImage decode(byte[] bytes, String format) {
		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
		if (!readers.hasNext()) {
			throw new IllegalStateException(format + " 읽기 플러그인 없음");
		}
		ImageReader reader = readers.next();
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			reader.setInput(in, true, true);
			long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
			if (pixels <= 0 || pixels > MAX_PIXELS) {
				throw new IllegalArgumentException("픽셀 수 상한 초과");
			}
			BufferedImage img = reader.read(0);
			if (img == null) {
				throw new IllegalArgumentException("읽을 수 없음");
			}
			return img;
		}
		catch (IOException | RuntimeException e) {
			if (e instanceof IllegalArgumentException iae) {
				throw iae;
			}
			throw new IllegalArgumentException("깨진 이미지", e);
		}
		finally {
			reader.dispose();
		}
	}

	/** EXIF 방향(1~8)을 픽셀에 반영한다. */
	static BufferedImage orient(BufferedImage src, int orientation) {
		if (orientation < 2 || orientation > 8) {
			return src;
		}
		int w = src.getWidth();
		int h = src.getHeight();
		boolean swap = orientation >= 5;
		AffineTransform t = new AffineTransform();
		switch (orientation) {
			case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
			case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
			case 4 -> { t.translate(0, h); t.scale(1, -1); }
			case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
			case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
			case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
			default -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
		}
		BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(src, t, null);
		g.dispose();
		return out;
	}

	/** 긴 변이 side 이하가 되게 줄인다(작으면 그대로, 흰 바탕 RGB 로). 크게 줄일 때는 반씩 여러 번 줄여 계단 현상을 줄인다. */
	static BufferedImage fit(BufferedImage src, int side) {
		int w = src.getWidth();
		int h = src.getHeight();
		double scale = Math.min(1.0, (double) side / Math.max(w, h));
		int tw = Math.max(1, (int) Math.round(w * scale));
		int th = Math.max(1, (int) Math.round(h * scale));
		BufferedImage current = src;
		while (current.getWidth() / 2 >= tw && current.getHeight() / 2 >= th) {
			current = draw(current, current.getWidth() / 2, current.getHeight() / 2);
		}
		return draw(current, tw, th);
	}

	private static BufferedImage draw(BufferedImage src, int w, int h) {
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, w, h);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(src, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	private static byte[] jpeg(BufferedImage img, float quality) {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			writer.write(null, new IIOImage(img, null, null), param);
		}
		catch (IOException e) {
			throw new IllegalStateException("JPEG 쓰기 실패", e);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

}
