package io.github.openrocketmcp.or;

import java.util.Map;
import java.util.WeakHashMap;

import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;

/**
 * Ends each flight branch once it is past apogee and its first recovery device is out (or a minute after apogee with
 * none), for studies that only read the ascent: apogee, stability, rail exit, Mach, flutter, ejection timing. The long
 * descent under the parachutes is most of a simulation's steps; skipping it changes none of those numbers.
 */
final class AscentOnly extends AbstractSimulationListener {
	/** Time after apogee to stop when nothing deploys (a late or missing deployment still shows as one). */
	static final double NO_DEPLOYMENT_WAIT = 60;

	/** Per branch: {apogee time, first deployment time}; branches of one flight share this listener. */
	private final Map<FlightDataBranch, double[]> times = new WeakHashMap<>();

	private synchronized double[] of(SimulationStatus s) {
		return times.computeIfAbsent(s.getFlightDataBranch(), b -> new double[] { Double.NaN, Double.NaN });
	}

	@Override
	public boolean handleFlightEvent(SimulationStatus status, FlightEvent event) {
		double[] t = of(status);
		if (event.getType() == FlightEvent.Type.APOGEE && Double.isNaN(t[0])) {
			t[0] = event.getTime();
		} else if (event.getType() == FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT && Double.isNaN(t[1])) {
			t[1] = event.getTime();
		}
		return true;
	}

	@Override
	public void postStep(SimulationStatus status) throws SimulationException {
		double[] t = of(status);
		if (Double.isNaN(t[0])) {
			return;
		}
		double now = status.getSimulationTime();
		if ((!Double.isNaN(t[1]) && now > Math.max(t[0], t[1]) + 0.5) || now > t[0] + NO_DEPLOYMENT_WAIT) {
			status.addEvent(new FlightEvent(FlightEvent.Type.SIMULATION_END, now));
		}
	}

	@Override
	public boolean isSystemListener() {
		return true;
	}
}
