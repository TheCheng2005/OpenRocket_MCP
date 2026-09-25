package io.github.openrocketmcp.or;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.models.wind.PinkNoiseWindModel;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.simulation.SimulationOptions;
import info.openrocket.core.simulation.listeners.SimulationListener;
import io.github.openrocketmcp.mcp.ToolException;

/**
 * Independent what-if simulations. Each variant runs on its own copy of the rocket (component ids preserved), so
 * the open design is never modified and variants can be simulated in parallel.
 */
public final class Variants {
	private static final int THREADS = Integer.getInteger("openrocketmcp.threads",
			Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors())));
	private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r -> {
		Thread t = new Thread(r, "openrocket-sim");
		t.setDaemon(true);
		return t;
	});

	/** Simulation listeners to attach when a variant runs (e.g. Monte Carlo drag / thrust scaling). */
	private static final Map<Simulation, SimulationListener[]> LISTENERS = Collections.synchronizedMap(new IdentityHashMap<>());

	private Variants() {
	}

	/** Attaches listeners used when {@code sim} is run through {@link #runAll}. */
	public static Simulation listen(Simulation sim, SimulationListener... listeners) {
		LISTENERS.put(sim, listeners);
		return sim;
	}

	/**
	 * A copy of {@code base} running on a copy of its rocket, with {@code edit} applied to that copy and optional
	 * launch-condition overrides. Must be called on the request thread (OpenRocket objects are not thread-safe to
	 * copy concurrently); the returned simulation can then be run on any thread.
	 */
	public static Simulation of(Simulation base, OpenRocketDocument doc, Consumer<Rocket> edit, Sims.Overrides o) {
		Rocket copy = base.getRocket().copyWithOriginalID();
		if (edit != null) {
			edit.accept(copy);
		}
		Simulation sim = base.duplicateSimulation(copy);
		sim.removeChangeListener(doc); // what-if runs must not mark the design as changed
		if (o != null) {
			Sims.apply(sim.getOptions(), o);
		}
		// Same turbulence for every variant of this base, so differences come from the change being studied.
		seed(sim.getOptions(), base.getOptions().getRandomSeed());
		return sim;
	}

	/**
	 * Makes a simulation's randomness repeatable. OpenRocket seeds the wind model's turbulence once, from
	 * {@code new Random()}, when SimulationOptions is constructed, and setRandomSeed() does not reach it; so each
	 * copied simulation otherwise gets different gusts. Replace the average wind model with one seeded from
	 * {@code seed}, keeping its parameters.
	 */
	public static void seed(SimulationOptions options, int seed) {
		var type = options.getWindModelType(); // wind-model change events select the average model
		options.setRandomSeed(seed);
		try {
			Field f = SimulationOptions.class.getDeclaredField("averageWindModel");
			f.setAccessible(true);
			PinkNoiseWindModel old = (PinkNoiseWindModel) f.get(options);
			PinkNoiseWindModel seeded = new PinkNoiseWindModel(seed);
			seeded.loadFrom(old);
			f.set(options, seeded);
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Different OpenRocket internals: results stay valid, only turbulence is not repeatable.
		}
		Winds.seedLevels(options, seed);
		options.setWindModelType(type);
	}

	/** Result of one run: the simulation, or the error that stopped it. */
	public record Run(Simulation sim, String error) {
		public boolean ok() {
			return error == null;
		}
	}

	/** Simulates all variants in parallel, preserving order. Failed runs are reported, not thrown. */
	public static List<Run> runAll(List<Simulation> sims) {
		List<Future<Run>> futures = new ArrayList<>();
		for (Simulation s : sims) {
			futures.add(POOL.submit(() -> {
				try {
					SimulationListener[] l = LISTENERS.remove(s);
					Sims.run(s, l == null ? new SimulationListener[0] : l);
					return new Run(s, null);
				} catch (ToolException e) {
					return new Run(s, e.getMessage());
				}
			}));
		}
		List<Run> out = new ArrayList<>();
		for (Future<Run> f : futures) {
			try {
				out.add(f.get());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ToolException("Interrupted");
			} catch (ExecutionException e) {
				Throwable c = e.getCause() == null ? e : e.getCause();
				out.add(new Run(null, c.getClass().getSimpleName() + ": " + c.getMessage()));
			}
		}
		return out;
	}

	public static int threads() {
		return THREADS;
	}
}
