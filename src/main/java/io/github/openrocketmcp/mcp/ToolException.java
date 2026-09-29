package io.github.openrocketmcp.mcp;

/**
 * An error the model can act on (bad argument, unknown component, ...). Reported to the client as a tool
 * result with {@code isError: true} rather than as a protocol error, so the model sees the message.
 */
public class ToolException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	public ToolException(String message) {
		super(message);
	}

	public ToolException(String message, Throwable cause) {
		super(message, cause);
	}
}
