package io.github.openrocketmcp.report;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.github.openrocketmcp.mcp.CallContext;

/**
 * Writes a {@link FlightAnimation} as a looping GIF (plays everywhere: chat, slides, Discord, GitHub) and, when ffmpeg is
 * installed, as an H.264 MP4 for presentations and video editors. Frames render in parallel batches and stream to the
 * encoders, so memory stays flat however long the animation is.
 */
public final class Video {
	private Video() {
	}

	public record Written(Path gif, Path mp4, int frames, double seconds, long gifBytes, long mp4Bytes, String mp4Note) {
	}

	/**
	 * The ffmpeg executable: $OPENROCKET_MCP_FFMPEG or $FFMPEG if set, else ffmpeg on the PATH; null when there is none.
	 */
	public static String ffmpeg() {
		for (String env : new String[] { "OPENROCKET_MCP_FFMPEG", "FFMPEG" }) {
			String v = System.getenv(env);
			if (v != null && !v.isBlank() && Files.isExecutable(Path.of(v))) {
				return v;
			}
		}
		String path = System.getenv("PATH");
		if (path == null) {
			return null;
		}
		for (String dir : path.split(File.pathSeparator)) {
			for (String name : new String[] { "ffmpeg", "ffmpeg.exe" }) {
				Path p = Path.of(dir, name);
				if (Files.isExecutable(p)) {
					return p.toString();
				}
			}
		}
		return null;
	}

	/**
	 * @param gifWidth the GIF's width (frames are scaled down to it; GIFs get large fast)
	 * @param mp4      where to write the MP4 at the animation's full size, or null for none
	 */
	public static Written write(FlightAnimation a, Path gif, int gifWidth, Path mp4) throws IOException, InterruptedException {
		CallContext ctx = CallContext.current();
		int n = a.frames;
		// Palette from frames spread over the flight, including the key moments.
		List<BufferedImage> samples = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			samples.add(scale(a.frame((int) ((long) i * (n - 1) / 7)), gifWidth));
		}
		for (double[] km : a.keyMoments()) {
			samples.add(scale(a.render(km[0], 0), gifWidth));
		}
		Process ff = null;
		OutputStream ffIn = null;
		String note = null;
		String exe = mp4 == null ? null : ffmpeg();
		if (mp4 != null && exe == null) {
			note = "ffmpeg not found: install it (or set OPENROCKET_MCP_FFMPEG) for an MP4; the GIF is written.";
		}
		Path ffLog = null;
		if (exe != null) {
			ffLog = Files.createTempFile("ffmpeg", ".log");
			ProcessBuilder pb = new ProcessBuilder(exe, "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s",
					a.w + "x" + a.h, "-r", Double.toString(a.fps()), "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20",
					"-preset", "medium", "-movflags", "+faststart", mp4.toAbsolutePath().toString());
			pb.redirectErrorStream(true).redirectOutput(ffLog.toFile());
			ff = pb.start();
			ffIn = ff.getOutputStream();
		}
		ctx.expect(n, "frames");
		int batch = Math.max(4, 2 * Runtime.getRuntime().availableProcessors());
		byte[] rgb = new byte[a.w * a.h * 3];
		try (Gif g = new Gif(gif, a.fps(), samples)) {
			for (int from = 0; from < n; from += batch) {
				ctx.checkCancelled();
				for (BufferedImage img : a.frames(from, Math.min(n, from + batch))) {
					g.add(scale(img, gifWidth));
					if (ffIn != null) {
						int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
						for (int i = 0; i < px.length; i++) {
							rgb[3 * i] = (byte) (px[i] >> 16);
							rgb[3 * i + 1] = (byte) (px[i] >> 8);
							rgb[3 * i + 2] = (byte) px[i];
						}
						try {
							ffIn.write(rgb);
						} catch (IOException e) {
							ffIn = null; // ffmpeg quit (e.g. no libx264); report below
						}
					}
					ctx.advance();
				}
			}
		} finally {
			if (ff != null) {
				try {
					if (ffIn != null) {
						ffIn.close();
					}
				} catch (IOException ignored) {
					// already gone
				}
				if (!ff.waitFor(120, TimeUnit.SECONDS)) {
					ff.destroyForcibly();
				}
			}
		}
		long mp4Bytes = 0;
		if (ff != null) {
			if (ff.exitValue() == 0 && Files.isRegularFile(mp4)) {
				mp4Bytes = Files.size(mp4);
			} else {
				String log = Files.readString(ffLog, StandardCharsets.UTF_8).strip();
				note = "ffmpeg failed" + (log.isEmpty() ? "" : ": " + log.lines().reduce((x, y) -> y).orElse("")) + "; the GIF is written.";
			}
			Files.deleteIfExists(ffLog);
		}
		return new Written(gif, mp4Bytes > 0 ? mp4 : null, n, a.seconds(), Files.size(gif), mp4Bytes, note);
	}

	static BufferedImage scale(BufferedImage img, int width) {
		if (width >= img.getWidth()) {
			return img;
		}
		int height = (int) Math.round(img.getHeight() * (double) width / img.getWidth());
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(img, 0, 0, width, height, null);
		g.dispose();
		return out;
	}
}
