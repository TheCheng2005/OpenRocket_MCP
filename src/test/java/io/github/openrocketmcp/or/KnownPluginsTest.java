package io.github.openrocketmcp.or;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import info.openrocket.core.plugin.AnnotationFinderImpl;
import info.openrocket.core.plugin.Plugin;
import info.openrocket.core.simulation.extension.SimulationExtensionProvider;

/** The fixed plugin list is what OpenRocket's own classpath scan finds, so binding from it changes nothing. */
class KnownPluginsTest {
	@Test
	void listMatchesTheScan() {
		Set<String> scanned = new TreeSet<>();
		for (Class<?> c : new AnnotationFinderImpl().findAnnotatedTypes(Plugin.class)) {
			scanned.add(c.getName());
		}
		assertEquals(scanned, new TreeSet<>(KnownPlugins.CLASSES), "update KnownPlugins.CLASSES for this OpenRocket version");
	}

	@Test
	void simulationExtensionsAreBound() {
		OrRuntime.init();
		var providers = info.openrocket.core.startup.Application.getInjector()
				.getInstance(com.google.inject.Key.get(new com.google.inject.TypeLiteral<Set<SimulationExtensionProvider>>() {
				}));
		assertEquals(8, providers.size(), providers.toString());
	}
}
