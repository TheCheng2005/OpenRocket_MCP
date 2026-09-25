package io.github.openrocketmcp.or;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.models.wind.MultiLevelPinkNoiseWindModel;
import info.openrocket.core.models.wind.PinkNoiseWindModel;
import info.openrocket.core.models.wind.WindModel;
import info.openrocket.core.models.wind.WindModelType;
import info.openrocket.core.simulation.SimulationOptions;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * Wind profiles: OpenRocket's multi-level wind model (speed, direction and turbulence per altitude, interpolated in
 * between), from forecast / sounding levels or a power-law shear profile from the ground wind.
 */
public final class Winds {
	private Winds() {
	}

	/** One level; SI, direction in radians (wind FROM), turbulence as a standard deviation in m/s (NaN = default). */
	public record Level(double altitude, double speed, double direction, double sd) {
	}

	/*
	 * OpenRocket 24.12's SimulationOptions.getWindSpeedAverage / getWindDirection / getWindTurbulenceIntensity /
	 * getWindSpeedDeviation (the getters!) and their setters switch the simulation to the average wind model. Read and
	 * write through these helpers so a multi-level profile survives.
	 */

	/** Average-model (ground) wind speed, without changing the wind model. */
	public static double speed(SimulationOptions o) {
		return o.getAverageWindModel().getAverage();
	}

	/** Average-model wind direction (from), without changing the wind model. */
	public static double direction(SimulationOptions o) {
		return o.getAverageWindModel().getDirection();
	}

	public static double turbulence(SimulationOptions o) {
		return o.getAverageWindModel().getTurbulenceIntensity();
	}

	/** Sets the average-model turbulence intensity, keeping the selected wind model. */
	public static void setTurbulence(SimulationOptions o, double intensity) {
		WindModelType type = o.getWindModelType();
		o.setWindTurbulenceIntensity(intensity);
		o.setWindModelType(type);
	}

	public static boolean isMultiLevel(SimulationOptions o) {
		return o.getWindModelType() == WindModelType.MULTI_LEVEL;
	}

	/** Replaces the profile and switches the simulation to the multi-level model. */
	public static void setProfile(SimulationOptions o, List<Level> levels, boolean agl) {
		if (levels.isEmpty()) {
			throw new ToolException("A wind profile needs at least one level.");
		}
		MultiLevelPinkNoiseWindModel m = o.getMultiLevelWindModel();
		m.clearLevels();
		for (Level l : levels) {
			if (l.speed() < 0 || l.altitude() < -500) {
				throw new ToolException("Invalid wind level at " + Units.fmt(l.altitude(), Dim.DISTANCE));
			}
			if (Double.isNaN(l.sd())) {
				m.addWindLevel(l.altitude(), l.speed(), norm(l.direction()));
			} else {
				m.addWindLevel(l.altitude(), l.speed(), norm(l.direction()), l.sd());
			}
		}
		m.sortLevels();
		m.setAltitudeReference(agl ? WindModel.AltitudeReference.AGL : WindModel.AltitudeReference.MSL);
		// Keep the average model consistent with the ground level for tools that report "the" wind speed. These
		// setters switch OpenRocket back to the average model, so they go before selecting the multi-level model.
		Level ground = levels.stream().min((a, b) -> Double.compare(a.altitude(), b.altitude())).get();
		o.setWindSpeedAverage(ground.speed());
		o.setWindDirection(norm(ground.direction()));
		o.setWindModelType(WindModelType.MULTI_LEVEL);
	}

	public static void useAverage(SimulationOptions o) {
		o.setWindModelType(WindModelType.AVERAGE);
	}

	public static List<Level> levels(SimulationOptions o) {
		List<Level> out = new ArrayList<>();
		for (MultiLevelPinkNoiseWindModel.LevelWindModel l : o.getMultiLevelWindModel().getLevels()) {
			out.add(new Level(l.getAltitude(), l.getSpeed(), l.getDirection(), l.getStandardDeviation()));
		}
		return out;
	}

	/**
	 * Power-law shear profile v(h) = v_ground (h / h_ref)^alpha above h_ref (10 m, the height ground winds are measured
	 * at), constant below; alpha ~ 1/7 over open terrain. Direction constant.
	 */
	public static List<Level> powerLaw(double groundSpeed, double direction, double alpha, double top, double turbulence) {
		double[] hs = { 0, 10, 50, 100, 200, 350, 500, 750, 1000, 1500, 2000, 3000, 4000, 6000, 8000, 10000, 12000 };
		List<Level> out = new ArrayList<>();
		for (double h : hs) {
			if (h > top * 1.001 && !out.isEmpty() && out.get(out.size() - 1).altitude() >= top) {
				break;
			}
			double v = groundSpeed * Math.pow(Math.max(h, 10) / 10, alpha);
			out.add(new Level(h, v, direction, Double.isNaN(turbulence) ? Double.NaN : turbulence * v));
		}
		return out;
	}

	/**
	 * Scales / rotates the profile so its lowest level has {@code speed} and {@code direction} (NaN = unchanged). Used when
	 * a tool overrides "the wind speed" (sweeps, the design-wind case, Monte Carlo) on a multi-level simulation, so the
	 * shape of the profile is kept.
	 */
	public static void setGround(SimulationOptions o, double speed, double direction) {
		WindModelType type = o.getWindModelType();
		// OpenRocket's average-wind setters also select the average model; restore the model type afterwards.
		if (!Double.isNaN(speed)) {
			o.setWindSpeedAverage(speed);
		}
		if (!Double.isNaN(direction)) {
			o.setWindDirection(norm(direction));
		}
		o.setWindModelType(type);
		if (type != WindModelType.MULTI_LEVEL) {
			return;
		}
		List<MultiLevelPinkNoiseWindModel.LevelWindModel> ls = o.getMultiLevelWindModel().getLevels();
		if (ls.isEmpty()) {
			return;
		}
		MultiLevelPinkNoiseWindModel.LevelWindModel ground = ls.get(0);
		for (MultiLevelPinkNoiseWindModel.LevelWindModel l : ls) {
			if (l.getAltitude() < ground.getAltitude()) {
				ground = l;
			}
		}
		double g = ground.getSpeed();
		double dRot = Double.isNaN(direction) ? 0 : direction - ground.getDirection();
		for (MultiLevelPinkNoiseWindModel.LevelWindModel l : ls) {
			if (!Double.isNaN(speed)) {
				double sdRatio = l.getSpeed() > 0 ? l.getStandardDeviation() / l.getSpeed() : 0;
				double v = g > 1e-9 ? l.getSpeed() * speed / g : speed;
				l.setSpeed(v);
				if (sdRatio > 0) {
					l.setStandardDeviation(sdRatio * v);
				}
			}
			if (dRot != 0) {
				l.setDirection(norm(l.getDirection() + dRot));
			}
		}
	}

	/**
	 * Seeds every level's turbulence from {@code seed} (OpenRocket seeds each level from {@code new Random()}), so runs
	 * with a wind profile are repeatable. See {@link Variants#seed}.
	 */
	static void seedLevels(SimulationOptions o, int seed) {
		try {
			Field f = MultiLevelPinkNoiseWindModel.LevelWindModel.class.getDeclaredField("model");
			f.setAccessible(true);
			int i = 0;
			for (MultiLevelPinkNoiseWindModel.LevelWindModel l : o.getMultiLevelWindModel().getLevels()) {
				PinkNoiseWindModel old = (PinkNoiseWindModel) f.get(l);
				PinkNoiseWindModel seeded = new PinkNoiseWindModel(seed * 31 + (++i));
				seeded.loadFrom(old);
				f.set(l, seeded);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			// Different OpenRocket internals: turbulence is then not repeatable, results stay valid.
		}
	}

	public static Map<String, Object> describe(SimulationOptions o) {
		Map<String, Object> m = new LinkedHashMap<>();
		if (!isMultiLevel(o)) {
			m.put("model", "average (one speed and direction at all altitudes)");
			m.put("speed", Units.fmt(speed(o), Dim.VELOCITY));
			m.put("direction", Units.fmt(direction(o), Dim.ANGLE) + " (from)");
			m.put("turbulenceIntensity", Units.num(turbulence(o)));
			return m;
		}
		m.put("model", "multi-level (interpolated between levels)");
		m.put("altitudeReference", o.getMultiLevelWindModel().getAltitudeReference().name());
		List<String> rows = new ArrayList<>();
		for (Level l : levels(o)) {
			rows.add(Units.fmt(l.altitude(), Dim.DISTANCE) + ": " + Units.fmt(l.speed(), Dim.VELOCITY) + " from "
					+ Units.num(Math.toDegrees(l.direction())) + " deg, sd " + Units.fmt(l.sd(), Dim.VELOCITY));
		}
		m.put("levels", rows);
		return m;
	}

	static double norm(double a) {
		double t = 2 * Math.PI;
		return ((a % t) + t) % t;
	}
}
