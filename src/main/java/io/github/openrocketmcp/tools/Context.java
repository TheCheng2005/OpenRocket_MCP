package io.github.openrocketmcp.tools;

import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.standards.Standards;

/** Shared server state. */
public final class Context {
	public final Designs designs = new Designs();
	private volatile Standards standards;

	public Context(Standards standards) {
		this.standards = standards;
	}

	public Standards standards() {
		return standards;
	}

	public void setStandards(Standards s) {
		this.standards = s;
	}
}
