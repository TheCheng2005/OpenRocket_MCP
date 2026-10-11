package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.List;

import info.openrocket.core.document.Simulation;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import io.github.openrocketmcp.calc.Atmosphere;
import io.github.openrocketmcp.calc.Parachutes;
import io.github.openrocketmcp.mcp.ToolException;

/**
 * Steady descent rate against canopy diameter, for one or more drag coefficients:
 * v = sqrt(2 m g / (rho (n Cd A + CdA_other))). From a design, the mass is the simulated mass under the device and
 * CdA_other is calibrated so the curve passes through the simulated rate of the device as it is now: the airframe's
 * own drag and any parachute still attached (a drogue under the main) are in it, and the chart agrees with
 * check_requirements.
 */
public final class ParachuteSizes {
	private ParachuteSizes() {
	}

	/**
	 * What the canopy has to hold up. {@code density} is where the rate is read (check_requirements reads it over the
	 * steady part of the descent); {@code otherCdA} the drag that is there without the canopy.
	 */
	public record Setup(double mass, double density, double altitudeAgl, double otherCdA, int count, String massSource,
			RecoveryDevice device, String role, double simulatedRate) {
		/** Rate under {@code count} canopies of this diameter and Cd. */
		public double rate(double cd, double diameter) {
			return Parachutes.descentRate(mass, count * cd * Parachutes.circleArea(diameter) + otherCdA, density);
		}

		/** Diameter for {@code v}; 0 when the other drag alone is already slow enough. */
		public double diameterFor(double cd, double v) {
			double need = Parachutes.requiredCdA(mass, v, density) - otherCdA;
			return need <= 0 ? 0 : Parachutes.diameterFor(need / count, cd);
		}
	}

	/** Setup for a mass the caller gives (no design): no other drag unless given. */
	public static Setup given(double mass, double density, double altitudeAgl, double otherCdA, int count) {
		return new Setup(mass, density, altitudeAgl, Math.max(0, otherCdA), count, "given", null, null, Double.NaN);
	}

	/**
	 * Setup from a simulated flight for {@code device} (null = the last device to open on the main branch, the main).
	 */
	public static Setup fromFlight(Simulation sim, String device, int count) {
		List<Sims.Deployment> deps = Sims.deployments(sim);
		if (deps.isEmpty()) {
			throw new ToolException("No parachute opened in the simulation: set_deployment first, or give mass instead.");
		}
		Sims.Deployment dep = null;
		if (device == null) {
			String main = deps.get(0).branch();
			for (Sims.Deployment d : deps) {
				if (d.branch().equals(main)) {
					dep = d; // the last one on the main branch
				}
			}
		} else {
			for (Sims.Deployment d : deps) {
				if (Components.shortId(d.device()).equalsIgnoreCase(device) || d.device().getName().equalsIgnoreCase(device)
						|| d.device().getID().toString().startsWith(device)) {
					dep = d;
				}
			}
			if (dep == null) {
				throw new ToolException("'" + device + "' did not open in the simulation. Devices that opened: "
						+ deps.stream().map(d -> d.device().getName()).distinct().toList() + ".");
			}
		}
		List<Sims.Deployment> branch = new ArrayList<>();
		for (Sims.Deployment d : deps) {
			if (d.branch().equals(dep.branch())) {
				branch.add(d);
			}
		}
		String role = Recovery.role(branch, branch.indexOf(dep));
		// Read the rate where check_requirements reads it: the middle of the steady part of this device's descent.
		double tMid = dep.time() + 0.775 * (dep.descentWindowEnd() - dep.time());
		FlightDataBranch b = branchNamed(sim, dep.branch());
		double hAgl = Sims.at(b, FlightDataType.TYPE_ALTITUDE, tMid);
		double rho = Sims.at(b, FlightDataType.TYPE_AIR_DENSITY, tMid);
		if (Double.isNaN(rho)) {
			rho = Atmosphere.at(hAgl + sim.getOptions().getLaunchAltitude(), 0).density();
		}
		double v = dep.steadyDescentRate();
		// The live part, not the simulated copy: the user edits this one.
		RecoveryDevice live = (RecoveryDevice) Components.find(sim.getRocket(), dep.device().getID().toString());
		double own = count * live.getCD() * live.getArea();
		double other = Double.isNaN(v) || v <= 0 ? 0 : Math.max(0, Parachutes.requiredCdA(dep.mass(), v, rho) - own);
		return new Setup(dep.mass(), rho, hAgl, other, count, "simulated mass under " + live.getName() + " (" + dep.branch() + ")",
				live, role, v);
	}

	private static FlightDataBranch branchNamed(Simulation sim, String name) {
		for (FlightDataBranch b : sim.getSimulatedData().getBranches()) {
			if (b.getName().equals(name)) {
				return b;
			}
		}
		return sim.getSimulatedData().getBranch(0);
	}

	/** Canopy diameter of a device: a parachute's diameter, else the diameter of a circle of the same area. */
	public static double diameterOf(RecoveryDevice d) {
		return d instanceof Parachute p ? p.getDiameter() : Math.sqrt(4 * d.getArea() / Math.PI);
	}

	/** Diameters from {@code lo} to {@code hi}, evenly spaced. */
	public static double[] range(double lo, double hi, int n) {
		double[] out = new double[n];
		for (int i = 0; i < n; i++) {
			out[i] = lo + (hi - lo) * i / (n - 1);
		}
		return out;
	}
}
