package io.github.openrocketmcp.or;

import com.google.inject.AbstractModule;

import info.openrocket.core.database.ComponentPresetDao;
import info.openrocket.core.database.ComponentPresetDatabase;
import info.openrocket.core.database.motor.ThrustCurveMotorSetDatabase;
import info.openrocket.core.plugin.PluginModule;
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
		// OpenRocket's core module binds the preset database but not the ComponentPresetDao interface that the .ork
		// loader asks for (the desktop app binds it in its GUI module). Without it, designs that use catalogue parts
		// (e.g. the "Deployable payload" example) fail to open.
		OpenRocketCore.initialize(new PluginModule(), new AbstractModule() { // PluginModule: what initialize() passes
			@Override
			protected void configure() {
				bind(ComponentPresetDao.class).to(ComponentPresetDatabase.class);
			}
		});
		initialized = true;
	}

	/** Motor database; blocks until the bundled thrust curves have loaded. */
	public static ThrustCurveMotorSetDatabase motors() {
		init();
		return Application.getInjector().getInstance(ThrustCurveMotorSetDatabase.class);
	}

	/** Component preset database (parachutes, tubes, ...); blocks until loaded. */
	public static ComponentPresetDao presets() {
		init();
		return Application.getInjector().getInstance(ComponentPresetDatabase.class);
	}
}
