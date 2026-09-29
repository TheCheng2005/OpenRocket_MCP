package io.github.openrocketmcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

import info.openrocket.core.rocketcomponent.FlightConfiguration;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Geometry;
import io.github.openrocketmcp.or.MassBudget;
import io.github.openrocketmcp.units.Dim;

/** Mass budget and weigh-in tracking. */
public final class MassTools {
	private MassTools() {
	}

	public static void register(McpServer s, Context ctx) {
		JsonObject item = Schema.object()
				.str("part", "Part name as the team calls it, or the component's name / id.", true)
				.qty("mass", "Mass of the line, e.g. \"245 g\" (all pieces together).", true)
				.qty("cg", "CG from the part's front end (or from the nose tip with cgFromNose), optional.", false)
				.bool("cgFromNose", "The CG is measured from the nose tip.", false)
				.str("status", "measured / estimated / allocated.", false)
				.bool("section", "The mass is this part and everything inside it (a weighed section).", false)
				.str("parent", "For a part the design does not have yet: the component to add it in.", false)
				.bool("apply", "Use this line when applying (default true).", false).build();
		s.tool(new ToolDef("mass_budget", "Mass budget and weigh-in tracking",
				"Compare the team's mass budget (allocations, estimates or weigh-in results) with the OpenRocket model, part "
						+ "by part (biggest differences first), with totals: budget, parts the budget leaves out, projected dry "
						+ "and launch mass, contingency on everything not weighed yet, and the margin to a target launch mass. "
						+ "With apply=true the numbers go into the design as OpenRocket mass (and CG) overrides — a whole "
						+ "section for lines marked section — and lines for parts the design lacks are added as mass components "
						+ "in their parent; the new launch mass, CG and stability are returned and undo reverts it in one step. "
						+ "The budget is a CSV (text or file; columns like part, mass (g), cg (mm), status, section, parent, qty) or "
						+ "a list of items. With no budget, writes the model's own breakdown as a CSV template to fill in.",
				Schema.object().str("designId", DesignTools.DESIGN_ID, false).str("configuration", DesignTools.CONFIG, false)
						.str("csv", "The budget as CSV text.", false)
						.str("path", "The budget as a CSV file in the workspace.", false)
						.array("items", "The budget as a list of lines.", item, false)
						.str("massUnit", "Unit of bare numbers in the CSV mass column when the header gives none (default g).", false)
						.str("lengthUnit", "Unit of bare numbers in the CSV CG column (default mm).", false)
						.qty("targetLaunchMass", "The team's launch mass target, for the margin.", false)
						.num("contingency", "Growth allowance on everything not weighed yet, e.g. 0.1 for 10%.", false)
						.bool("apply", "Write the budget masses into the design (default false).", false)
						.bool("addMissing", "When applying, add lines that match no component as mass components in their "
								+ "parent (default true).", false)
						.str("csvPath", "Where to write the template when no budget is given (default \"<design>-mass-budget.csv\").",
								false).build(),
				false, a -> budget(ctx, a)));
	}

	private static Object budget(Context ctx, Args a) throws Exception {
		Designs.Design d = ctx.designs.get(a.str("designId", null));
		FlightConfiguration fc = Components.config(d.doc.getRocket(), a.str("configuration", null));
		List<MassBudget.Item> items = new ArrayList<>();
		String massUnit = a.str("massUnit", "g"), lengthUnit = a.str("lengthUnit", "mm");
		if (a.has("csv")) {
			items.addAll(MassBudget.parseCsv(a.str("csv"), massUnit, lengthUnit));
		}
		if (a.has("path")) {
			items.addAll(MassBudget.parseCsv(Files.readString(ctx.path(a.str("path"))), massUnit, lengthUnit));
		}
		if (a.has("items")) {
			for (Args it : a.objList("items")) {
				items.add(new MassBudget.Item(it.str("part"), it.qty("mass", Dim.MASS), it.qtyOrNaN("cg", Dim.LENGTH),
						it.bool("cgFromNose", false), it.str("status", ""), it.bool("section", false), it.str("parent", null),
						it.bool("apply", true), null));
			}
		}
		if (items.isEmpty()) {
			Path p = ctx.path(a.str("csvPath", Geometry.safe(d.name()) + "-mass-budget.csv"));
			if (p.getParent() != null) {
				Files.createDirectories(p.getParent());
			}
			Files.writeString(p, MassBudget.template(fc));
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("template", p.toAbsolutePath().toString());
			out.put("howToUse", "One row per component with the model's mass and CG (from each part's front). Replace masses with "
					+ "your allocations or scale readings, set status to measured for weighed parts, mark weighed sections with "
					+ "section = yes, add rows for parts the model lacks (with a parent), then call mass_budget with path = this "
					+ "file (apply=true to update the design).");
			return out;
		}
		double contingency = a.num("contingency", 0);
		if (contingency < 0 || contingency > 1) {
			throw new ToolException("contingency is a fraction, e.g. 0.1 for 10%.");
		}
		Map<String, Object> out = MassBudget.run(d, fc, items, new MassBudget.Options(a.qtyOrNaN("targetLaunchMass", Dim.MASS),
				contingency, a.bool("apply", false), a.bool("addMissing", true)));
		out.put("units", "CSV masses without a unit read as " + massUnit + ", CG as " + lengthUnit);
		return out;
	}
}
