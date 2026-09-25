package io.github.openrocketmcp.or;

import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;

/**
 * Aborts a simulation that runs away: longer than a wall-clock budget, or whose simulated time stops advancing (e.g.
 * a NaN state). A hung tool call is worse than a clear error, and a runaway run would also block the shared
 * simulation pool for every later request. Budget: system property openrocketmcp.simTimeoutSeconds (default 120).
 */
final class Watchdog extends AbstractSimulationListener {
	static final long BUDGET_NANOS = Long.getLong("openrocketmcp.simTimeoutSeconds", 120) * 1_000_000_000L;
	static final int STALL_STEPS = 20_000;

	private final long start = System.nanoTime();
	private double lastTime = Double.NEGATIVE_INFINITY;
	private int stalled;

	@Override
	public void postStep(SimulationStatus s) throws SimulationException {
		double t = s.getSimulationTime();
		if (!(t > lastTime)) {
			if (++stalled > STALL_STEPS) {
				throw new SimulationException("Simulation stopped advancing at t=" + t + " s (" + state(s) + ")");
			}
		} else {
			stalled = 0;
			lastTime = t;
		}
		if (System.nanoTime() - start > BUDGET_NANOS) {
			throw new SimulationException("Simulation exceeded " + BUDGET_NANOS / 1_000_000_000L + " s of computing time at t="
					+ t + " s (" + state(s) + ")");
		}
	}

	private static String state(SimulationStatus s) {
		var p = s.getRocketPosition();
		var v = s.getRocketVelocity();
		return "altitude " + (p == null ? "?" : p.z) + " m, velocity " + (v == null ? "?" : v.length()) + " m/s";
	}

	@Override
	public boolean isSystemListener() {
		return true;
	}
}
