package io.github.openrocketmcp.report;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.image.IndexColorModel;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Looping animated GIF written frame by frame, so a long animation never sits in memory. All frames share one
 * 256-colour palette (median cut over sample frames), stored as the file's global colour table, which keeps colours
 * steady from frame to frame and the file small.
 *
 * <p>The file is written here byte by byte (GIF89a, LZW) rather than through ImageIO: the JDK's GIF writer stores its
 * own default palette and remaps indexed frames, so the colours came out wrong and changed from frame to frame.
 */
public final class Gif implements Closeable {
	private final OutputStream out;
	private final IndexColorModel palette;
	private final byte[] lut = new byte[1 << 15]; // 5 bits per channel -> palette index
	private final double fps;
	private final Lzw lzw = new Lzw();
	private int frames, width, height;

	public Gif(Path file, double fps, List<BufferedImage> samples) throws IOException {
		this.fps = fps;
		this.palette = medianCut(samples);
		int n = palette.getMapSize();
		int[] pr = new int[n], pg = new int[n], pb = new int[n];
		for (int i = 0; i < n; i++) {
			pr[i] = palette.getRed(i);
			pg[i] = palette.getGreen(i);
			pb[i] = palette.getBlue(i);
		}
		for (int c = 0; c < lut.length; c++) {
			int r = (c >> 10 & 31) * 255 / 31, g = (c >> 5 & 31) * 255 / 31, b = (c & 31) * 255 / 31;
			int best = 0, bestD = Integer.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				int dr = r - pr[i], dg = g - pg[i], db = b - pb[i];
				int d = 3 * dr * dr + 4 * dg * dg + 2 * db * db;
				if (d < bestD) {
					bestD = d;
					best = i;
				}
			}
			lut[c] = (byte) best;
		}
		Files.deleteIfExists(file);
		out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16);
	}

	public void add(BufferedImage rgb) throws IOException {
		int w = rgb.getWidth(), h = rgb.getHeight();
		if (frames == 0) {
			width = w;
			height = h;
			header(w, h);
		} else if (w != width || h != height) {
			throw new IllegalArgumentException("GIF frames must all be " + width + " x " + height);
		}
		int[] src = rgb.getType() == BufferedImage.TYPE_INT_RGB ? ((DataBufferInt) rgb.getRaster().getDataBuffer()).getData()
				: rgb.getRGB(0, 0, w, h, null, 0, w);
		byte[] idx = new byte[w * h];
		for (int i = 0; i < src.length; i++) {
			int c = src[i];
			idx[i] = lut[(c >> 19 & 31) << 10 | (c >> 11 & 31) << 5 | (c >> 3 & 31)];
		}
		// Frame delays in hundredths of a second, rounded so they add up to the right total.
		int delay = Math.max(2, (int) Math.round((frames + 1) * 100 / fps) - (int) Math.round(frames * 100 / fps));
		// Graphic control extension: leave the frame in place, the delay, no transparency.
		out.write(new byte[] { 0x21, (byte) 0xF9, 4, 0x04, (byte) delay, (byte) (delay >> 8), 0, 0 });
		// Image descriptor: the whole screen, no local colour table (the global one is the palette).
		out.write(0x2C);
		short16(0);
		short16(0);
		short16(w);
		short16(h);
		out.write(0);
		lzw.encode(idx, out);
		frames++;
	}

	/** Header, logical screen with the palette as the global colour table, and the loop-forever extension. */
	private void header(int w, int h) throws IOException {
		out.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
		short16(w);
		short16(h);
		out.write(0xF7); // global table present, 8-bit colour resolution, 256 entries
		out.write(0);
		out.write(0);
		int n = palette.getMapSize();
		for (int i = 0; i < 256; i++) {
			out.write(i < n ? palette.getRed(i) : 0);
			out.write(i < n ? palette.getGreen(i) : 0);
			out.write(i < n ? palette.getBlue(i) : 0);
		}
		out.write(new byte[] { 0x21, (byte) 0xFF, 11 });
		out.write("NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII));
		out.write(new byte[] { 3, 1, 0, 0, 0 });
	}

	private void short16(int v) throws IOException {
		out.write(v & 255);
		out.write(v >> 8 & 255);
	}

	public int frames() {
		return frames;
	}

	@Override
	public void close() throws IOException {
		try {
			if (frames > 0) {
				out.write(0x3B);
			}
		} finally {
			out.close();
		}
	}

	/**
	 * GIF's variable-width LZW for 8-bit pixels (after Jef Poskanzer's GIFEncoder): codes grow from 9 to 12 bits; when
	 * the table is full a clear code starts it again. Output goes in data sub-blocks of up to 255 bytes.
	 */
	static final class Lzw {
		private static final int CLEAR = 256, END = 257, MAX = 4096;
		private final int[] codes = new int[MAX * 256];
		private final int[] stamp = new int[MAX * 256]; // entry valid when stamp == generation (no clearing needed)
		private int generation;
		private int bits, maxCode, next, acc, accBits;
		private boolean clearing;
		private final byte[] block = new byte[255];
		private int blockLen;
		private OutputStream out;

		void encode(byte[] px, OutputStream out) throws IOException {
			this.out = out;
			out.write(8); // minimum code size
			generation++;
			bits = 9;
			maxCode = (1 << bits) - 1;
			next = END + 1;
			acc = 0;
			accBits = 0;
			blockLen = 0;
			clearing = false;
			code(CLEAR);
			if (px.length > 0) {
				int ent = px[0] & 255;
				for (int i = 1; i < px.length; i++) {
					int c = px[i] & 255, key = ent << 8 | c;
					if (stamp[key] == generation) {
						ent = codes[key];
						continue;
					}
					code(ent);
					ent = c;
					if (next < MAX) {
						stamp[key] = generation;
						codes[key] = next++;
					} else { // table full: start again
						generation++;
						next = END + 1;
						clearing = true;
						code(CLEAR);
					}
				}
				code(ent);
			}
			code(END);
			if (accBits > 0) {
				put(acc & 255);
			}
			if (blockLen > 0) {
				flushBlock();
			}
			out.write(0); // block terminator
		}

		private void code(int c) throws IOException {
			acc |= c << accBits;
			accBits += bits;
			while (accBits >= 8) {
				put(acc & 255);
				acc >>>= 8;
				accBits -= 8;
			}
			// Widen once the table has used every code of this width; narrow again after a clear.
			if (clearing) {
				bits = 9;
				maxCode = (1 << bits) - 1;
				clearing = false;
			} else if (next > maxCode && bits < 12) {
				bits++;
				maxCode = (1 << bits) - 1;
			}
		}

		private void put(int b) throws IOException {
			block[blockLen++] = (byte) b;
			if (blockLen == 255) {
				flushBlock();
			}
		}

		private void flushBlock() throws IOException {
			out.write(blockLen);
			out.write(block, 0, blockLen);
			blockLen = 0;
		}
	}

	/** A 256-colour palette by median cut of the 15-bit colour histogram of the sample frames. */
	static IndexColorModel medianCut(List<BufferedImage> samples) {
		int[] hist = new int[1 << 15];
		for (BufferedImage img : samples) {
			int w = img.getWidth(), h = img.getHeight();
			for (int y = 0; y < h; y += 2) {
				for (int x = 0; x < w; x += 2) {
					int c = img.getRGB(x, y);
					hist[(c >> 19 & 31) << 10 | (c >> 11 & 31) << 5 | (c >> 3 & 31)]++;
				}
			}
		}
		List<int[]> boxes = new ArrayList<>(); // colour indices in each box
		List<Integer> all = new ArrayList<>();
		for (int c = 0; c < hist.length; c++) {
			if (hist[c] > 0) {
				all.add(c);
			}
		}
		boxes.add(all.stream().mapToInt(Integer::intValue).toArray());
		while (boxes.size() < 256) {
			int pick = -1;
			long bestScore = 0;
			for (int i = 0; i < boxes.size(); i++) {
				int[] b = boxes.get(i);
				if (b.length < 2) {
					continue;
				}
				long pop = 0;
				for (int c : b) {
					pop += hist[c];
				}
				long score = pop * (range(b)[1] + 1);
				if (score > bestScore) {
					bestScore = score;
					pick = i;
				}
			}
			if (pick < 0) {
				break;
			}
			int[] b = boxes.remove(pick);
			int ch = range(b)[0];
			int shift = ch == 0 ? 10 : ch == 1 ? 5 : 0;
			Integer[] boxed = new Integer[b.length];
			for (int i = 0; i < b.length; i++) {
				boxed[i] = b[i];
			}
			Arrays.sort(boxed, (x, y) -> Integer.compare(x >> shift & 31, y >> shift & 31));
			long half = 0, total = 0;
			for (int c : b) {
				total += hist[c];
			}
			int cut = 1;
			for (int i = 0; i < boxed.length - 1; i++) {
				half += hist[boxed[i]];
				if (half * 2 >= total) {
					cut = i + 1;
					break;
				}
			}
			int[] lo = new int[cut], hi = new int[boxed.length - cut];
			for (int i = 0; i < boxed.length; i++) {
				if (i < cut) {
					lo[i] = boxed[i];
				} else {
					hi[i - cut] = boxed[i];
				}
			}
			boxes.add(lo);
			boxes.add(hi);
		}
		int n = Math.max(2, boxes.size());
		byte[] r = new byte[n], g = new byte[n], bl = new byte[n];
		for (int i = 0; i < boxes.size(); i++) {
			long sr = 0, sg = 0, sb = 0, pop = 0;
			for (int c : boxes.get(i)) {
				long k = hist[c];
				sr += k * ((c >> 10 & 31) * 255 / 31);
				sg += k * ((c >> 5 & 31) * 255 / 31);
				sb += k * ((c & 31) * 255 / 31);
				pop += k;
			}
			if (pop > 0) {
				r[i] = (byte) (sr / pop);
				g[i] = (byte) (sg / pop);
				bl[i] = (byte) (sb / pop);
			}
		}
		return new IndexColorModel(8, n, r, g, bl);
	}

	/** {channel with the widest spread, that spread}. */
	private static int[] range(int[] box) {
		int[] lo = { 31, 31, 31 }, hi = { 0, 0, 0 };
		for (int c : box) {
			int[] v = { c >> 10 & 31, c >> 5 & 31, c & 31 };
			for (int k = 0; k < 3; k++) {
				lo[k] = Math.min(lo[k], v[k]);
				hi[k] = Math.max(hi[k], v[k]);
			}
		}
		int best = 0;
		for (int k = 1; k < 3; k++) {
			if (hi[k] - lo[k] > hi[best] - lo[best]) {
				best = k;
			}
		}
		return new int[] { best, hi[best] - lo[best] };
	}
}
