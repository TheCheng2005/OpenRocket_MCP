package io.github.openrocketmcp.report;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.awt.image.IndexColorModel;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;

/**
 * Looping animated GIF written frame by frame, so a long animation never sits in memory. All frames share one
 * 256-colour palette (median cut over sample frames), which keeps colours steady from frame to frame and the file small.
 */
public final class Gif implements Closeable {
	private final ImageWriter writer;
	private final ImageOutputStream out;
	private final IndexColorModel palette;
	private final byte[] lut = new byte[1 << 15]; // 5 bits per channel -> palette index
	private final double fps;
	private int frames;
	private boolean started;

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
		writer = ImageIO.getImageWritersByFormatName("gif").next();
		Files.deleteIfExists(file);
		out = ImageIO.createImageOutputStream(file.toFile());
		writer.setOutput(out);
	}

	public void add(BufferedImage rgb) throws IOException {
		int w = rgb.getWidth(), h = rgb.getHeight();
		BufferedImage idx = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, palette);
		byte[] dst = ((DataBufferByte) idx.getRaster().getDataBuffer()).getData();
		int[] src = rgb.getType() == BufferedImage.TYPE_INT_RGB ? ((DataBufferInt) rgb.getRaster().getDataBuffer()).getData()
				: rgb.getRGB(0, 0, w, h, null, 0, w);
		for (int i = 0; i < src.length; i++) {
			int c = src[i];
			dst[i] = lut[(c >> 19 & 31) << 10 | (c >> 11 & 31) << 5 | (c >> 3 & 31)];
		}
		if (!started) {
			writer.prepareWriteSequence(null);
			started = true;
		}
		// Frame delays in hundredths of a second, rounded so they add up to the right total.
		int delay = (int) Math.round((frames + 1) * 100 / fps) - (int) Math.round(frames * 100 / fps);
		writer.writeToSequence(new IIOImage(idx, null, metadata(idx, delay, frames == 0)), null);
		frames++;
	}

	private static int clamp(int v) {
		return v < 0 ? 0 : v > 255 ? 255 : v;
	}

	private IIOMetadata metadata(BufferedImage img, int delay, boolean first) throws IOException {
		ImageWriteParam p = writer.getDefaultWriteParam();
		IIOMetadata m = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), p);
		String f = m.getNativeMetadataFormatName();
		IIOMetadataNode root = (IIOMetadataNode) m.getAsTree(f);
		IIOMetadataNode gce = child(root, "GraphicControlExtension");
		gce.setAttribute("disposalMethod", "none");
		gce.setAttribute("userInputFlag", "FALSE");
		gce.setAttribute("transparentColorFlag", "FALSE");
		gce.setAttribute("delayTime", Integer.toString(Math.max(2, delay)));
		gce.setAttribute("transparentColorIndex", "0");
		if (first) {
			IIOMetadataNode apps = child(root, "ApplicationExtensions");
			IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
			app.setAttribute("applicationID", "NETSCAPE");
			app.setAttribute("authenticationCode", "2.0");
			app.setUserObject(new byte[] { 1, 0, 0 }); // loop forever
			apps.appendChild(app);
		}
		m.setFromTree(f, root);
		return m;
	}

	private static IIOMetadataNode child(IIOMetadataNode root, String name) {
		for (int i = 0; i < root.getLength(); i++) {
			if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
				return (IIOMetadataNode) root.item(i);
			}
		}
		IIOMetadataNode n = new IIOMetadataNode(name);
		root.appendChild(n);
		return n;
	}

	public int frames() {
		return frames;
	}

	@Override
	public void close() throws IOException {
		try {
			if (started) {
				writer.endWriteSequence();
			}
		} finally {
			out.close();
			writer.dispose();
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
