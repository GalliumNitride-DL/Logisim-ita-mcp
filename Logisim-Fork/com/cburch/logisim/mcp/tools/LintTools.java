/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.mcp.Json;
import com.cburch.logisim.mcp.McpInstructions;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;
import com.cburch.logisim.std.wiring.Ground;
import com.cburch.logisim.std.wiring.Power;
import com.cburch.logisim.std.wiring.Tunnel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One read-only pass over a circuit, reporting what is wrong with it rather than waiting for
 * the user to notice.
 *
 * <p>
 * The netlist answers "why does this read x or e"; this tool answers "what else is wrong",
 * including the things that are not electrical errors at all - a wire running through a port
 * (which shorts two nets), a tunnel with no label (they all join each other), coordinates off
 * the 10 pixel grid, a component at a negative coordinate where the user cannot see it,
 * components drawn on top of one another, and the conventions that keep a schematic readable:
 * tunnels facing east or west, ground and power facing the way the rest of the circuit faces
 * them.
 * </p>
 *
 * <p>
 * Findings are graded <b>error</b> (the circuit will not do what it says), <b>warning</b>
 * (readable but against the conventions this project writes down) and <b>info</b>. A clean
 * circuit returns an empty list and {@code ok: true}. Nothing here changes the circuit: each
 * finding names a fix in terms of the tools that do.
 * </p>
 */
public final class LintTools {

	/** Coordinates off this grid are the ones Logisim's wire repair mishandles. */
	private static final int GRID = 10;

	/** How far a wire end may be from a port before the two are called unrelated. */
	private static final int NEAR = 30;

	private LintTools() {
	}

	public static McpTool lint(final McpSession session) {
		return new AbstractTool("logisim_circuit_lint", McpInstructions.CIRCUIT_LINT,
				Schemas.object()
						.opt("circuit", Schemas.string("Circuit to inspect; defaults to the current circuit"))
						.opt("severity", Schemas.stringEnum("Lowest severity to report", "info", "warning", "error"))
						.opt("limit", Schemas.integer("Maximum number of findings (default 100)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				String minimum = ctx.optString("severity", "info");
				int limit = ctx.optInt("limit", Integer.valueOf(100)).intValue();

				List<JsonObject> findings = new ArrayList<JsonObject>();
				checkShorts(session, circuit, findings);
				checkFacing(session, circuit, findings);
				checkCoordinates(session, circuit, findings);
				checkOverlaps(session, circuit, findings);
				checkNets(session, circuit, findings);

				JsonArray array = new JsonArray();
				Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
				int errors = 0;
				int shown = 0;
				for (JsonObject finding : findings) {
					String severity = finding.get("severity").getAsString();
					Integer seen = counts.get(severity);
					counts.put(severity, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
					if ("error".equals(severity)) {
						errors++;
					}
					if (allows(minimum, severity) && shown < limit) {
						array.add(finding);
						shown++;
					}
				}

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("ok", Boolean.valueOf(errors == 0));
				ret.addProperty("finding_count", Integer.valueOf(findings.size()));
				JsonObject bySeverity = new JsonObject();
				for (Map.Entry<String, Integer> entry : counts.entrySet()) {
					bySeverity.addProperty(entry.getKey(), entry.getValue());
				}
				ret.add("counts", bySeverity);
				if (shown < findings.size()) {
					ret.addProperty("shown", Integer.valueOf(shown));
				}
				ret.add("findings", array);
				ret.addProperty("hint", findings.isEmpty()
						? "nothing to report: shorts, facing, coordinates, overlaps and the netlist are all clean"
						: "each finding names the tool that fixes it; 'error' means the circuit will not behave as "
								+ "drawn, 'warning' means it works but reads against the conventions");
				return ret;
			}
		};
	}

	private static boolean allows(String minimum, String severity) {
		return rank(severity) >= rank(minimum);
	}

	private static int rank(String severity) {
		if ("error".equals(severity)) {
			return 2;
		}
		if ("warning".equals(severity)) {
			return 1;
		}
		return 0;
	}

	/**
	 * A wire that passes through a port short circuits the two nets. Logisim splits the wire
	 * there, so the damage is invisible in the model and shows up only as a value that makes
	 * no sense.
	 */
	private static void checkShorts(McpSession session, Circuit circuit, List<JsonObject> findings) {
		Set<Location> ports = session.portLocations(circuit);
		if (ports.isEmpty()) {
			return;
		}
		for (Wire wire : circuit.getWires()) {
			Location blocked = EditTools.portInside(wire.getEnd0(), wire.getEnd1(), ports);
			if (blocked != null) {
				JsonObject finding = finding("wire_through_port", "error");
				finding.addProperty("message", "wire " + session.idOf(wire) + " (" + wire.getEnd0() + " -> "
						+ wire.getEnd1() + ") passes through the port at " + blocked
						+ ", so the two nets are joined");
				finding.addProperty("wire", session.idOf(wire));
				finding.add("loc", Json.array(Integer.valueOf(blocked.getX()), Integer.valueOf(blocked.getY())));
				String near = session.describeNearestPort(circuit, blocked, NEAR);
				if (near != null) {
					finding.addProperty("port", near);
				}
				finding.addProperty("fix", "logisim_wire_remove {loc: [" + blocked.getX() + ", " + blocked.getY()
						+ "]} removes that segment; then redraw the wire around the port");
				findings.add(finding);
			}
		}
	}

	/**
	 * Tunnels, ground and power: the conventions that make a schematic readable, reported as
	 * a fact rather than a rule - what the rest of the circuit does, and which item differs.
	 */
	private static void checkFacing(McpSession session, Circuit circuit, List<JsonObject> findings) {
		Map<String, List<String>> tunnelLabels = new LinkedHashMap<String, List<String>>();
		Map<String, Map<String, Integer>> facingCounts = new LinkedHashMap<String, Map<String, Integer>>();
		Map<String, List<Component>> byKind = new LinkedHashMap<String, List<Component>>();
		for (Component comp : circuit.getNonWires()) {
			String kind = null;
			if (comp.getFactory() instanceof Tunnel) {
				kind = "tunnel";
				String label = comp.getAttributeSet().getValue(StdAttr.LABEL);
				if (label == null || label.trim().isEmpty() || label.trim().equalsIgnoreCase("tunnel")) {
					JsonObject finding = finding("tunnel_label_default", "error");
					finding.addProperty("message", "tunnel " + session.idOf(comp) + " at " + comp.getLocation()
							+ " still has the default label " + (label == null || label.trim().isEmpty()
									? "(none)" : "\"" + label + "\"")
							+ ", so it joins every other tunnel that has it - Logisim connects tunnels by name");
					finding.addProperty("component", session.idOf(comp));
					finding.addProperty("fix", "logisim_component_set_attributes {component: \""
							+ session.idOf(comp) + "\", attributes: {\"label\": \"NAME\"}} with the signal's name");
					findings.add(finding);
				} else {
					List<String> at = tunnelLabels.get(label);
					if (at == null) {
						at = new ArrayList<String>();
						tunnelLabels.put(label, at);
					}
					at.add(session.idOf(comp));
				}
			} else if (comp.getFactory() instanceof Ground) {
				kind = "ground";
			} else if (comp.getFactory() instanceof Power) {
				kind = "power";
			}
			if (kind == null) {
				continue;
			}
			Direction facing = comp.getAttributeSet().getValue(StdAttr.FACING);
			String facingName = facing == null ? "unknown" : facing.toString();
			Map<String, Integer> counts = facingCounts.get(kind);
			if (counts == null) {
				counts = new LinkedHashMap<String, Integer>();
				facingCounts.put(kind, counts);
			}
			Integer seen = counts.get(facingName);
			counts.put(facingName, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
			List<Component> list = byKind.get(kind);
			if (list == null) {
				list = new ArrayList<Component>();
				byKind.put(kind, list);
			}
			list.add(comp);
		}

		// tunnels: north and south make the label read across the wire instead of along it
		for (Component comp : circuit.getNonWires()) {
			if (!(comp.getFactory() instanceof Tunnel)) {
				continue;
			}
			Direction facing = comp.getAttributeSet().getValue(StdAttr.FACING);
			if (facing != null && (facing == Direction.NORTH || facing == Direction.SOUTH)) {
				JsonObject finding = finding("tunnel_facing", "warning");
				finding.addProperty("message", "tunnel " + session.idOf(comp) + " faces " + facing
						+ "; tunnels normally face east or west so the label reads along the wire");
				finding.addProperty("component", session.idOf(comp));
				finding.addProperty("fix", "logisim_component_set_attributes {component: \"" + session.idOf(comp)
						+ "\", attributes: {\"facing\": \"east\"}}");
				findings.add(finding);
			}
		}
		reportMinority(session, "ground", byKind, facingCounts, findings);
		reportMinority(session, "power", byKind, facingCounts, findings);
	}

	/** Names the items whose facing is the odd one out, with what the majority does. */
	private static void reportMinority(McpSession session, String kind, Map<String, List<Component>> byKind,
			Map<String, Map<String, Integer>> facingCounts, List<JsonObject> findings) {
		Map<String, Integer> counts = facingCounts.get(kind);
		List<Component> comps = byKind.get(kind);
		if (counts == null || comps == null || counts.size() < 2) {
			return;
		}
		String common = null;
		int best = 0;
		for (Map.Entry<String, Integer> entry : counts.entrySet()) {
			if (entry.getValue().intValue() > best) {
				best = entry.getValue().intValue();
				common = entry.getKey();
			}
		}
		for (Component comp : comps) {
			Direction facing = comp.getAttributeSet().getValue(StdAttr.FACING);
			String facingName = facing == null ? "unknown" : facing.toString();
			if (facingName.equals(common)) {
				continue;
			}
			JsonObject finding = finding(kind + "_facing_inconsistent", "warning");
			finding.addProperty("message", "this " + kind + " at " + comp.getLocation() + " faces " + facingName
					+ ", while the " + best + " other " + kind + "(s) in this circuit face " + common);
			finding.addProperty("component", session.idOf(comp));
			finding.addProperty("fix", "logisim_component_set_attributes {component: \"" + session.idOf(comp)
					+ "\", attributes: {\"facing\": \"" + common + "\"}}");
			findings.add(finding);
		}
	}

	/** Coordinates off the grid, and coordinates the user cannot see. */
	private static void checkCoordinates(McpSession session, Circuit circuit, List<JsonObject> findings) {
		for (Component comp : circuit.getNonWires()) {
			Location loc = comp.getLocation();
			if (loc.getX() < 0 || loc.getY() < 0) {
				JsonObject finding = finding("negative_coordinate", "warning");
				finding.addProperty("message", circuitComponent(session, comp) + " sits at " + loc
						+ ", which is off the canvas, so the user cannot see it");
				finding.addProperty("component", session.idOf(comp));
				finding.addProperty("fix", "logisim_component_move it to x >= 0 and y >= 0");
				findings.add(finding);
			} else if (loc.getX() % GRID != 0 || loc.getY() % GRID != 0) {
				JsonObject finding = finding("off_grid", "warning");
				finding.addProperty("message", circuitComponent(session, comp) + " sits at " + loc
						+ ", which is off the " + GRID + " pixel grid: Logisim's own wire repair can split a wire "
						+ "within 2 units of a port and leave a diagonal wire behind");
				finding.addProperty("component", session.idOf(comp));
				finding.addProperty("fix", "logisim_component_move it to multiples of " + GRID);
				findings.add(finding);
			}
		}
		for (Wire wire : circuit.getWires()) {
			Location[] ends = { wire.getEnd0(), wire.getEnd1() };
			for (Location end : ends) {
				if (end.getX() % GRID != 0 || end.getY() % GRID != 0) {
					JsonObject finding = finding("off_grid", "warning");
					finding.addProperty("message", "wire " + session.idOf(wire) + " ends at " + end + ", off the "
							+ GRID + " pixel grid");
					finding.addProperty("wire", session.idOf(wire));
					finding.addProperty("fix", "logisim_wire_remove {loc: [...]} and redraw on the grid");
					findings.add(finding);
					break;
				}
			}
		}
	}

	/** Components drawn on top of one another, which makes a schematic ambiguous to read. */
	private static void checkOverlaps(McpSession session, Circuit circuit, List<JsonObject> findings) {
		List<Component> comps = new ArrayList<Component>(circuit.getNonWires());
		for (int i = 0; i < comps.size(); i++) {
			Bounds a = comps.get(i).getBounds();
			for (int j = i + 1; j < comps.size(); j++) {
				Bounds b = comps.get(j).getBounds();
				if (overlaps(a, b)) {
					JsonObject finding = finding("overlap", "warning");
					finding.addProperty("message", circuitComponent(session, comps.get(i)) + " at "
							+ comps.get(i).getLocation() + " overlaps " + circuitComponent(session, comps.get(j))
							+ " at " + comps.get(j).getLocation());
					finding.addProperty("component", session.idOf(comps.get(i)));
					finding.addProperty("other_component", session.idOf(comps.get(j)));
					finding.addProperty("fix", "move one of them clear; logisim_circuit_describe gives their bounds");
					findings.add(finding);
				}
			}
		}
	}

	/**
	 * The electrical checks, taken from the netlist rather than recomputed: a net with more than
	 * one driver reads 'e', a net with none reads 'x', and a port that touches no wire at all is
	 * usually unfinished work.
	 */
	private static void checkNets(McpSession session, Circuit circuit, List<JsonObject> findings) {
		JsonObject nets = session.describeNets(circuit, null, null, true, true, 1000, false);
		JsonArray problems = nets.has("problems") ? nets.getAsJsonArray("problems") : null;
		if (problems != null) {
			for (JsonElement problem : problems) {
				String text = problem.getAsString();
				JsonObject finding = finding("net_problem", "error");
				finding.addProperty("message", text);
				if (text.startsWith("net ")) {
					int space = text.indexOf(' ', 4);
					if (space > 4) {
						finding.addProperty("net", text.substring(4, space));
					}
				}
				finding.addProperty("fix", "logisim_circuit_nets {problems_only: true} lists the ports on that net, "
						+ "with their names and positions");
				findings.add(finding);
			}
		}
		if (nets.has("unconnected_ports")) {
			JsonArray unconnected = nets.getAsJsonArray("unconnected_ports");
			for (JsonElement element : unconnected) {
				JsonObject port = element.getAsJsonObject();
				JsonObject finding = finding("unconnected_port", "warning");
				StringBuilder buf = new StringBuilder("port ");
				buf.append(port.get("component").getAsString());
				if (Json.isPresent(port, "port")) {
					buf.append('.').append(port.get("port").getAsInt());
				}
				if (Json.isPresent(port, "name")) {
					buf.append(" (").append(port.get("name").getAsString()).append(')');
				}
				buf.append(" is connected to nothing, so it reads 'x'");
				finding.addProperty("message", buf.toString());
				if (Json.isPresent(port, "component")) {
					finding.addProperty("component", port.get("component").getAsString());
				}
				if (Json.isPresent(port, "loc")) {
					finding.add("loc", port.get("loc"));
				}
				finding.addProperty("fix", "wire it up, or remove the component if it is not wanted");
				findings.add(finding);
			}
		}
	}

	private static String circuitComponent(McpSession session, Component comp) {
		return comp.getFactory().getName() + " " + session.idOf(comp);
	}

	/** Rectangle overlap, because Logisim's Bounds offers no test for one. */
	private static boolean overlaps(Bounds a, Bounds b) {
		return a.getX() < b.getX() + b.getWidth() && b.getX() < a.getX() + a.getWidth()
				&& a.getY() < b.getY() + b.getHeight() && b.getY() < a.getY() + a.getHeight();
	}

	private static JsonObject finding(String kind, String severity) {
		JsonObject ret = new JsonObject();
		ret.addProperty("kind", kind);
		ret.addProperty("severity", severity);
		return ret;
	}
}
