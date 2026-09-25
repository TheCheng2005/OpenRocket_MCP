package io.github.openrocketmcp.or;

import info.openrocket.core.database.ComponentPresetDao;
import info.openrocket.core.database.ComponentPresetDatabase;
import info.openrocket.core.database.motor.ThrustCurveMotorSetDatabase;
import info.openrocket.core.startup.Application;
import info.openrocket.core.startup.OpenRocketCore;

/**
 * Boots OpenRocket core headlessly (no Swing) and gives access to its databases.
 */
public final class OrRuntime {
	private static volatile boolean initialized;

	private OrRuntime() {
	}

	public static synchronized void init() {
		if (initialized) {
			return;
		}
		System.setProperty("java.awt.headless", "true");
		OpenRocketCore.initialize();
		initialized = true;
	}

	/** Motor database; blocks until the bundled thrust curves have loaded. */
	public static ThrustCurveMotorSetDatabase motors() {
		init();
		return Application.getThrustCurveMotorSetDatabase();
	}

	/** Component preset database (parachutes, tubes, ...); blocks until loaded. */
	public static ComponentPresetDao presets() {
		init();
		return Application.getInjector().getInstance(ComponentPresetDatabase.class);
	}
}
