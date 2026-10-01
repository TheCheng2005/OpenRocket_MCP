package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Files from Excel and altimeter software: UTF-8 or Windows-1252, with or without a byte-order mark. */
class TextFilesTest {
	@Test
	void decodesWhatTeamsActuallyHave() {
		String text = "part,mass (g),note\nnose,200,20° cone\n";
		assertEquals(text, TextFiles.decode(text.getBytes(StandardCharsets.UTF_8)));
		assertEquals(text, TextFiles.decode(text.getBytes(Charset.forName("windows-1252"))));
		byte[] utf8 = text.getBytes(StandardCharsets.UTF_8), bom = new byte[utf8.length + 3];
		bom[0] = (byte) 0xEF;
		bom[1] = (byte) 0xBB;
		bom[2] = (byte) 0xBF;
		System.arraycopy(utf8, 0, bom, 3, utf8.length);
		assertEquals(text, TextFiles.decode(bom), "the byte-order mark is not part of the first header");
	}
}
