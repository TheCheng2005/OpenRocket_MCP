package io.github.openrocketmcp.or;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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
import io.github.openrocketmcp.mcp.CallContext;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

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
	/** Listeners for a simulation's next run; weak (Simulation uses identity equality), so unflown variants are freed. */
	private static final Map<Simulation, SimulationListener[]> LISTENERS = Collections.synchronizedMap(new WeakHashMap<>());

	private Variants() {
	}

	/** Attaches listeners used when {@code sim} is run through {@link #runAll}. */
	public static Simulation listen(Simulation sim, SimulationListener... listeners) {
		LISTENERS.put(sim, listeners);
		return sim;
	}

	/** Ends the flight once it is past apogee and its first recovery device is out (see {@link AscentOnly}). */
	public static Simulation ascentOnly(Simulation sim) {
		SimulationListener[] had = LISTENERS.get(sim);
		SimulationListener[] l = had == null ? new SimulationListener[1] : Arrays.copyOf(had, had.length + 1);
		l[l.length - 1] = new AscentOnly();
		LISTENERS.put(sim, l);
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

	private record Flown(String stamp, SimulationOptions options, Simulation sim) {
	}

	/** Flights of a simulation in other ground winds, reused while the design and its conditions are unchanged. */
	private static final Map<Simulation, Map<Double, Flown>> WIND = Collections.synchronizedMap(new WeakHashMap<>());

	/**
	 * The simulation flown in each steady ground wind (a wind profile is scaled from its lowest level). The rule check's
	 * design-wind case and the flight card's drift table ask for the same flights again and again; they are flown once
	 * (in parallel) and reused until the design, its configuration or the launch conditions change.
	 */
	/**
	 * Drops the cached wind cases of these simulations. Each cached flight belongs to its design's document, which holds
	 * the simulation the cache is keyed on, so the weak keys alone never let a closed design go.
	 */
	public static void forget(Collection<Simulation> sims) {
		for (Simulation s : sims) {
			WIND.remove(s);
		}
	}

	static boolean hasWindCases(Simulation sim) {
		return WIND.containsKey(sim);
	}

	public static List<Run> windCases(Simulation base, OpenRocketDocument doc, List<Double> speeds) {
		String stamp = Sims.stamp(base);
		Map<Double, Flown> cache = WIND.computeIfAbsent(base, k -> new ConcurrentHashMap<>());
		Run[] out = new Run[speeds.size()];
		List<Simulation> todo = new ArrayList<>();
		List<Integer> where = new ArrayList<>();
		for (int i = 0; i < speeds.size(); i++) {
			Flown f = cache.get(speeds.get(i));
			if (f != null && f.stamp().equals(stamp) && f.options().equals(base.getOptions())) {
				out[i] = new Run(f.sim(), null);
			} else {
				Simulation v = of(base, doc, null, null);
				Winds.setGround(v.getOptions(), speeds.get(i), Double.NaN);
				todo.add(v);
				where.add(i);
			}
		}
		List<Run> runs = todo.isEmpty() ? List.of() : runAll(todo);
		for (int j = 0; j < runs.size(); j++) {
			int i = where.get(j);
			out[i] = runs.get(j);
			if (runs.get(j).ok()) {
				cache.put(speeds.get(i), new Flown(stamp, base.getOptions().clone(), runs.get(j).sim()));
			}
		}
		return List.of(out);
	}

	/** One ground-wind case, flown or reused; throws when the flight fails. */
	public static Simulation windCase(Simulation base, OpenRocketDocument doc, double speed) {
		Run r = windCases(base, doc, List.of(speed)).get(0);
		if (!r.ok()) {
			throw new ToolException("The " + Units.fmt(speed, Dim.VELOCITY)
					+ " wind case failed: " + r.error());
		}
		return r.sim();
	}

	/** Result of one run: the simulation, or the error that stopped it. */
	public record Run(Simulation sim, String error) {
		public boolean ok() {
			return error == null;
		}
	}

	/** Simulates all variants in parallel, preserving order. Failed runs are reported, not thrown. */
	public static List<Run> runAll(List<Simulation> sims) {
		// The tool call this runs for: progress is counted per finished flight, and a cancelled call stops here.
		CallContext call = CallContext.current();
		call.checkCancelled();
		List<Future<Run>> futures = new ArrayList<>();
		for (Simulation s : sims) {
			futures.add(POOL.submit(() -> {
				if (call.isCancelled()) {
					LISTENERS.remove(s);
					return new Run(s, "cancelled");
				}
				try {
					SimulationListener[] l = LISTENERS.remove(s);
					Sims.run(s, l == null ? new SimulationListener[0] : l);
					return new Run(s, null);
				} catch (ToolException e) {
					return new Run(s, e.getMessage());
				} finally {
					call.advance();
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
		call.checkCancelled();
		return out;
	}

	/**
	 * Runs independent tasks on the simulation pool, in order. The tasks must not call {@link #runAll} themselves (they
	 * would wait on the pool they run in).
	 */
	static <T> List<T> parallel(List<Callable<T>> tasks) {
		List<Future<T>> futures = new ArrayList<>();
		for (Callable<T> t : tasks) {
			futures.add(POOL.submit(t));
		}
		List<T> out = new ArrayList<>();
		for (Future<T> f : futures) {
			try {
				out.add(f.get());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new ToolException("Interrupted");
			} catch (ExecutionException e) {
				Throwable c = e.getCause() == null ? e : e.getCause();
				throw c instanceof RuntimeException r ? r : new ToolException(c.getMessage(), c);
			}
		}
		return out;
	}

	public static int threads() {
		return THREADS;
	}
}
