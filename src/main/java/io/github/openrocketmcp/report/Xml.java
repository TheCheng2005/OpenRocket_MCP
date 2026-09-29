package io.github.openrocketmcp.report;

/** Escaping for the SVG, KML and HTML text we write. */
public final class Xml {
	private Xml() {
	}

	/** Text safe inside an element or a double-quoted attribute; null becomes "". */
	public static String esc(String s) {
		return s == null ? ""
				: s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
