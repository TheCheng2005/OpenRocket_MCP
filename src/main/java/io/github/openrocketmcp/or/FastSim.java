package io.github.openrocketmcp.or;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.aerodynamics.BarrowmanCalculator;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.logging.Warning;
import info.openrocket.core.logging.WarningSet;
import info.openrocket.core.masscalc.RigidBody;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.simulation.BasicEventSimulationEngine;
import info.openrocket.core.simulation.BasicLandingStepper;
import info.openrocket.core.simulation.BasicTumbleStepper;
import info.openrocket.core.simulation.MotorClusterState;
import info.openrocket.core.simulation.RK4SimulationStepper;
import info.openrocket.core.simulation.SimulationConditions;
import info.openrocket.core.simulation.SimulationStatus;
import info.openrocket.core.simulation.exception.SimulationException;
import info.openrocket.core.simulation.listeners.AbstractSimulationListener;
import info.openrocket.core.simulation.listeners.SimulationListener;
import io.github.openrocketmcp.mcp.Log;

/**
 * OpenRocket's simulation engine with three per-step recomputations cached. At every integration stage (four times
 * per RK4 step) OpenRocket recomputes the airframe's mass, CG and inertia from every component, the motors' mass from
 * the motor mounts, and re-checks the geometry for diameter steps and open airframes. The airframe and geometry only
 * change when a stage separates, the motors only while they burn. On a rocket with many internal parts that was
 * 60-90 % of the simulation time.
 * <p>
 * Both caches are keyed on the flight configuration object, its modification id, the rocket's modification id and
 * the set of active stages, so stage separation, edits and Monte Carlo mass scaling all recompute them; cached
 * geometry warnings are replayed so the warning list is unchanged. A simulation with a listener that hooks the mass
 * calculation (an OpenRocket extension stored in the design, say) computes the mass every step, so results are
 * identical either way (FastSimTest flies every bundled design both ways). If a future OpenRocket changes the
 * engine's internals, {@link #install} quietly leaves the standard engine in place.
 */
public final class FastSim {
	private static final Field ENGINE_CLASS, FLIGHT, LANDING, TUMBLE;
	private static final boolean ENABLED;

	static {
		Field ec = null, f = null, l = null, t = null;
		boolean ok = !Boolean.getBoolean("openrocketmcp.noFastSim");
		if (ok) {
			try {
				ec = Simulation.class.getDeclaredField("simulationEngineClass");
				f = BasicEventSimulationEngine.class.getDeclaredField("flightStepper");
				l = BasicEventSimulationEngine.class.getDeclaredField("landingStepper");
				t = BasicEventSimulationEngine.class.getDeclaredField("tumbleStepper");
				for (Field x : new Field[] { ec, f, l, t }) {
					x.setAccessible(true);
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				Log.info("Fast simulation disabled (" + e + "); using OpenRocket's standard engine.");
				ok = false;
			}
		}
		ENGINE_CLASS = ec;
		FLIGHT = f;
		LANDING = l;
		TUMBLE = t;
		ENABLED = ok;
	}

	private FastSim() {
	}

	/** Makes {@code sim} fly on the caching engine (once per simulation object; harmless to repeat). */
	static void install(Simulation sim) {
		if (!ENABLED) {
			return;
		}
		try {
			if (ENGINE_CLASS.get(sim) != Engine.class) {
				ENGINE_CLASS.set(sim, Engine.class);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			Log.info("Fast simulation not installed: " + e);
		}
	}

	/** The engine OpenRocket instantiates by reflection; swaps in the caching steppers and aerodynamics. */
	public static final class Engine extends BasicEventSimulationEngine {
		public Engine() {
			try {
				FLIGHT.set(this, new Rk4());
				LANDING.set(this, new Landing());
				TUMBLE.set(this, new Tumble());
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}

		@Override
		public void simulate(SimulationConditions c) throws SimulationException {
			if (c.getAerodynamicCalculator() != null && c.getAerodynamicCalculator().getClass() == BarrowmanCalculator.class) {
				c.setAerodynamicCalculator(new Aero());
			}
			super.simulate(c);
		}
	}

	/** Barrowman aerodynamics with the geometry check done once per configuration and its warnings replayed. */
	static final class Aero extends BarrowmanCalculator {
		private FlightConfiguration config;
		private Object configMod, rocketMod;
		private long stages;
		private final Map<RocketComponent, List<Warning>> checked = new IdentityHashMap<>();

		@Override
		public Aero newInstance() {
			return new Aero();
		}

		@Override
		public void checkGeometry(FlightConfiguration fc, RocketComponent root, WarningSet warnings) {
			if (fc != config || fc.getModID() != configMod || fc.getRocket().getModID() != rocketMod
					|| stages(fc) != stages) {
				checked.clear();
				config = fc;
				configMod = fc.getModID();
				rocketMod = fc.getRocket().getModID();
				stages = stages(fc);
			}
			List<Warning> found = checked.get(root);
			if (found == null) {
				WarningSet tmp = new WarningSet();
				super.checkGeometry(fc, root, tmp); // recurses into this method for sub-stages and pods
				found = new ArrayList<>(tmp);
				checked.put(root, found);
			}
			for (Warning w : found) {
				warnings.add(w);
			}
		}
	}

	/**
	 * One cached mass per stepper (each engine run has its own steppers, so nothing is shared between runs). With
	 * {@code motors} set it caches the motor mass: valid while every active motor's thrust-curve time is unchanged or
	 * past its last data point (burnt out) or zero (not ignited), the only states in which OpenRocket's interpolation
	 * gives the same mass and CG. While a motor burns it is recomputed every step.
	 */
	static final class Cache {
		private final boolean motors;
		private FlightConfiguration config;
		private Object configMod, rocketMod;
		private long stages;
		private Object[] motorStates = new Object[0];
		private double[] motorTimes = new double[0];
		private RigidBody body;
		private List<SimulationListener> checkedFor;
		private boolean usable;
		private final Map<Motor, Double> lastPoint = new IdentityHashMap<>();

		Cache(boolean motors) {
			this.motors = motors;
		}

		RigidBody get(SimulationStatus s) {
			if (body == null || !usable(s)) {
				return null;
			}
			FlightConfiguration fc = s.getConfiguration();
			if (fc != config || fc.getModID() != configMod || fc.getRocket().getModID() != rocketMod || stages != stages(fc)) {
				return null;
			}
			if (motors) {
				Collection<MotorClusterState> active = s.getActiveMotors();
				if (active.size() != motorStates.length) {
					return null;
				}
				int i = 0;
				for (MotorClusterState m : active) {
					double t = motorTime(m, s.getSimulationTime());
					if (m != motorStates[i] || Double.isNaN(t) || Double.compare(t, motorTimes[i]) != 0) {
						return null;
					}
					i++;
				}
			}
			return body;
		}

		void put(SimulationStatus s, RigidBody b) {
			if (!usable(s)) {
				return;
			}
			FlightConfiguration fc = s.getConfiguration();
			if (motors) {
				Collection<MotorClusterState> active = s.getActiveMotors();
				Object[] st = new Object[active.size()];
				double[] ts = new double[st.length];
				int i = 0;
				for (MotorClusterState m : active) {
					st[i] = m;
					ts[i] = motorTime(m, s.getSimulationTime());
					if (Double.isNaN(ts[i])) {
						body = null; // not a thrust-curve motor: never cached
						return;
					}
					i++;
				}
				motorStates = st;
				motorTimes = ts;
			}
			config = fc;
			configMod = fc.getModID();
			rocketMod = fc.getRocket().getModID();
			stages = stages(fc);
			body = b;
		}

		/** Motor time as far as its mass is concerned: +inf once past the last thrust-curve point; NaN if unknown. */
		private double motorTime(MotorClusterState m, double simTime) {
			if (!(m.getMotor() instanceof ThrustCurveMotor tc)) {
				return Double.NaN;
			}
			double last = lastPoint.computeIfAbsent(tc, k -> {
				double[] tp = tc.getTimePoints();
				return tp == null || tp.length == 0 ? Double.NaN : tp[tp.length - 1];
			});
			double t = m.getMotorTime(simTime);
			return Double.isNaN(last) ? Double.NaN : t >= last ? Double.POSITIVE_INFINITY : t;
		}

		private boolean usable(SimulationStatus s) {
			List<SimulationListener> ls = s.getSimulationConditions().getSimulationListenerList();
			if (ls != checkedFor) {
				checkedFor = ls;
				usable = ls.stream().noneMatch(FastSim::hooksMass);
			}
			return usable;
		}
	}

	static long stages(FlightConfiguration fc) {
		long m = 0;
		for (AxialStage st : fc.getActiveStages()) {
			m |= 1L << Math.min(63, st.getStageNumber());
		}
		return m;
	}

	/** True if the listener overrides OpenRocket's mass hooks, so the mass must be asked for at every step. */
	static boolean hooksMass(SimulationListener l) {
		try {
			Method pre = l.getClass().getMethod("preMassCalculation", SimulationStatus.class);
			Method post = l.getClass().getMethod("postMassCalculation", SimulationStatus.class, RigidBody.class);
			return pre.getDeclaringClass() != AbstractSimulationListener.class
					|| post.getDeclaringClass() != AbstractSimulationListener.class;
		} catch (NoSuchMethodException e) {
			return false; // not a computation listener: it cannot touch the mass
		}
	}

	static final class Rk4 extends RK4SimulationStepper {
		private final Cache structure = new Cache(false), motor = new Cache(true);

		@Override
		protected RigidBody calculateStructureMass(SimulationStatus s) throws SimulationException {
			RigidBody b = structure.get(s);
			if (b == null) {
				b = super.calculateStructureMass(s);
				structure.put(s, b);
			}
			return b;
		}

		@Override
		protected RigidBody calculateMotorMass(SimulationStatus s) throws SimulationException {
			RigidBody b = motor.get(s);
			if (b == null) {
				b = super.calculateMotorMass(s);
				motor.put(s, b);
			}
			return b;
		}
	}

	static final class Landing extends BasicLandingStepper {
		private final Cache structure = new Cache(false), motor = new Cache(true);

		@Override
		protected RigidBody calculateStructureMass(SimulationStatus s) throws SimulationException {
			RigidBody b = structure.get(s);
			if (b == null) {
				b = super.calculateStructureMass(s);
				structure.put(s, b);
			}
			return b;
		}

		@Override
		protected RigidBody calculateMotorMass(SimulationStatus s) throws SimulationException {
			RigidBody b = motor.get(s);
			if (b == null) {
				b = super.calculateMotorMass(s);
				motor.put(s, b);
			}
			return b;
		}
	}

	static final class Tumble extends BasicTumbleStepper {
		private final Cache structure = new Cache(false), motor = new Cache(true);

		@Override
		protected RigidBody calculateStructureMass(SimulationStatus s) throws SimulationException {
			RigidBody b = structure.get(s);
			if (b == null) {
				b = super.calculateStructureMass(s);
				structure.put(s, b);
			}
			return b;
		}

		@Override
		protected RigidBody calculateMotorMass(SimulationStatus s) throws SimulationException {
			RigidBody b = motor.get(s);
			if (b == null) {
				b = super.calculateMotorMass(s);
				motor.put(s, b);
			}
			return b;
		}
	}
}
