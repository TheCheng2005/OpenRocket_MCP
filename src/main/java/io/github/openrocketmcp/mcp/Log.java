package io.github.openrocketmcp.mcp;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Logging to stderr. stdout is reserved for the MCP protocol.
 */
public final class Log {
	private static final PrintStream ERR = System.err;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private Log() {
	}

	public static void info(String message) {
		ERR.println("[openrocket-mcp " + LocalTime.now().format(TIME) + "] " + message);
	}

	public static void error(String message, Throwable t) {
		info("ERROR " + message + ": " + t);
		t.printStackTrace(ERR);
	}
}
