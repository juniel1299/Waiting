package com.daumjari.photo;

/** JPEG 의 EXIF(APP1) 에서 방향 태그(0x0112)만 읽는다. 없거나 깨졌으면 1(그대로). */
final class ExifOrientation {

	private ExifOrientation() {
	}

	static int read(byte[] b) {
		int i = 2;
		while (i + 4 <= b.length && (b[i] & 0xFF) == 0xFF) {
			int marker = b[i + 1] & 0xFF;
			int length = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
			if (marker == 0xDA || length < 2) {
				return 1;
			}
			if (marker == 0xE1 && i + 10 <= b.length && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') {
				return fromTiff(b, i + 10, Math.min(b.length, i + 2 + length));
			}
			i += 2 + length;
		}
		return 1;
	}

	private static int fromTiff(byte[] b, int start, int end) {
		if (start + 8 > end) {
			return 1;
		}
		boolean little = b[start] == 'I' && b[start + 1] == 'I';
		long ifd = u32(b, start + 4, little);
		int pos = (int) (start + ifd);
		if (ifd < 8 || pos + 2 > end) {
			return 1;
		}
		int count = u16(b, pos, little);
		for (int n = 0; n < count; n++) {
			int entry = pos + 2 + n * 12;
			if (entry + 12 > end) {
				return 1;
			}
			if (u16(b, entry, little) == 0x0112) {
				int value = u16(b, entry + 8, little);
				return value >= 1 && value <= 8 ? value : 1;
			}
		}
		return 1;
	}

	private static int u16(byte[] b, int i, boolean little) {
		return little ? (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) : ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
	}

	private static long u32(byte[] b, int i, boolean little) {
		return little ? (u16(b, i, true) | ((long) u16(b, i + 2, true) << 16)) : (((long) u16(b, i, false) << 16) | u16(b, i + 2, false));
	}

}
