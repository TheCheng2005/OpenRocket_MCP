package io.github.openrocketmcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.openrocketmcp.calc.Advanced;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.standards.Standards;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/** Hybrid / liquid / tribrid program checks from the Launch Canada 2027 edicts. */
public final class AdvancedTools {
	private AdvancedTools() {
	}

	static double rule(Standards std, String key, double fallback) {
		double v = std.rule(key, Dim.DIMENSIONLESS);
		return Double.isNaN(v) ? fallback : v;
	}

	public static void register(McpServer s, Context ctx) {
		s.tool(new ToolDef("pressure_vessel", "Pressure vessel margins (LC 2027)",
				"Check a tank, COPV or combustion chamber against the LC 2027 edicts: MEOP = relief valve set pressure; proof "
						+ ">= 1.5 x MEOP; metal burst >= 2 x MEOP x weld knockdown (>= 1.2, fully penetrated welds only); COPVs and "
						+ "combustion chambers burst >= 4 x MEOP. Give burstPressure, or outerDiameter + wallThickness + "
						+ "ultimateStrength (minimum literature value for the alloy and temper) for a thin-wall Barlow estimate.",
				Schema.object()
						.qty("meop", "Maximum expected operating pressure = relief valve set pressure, e.g. \"800 psi\".", true)
						.qty("burstPressure", "Design burst pressure at the weakest point.", false)
						.qty("outerDiameter", "Cylinder outer diameter (Barlow estimate).", false)
						.qty("wallThickness", "Wall thickness (Barlow estimate).", false)
						.qty("ultimateStrength", "Minimum ultimate tensile strength of the material, e.g. \"290 MPa\" (6061-T6).", false)
						.bool("copv", "Composite-overwrapped vessel or combustion chamber (4 x MEOP rule).", false)
						.bool("welded", "Fully penetrated welds on the pressure boundary.", false)
						.num("weldKnockdown", "Weld knockdown factor (>= 1.2; justify type, material, joint, quality, source).", false)
						.qty("proofPressure", "Planned proof (hydrostatic) test pressure.", false).build(),
				true, a -> {
					Standards std = ctx.standards();
					double meop = a.qty("meop", Dim.PRESSURE);
					boolean copv = a.bool("copv", false);
					boolean welded = a.bool("welded", false);
					double minKd = rule(std, "pressureVessels.minWeldKnockdown", 1.2);
					double kd = welded ? Math.max(minKd, a.num("weldKnockdown", minKd)) : 1.0;
					double burst;
					String basis;
					if (a.has("burstPressure")) {
						burst = a.qty("burstPressure", Dim.PRESSURE);
						basis = "given";
					} else if (a.has("outerDiameter") && a.has("wallThickness") && a.has("ultimateStrength")) {
						burst = Advanced.barlowBurst(a.qty("ultimateStrength", Dim.PRESSURE), a.qty("wallThickness", Dim.LENGTH),
								a.qty("outerDiameter", Dim.LENGTH));
						basis = "Barlow P = 2 S t / OD (thin wall; check heads, ports and welds separately)";
					} else {
						throw new ToolException("Give burstPressure, or outerDiameter, wallThickness and ultimateStrength.");
					}
					double required = Advanced.requiredBurst(meop, copv, kd);
					double proofMin = rule(std, "pressureVessels.proofFactor", 1.5) * meop;
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("meop", Units.fmt(meop, Dim.PRESSURE));
					out.put("burstPressure", Units.fmt(burst, Dim.PRESSURE) + " (" + basis + ")");
					out.put("requiredBurst", Units.fmt(required, Dim.PRESSURE) + (copv ? " (4 x MEOP)" : " (2 x MEOP" + (welded ? " x knockdown " + Units.num(kd) : "") + ")"));
					out.put("burstMargin", Units.num(burst / meop) + " x MEOP");
					out.put("burstCheck", burst >= required * (1 - 1e-9) ? "PASS" : "FAIL");
					out.put("maxAllowedMeop", Units.fmt(Advanced.maxMeop(burst, copv, kd), Dim.PRESSURE));
					out.put("minimumProofPressure", Units.fmt(proofMin, Dim.PRESSURE) + " (1.5 x MEOP)");
					if (a.has("proofPressure")) {
						double proof = a.qty("proofPressure", Dim.PRESSURE);
						out.put("proofCheck", proof >= proofMin * (1 - 1e-9) ? "PASS" : "FAIL: proof below 1.5 x MEOP");
						if (proof >= burst * 0.9) {
							out.put("proofWarning", "proof pressure is within 10% of burst");
						}
					}
					List<String> notes = new ArrayList<>();
					notes.add("Use the minimum literature strength for the alloy and temper at the weakest point (edict).");
					if (welded) {
						notes.add("Report the weld knockdown factor with weld type, material, joint type, weld quality and source.");
					}
					if (copv) {
						notes.add("COPVs: batch test showing failure above 4 x MEOP, or a 1.5 x MEOP proof test with calculated burst > 4 x MEOP. "
								+ "COTS tanks need no proof test if operated below 1/4 of burst.");
					}
					notes.add("Below 4 x MEOP, pressurize and depressurize remotely only (DTEG R2.2.32).");
					out.put("notes", notes);
					return out;
				}));

		s.tool(new ToolDef("advanced_probation", "Advanced probation level and AASI (LC 2027)",
				"Probation level for hybrid / liquid / tribrid entries from the total volume of gaseous and liquid pressurants and "
						+ "propellants (GLPP: ullage, pressurant, oxidizer, fuel; not solid fuel), the static-fire Isp needed to come to "
						+ "competition, and the Altitude Adjusted Specific Impulse AASI = min(1, actual/target altitude) x static-fire "
						+ "Isp with whether it clears the level.",
				Schema.object()
						.qty("glppVolume", "Total GLPP volume, e.g. \"6 L\".", true)
						.num("staticFireIsp", "Static-fire specific impulse (s).", false)
						.qty("totalImpulse", "Static-fire total impulse (with propellantMass, to compute Isp).", false)
						.qty("propellantMass", "Propellant mass consumed in that static fire.", false)
						.qty("targetAltitude", "Target altitude of the flight.", false)
						.qty("actualAltitude", "Altitude actually reached.", false)
						.qty("predictedAltitude", "Predicted altitude (overshoot check).", false)
						.bool("stagedOrCluster", "Two-stage or clustered vehicle (impulse caps apply).", false)
						.qty("vehicleTotalImpulse", "Total impulse of all motors (two-stage / cluster cap).", false).build(),
				true, a -> {
					Standards std = ctx.standards();
					JsonArray levels = std.rules().has("probationLevels") ? std.rules().getAsJsonArray("probationLevels") : null;
					if (levels == null) {
						throw new ToolException("The active rule set has no probation levels (use launch-canada-2027).");
					}
					double v = a.qty("glppVolume", Dim.VOLUME);
					JsonObject level = null;
					for (JsonElement e : levels) {
						JsonObject l = e.getAsJsonObject();
						double lo = Units.toSi(l.get("glppMin").getAsString(), Dim.VOLUME);
						double hi = l.has("glppMax") ? Units.toSi(l.get("glppMax").getAsString(), Dim.VOLUME) : Double.MAX_VALUE;
						if (v <= hi + 1e-12 && (v >= lo - 1e-12 || level == null)) {
							level = l;
							break;
						}
					}
					if (level == null) {
						level = levels.get(levels.size() - 1).getAsJsonObject();
					}
					double isp = a.num("staticFireIsp", Double.NaN);
					if (Double.isNaN(isp) && a.has("totalImpulse") && a.has("propellantMass")) {
						isp = Advanced.isp(a.qty("totalImpulse", Dim.IMPULSE), a.qty("propellantMass", Dim.MASS));
					}
					double minIsp = Units.toSi(level.get("minStaticFireIsp").getAsString(), Dim.TIME);
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("glppVolume", Units.fmt(v, Dim.VOLUME) + " (" + Units.num(v * 1000) + " L)");
					out.put("probationLevel", level.get("level").getAsString() + " (" + level.get("glppMin").getAsString()
							+ (level.has("glppMax") ? " - " + level.get("glppMax").getAsString() : "+") + " GLPP)");
					if (v < Units.toSi("2 L", Dim.VOLUME)) {
						out.put("note", "Below 2 L GLPP; the edicts' levels start at 2 L (2 L is the recommended first entry).");
					}
					out.put("minStaticFireIspToCompete", Units.num(minIsp) + " s");
					if (!Double.isNaN(isp)) {
						out.put("staticFireIsp", Units.num(isp) + " s -> " + (isp >= minIsp ? "meets" : "BELOW") + " the requirement to come to competition");
					}
					if (level.has("stagedOrClusterMaxImpulse") && a.bool("stagedOrCluster", false)) {
						double cap = Units.toSi(level.get("stagedOrClusterMaxImpulse").getAsString(), Dim.IMPULSE);
						out.put("stagedOrClusterImpulseCap", Units.fmt(cap, Dim.IMPULSE));
						if (a.has("vehicleTotalImpulse")) {
							double ti = a.qty("vehicleTotalImpulse", Dim.IMPULSE);
							out.put("vehicleTotalImpulse", Units.fmt(ti, Dim.IMPULSE) + (ti <= cap ? " (within cap)" : " (EXCEEDS cap)"));
						}
					}
					if (a.has("targetAltitude") && a.has("actualAltitude") && !Double.isNaN(isp)) {
						double tgt = a.qty("targetAltitude", Dim.DISTANCE), act = a.qty("actualAltitude", Dim.DISTANCE);
						double aasi = Advanced.aasi(act, tgt, isp);
						out.put("aasi", Units.num(aasi) + " s = min(1, " + Units.num(act / tgt) + ") x " + Units.num(isp) + " s");
						if (level.has("aasiToAdvance")) {
							double need = Units.toSi(level.get("aasiToAdvance").getAsString(), Dim.TIME);
							boolean over50 = act > 0.5 * tgt;
							boolean overshoot = a.has("predictedAltitude") && act > 1.25 * a.qty("predictedAltitude", Dim.DISTANCE);
							out.put("clearsLevel", aasi > need && over50 && !overshoot ? "yes (with a successful recovery)"
									: "no: needs AASI > " + Units.num(need) + " s, altitude > 50% of target"
											+ (a.has("predictedAltitude") ? " and <= 25% over prediction" : "") + ", and recovery");
						}
					}
					out.put("ref", "LC 2027 Edicts: Advanced Probationary Levels");
					return out;
				}));
	}
}
