package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.multibindings.Multibinder;

import info.openrocket.core.plugin.PluginModule;

/**
 * OpenRocket's plugins, bound the way its {@link PluginModule} binds them but from a fixed list. PluginModule finds
 * them by scanning every jar on the classpath for @Plugin, which took 5.5 of the app's 6.4 s start; the list is what
 * that scan finds in the OpenRocket version we ship (KnownPluginsTest runs the scan and compares). If a class is
 * missing (another OpenRocket version), the scan is used after all.
 */
final class KnownPlugins extends AbstractModule {
	static final List<String> CLASSES = List.of(
			"info.openrocket.core.formatting.RocketSubstitutor",
			"info.openrocket.core.formatting.MotorConfigurationSubstitutor",
			"info.openrocket.core.simulation.extension.SimulationExtensionProvider",
			"info.openrocket.core.simulation.extension.example.AirStartProvider",
			"info.openrocket.core.simulation.extension.example.CSVSaveProvider",
			"info.openrocket.core.simulation.extension.example.DampingMomentProvider",
			"info.openrocket.core.simulation.extension.example.PrintSimulationProvider",
			"info.openrocket.core.simulation.extension.example.RollControlProvider",
			"info.openrocket.core.simulation.extension.example.StopSimulationProvider",
			"info.openrocket.core.simulation.extension.impl.JavaCodeProvider",
			"info.openrocket.core.simulation.extension.impl.ScriptingProvider");

	private final List<Class<?>> types;

	private KnownPlugins(List<Class<?>> types) {
		this.types = types;
	}

	/** The fixed list when every class is there, else OpenRocket's own scanning module. */
	static Module create() {
		List<Class<?>> types = new ArrayList<>();
		try {
			for (String name : CLASSES) {
				types.add(Class.forName(name, false, KnownPlugins.class.getClassLoader()));
			}
		} catch (ClassNotFoundException | LinkageError e) {
			return new PluginModule();
		}
		return new KnownPlugins(types);
	}

	@Override
	@SuppressWarnings({ "unchecked", "rawtypes" })
	protected void configure() {
		// As PluginModule.configure: every annotated class is bound under each annotated interface it implements.
		List<Class<?>> interfaces = types.stream().filter(Class::isInterface).toList();
		Map<Class<?>, Multibinder> binders = new HashMap<>();
		for (Class<?> c : types) {
			if (c.isInterface()) {
				continue;
			}
			for (Class<?> i : interfaces) {
				if (i.isAssignableFrom(c)) {
					binders.computeIfAbsent(i, k -> Multibinder.newSetBinder(binder(), (Class) k)).addBinding().to((Class) c);
				}
			}
		}
	}
}
