package io.github.openrocketmcp.mcp;

/**
 * An error the model can act on (bad argument, unknown component, ...). Reported to the client as a tool
 * result with {@code isError: true} rather than as a protocol error, so the model sees the message.
 *
 * <p>Its {@link Kind} tells the app whose problem it is: what was asked (input, the default), the flight itself
 * (physics), or the computer around the engine (system: no internet, a program not installed).
 */
public class ToolException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	/** Whose problem an error or a result note is; see {@link Issues}. */
	public enum Kind {
		/** What was asked: a value, a name, a missing argument. The user fixes the request. */
		INPUT,
		/** A result that cannot be trusted as given: not computed, or outside what the model is valid for. */
		OUTPUT,
		/** The computer around the engine (network, files, an external program), or a bug in the engine. */
		SYSTEM,
		/** The rocket or its flight: it does not lift off, tumbles, never reaches apogee. The design needs a change. */
		PHYSICS;

		public String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	private final Kind kind;
	private String field;

	public ToolException(String message) {
		this(message, Kind.INPUT, null);
	}

	public ToolException(String message, Throwable cause) {
		this(message, Kind.INPUT, cause);
	}

	protected ToolException(String message, Kind kind, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	/** The design or its flight cannot do what was asked. */
	public static ToolException physics(String message) {
		return new ToolException(message, Kind.PHYSICS, null);
	}

	/** Something outside the engine failed: the network, a file, an external program. */
	public static ToolException system(String message) {
		return new ToolException(message, Kind.SYSTEM, null);
	}

	public static ToolException system(String message, Throwable cause) {
		return new ToolException(message, Kind.SYSTEM, cause);
	}

	/** The argument the error is about, when the message does not quote it. */
	public ToolException field(String name) {
		this.field = name;
		return this;
	}

	public Kind kind() {
		return kind;
	}

	public String field() {
		return field;
	}
}
