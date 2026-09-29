package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Streamer;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * A simulated flight as the animation needs it: per stage branch, position east / north / up of the pad, the rocket's
 * attitude and accumulated roll angle, the numbers shown on screen, and the flight events worth a caption.
 */
public final class FlightTrack {
	private FlightTrack() {
	}

	/** One stage's path (branch 0 is the sustainer / whole vehicle; later branches are dropped stages). */
	public static final class Track {
		public final String name;
		public final double[] t, east, north, alt, speed, vz, acc, mach, theta, phi, roll, thrust, cg, horiz, windSpeed, windDir;

		Track(FlightDataBranch b) {
			Branch br = Branch.of(b);
			name = b.getName();
			t = br.time;
			east = br.col(FlightDataType.TYPE_POSITION_X);
			north = br.col(FlightDataType.TYPE_POSITION_Y);
			alt = br.col(FlightDataType.TYPE_ALTITUDE);
			speed = br.col(FlightDataType.TYPE_VELOCITY_TOTAL);
			vz = br.col(FlightDataType.TYPE_VELOCITY_Z);
			acc = br.col(FlightDataType.TYPE_ACCELERATION_TOTAL);
			mach = br.col(FlightDataType.TYPE_MACH_NUMBER);
			theta = br.col(FlightDataType.TYPE_ORIENTATION_THETA);
			phi = br.col(FlightDataType.TYPE_ORIENTATION_PHI);
			thrust = br.col(FlightDataType.TYPE_THRUST_FORCE);
			cg = br.col(FlightDataType.TYPE_CG_LOCATION);
			horiz = br.col(FlightDataType.TYPE_POSITION_XY);
			windSpeed = br.col(FlightDataType.TYPE_WIND_VELOCITY);
			windDir = br.col(FlightDataType.TYPE_WIND_DIRECTION);
			double[] rate = br.col(FlightDataType.TYPE_ROLL_RATE);
			roll = new double[t.length];
			for (int i = 1; i < t.length; i++) {
				double r0 = Double.isNaN(rate[i - 1]) ? 0 : rate[i - 1], r1 = Double.isNaN(rate[i]) ? 0 : rate[i];
				roll[i] = roll[i - 1] + 0.5 * (r0 + r1) * (t[i] - t[i - 1]);
			}
		}

		public double start() {
			return t.length == 0 ? 0 : t[0];
		}

		public double end() {
			return t.length == 0 ? 0 : t[t.length - 1];
		}

		/** Linear interpolation of a column at a time (clamped to the branch; NaN samples are skipped). */
		public double at(double[] col, double time) {
			if (t.length == 0) {
				return Double.NaN;
			}
			if (time <= t[0]) {
				return firstValid(col, 0, 1);
			}
			if (time >= t[t.length - 1]) {
				return firstValid(col, t.length - 1, -1);
			}
			int i = Arrays.binarySearch(t, time);
			if (i >= 0) {
				return Double.isNaN(col[i]) ? firstValid(col, i, -1) : col[i];
			}
			int hi = -i - 1, lo = hi - 1;
			double a = col[lo], b = col[hi];
			if (Double.isNaN(a) || Double.isNaN(b)) {
				return Double.isNaN(a) ? (Double.isNaN(b) ? firstValid(col, lo, -1) : b) : a;
			}
			double u = (time - t[lo]) / (t[hi] - t[lo]);
			return a + u * (b - a);
		}

		private static double firstValid(double[] col, int from, int step) {
			for (int i = from; i >= 0 && i < col.length; i += step) {
				if (!Double.isNaN(col[i])) {
					return col[i];
				}
			}
			return Double.NaN;
		}

		/** Position {east, north, up} (m). */
		public double[] position(double time) {
			double e = at(east, time), n = at(north, time), z = at(alt, time);
			return new double[] { Double.isNaN(e) ? 0 : e, Double.isNaN(n) ? 0 : n, Double.isNaN(z) ? 0 : Math.max(0, z) };
		}

		/**
		 * Unit vector of the rocket's nose direction: OpenRocket's orientation theta is the axis elevation above the
		 * horizon and phi its heading counter-clockwise from east; the flight path is used where they were not recorded.
		 */
		public double[] axis(double time) {
			double th = at(theta, time), ph = at(phi, time);
			if (!Double.isNaN(th) && !Double.isNaN(ph)) {
				return new double[] { Math.cos(th) * Math.cos(ph), Math.cos(th) * Math.sin(ph), Math.sin(th) };
			}
			double[] a = position(time - 0.05), b = position(time + 0.05);
			double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2], l = Math.sqrt(dx * dx + dy * dy + dz * dz);
			return l < 1e-6 ? new double[] { 0, 0, 1 } : new double[] { dx / l, dy / l, dz / l };
		}
	}

	/** Something to caption; {@code size} is a parachute's diameter or a streamer's length (m). */
	public record Event(double time, String kind, String title, String detail, String device, double size, boolean streamer) {
	}

	public record Flight(List<Track> tracks, List<Event> events, double maxThrust, double burnout, double apogeeTime,
			double landingTime, double launchAltitude) {
		public Track main() {
			return tracks.get(0);
		}
	}

	public static Flight of(Simulation sim) {
		FlightData data = sim.getSimulatedData();
		if (data == null || data.getBranchCount() == 0) {
			throw new IllegalStateException("The simulation has no flight data.");
		}
		List<Track> tracks = new ArrayList<>();
		for (FlightDataBranch b : data.getBranches()) {
			tracks.add(new Track(b));
		}
		Track m = tracks.get(0);
		FlightDataBranch b0 = data.getBranch(0);
		List<Event> ev = new ArrayList<>();
		double burnout = 0, apogee = Double.NaN, landing = m.end(), maxThrust = 0;
		for (double f : m.thrust) {
			if (!Double.isNaN(f)) {
				maxThrust = Math.max(maxThrust, f);
			}
		}
		for (FlightEvent e : b0.getEvents()) {
			double t = e.getTime();
			double[] p = m.position(t);
			String at = "at " + Units.fmt(p[2], Dim.DISTANCE);
			switch (e.getType()) {
				case LIFTOFF -> ev.add(new Event(t, "liftoff", "Liftoff", "", null, 0, false));
				case LAUNCHROD -> ev.add(new Event(t, "rail", "Rail clear", Units.fmt(m.at(m.speed, t), Dim.VELOCITY), null, 0, false));
				case IGNITION -> {
					if (t > 0.05) {
						ev.add(new Event(t, "ignition", "Ignition", at, null, 0, false));
					}
				}
				case BURNOUT -> {
					if (ev.stream().noneMatch(x -> x.kind().equals("burnout") && Math.abs(x.time() - t) < 0.1)) {
						ev.add(new Event(t, "burnout", "Motor burnout", at + ", " + Units.fmt(m.at(m.speed, t), Dim.VELOCITY), null, 0,
								false));
					}
					burnout = Math.max(burnout, t);
				}
				case STAGE_SEPARATION -> ev.add(new Event(t, "separation", "Stage separation", at, null, 0, false));
				case APOGEE -> {
					apogee = t;
					ev.add(new Event(t, "apogee", "Apogee", Units.fmt(p[2], Dim.DISTANCE) + " AGL", null, 0, false));
				}
				case RECOVERY_DEVICE_DEPLOYMENT -> {
					if (e.getSource() instanceof RecoveryDevice rd) {
						boolean streamer = rd instanceof Streamer;
						double size = rd instanceof Parachute pc ? pc.getDiameter() : rd instanceof Streamer s ? s.getStripLength() : 0;
						ev.add(new Event(t, "deploy", rd.getName() + " out", at + ", " + Units.fmt(m.at(m.speed, t), Dim.VELOCITY), rd.getName(),
								size, streamer));
					}
				}
				case GROUND_HIT -> {
					landing = t;
					ev.add(new Event(t, "landing", "Touchdown", Units.fmt(Math.abs(m.at(m.vz, t - 0.05)), Dim.VELOCITY) + ", "
							+ Units.fmt(Math.hypot(p[0], p[1]), Dim.DISTANCE) + " from the pad", null, 0, false));
				}
				default -> {
					// not captioned
				}
			}
		}
		// Mach 1 and maximum velocity come from the data, not from events.
		int iMax = 0;
		for (int i = 0; i < m.t.length; i++) {
			if (!Double.isNaN(m.speed[i]) && (Double.isNaN(m.speed[iMax]) || m.speed[i] > m.speed[iMax])) {
				iMax = i;
			}
		}
		for (int i = 0; i < m.t.length && i <= iMax; i++) {
			if (m.mach[i] >= 1) {
				ev.add(new Event(m.t[i], "mach1", "Mach 1", "supersonic", null, 0, false));
				break;
			}
		}
		if (m.t.length > 0) {
			ev.add(new Event(m.t[iMax], "maxv", "Max velocity", Units.fmt(m.speed[iMax], Dim.VELOCITY)
					+ (Double.isNaN(m.mach[iMax]) ? "" : ", Mach " + String.format(Locale.ROOT, "%.2f", m.mach[iMax])), null, 0, false));
		}
		ev.sort((x, y) -> Double.compare(x.time(), y.time()));
		if (Double.isNaN(apogee)) {
			apogee = m.t.length == 0 ? 0 : m.t[iMax];
		}
		return new Flight(tracks, ev, maxThrust, burnout, apogee, landing, sim.getOptions().getLaunchAltitude());
	}
}
