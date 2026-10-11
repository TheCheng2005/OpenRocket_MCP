package io.github.openrocketmcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import info.openrocket.core.document.Simulation;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.CallContext;
import io.github.openrocketmcp.mcp.McpServer;
import io.github.openrocketmcp.mcp.McpServer.Resource;
import io.github.openrocketmcp.mcp.Schema;
import io.github.openrocketmcp.mcp.ToolDef;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Geometry;
import io.github.openrocketmcp.or.Requirements;
import io.github.openrocketmcp.or.Variants;
import io.github.openrocketmcp.report.Workbench;
import io.github.openrocketmcp.units.Dim;

/**
 * The interactive design view (MCP Apps): {@code show_design} opens it in the chat (and writes a standalone HTML copy);
 * the view itself fetches its data with {@code workbench_data}, a tool hosts keep away from the model.
 */
public final class WorkbenchTools {
	static final List<String> TABS = List.of("model", "flight", "trajectory", "checks");
	/** Views shown in this session, newest last: a view scrolled back to in the chat still shows what it showed. */
	private static final int KEEP = 8;

	private WorkbenchTools() {
	}

	public static void register(McpServer s, Context ctx) {
		Map<String, Map<String, Object>> views = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> e) {
				return size() > KEEP;
			}
		});
		AtomicInteger counter = new AtomicInteger();

		JsonObject resourceMeta = new JsonObject();
		JsonObject ui = new JsonObject();
		ui.addProperty("prefersBorder", true);
		ui.add("csp", new JsonObject()); // no network, no external scripts: everything is in the page
		resourceMeta.add("ui", ui);
		s.resource(new Resource(Workbench.URI, "OpenRocket design view",
				"Interactive view of a design and its flight (3-D model, flight plots, trajectory, rule checks).",
				McpServer.UI_MIME, Workbench::html, resourceMeta, false));

		s.tool(new ToolDef("show_design", "Show the design interactively",
				"Open an interactive view of the design and its simulated flight: the 3-D rocket (rotate, cut-away, exploded; "
						+ "click a part to see and edit its properties), CG and CP moving through the flight, a 3-D replay of the "
						+ "flight, linked plots (altitude, speed, acceleration, Mach, stability, thrust and drag, angle of attack, "
						+ "mass) with events marked, the trajectory and ground track, and the rule checks. In Claude Desktop and "
						+ "claude.ai the view appears in the chat; it is also written as a standalone HTML file to open in a browser "
						+ "or send to the team. Use it whenever someone wants to see, explore or present the rocket or its flight "
						+ "rather than read numbers.",
				SimTools.overrides(SimTools.simSelect(Schema.object()))
						.enumStr("tab", "Tab to open first (default model).", false, TABS.toArray(String[]::new))
						.str("path", "Standalone HTML file to write (default <design>-view.html in the workspace).", false)
						.build(),
				false, a -> {
					Designs.Design d = ctx.designs.get(a.str("designId", null));
					Simulation sim = SimTools.runSelected(ctx, a);
					Map<String, Object> data = build(ctx, d, sim);
					String viewId = "v" + counter.incrementAndGet();
					data.put("viewId", viewId);
					String tab = a.str("tab", "model");
					data.put("tab", tab);
					views.put(viewId, data);
					Path p = ctx.path(a.str("path", Geometry.safe(d.name()) + "-view.html"));
					if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html")) {
						throw new ToolException("path must end in .html.");
					}
					if (p.getParent() != null) {
						Files.createDirectories(p.getParent());
					}
					Files.writeString(p, Workbench.standalone(data));

					Map<String, Object> view = new LinkedHashMap<>();
					view.put("viewId", viewId);
					view.put("designId", d.id);
					view.put("simulation", sim.getName());
					view.put("tab", tab);
					CallContext.current().structured(view);

					Map<String, Object> out = new LinkedHashMap<>();
					Boolean ui2 = s.uiClient();
					out.put("view", Boolean.TRUE.equals(ui2)
							? "Shown in the chat as an interactive view (tabs: rocket, flight plots, trajectory, rule checks)."
							: "Shown in the chat where the client supports interactive views (Claude Desktop, claude.ai); "
									+ "otherwise open the HTML file in a browser.");
					out.put("file", p.toString());
					out.put("design", d.name() + ", " + sim.getName());
					out.put("summary", summary(data));
					Object checks = data.get("checks");
					if (checks instanceof Map<?, ?> c) {
						out.put("checks", c.get("summary"));
					}
					out.put("note", "Edits made in the view (part properties) change the design in this session like "
							+ "edit_components does; undo reverts them.");
					return out;
				}, meta(true)));

		s.tool(new ToolDef("workbench_data", "Data for the interactive view",
				"Used by the interactive design view to load its data (meshes, flight series, checks); not useful to call "
						+ "directly - use show_design.",
				SimTools.simSelect(Schema.object())
						.str("viewId", "The view's id (from show_design).", false)
						.bool("refresh", "Rebuild from the design as it is now (after an edit) instead of the snapshot.", false)
						.build(),
				true, a -> {
					String viewId = a.str("viewId", null);
					Map<String, Object> data = viewId == null ? null : views.get(viewId);
					if (data == null || a.bool("refresh", false)) {
						Map<?, ?> shown = data == null ? Map.of() : (Map<?, ?>) data.get("design");
						String designId = a.str("designId", shown.containsKey("designId") ? String.valueOf(shown.get("designId")) : null);
						if (designId == null || ctx.designs.all().stream().noneMatch(x -> x.id.equals(designId))) {
							throw new ToolException("This view's design is no longer open (the server restarted or the design "
									+ "was closed). Ask Claude to show the design again.");
						}
						Designs.Design d = ctx.designs.get(designId);
						JsonObject args = a.raw().deepCopy();
						args.addProperty("designId", designId);
						if (!args.has("simulation") && shown.containsKey("simulation")) {
							args.addProperty("simulation", String.valueOf(shown.get("simulation")));
						}
						Simulation sim = SimTools.runSelected(ctx, new Args(args));
						Object tab = data == null ? "model" : data.get("tab");
						data = build(ctx, d, sim);
						data.put("tab", tab);
						if (viewId != null) {
							data.put("viewId", viewId);
							views.put(viewId, data);
						}
					}
					CallContext.current().structured(data);
					Map<?, ?> rocket = (Map<?, ?>) data.get("rocket");
					return "View data for " + ((Map<?, ?>) data.get("design")).get("name") + ": "
							+ ((List<?>) rocket.get("parts")).size() + " parts, "
							+ ((List<?>) ((Map<?, ?>) data.get("flight")).get("t")).size() + " flight points.";
				}, meta(false)));
	}

	static Map<String, Object> build(Context ctx, Designs.Design d, Simulation sim) {
		Simulation wind = null;
		double maxWind = ctx.standards().rule("maxGroundWind.value", Dim.VELOCITY);
		if (!Double.isNaN(maxWind)) {
			try {
				wind = Variants.windCase(sim, d.doc, maxWind);
			} catch (RuntimeException e) {
				wind = null; // the view still shows the rest; check_requirements reports why
			}
		}
		Map<String, Object> checks = Requirements.check(sim, wind, ctx.standards()).render(ctx.standards().rulesName());
		return Workbench.data(d.name(), d.id, sim, checks, ctx.standards());
	}

	/** The headline numbers, as the model reads them. */
	static String summary(Map<String, Object> data) {
		StringBuilder b = new StringBuilder();
		for (Object o : (List<?>) data.get("metrics")) {
			Map<?, ?> m = (Map<?, ?>) o;
			if (b.length() > 0) {
				b.append("; ");
			}
			b.append(m.get("label")).append(' ');
			b.append(m.containsKey("text") ? m.get("text") : io.github.openrocketmcp.units.Units.num(((Number) m.get("value")).doubleValue())
					+ " " + m.get("unit"));
		}
		return b.toString();
	}

	/** {@code _meta} linking a tool to the view; app-only tools are hidden from the model by the host. */
	static JsonObject meta(boolean model) {
		JsonObject ui = new JsonObject();
		ui.addProperty("resourceUri", Workbench.URI);
		JsonArray vis = new JsonArray();
		if (model) {
			vis.add("model");
		}
		vis.add("app");
		ui.add("visibility", vis);
		JsonObject m = new JsonObject();
		m.add("ui", ui);
		if (model) {
			m.addProperty("ui/resourceUri", Workbench.URI); // the earlier flat key, for hosts that still read it
		}
		return m;
	}
}
