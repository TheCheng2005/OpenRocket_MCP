package io.github.openrocketmcp;

import java.util.List;

import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.McpServer.Prompt;
import io.github.openrocketmcp.mcp.McpServer.PromptArg;
import io.github.openrocketmcp.mcp.McpServer.Resource;
import io.github.openrocketmcp.tools.Context;

/** Guided workflows (MCP prompts) and reference resources. */
final class Prompts {
	private Prompts() {
	}

	static final String METHODS = """
			# Methods used by openrocket-mcp

			## Flight
			OpenRocket 24.12 six-degree-of-freedom simulation (Barrowman aerodynamics). Stability margin = (CP - CG) / max body
			diameter. Launch Canada requires RASAero CP/CD overrides when the airframe diameter changes (DTEG R10.3.1).

			## Atmosphere
			International Standard Atmosphere to 20 km: T = 288.15 - 0.0065 h, p = 101325 (T/288.15)^5.2559, rho = p / (287.05 T).

			## Descent
			v = sqrt(2 m g / (rho Cd A)). Cd and A must share a reference area (nominal for OpenRocket and most vendors;
			projected for some vendor Cd values such as 2.2).

			## Opening load (Knacke, Parachute Recovery Systems Design Manual, NWC TP 6575, DTIC ADA247666)
			- Infinite mass: F = Cx * 0.5 rho v^2 * Cd A. Cx from Knacke table 5-1 (team default 1.4).
			- Finite mass: integrate the vehicle's 2D point-mass motion while CdA(t) = CdA (t/t_f)^j, t_f = n D0 / v0.
			  n (canopy fill constant) and j come from team standards; calibrate from test data.
			- Mass ratio Rm = rho CdA^1.5 / m indicates which regime applies (small: infinite mass; large: finite mass).
			- v is the airspeed at deployment from the simulation (vertical + horizontal + wind), not only the vertical speed.

			## Shear pins
			n = ceil(F_design * SF / F_pin), at least 3 evenly spaced (DTEG 8.8.3).

			## Black powder
			Ideal gas: m = P V / (R T), R = 266 in lbf / (lbm R), T = 3307 R, i.e. grams = P[psi] V[in^3] / 879,662 * 453.6
			(~0.006 D^2 L grams at 15 psi). P = (pins * F_pin + extra) * SF / bulkhead area. Backup = primary * factor.
			Charge well volume <= 2 x charge volume (DTEG 8.8.4). Always ground test.

			## Bay volume
			Packed volume = vendor packing volume + cord width x thickness x length, times a packing factor. Record measured
			packing factors from test fits in the team standards.

			## Fin flutter (screen)
			NACA TN 4197 (Martin 1958): Vf = a sqrt(G / (K AR^3 (lambda+1) P / (2 (AR+2) (t/c)^3))), K = 39.3/14.696 = 2.674
			with G and P in the same units; AR = span^2 / area, lambda = tip/root, t/c = thickness / root chord. Apogee Peak of
			Flight #291/#411 printed K = 1.337, which overestimates Vf by sqrt 2 (corrected in #615). Evaluated at every point of
			the simulated flight with the local pressure and speed of sound. Solid isotropic plate only; composite layups need an
			effective G. Required margin (flutter speed / airspeed) is a team standard (structures.flutterMinMargin).

			## Ballast
			Static first guess: m = M (x_cg - x_t) / (x_t - x_b), x_t = x_cp - target * d, shifted by the difference between
			the static margin and the simulated minimum; then secant iterations on the simulated minimum ascent stability.
			A section whose mass (and CG) is overridden for its subcomponents gets the ballast added to the override.

			## Winds aloft
			OpenRocket multi-level wind model: speed, direction and turbulence (sd) per altitude, interpolated between levels.
			Power-law profile: v(h) = v_ground (h / 10 m)^alpha above 10 m (alpha ~ 1/7 open terrain). Overriding the wind
			speed / direction on a profile scales / rotates every level from the lowest one.

			## Aerodynamics
			OpenRocket Barrowman model at zero angle of attack, Reynolds number from sea-level ISA at each Mach; CD = friction +
			pressure + base. Margin = (CP - CG) / max body diameter.

			## Monte Carlo
			Randomized: wind speed/direction, launch angle/direction, turbulence seed; optionally structure mass (each
			component scaled, motors excluded), airframe drag (simulation listener scaling CD), motor thrust (listener scaling
			thrust, same burn time) and each parachute's Cd. Drivers = Pearson correlation of each input with the outputs.
			""";

	static void register(McpServer s, Context ctx) {
		s.resource(new Resource("openrocket://standards", "Team standards", "Active team standards (JSON).",
				"application/json", () -> ctx.standards().json()));
		s.resource(new Resource("openrocket://rules", "Competition rule set", "Active rule-set thresholds with references (JSON).",
				"application/json", () -> ctx.standards().rulesJson()));
		s.resource(new Resource("openrocket://methods", "Calculation methods", "Equations, constants and sources used by the tools.",
				"text/markdown", () -> METHODS));

		s.prompt(new Prompt("recovery_review", "Walk through the full recovery chain for a design and write it up.",
				List.of(new PromptArg("design", "Path to the .ork file (or an open designId).", true),
						new PromptArg("pinType", "Shear pin type from the team standards, e.g. \"4-40 nylon\".", false)),
				args -> """
						Review the recovery system of %s.
						1. open_design (unless it is already open), then get_design and get_standards.
						2. check_requirements to see the rule status of descent rates and deployment altitudes.
						3. recovery_analysis%s to get deployment airspeed, opening loads and pins for every device in every stage.
						4. For the drogue (or first event), run deployment_delay_sweep with delays 0-4 s to find the latest safe deployment.
						5. For each bay: ejection_charge (pins to break) and recovery_bay_fit (parachute + cord volumes).
						6. If a descent rate is out of range, size_parachute and propose a real parachute from the parts database.
						Write the result like a team test report: inputs, equations, values, results, and which numbers are
						simulated vs. calculated vs. assumed. List open risks and required ground tests.
						""".formatted(args.get("design"), args.getOrDefault("pinType", "").isBlank() ? ""
						: " with pinType \"" + args.get("pinType") + "\"")));

		s.prompt(new Prompt("design_review", "Launch Canada style design review of a vehicle.",
				List.of(new PromptArg("design", "Path to the .ork file (or an open designId).", true)),
				args -> """
						Prepare a design review for %s against the active rule set (get_standards).
						- get_design: configuration, masses, CG/CP and stability per stage stack.
						- check_requirements (includes the maximum-wind case). Explain every FAIL/WARN and propose fixes.
						- get_flight_data for stability vs time: one series up to rail departure and one for the full ascent (DTEG R10.3.2).
						- For staged vehicles: sustainer stability after separation, tilt and altitude at ignition, inhibit altitude.
						- recovery_analysis for every stage.
						- fin_flutter for every fin set (margin along the flight); ballast if the stability floor is not met.
						- monte_carlo with vehicle uncertainty (massSd, dragSd, thrustSd, chuteCdSd) for the landing area and drivers.
						Summarize as a table of requirement, value, status and reference, then the action list.
						""".formatted(args.get("design"))));

		s.prompt(new Prompt("motor_selection", "Choose a motor for a goal.",
				List.of(new PromptArg("design", "Path to the .ork file (or an open designId).", true),
						new PromptArg("goal", "e.g. \"10,000 ft apogee\" or \"max altitude on an L2 motor\".", true),
						new PromptArg("certLevel", "L1, L2 or L3.", false)),
				args -> """
						Find a motor for %s with the goal: %s%s.
						Open the design, identify the motor mount with get_design, then rank_motors with the right objective and
						filters. For the top candidates, set_motor on a new flight configuration and run check_requirements (rail exit,
						thrust-to-weight, stability, Mach). Recommend one, with the ejection delay (optimum delay) and trade-offs.
						""".formatted(args.get("design"), args.get("goal"),
						args.getOrDefault("certLevel", "").isBlank() ? "" : " (certification " + args.get("certLevel") + ")")));
	}
}
