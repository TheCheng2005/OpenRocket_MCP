package io.github.openrocketmcp.or;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import info.openrocket.core.rocketcomponent.ComponentAssembly;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.MassComponent;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.position.AxialMethod;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.units.Dim;
import io.github.openrocketmcp.units.Units;

/**
 * A team's mass budget (allocations or weigh-in results) against the OpenRocket model: part-by-part comparison, totals
 * against a target, parts the budget does not cover, and applying the numbers as OpenRocket mass / CG overrides so the
 * simulations fly the rocket as built.
 */
public final class MassBudget {
	private MassBudget() {
	}

	/**
	 * One line of the budget.
	 *
	 * @param mass    SI, for all {@code qty} pieces together
	 * @param cg      CG in m, or NaN
	 * @param cgFromNose whether {@code cg} is measured from the nose tip (else from the part's front end)
	 * @param section the mass is the part and everything inside it (a weighed section)
	 * @param parent  where to add the part if the design has no such component, or null
	 */
	public record Item(String part, double mass, double cg, boolean cgFromNose, String status, boolean section, String parent,
			boolean apply, String componentId) {
		public Item(String part, double mass, double cg, boolean cgFromNose, String status, boolean section, String parent,
				boolean apply) {
			this(part, mass, cg, cgFromNose, status, section, parent, apply, null);
		}
	}

	// ------------------------------------------------------------------------------------------- parsing

	/** Parses a CSV budget. Header names are flexible; units come from the header ("mass (g)", "mass_lb") or the cells. */
	public static List<Item> parseCsv(String text, String defaultMassUnit, String defaultLengthUnit) {
		List<List<String>> rows = csv(text);
		if (rows.isEmpty()) {
			throw new ToolException("The mass budget is empty.");
		}
		List<String> h = new ArrayList<>();
		for (String c : rows.get(0)) {
			h.add(c.trim().toLowerCase(Locale.ROOT));
		}
		int iPart = find(h, "part", "component", "name", "item", "description");
		int iMass = find(h, "mass", "weight", "measured", "allocated", "budget");
		if (iPart < 0 || iMass < 0) {
			throw new ToolException("The budget needs a part (or component / name) column and a mass column; found: " + h);
		}
		int iCgNose = find(h, "cg_from_nose", "cg from nose", "cg (from nose", "cg_nose", "cg from tip");
		int iCg = iCgNose >= 0 ? iCgNose : find(h, "cg");
		int iStatus = find(h, "status", "type", "source");
		int iSection = find(h, "section", "includes children", "whole", "assembly");
		int iParent = find(h, "parent", "location", "mounted in");
		int iQty = find(h, "qty", "quantity", "count", "number");
		int iApply = find(h, "apply", "use");
		int iId = find(h, "component_id", "component id", "id");
		String massUnit = unitOf(h.get(iMass), defaultMassUnit);
		String cgUnit = iCg >= 0 ? unitOf(h.get(iCg), defaultLengthUnit) : defaultLengthUnit;
		List<Item> out = new ArrayList<>();
		for (int r = 1; r < rows.size(); r++) {
			List<String> row = rows.get(r);
			String part = cell(row, iPart);
			String m = cell(row, iMass);
			if (part.isBlank() || m.isBlank() || part.toLowerCase(Locale.ROOT).startsWith("total")) {
				continue;
			}
			double mass;
			try {
				mass = quantity(m, massUnit, Dim.MASS);
			} catch (RuntimeException e) {
				throw new ToolException("Row " + (r + 1) + " (" + part + "): cannot read the mass '" + m + "'.");
			}
			String q = cell(row, iQty);
			if (!q.isBlank()) {
				mass *= Double.parseDouble(q.trim());
			}
			String cg = cell(row, iCg);
			double cgv = cg.isBlank() ? Double.NaN : quantity(cg, cgUnit, Dim.LENGTH);
			String sec = cell(row, iSection).toLowerCase(Locale.ROOT);
			String ap = cell(row, iApply).toLowerCase(Locale.ROOT);
			out.add(new Item(part.trim(), mass, cgv, iCgNose >= 0, cell(row, iStatus).trim(),
					sec.startsWith("y") || sec.equals("true") || sec.equals("1") || sec.equals("section"),
					blankToNull(cell(row, iParent)), !(ap.startsWith("n") || ap.equals("false") || ap.equals("0")),
					blankToNull(cell(row, iId))));
		}
		if (out.isEmpty()) {
			throw new ToolException("No budget rows with a part and a mass.");
		}
		return out;
	}

	static double quantity(String text, String unit, Dim dim) {
		String t = text.trim().replace(",", ".");
		if (t.matches("[-+0-9.eE]+")) {
			return Units.toSi(t + " " + unit, dim);
		}
		return Units.toSi(t, dim);
	}

	static String unitOf(String header, String fallback) {
		Matcher m = Pattern.compile("[(\\[]\\s*([a-z]+)\\s*[)\\]]|[_ ]([a-z]+)$").matcher(header);
		while (m.find()) {
			String u = m.group(1) != null ? m.group(1) : m.group(2);
			if (u.matches("g|kg|lb|lbs|oz|gram|grams|mm|cm|m|in|ft")) {
				return u.equals("lbs") ? "lb" : u.startsWith("gram") ? "g" : u;
			}
		}
		return fallback;
	}

	private static int find(List<String> h, String... names) {
		for (String n : names) {
			for (int i = 0; i < h.size(); i++) {
				if (h.get(i).equals(n)) {
					return i;
				}
			}
		}
		for (String n : names) {
			for (int i = 0; i < h.size(); i++) {
				if (h.get(i).startsWith(n)) {
					return i;
				}
			}
		}
		return -1;
	}

	private static String cell(List<String> row, int i) {
		return i < 0 || i >= row.size() ? "" : row.get(i);
	}

	private static String blankToNull(String s) {
		return s == null || s.isBlank() ? null : s.trim();
	}

	/** Minimal CSV: commas (or semicolons / tabs if the header has no commas), double-quoted fields. */
	static List<List<String>> csv(String text) {
		String first = text.lines().findFirst().orElse("");
		char sep = first.contains(",") ? ',' : first.contains(";") ? ';' : first.contains("\t") ? '\t' : ',';
		List<List<String>> rows = new ArrayList<>();
		for (String line : text.split("\\R")) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			List<String> cells = new ArrayList<>();
			StringBuilder b = new StringBuilder();
			boolean q = false;
			for (int i = 0; i < line.length(); i++) {
				char c = line.charAt(i);
				if (c == '"') {
					if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') {
						b.append('"');
						i++;
					} else {
						q = !q;
					}
				} else if (c == sep && !q) {
					cells.add(b.toString());
					b.setLength(0);
				} else {
					b.append(c);
				}
			}
			cells.add(b.toString());
			rows.add(cells);
		}
		return rows;
	}

	// ------------------------------------------------------------------------------------------- matching

	/** The component a budget line refers to, and how it was found; null component if none. */
	public record Match(RocketComponent component, String how) {
	}

	static String norm(String s) {
		return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}

	public static Match match(Rocket r, String part) {
		List<RocketComponent> all = new ArrayList<>();
		for (RocketComponent c : r) {
			if (!(c instanceof Rocket)) {
				all.add(c);
			}
		}
		String p = norm(part);
		for (RocketComponent c : all) {
			if (Components.shortId(c).equalsIgnoreCase(part.trim()) || c.getID().toString().equalsIgnoreCase(part.trim())) {
				return new Match(c, "id");
			}
		}
		List<RocketComponent> named = new ArrayList<>();
		for (RocketComponent c : all) {
			if (norm(c.getName()).equals(p)) {
				named.add(c);
			}
		}
		if (named.size() == 1) {
			return new Match(named.get(0), "name");
		}
		if (named.size() > 1) {
			return new Match(null, "ambiguous: " + named.size() + " components are called '" + part + "'; use the component id");
		}
		List<RocketComponent> contains = new ArrayList<>();
		for (RocketComponent c : all) {
			String n = norm(c.getName());
			if (!n.isEmpty() && (n.contains(p) || p.contains(n))) {
				contains.add(c);
			}
		}
		if (contains.size() == 1) {
			return new Match(contains.get(0), "partial name");
		}
		// Word overlap, e.g. "lower body tube" for "Lower airframe" does not match, "fin can fins" does match "Fins".
		Set<String> words = new HashSet<>(List.of(p.split(" ")));
		RocketComponent best = null;
		double bestScore = 0;
		boolean tie = false;
		for (RocketComponent c : all) {
			Set<String> w = new HashSet<>(List.of(norm(c.getName()).split(" ")));
			Set<String> inter = new HashSet<>(w);
			inter.retainAll(words);
			Set<String> union = new HashSet<>(w);
			union.addAll(words);
			double score = union.isEmpty() ? 0 : (double) inter.size() / union.size();
			if (score > bestScore + 1e-9) {
				bestScore = score;
				best = c;
				tie = false;
			} else if (Math.abs(score - bestScore) < 1e-9 && score > 0) {
				tie = true;
			}
		}
		if (best != null && bestScore >= 0.5 && !tie) {
			return new Match(best, "similar name");
		}
		return new Match(null, contains.size() > 1 ? "ambiguous: " + contains.size() + " components match" : "no match");
	}

	// ------------------------------------------------------------------------------------------- model masses

	/** Mass of each component (all instances, weighed-section overrides applied) and of each subtree. */
	static final class ModelMasses {
		final Map<RocketComponent, double[]> own; // {mass, cg x}
		final double motorMass;

		ModelMasses(FlightConfiguration fc) {
			double[] motor = new double[2];
			own = Loads.componentMasses(fc, motor);
			motorMass = motor[0];
		}

		double own(RocketComponent c) {
			double[] v = own.get(c);
			return v == null ? 0 : v[0];
		}

		double subtree(RocketComponent c) {
			double m = own(c);
			for (RocketComponent ch : c.getChildren()) {
				m += subtree(ch);
			}
			return m;
		}

		double dry() {
			double m = 0;
			for (double[] v : own.values()) {
				m += v[0];
			}
			return m;
		}
	}

	// ------------------------------------------------------------------------------------------- compare and apply

	public record Options(double targetLaunchMass, double contingency, boolean apply, boolean addMissing) {
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> run(Designs.Design d, FlightConfiguration fc, List<Item> items, Options o) {
		Rocket rocket = d.doc.getRocket();
		Analysis.Stability before = Analysis.stability(fc, 0.3);
		ModelMasses mm = new ModelMasses(fc);
		List<Map<String, Object>> rows = new ArrayList<>();
		List<RocketComponent> matched = new ArrayList<>();
		Map<Item, RocketComponent> target = new LinkedHashMap<>();
		List<String> unmatched = new ArrayList<>();
		double budgetTotal = 0, notMeasured = 0, measured = 0;
		// Sections first, so parts listed inside a weighed section are not counted twice.
		Set<RocketComponent> sections = new HashSet<>();
		Map<Item, Match> matches = new LinkedHashMap<>();
		for (Item it : items) {
			Match m = it.componentId() != null ? match(rocket, it.componentId()) : match(rocket, it.part());
			if (it.componentId() != null && m.component() == null) {
				m = match(rocket, it.part());
			}
			matches.put(it, m);
			if (m.component() != null && it.section()) {
				sections.add(m.component());
			}
		}
		for (Item it : items) {
			Match m = matches.get(it);
			RocketComponent c = m.component();
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("part", it.part());
			row.put("budget", Units.fmt(it.mass(), Dim.MASS) + (it.status().isBlank() ? "" : " (" + it.status() + ")"));
			RocketComponent inside = c == null ? null : enclosingSection(c, sections);
			boolean counted = inside == null || inside == c;
			if (counted) {
				budgetTotal += it.mass();
				if (it.status().toLowerCase(Locale.ROOT).startsWith("meas") || it.status().toLowerCase(Locale.ROOT).startsWith("weigh")) {
					measured += it.mass();
				} else {
					notMeasured += it.mass();
				}
			}
			if (c == null) {
				row.put("component", m.how() + (it.parent() != null ? "; will be added in " + it.parent() : ""));
				if (it.parent() != null) {
					target.put(it, null);
				} else {
					unmatched.add(it.part());
				}
			} else {
				matched.add(c);
				double model = it.section() ? mm.subtree(c) : mm.own(c);
				row.put("component", c.getName() + " [" + Components.shortId(c) + "]" + (it.section() ? " and everything in it" : "")
						+ (m.how().equals("name") || m.how().equals("id") ? "" : " (" + m.how() + ")"));
				row.put("model", Units.fmt(model, Dim.MASS));
				double diff = it.mass() - model;
				row.put("difference", (diff >= 0 ? "+" : "") + Units.fmt(diff, Dim.MASS)
						+ (model > 0 ? String.format(Locale.ROOT, " (%+.0f%%)", 100 * diff / model) : ""));
				row.put("flag", Math.abs(diff) > Math.max(0.02, 0.1 * Math.max(model, it.mass())) ? "CHECK" : "ok");
				if (!counted) {
					row.put("note", "inside the weighed section " + inside.getName() + ": not added to the total again");
				}
				target.put(it, c);
				row.put("_abs", Math.abs(diff));
			}
			rows.add(row);
		}
		rows.sort((a, b) -> Double.compare((double) b.getOrDefault("_abs", -1.0), (double) a.getOrDefault("_abs", -1.0)));
		rows.forEach(r -> r.remove("_abs"));

		// Model parts the budget does not cover (not matched, not inside a matched section or weighed part).
		List<Object[]> uncovered = new ArrayList<>();
		double uncoveredMass = 0;
		for (Map.Entry<RocketComponent, double[]> e : mm.own.entrySet()) {
			RocketComponent c = e.getKey();
			if (c instanceof ComponentAssembly || e.getValue()[0] < 0.001 || covered(c, matched, sections)) {
				continue;
			}
			uncovered.add(new Object[] { c, e.getValue()[0] });
			uncoveredMass += e.getValue()[0];
		}
		uncovered.sort((a, b) -> Double.compare((double) b[1], (double) a[1]));

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", rows);
		Map<String, Object> tot = new LinkedHashMap<>();
		tot.put("budget", Units.fmt(budgetTotal, Dim.MASS) + " (" + items.size() + " lines" + (measured > 0 ? ", "
				+ Math.round(100 * measured / Math.max(1e-9, budgetTotal)) + "% of it weighed" : "") + ")");
		tot.put("modelDry", Units.fmt(mm.dry(), Dim.MASS) + " (structure, no motor)");
		tot.put("notInBudget", Units.fmt(uncoveredMass, Dim.MASS) + " (model parts the budget does not list)");
		double projected = budgetTotal + uncoveredMass;
		double extra = o.contingency() * (notMeasured + uncoveredMass);
		tot.put("projectedDry", Units.fmt(projected, Dim.MASS) + " = budget + parts not in it");
		if (o.contingency() > 0) {
			tot.put("projectedDryWithContingency", Units.fmt(projected + extra, Dim.MASS) + " (+" + Math.round(o.contingency() * 100)
					+ "% on everything not weighed: " + Units.fmt(extra, Dim.MASS) + ")");
		}
		tot.put("motor", Units.fmt(mm.motorMass, Dim.MASS));
		double launch = projected + extra + mm.motorMass;
		tot.put("projectedLaunch", Units.fmt(launch, Dim.MASS) + (o.contingency() > 0 ? " (with contingency)" : ""));
		if (!Double.isNaN(o.targetLaunchMass())) {
			double margin = o.targetLaunchMass() - launch;
			tot.put("target", Units.fmt(o.targetLaunchMass(), Dim.MASS));
			tot.put("margin", (margin >= 0 ? "+" : "") + Units.fmt(margin, Dim.MASS) + (margin >= 0 ? " under the target" : " OVER the target"));
		}
		out.put("totals", tot);
		if (!uncovered.isEmpty()) {
			List<String> top = new ArrayList<>();
			for (int i = 0; i < Math.min(8, uncovered.size()); i++) {
				RocketComponent c = (RocketComponent) uncovered.get(i)[0];
				top.add(c.getName() + " " + Units.fmt((double) uncovered.get(i)[1], Dim.MASS));
			}
			out.put("heaviestNotInBudget", top);
		}
		if (!unmatched.isEmpty()) {
			out.put("unmatched", unmatched.size() + " line(s) match no component and have no parent: " + String.join(", ", unmatched)
					+ ". Give the component name or id, or a parent to add them in.");
		}

		if (o.apply()) {
			List<String> applied = new ArrayList<>();
			// Several lines for one component (e.g. each fin weighed separately) become one override of their sum.
			Map<RocketComponent, List<Item>> byComponent = new LinkedHashMap<>();
			for (Map.Entry<Item, RocketComponent> e : target.entrySet()) {
				Item it = e.getKey();
				if (!it.apply()) {
					continue;
				}
				if (e.getValue() == null) {
					if (o.addMissing()) {
						applied.add(add(rocket, it));
					}
				} else {
					byComponent.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(it);
				}
			}
			for (Map.Entry<RocketComponent, List<Item>> e : byComponent.entrySet()) {
				List<Item> lines = e.getValue();
				Item it = lines.size() == 1 ? lines.get(0) : combine(lines);
				applied.add(override(fc, e.getKey(), it) + (lines.size() > 1 ? " (sum of " + lines.size() + " lines)" : ""));
			}
			d.doc.setSaved(false);
			Analysis.settle(fc);
			Analysis.Stability after = Analysis.stability(fc, 0.3);
			out.put("applied", applied);
			Map<String, Object> ch = new LinkedHashMap<>();
			ch.put("launchMass", Units.fmt(before.launchMass(), Dim.MASS) + " -> " + Units.fmt(after.launchMass(), Dim.MASS));
			ch.put("cgAtLaunch", Units.fmt(before.cgLaunchX(), Dim.LENGTH) + " -> " + Units.fmt(after.cgLaunchX(), Dim.LENGTH));
			ch.put("stabilityAtLaunch", Units.num(before.marginCalibers()) + " -> " + Units.num(after.marginCalibers()) + " cal");
			out.put("change", ch);
			out.put("next", "Re-run check_requirements (stability, rail exit and thrust-to-weight change with mass) and rank_motors "
					+ "if the apogee target moved. undo reverts all of this in one step.");
		} else {
			out.put("next", "apply=true writes these masses into the design as OpenRocket mass (and CG) overrides; undo reverts them.");
		}
		return out;
	}

	/** One line standing for several lines of the same component: summed mass, mass-weighted CG when all give one. */
	static Item combine(List<Item> lines) {
		double m = 0, mx = 0;
		boolean cg = true, fromNose = lines.get(0).cgFromNose(), section = false;
		for (Item i : lines) {
			m += i.mass();
			cg &= !Double.isNaN(i.cg()) && i.cgFromNose() == fromNose;
			mx += i.mass() * i.cg();
			section |= i.section();
		}
		Item f = lines.get(0);
		return new Item(f.part(), m, cg && m > 0 ? mx / m : Double.NaN, fromNose, f.status(), section, f.parent(), true, f.componentId());
	}

	private static RocketComponent enclosingSection(RocketComponent c, Set<RocketComponent> sections) {
		for (RocketComponent p = c; p != null; p = p.getParent()) {
			if (sections.contains(p)) {
				return p;
			}
		}
		return null;
	}

	private static boolean covered(RocketComponent c, List<RocketComponent> matched, Set<RocketComponent> sections) {
		if (matched.contains(c)) {
			return true;
		}
		for (RocketComponent p = c.getParent(); p != null; p = p.getParent()) {
			if (sections.contains(p)) {
				return true;
			}
		}
		return false;
	}

	/** Sets the component's (or section's) mass and CG override so OpenRocket uses the budget number. */
	static String override(FlightConfiguration fc, RocketComponent c, Item it) {
		RocketComponent owner = sectionOwner(c);
		// A line not marked as a section is the part alone: a section override it had before no longer applies.
		boolean wasSection = !it.section() && c.isMassOverridden() && c.isSubcomponentsOverriddenMass();
		c.setMassOverridden(true);
		c.setOverrideMass(it.mass());
		c.setSubcomponentsOverriddenMass(it.section());
		if (wasSection && c.isCGOverridden()) {
			c.setSubcomponentsOverriddenCG(false);
		}
		// A component with several copies (fins, rail buttons) may take the override per copy: check OpenRocket's total.
		if (owner == null) {
			ModelMasses now = new ModelMasses(fc);
			double total = it.section() ? now.subtree(c) : now.own(c);
			if (total > 1e-9 && Math.abs(total - it.mass()) > 1e-6 * Math.max(1, it.mass())) {
				c.setOverrideMass(it.mass() * it.mass() / total);
			}
		}
		String cg = "";
		if (!Double.isNaN(it.cg())) {
			double rel = it.cgFromNose() ? it.cg() - Structures.absoluteX(c, 0) : it.cg();
			c.setCGOverridden(true);
			c.setOverrideCGX(rel);
			if (it.section()) {
				c.setSubcomponentsOverriddenCG(true);
			}
			cg = ", CG " + Units.fmt(rel, Dim.LENGTH) + " from its front";
		}
		return c.getName() + (it.section() ? " (section, everything in it)" : "") + ": mass " + Units.fmt(it.mass(), Dim.MASS) + cg
				+ (wasSection ? "; it used to override everything inside it, now only itself (mark the line as a section to keep that)"
						: "")
				+ (owner == null ? "" : "; NOTE: " + owner.getName() + " overrides the mass of everything inside it ("
						+ Units.fmt(owner.getOverrideMass(), Dim.MASS) + "), so this only changes how that mass is shared out. Give "
						+ owner.getName() + " as a section line with its weighed total, or remove its override.");
	}

	/** The ancestor whose section override (mass of everything inside it) hides this component's own mass, or null. */
	static RocketComponent sectionOwner(RocketComponent c) {
		RocketComponent found = null;
		for (RocketComponent p = c.getParent(); p != null; p = p.getParent()) {
			if (p.isMassOverridden() && p.isSubcomponentsOverriddenMass()) {
				found = p;
			}
		}
		return found;
	}

	/** Adds a budget line the design lacks as a mass component inside its parent (at its CG if given). */
	static String add(Rocket r, Item it) {
		Match pm = match(r, it.parent());
		if (pm.component() == null) {
			return it.part() + ": NOT added, parent '" + it.parent() + "' " + pm.how();
		}
		RocketComponent parent = pm.component();
		MassComponent m = new MassComponent();
		m.setName(it.part());
		double len = Math.min(0.05, Math.max(0.01, parent.getLength() * 0.1));
		m.setLength(len);
		m.setComponentMass(it.mass());
		if (!Double.isNaN(it.cg())) {
			double rel = it.cgFromNose() ? it.cg() - Structures.absoluteX(parent, 0) : it.cg();
			m.setAxialMethod(AxialMethod.TOP);
			m.setAxialOffset(Math.max(0, Math.min(parent.getLength() - len, rel - len / 2)));
		} else {
			m.setAxialMethod(AxialMethod.MIDDLE);
			m.setAxialOffset(0);
		}
		parent.addChild(m);
		String warn = Components.overrideWarning(m);
		return it.part() + ": added as a mass component in " + parent.getName() + " (" + Units.fmt(it.mass(), Dim.MASS)
				+ (Double.isNaN(it.cg()) ? ", centred" : "") + ")" + (warn == null ? "" : "; " + warn);
	}

	// ------------------------------------------------------------------------------------------- template

	/** The model's own mass breakdown as a budget sheet to fill in (one row per component with mass). */
	public static String template(FlightConfiguration fc) {
		ModelMasses mm = new ModelMasses(fc);
		StringBuilder b = new StringBuilder("part,component_id,mass (g),cg (mm),status,section,parent\n");
		Map<RocketComponent, Integer> depth = new HashMap<>();
		for (RocketComponent c : fc.getRocket()) {
			if (c instanceof Rocket || c instanceof ComponentAssembly) {
				continue;
			}
			double[] v = mm.own.get(c);
			double m = v == null ? 0 : v[0];
			if (m < 0.0005 && c.getChildCount() == 0) {
				continue;
			}
			double cgRel = v == null ? Double.NaN : v[1] - Structures.absoluteX(c, 0);
			depth.put(c, 0);
			b.append(String.format(Locale.ROOT, "\"%s\",%s,%.1f,%s,model estimate,no,%s%n", c.getName().replace("\"", "'"),
					Components.shortId(c), m * 1000, Double.isNaN(cgRel) ? "" : String.format(Locale.ROOT, "%.1f", cgRel * 1000),
					c.getParent() == null || c.getParent() instanceof ComponentAssembly ? "" : "\"" + c.getParent().getName() + "\""));
		}
		return b.toString();
	}
}
