package io.github.openrocketmcp.or;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Text files people bring (altimeter logs, mass budgets, aero tables): UTF-8 when it is, else Windows-1252 (what Excel
 * and many altimeter programs write on Windows), without a byte-order mark either way.
 */
public final class TextFiles {
	private TextFiles() {
	}

	public static String read(Path p) throws IOException {
		return decode(Files.readAllBytes(p));
	}

	static String decode(byte[] b) {
		int start = b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF ? 3 : 0;
		ByteBuffer buf = ByteBuffer.wrap(b, start, b.length - start);
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(buf).toString();
		} catch (CharacterCodingException e) {
			return new String(b, start, b.length - start, Charset.forName("windows-1252"));
		}
	}
}
