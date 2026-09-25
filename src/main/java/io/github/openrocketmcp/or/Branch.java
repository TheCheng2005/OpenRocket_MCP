package io.github.openrocketmcp.or;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;

/**
 * Read-only, array-backed view of a simulated {@link FlightDataBranch}.
 *
 * <p>OpenRocket's {@code DataBranch.get(type)} copies the whole column on every call and
 * {@code getDataIndexOfTime} is a linear scan, so per-point lookups are quadratic. This view copies each column
 * once and looks times up by binary search. Views are cached per branch; branches do not change once a
 * simulation has finished.
 */
final class Branch {
	private static final Map<FlightDataBranch, Branch> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

	/*
	 * Weak: the cache below is a WeakHashMap keyed by the FlightDataBranch; a strong reference from the value back to
	 * its key would keep every simulated flight alive for the life of the server (it did: a long session, or the test
	 * suite, eventually spent all its time in garbage collection).
	 */
	private final java.lang.ref.WeakReference<FlightDataBranch> raw;
	final double[] time;
	private final Map<FlightDataType, double[]> columns = new ConcurrentHashMap<>();

	private Branch(FlightDataBranch raw) {
		this.raw = new java.lang.ref.WeakReference<>(raw);
		this.time = toArray(raw.get(FlightDataType.TYPE_TIME));
	}

	static Branch of(FlightDataBranch b) {
		if (b.isMutable()) {
			return new Branch(b); // still being written; do not cache
		}
		return CACHE.computeIfAbsent(b, Branch::new);
	}

	private static double[] toArray(List<Double> list) {
		if (list == null) {
			return new double[0];
		}
		double[] out = new double[list.size()];
		for (int i = 0; i < out.length; i++) {
			Double v = list.get(i);
			out[i] = v == null ? Double.NaN : v;
		}
		return out;
	}

	/** Column values; NaN-filled when the type was not recorded. */
	double[] col(FlightDataType type) {
		return columns.computeIfAbsent(type, t -> {
			FlightDataBranch b = raw.get();
			double[] v = b == null ? new double[0] : toArray(b.get(t));
			if (v.length == 0) {
				v = new double[time.length];
				Arrays.fill(v, Double.NaN);
			}
			return v;
		});
	}

	int size() {
		return time.length;
	}

	/** First index with time >= t (clamped to the data range). */
	int index(double t) {
		if (time.length == 0) {
			return -1;
		}
		int i = Arrays.binarySearch(time, t);
		if (i < 0) {
			i = -i - 1;
		} else {
			while (i > 0 && time[i - 1] == t) {
				i--;
			}
		}
		return Math.min(i, time.length - 1);
	}

	double at(FlightDataType type, double t) {
		int i = index(t);
		if (i < 0) {
			return Double.NaN;
		}
		double[] c = col(type);
		return i < c.length ? c[i] : Double.NaN;
	}

	/** Airspeed at index i: Mach x speed of sound (includes wind), else ground-relative speed. */
	double airspeed(int i) {
		double v = col(FlightDataType.TYPE_MACH_NUMBER)[i] * col(FlightDataType.TYPE_SPEED_OF_SOUND)[i];
		return Double.isNaN(v) ? col(FlightDataType.TYPE_VELOCITY_TOTAL)[i] : v;
	}

	double last(FlightDataType type) {
		double[] c = col(type);
		return c.length == 0 ? Double.NaN : c[c.length - 1];
	}
}
