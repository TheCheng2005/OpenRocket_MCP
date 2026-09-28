package io.github.openrocketmcp.report;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.util.Base64;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import io.github.openrocketmcp.mcp.CallContext;
import io.github.openrocketmcp.mcp.Log;

/**
 * Our SVG plots and drawings as PNG, so a tool can show them in the chat (MCP image content) as well as write the
 * file. Rendering is best effort: without fonts or a graphics stack the tool still returns its text and file.
 */
public final class Png {
	private Png() {
	}

	static {
		System.setProperty("java.awt.headless", "true");
	}

	/** Set OPENROCKET_MCP_INLINE_IMAGES=0 to send text results only (e.g. for clients that show images poorly). */
	static boolean enabled() {
		String v = System.getenv("OPENROCKET_MCP_INLINE_IMAGES");
		return v == null || !(v.equals("0") || v.equalsIgnoreCase("false") || v.equalsIgnoreCase("off"));
	}

	/** PNG bytes of an SVG document, scaled to {@code width} pixels (the height follows the aspect ratio). */
	public static byte[] render(String svg, int width) throws Exception {
		PNGTranscoder t = new PNGTranscoder();
		t.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) width);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		t.transcode(new TranscoderInput(new StringReader(svg)), new TranscoderOutput(out));
		return out.toByteArray();
	}

	/**
	 * Shows the SVG in the chat with the current tool result, at its own width (capped at 1400 px); returns whether it
	 * was attached.
	 */
	public static boolean attach(String svg, String alt) {
		if (!enabled() || svg == null) {
			return false;
		}
		try {
			int w = Math.min(1400, widthOf(svg));
			byte[] png = render(svg, w);
			CallContext.current().attach(new CallContext.Image("image/png", Base64.getEncoder().encodeToString(png), alt));
			return true;
		} catch (Throwable e) { // a missing font or graphics library must not fail the analysis
			Log.info("Could not render an image for the chat (" + e.getClass().getSimpleName() + ": " + e.getMessage()
					+ "); the SVG file is still written.");
			return false;
		}
	}

	/** Attaches an SVG file the tool has just written. */
	public static boolean attachFile(java.nio.file.Path svg, String alt) {
		try {
			return attach(java.nio.file.Files.readString(svg), alt);
		} catch (java.io.IOException e) {
			return false;
		}
	}

	static int widthOf(String svg) {
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("<svg[^>]*\\swidth=\"([0-9.]+)").matcher(svg);
		return m.find() ? (int) Math.round(Double.parseDouble(m.group(1))) : 900;
	}
}
