/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.mcp.Json;
import com.cburch.logisim.mcp.McpException;
import com.cburch.logisim.mcp.McpInstructions;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The circuit editing tools. Everything here is committed as one undoable
 * Logisim action per call, through {@link CircuitMutation}, which is the very
 * same mechanism the Logisim GUI uses.
 */
public final class EditTools {
	/** Guard against an agent accidentally asking for an enormous construction. */
	private static final int MAX_PATH_POINTS = 500;

	private EditTools() {
	}

	public static McpTool addComponent(final McpSession session) {
		return new AbstractTool("logisim_component_add", McpInstructions.COMPONENT_ADD,
				Schemas.object()
						.opt("lib", Schemas.string("Library name, for example 'Wiring', 'Gates', 'Memory', 'I/O' or "
								+ "'Circuits'; omit when the type name is unambiguous"))
						.req("type", Schemas.string("Component type as listed by logisim_component_catalog, for "
								+ "example 'Pin', 'AND Gate', 'D Flip-Flop', 'Multiplexer', 'RAM', or the name of a "
								+ "circuit in this project"))
						.req("x", Schemas.integer("X coordinate of the component's centre/anchor"))
						.req("y", Schemas.integer("Y coordinate of the component's centre/anchor"))
						.opt("label", Schemas.string("Label for this component, e.g. the pin or signal name"))
						.opt("facing", Schemas.stringEnum("Orientation of the component",
								"east", "west", "north", "south"))
						.opt("width", Schemas.integer("Bus width in bits, when the component has a width attribute"))
						.opt("attributes", Schemas.freeObject("Any further attributes by name, exactly as reported by "
								+ "logisim_component_catalog, e.g. {\"output\": true} for an output Pin or "
								+ "{\"trigger\": \"falling\"}"))
						.opt("on_unknown_attribute", Schemas.stringEnum("What to do when an attribute does not exist on "
								+ "this component, which is common for 'label' (Arithmetic and Plexers components have "
								+ "none): 'warn' places the component anyway and lists the attribute under "
								+ "ignored_attributes, 'fail' refuses the whole call", "warn", "fail"))
						.opt("dry_run", Schemas.bool("Validate and report, but place nothing: the same ports, warnings "
								+ "and errors come back, and the circuit is left untouched"))
						.opt("circuit", Schemas.string("Circuit to add into; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				session.requireHealthyWires(circuit);
				ComponentFactory factory = session.factoryFor(ctx.optString("lib", null), ctx.requireString("type"));
				AttributeSet attrs = factory.createAttributeSet();
				boolean lenient = !"fail".equalsIgnoreCase(ctx.optString("on_unknown_attribute", "warn"));
				boolean dryRun = ctx.optBool("dry_run", false);
				List<String> ignored = new ArrayList<String>();

				JsonObject extra = ctx.optObject("attributes");
				JsonElement ranges = extra == null ? null : extra.get("bit_ranges");
				if (ranges != null && ranges.isJsonObject()) {
					expandBitRanges(session, attrs, factory, ranges.getAsJsonObject(), ignored, lenient);
					extra.remove("bit_ranges");
				}
				if (extra != null) {
					for (Map.Entry<String, JsonElement> entry : extra.entrySet()) {
						String ignoredName = applyAttribute(session, attrs, factory, entry.getKey(), entry.getValue(),
								lenient);
						if (ignoredName != null) {
							ignored.add(ignoredName);
						}
					}
				}
				if (ctx.has("label")) {
					String ignoredName = applyAttribute(session, attrs, factory, StdAttr.LABEL.getName(),
							new JsonPrimitive(ctx.requireString("label")), lenient);
					if (ignoredName != null) {
						ignored.add("label (this component has no label)");
					}
				}
				if (ctx.has("facing")) {
					String ignoredName = applyAttribute(session, attrs, factory, StdAttr.FACING.getName(),
							new JsonPrimitive(ctx.requireString("facing")), lenient);
					if (ignoredName != null) {
						ignored.add("facing (this component has a fixed orientation)");
					}
				}
				if (ctx.has("width")) {
					String ignoredName = applyAttribute(session, attrs, factory, StdAttr.WIDTH.getName(),
							new JsonPrimitive(Integer.valueOf(ctx.requireInt("width"))), lenient);
					if (ignoredName != null) {
						ignored.add("width (this component has a fixed width)");
					}
				}

				Location loc = Location.create(ctx.requireInt("x"), ctx.requireInt("y"));
				Component component = factory.createComponent(loc, attrs);
				requireOnCanvas(component, "the position of the new " + factory.getName());
				CircuitMutation mutation = new CircuitMutation(circuit);
				mutation.add(component);
				if (!dryRun) {
					session.commit(mutation, "add " + factory.getName());
					session.noteTouched(component);
				}

				JsonObject ret = session.describeComponent(component, true, true);
				ret.addProperty("circuit", circuit.getName());
				JsonArray warnings = new JsonArray();
				for (EndData end : component.getEnds()) {
					if (end != null && !circuit.isConnected(end.getLocation(), component)) {
						warnings.add("port at " + end.getLocation() + " is not connected to anything yet");
					}
				}
				if (warnings.size() > 0) {
					ret.add("warnings", warnings);
				}
				if (ignored.size() > 0) {
					JsonArray ignoredArray = new JsonArray();
					for (String name : ignored) {
						ignoredArray.add(name);
					}
					ret.add("ignored_attributes", ignoredArray);
					ret.addProperty("ignored_note", "these attributes do not exist on a " + factory.getName()
							+ " and were skipped; the component was placed without them. Pass "
							+ "on_unknown_attribute: \"fail\" to be refused instead");
				}
				ret.addProperty("hint", "the ports above are authoritative: wire to their 'ref' (for example "
						+ session.idOf(component) + ".0) or 'ref_by_name' instead of working out coordinates");
				if (dryRun) {
					ret.addProperty("dry_run", Boolean.TRUE);
					ret.addProperty("dry_run_note", "nothing was changed: this is what the call would place, with the same "
							+ "ports and warnings. Drop dry_run to do it");
				}
				return ret;
			}
		};
	}

	/** True when a point lies on an axis aligned segment, end points included. */
	private static boolean onSegment(Location from, Location to, Location point) {
		int x0 = from.getX();
		int y0 = from.getY();
		int x1 = to.getX();
		int y1 = to.getY();
		int px = point.getX();
		int py = point.getY();
		if (x0 == x1) {
			return px == x0 && py >= Math.min(y0, y1) && py <= Math.max(y0, y1);
		}
		if (y0 == y1) {
			return py == y0 && px >= Math.min(x0, x1) && px <= Math.max(x0, x1);
		}
		return false;
	}

	/**
	 * Warns when a path's start or end touches neither a port nor a wire, and names the port
	 * it probably meant. An endpoint that connects nothing is not an error - people do draw
	 * stubs - but it is the signature of a guessed coordinate, so it is worth a sentence.
	 */
	private static void countEndpoint(Map<Location, Integer> counts, Location loc) {
		Integer seen = counts.get(loc);
		counts.put(loc, Integer.valueOf(seen == null ? 1 : seen.intValue() + 1));
	}

	/**
	 * Warns when an endpoint of a path touches neither a port nor a wire that already
	 * existed, and names the port it most likely meant. A stub is not an error - people draw
	 * them - but it is the signature of a coordinate worked out by hand, and the nearest port
	 * is nearly always the one that was wanted.
	 */
	private static void warnIfConnectsNothing(McpSession session, Circuit circuit, java.util.Set<Location> ports,
			List<Wire> wiresBefore, Location loc, JsonArray warnings, boolean sharedWithinCall) {
		if (sharedWithinCall || ports.contains(loc)) {
			return;
		}
		for (Wire wire : wiresBefore) {
			if (onSegment(wire.getEnd0(), wire.getEnd1(), loc)) {
				return;
			}
		}
		String near = session.describeNearestPort(circuit, loc, 30);
		warnings.add("an endpoint at " + loc + " is not a port and does not touch any wire that was already there, so "
				+ "it connects nothing" + (near == null ? "" : "; " + near));
	}

	/** True when nothing about a segment would connect two unintended nets. */
	static boolean isClear(Location from, Location to, java.util.Set<Location> ports) {
		return portInside(from, to, ports) == null;
	}
	/**
	 * Applies one attribute, or - with {@code lenient} - reports that it does not exist.
	 *
	 * <p>
	 * Components in the Arithmetic and Plexers libraries have no 'label' attribute, so an
	 * agent that labels everything it places, which is good practice, used to lose the whole
	 * call over it: the component was not placed at all. With {@code lenient} the attribute is
	 * skipped and the caller lists it under {@code ignored_attributes}, so nothing is dropped
	 * silently and a misspelt name is still visible in the reply.
	 * </p>
	 *
	 * @return the name of an attribute that was not applied, or null when it was applied
	 */
	private static String applyAttribute(McpSession session, AttributeSet attrs, ComponentFactory factory, String name,
			JsonElement value, boolean lenient) {
		Attribute<?> attribute = attrs.getAttribute(name);
		if (attribute == null) {
			if (lenient) {
				return name;
			}
			throw new McpException("a " + factory.getName() + " has no attribute '" + name + "'; its attributes are: "
					+ CatalogTools.attributeNames(attrs));
		}
		if (attrs.isReadOnly(attribute)) {
			throw new McpException("attribute '" + name + "' of a " + factory.getName() + " is read only");
		}
		setValue(attrs, attribute, session.attributeFromJson(attrs, attribute, value));
		return null;
	}

	/**
	 * Expands a splitter's 'bit_ranges' shorthand into the per-bit attributes Logisim stores, and
	 * sets the input width and fan out to match.
	 *
	 * <p>
	 * {"0-4": 1, "5-31": 0} takes 32 bits in, sends bits 0 to 4 to output 1 and bits 5 to 31 to
	 * output 0 - two attributes instead of the thirty-two a caller would otherwise need. Outputs
	 * are numbered from 0 in the shorthand; Logisim stores them from 1, and this is the one place
	 * that converts. The width is applied first, because the per-bit attributes only exist up to
	 * the input width.
	 * </p>
	 */
	private static void expandBitRanges(McpSession session, AttributeSet attrs, ComponentFactory factory,
			JsonObject ranges, List<String> ignored, boolean lenient) {
		if (attrs.getAttribute("bit0") == null) {
			ignored.add("bit_ranges (this component has no per-bit attributes; only a splitter does)");
			return;
		}
		java.util.TreeMap<Integer, Integer> bits = new java.util.TreeMap<Integer, Integer>();
		for (Map.Entry<String, JsonElement> entry : ranges.entrySet()) {
			String key = entry.getKey().trim();
			int dash = key.indexOf('-');
			int from;
			int to;
			int value;
			try {
				value = entry.getValue().getAsInt();
				if (dash < 0) {
					from = Integer.parseInt(key);
					to = from;
				} else {
					from = Integer.parseInt(key.substring(0, dash).trim());
					to = Integer.parseInt(key.substring(dash + 1).trim());
				}
			} catch (RuntimeException e) {
				throw new McpException("'bit_ranges' keys look like \"0-4\" or \"7\" and its values are output "
						+ "numbers counted from 0; '" + entry.getKey() + "' does not fit that");
			}
			if (from < 0 || to < from || value < 0) {
				throw new McpException("'bit_ranges' entry '" + entry.getKey() + "' is backwards or negative");
			}
			for (int i = from; i <= to; i++) {
				bits.put(Integer.valueOf(i), Integer.valueOf(value));
			}
		}
		if (bits.isEmpty()) {
			return;
		}
		int fanout = 1;
		for (Integer value : bits.values()) {
			fanout = Math.max(fanout, value.intValue() + 1);
		}
		applyRangeAttribute(session, attrs, factory, "incoming", bits.lastKey().intValue() + 1, lenient, ignored,
				"the input width");
		applyRangeAttribute(session, attrs, factory, "fanout", fanout, lenient, ignored, "the fan out");
		for (Map.Entry<Integer, Integer> entry : bits.entrySet()) {
			applyRangeAttribute(session, attrs, factory, "bit" + entry.getKey(),
					entry.getValue().intValue() + 1, lenient, ignored,
					"the output for bit " + entry.getKey());
		}
	}

	private static void applyRangeAttribute(McpSession session, AttributeSet attrs, ComponentFactory factory,
			String name, int value, boolean lenient, List<String> ignored, String what) {
		String skipped = applyAttribute(session, attrs, factory, name, new JsonPrimitive(Integer.valueOf(value)),
				lenient);
		if (skipped != null) {
			ignored.add(skipped + " (" + what + " from bit_ranges)");
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void setValue(AttributeSet attrs, Attribute attribute, Object value) {
		attrs.setValue(attribute, value);
	}

	public static McpTool removeComponent(final McpSession session) {
		return new AbstractTool("logisim_component_remove", McpInstructions.COMPONENT_REMOVE,
				Schemas.object()
						.req("component", Schemas.componentRef("The component or wire to remove."))
						.opt("circuit", Schemas.string("Circuit to modify; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				Component component = session.requireComponent(circuit, ctx.optElement("component"));
				// Removing the broken wire itself is the manual way out of a damaged circuit, so
				// that single case is allowed even though the circuit is not healthy.
				if (!session.skewedWires(circuit).contains(component)) {
					session.requireHealthyWires(circuit);
				}
				String id = session.idOf(component);
				CircuitMutation mutation = new CircuitMutation(circuit);
				mutation.remove(component);
				session.commit(mutation, "remove " + component.getFactory().getName());
				return Json.object("removed", id, "type",
						component instanceof Wire ? "Wire" : component.getFactory().getName());
			}
		};
	}

	public static McpTool moveComponent(final McpSession session) {
		return new AbstractTool("logisim_component_move", McpInstructions.COMPONENT_MOVE,
				Schemas.object()
						.req("component", Schemas.componentRef("The component to move."))
						.opt("x", Schemas.integer("New absolute X coordinate"))
						.opt("y", Schemas.integer("New absolute Y coordinate"))
						.opt("dx", Schemas.integer("Relative X offset (used when x is absent)"))
						.opt("dy", Schemas.integer("Relative Y offset (used when y is absent)"))
						.opt("circuit", Schemas.string("Circuit to modify; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				Component component = session.requireComponent(circuit, ctx.optElement("component"));
				if (component instanceof Wire) {
					throw new McpException("a wire cannot be moved; remove it with logisim_wire_remove and add the "
							+ "new one with logisim_wire_add");
				}
				session.requireHealthyWires(circuit);
				Location oldLoc = component.getLocation();
				int x = ctx.has("x") ? ctx.requireInt("x") : oldLoc.getX() + ctx.optInt("dx", Integer.valueOf(0));
				int y = ctx.has("y") ? ctx.requireInt("y") : oldLoc.getY() + ctx.optInt("dy", Integer.valueOf(0));
				if (x == oldLoc.getX() && y == oldLoc.getY()) {
					throw new McpException("the component is already at " + oldLoc);
				}

				int attachedBefore = countAttachedWires(circuit, component);
				AttributeSet attrs = (AttributeSet) component.getAttributeSet().clone();
				Component replacement = component.getFactory().createComponent(Location.create(x, y), attrs);
				requireOnCanvas(replacement, "the new position of this " + component.getFactory().getName());
				CircuitMutation mutation = new CircuitMutation(circuit);
				mutation.replace(component, replacement);
				session.commit(mutation, "move " + component.getFactory().getName());
				session.noteTouched(component);
				// keep the id the agent already knows, so its earlier references stay valid
				session.transferId(component, replacement);

				JsonObject ret = session.describeComponent(replacement, true, true);
				ret.addProperty("moved_from", oldLoc.toString());
				if (attachedBefore > 0) {
					ret.addProperty("detached_wire_count", attachedBefore);
					ret.add("warning", Json.element("Logisim does not move wires with a component, so "
							+ attachedBefore + " wire(s) that used to touch this component are now dangling. "
							+ "Re-add them with logisim_wire_add, or check with logisim_circuit_describe."));
				}
				return ret;
			}
		};
	}

	private static int countAttachedWires(Circuit circuit, Component component) {
		int count = 0;
		List<Location> ends = new ArrayList<Location>();
		for (EndData end : component.getEnds()) {
			if (end != null) {
				ends.add(end.getLocation());
			}
		}
		for (Wire wire : circuit.getWires()) {
			for (Location end : ends) {
				if (wire.getEnd0().equals(end) || wire.getEnd1().equals(end)) {
					count++;
					break;
				}
			}
		}
		return count;
	}

	public static McpTool setAttributes(final McpSession session) {
		return new AbstractTool("logisim_component_set_attributes", McpInstructions.COMPONENT_SET_ATTRIBUTES,
				Schemas.object()
						.req("component", Schemas.componentRef("The component to modify."))
						.req("attributes", Schemas.freeObject("Attributes to set, by name, e.g. {\"width\": 8}"))
						.opt("circuit", Schemas.string("Circuit to modify; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				Component component = session.requireComponent(circuit, ctx.optElement("component"));
				JsonObject requested = ctx.requireObject("attributes");
				session.requireHealthyWires(circuit);
				AttributeSet attrs = component.getAttributeSet();
				CircuitMutation mutation = new CircuitMutation(circuit);
				JsonArray applied = new JsonArray();
				for (Map.Entry<String, JsonElement> entry : requested.entrySet()) {
					String name = entry.getKey();
					Attribute<?> attribute = attrs.getAttribute(name);
					if (attribute == null) {
						throw new McpException("a " + component.getFactory().getName() + " has no attribute '"
								+ name + "'; its attributes are: " + CatalogTools.attributeNames(attrs));
					}
					if (attrs.isReadOnly(attribute)) {
						throw new McpException("attribute '" + name + "' is read only");
					}
					Object value = session.attributeFromJson(attrs, attribute, entry.getValue());
					mutation.set(component, attribute, value);
					applied.add(name);
				}
				session.commit(mutation, "set attributes");
				session.noteTouched(component);
				JsonObject ret = session.describeComponent(component, true, true);
				ret.add("updated", applied);
				return ret;
			}
		};
	}

	public static McpTool addWire(final McpSession session) {
		return new AbstractTool("logisim_wire_add", McpInstructions.WIRE_ADD,
				Schemas.object()
						.opt("points", Schemas.array(Schemas.any("a point: [x, y], or a name such as \"A\" (pin or "
								+ "component label), \"c3\" (component id) or \"c3.1\" (component id and port index) - "
								+ "coordinates prefer the first form"),
								"The path to draw, in order; at least two points"))
						.opt("paths", Schemas.array(Schemas.any("a list of points, as for 'points'"),
								"Several paths to draw in one call. All of them are committed as a single undoable edit, "
										+ "which is much faster than one call per wire because Logisim repairs the whole "
										+ "circuit's wiring on every edit"))
						.opt("dry_run", Schemas.bool("Route and report, but draw nothing: the same segments, wire ids "
								+ "and warnings come back and the circuit is left untouched. Worth doing before a large "
								+ "batch, because a wire that runs through a port joins two nets"))
						.opt("from", Schemas.any("Start point, used with 'to'"))
						.opt("to", Schemas.any("End point, used with 'from'"))
						.opt("circuit", Schemas.string("Circuit to modify; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				session.requireHealthyWires(circuit);
				List<List<Location>> paths = new ArrayList<List<Location>>();
				boolean dryRun = ctx.optBool("dry_run", false);
				JsonArray rawPaths = ctx.optArray("paths");
				if (rawPaths != null) {
					int index = 0;
					for (JsonElement element : rawPaths) {
						if (!element.isJsonArray()) {
							throw new McpException("'paths[" + index + "]' must be a list of points");
						}
						paths.add(readPoints(ctx.session(), circuit, element.getAsJsonArray(),
								"'paths[" + index + "]'"));
						index++;
					}
					if (paths.isEmpty()) {
						throw new McpException("'paths' must contain at least one path");
					}
				} else {
					paths.add(readPath(ctx, circuit));
				}

				java.util.Set<Location> ports = session.portLocations(circuit);
				java.util.Set<Wire> existing = new java.util.HashSet<Wire>(circuit.getWires());
				// The wires as they are now, before this call adds any: an endpoint that only the
				// wire we are about to draw touches is still an endpoint that connects nothing.
				List<Wire> wiresBefore = new ArrayList<Wire>(circuit.getWires());
				CircuitMutation mutation = new CircuitMutation(circuit);
				JsonArray ids = new JsonArray();
				JsonArray throughPorts = new JsonArray();
				List<Wire> createdWires = new ArrayList<Wire>();
				int created = 0;

				for (List<Location> path : paths) {
					List<Location> expanded = expandPath(path, ports, existing);
					for (int i = 0; i + 1 < expanded.size(); i++) {
						Location a = expanded.get(i);
						Location b = expanded.get(i + 1);
						if (a.equals(b)) {
							continue;
						}
						if (a.getX() != b.getX() && a.getY() != b.getY()) {
							// must never happen: a non-axis-aligned wire cannot be iterated by Logisim
							throw new McpException("internal error: refusing to add the non-axis-aligned wire " + a
									+ " -> " + b);
						}
						Wire wire = Wire.create(a, b);
						if (existing.contains(wire)) {
							continue;
						}
						mutation.add(wire);
						existing.add(wire);
						createdWires.add(wire);
						ids.add(session.idOf(wire));
						created++;
						Location blocked = portInside(a, b, ports);
						if (blocked != null) {
							throughPorts.add("the segment " + a + " -> " + b + " (wire " + session.idOf(wire)
									+ ") passes through the port at " + blocked
									+ ", so the two nets are now connected. Remove just that segment with "
									+ "logisim_wire_remove {loc: [" + blocked.getX() + ", " + blocked.getY()
									+ "]}, or undo the whole call, which is one undo step");
						}
					}
				}
				if (!dryRun) {
					session.commit(mutation, "add wire");
					for (Wire wire : createdWires) {
						session.noteTouched(wire);
					}
				}
				// Endpoints that connect nothing: the mistake an agent makes when it works a
				// coordinate out by hand instead of asking for the port.
				Map<Location, Integer> endpoints = new java.util.LinkedHashMap<Location, Integer>();
				for (List<Location> path : paths) {
					countEndpoint(endpoints, path.get(0));
					if (path.size() > 1) {
						countEndpoint(endpoints, path.get(path.size() - 1));
					}
				}
				for (Map.Entry<Location, Integer> entry : endpoints.entrySet()) {
					warnIfConnectsNothing(session, circuit, ports, wiresBefore, entry.getKey(), throughPorts,
							entry.getValue().intValue() > 1);
				}

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("path_count", paths.size());
				ret.addProperty("segments_created", created);
				ret.add("wire_ids", ids);
				if (dryRun) {
					ret.addProperty("dry_run", Boolean.TRUE);
					ret.addProperty("dry_run_note", "nothing was changed and no wire id was assigned: this is the route "
							+ "the call would take, with the same warnings. Drop dry_run to draw it");
				}
				if (throughPorts.size() > 0) {
					ret.add("warnings", throughPorts);
				}
				return ret;
			}
		};
	}

	private static List<Location> readPath(ToolContext ctx, Circuit circuit) {
		JsonArray points = ctx.optArray("points");
		List<Location> ret = new ArrayList<Location>();
		if (points != null) {
			return readPoints(ctx.session(), circuit, points, "'points'");
		}
		if (!ctx.has("from") || !ctx.has("to")) {
			throw new McpException("give 'points' (a list of points), 'paths' (a list of such lists), or both "
					+ "'from' and 'to'");
		}
		ret.add(readPoint(ctx.session(), circuit, ctx.optElement("from"), "'from'"));
		ret.add(readPoint(ctx.session(), circuit, ctx.optElement("to"), "'to'"));
		if (ret.size() < 2) {
			throw new McpException("a wire needs at least two points");
		}
		return ret;
	}

	private static List<Location> readPoints(McpSession session, Circuit circuit, JsonArray points, String what) {
		if (points.size() > MAX_PATH_POINTS) {
			throw new McpException(what + " has " + points.size() + " entries; the maximum is " + MAX_PATH_POINTS);
		}
		List<Location> ret = new ArrayList<Location>();
		for (JsonElement element : points) {
			ret.add(readPoint(session, circuit, element, what + " entry"));
		}
		if (ret.size() < 2) {
			throw new McpException(what + " needs at least two points");
		}
		return ret;
	}

	/**
	 * Resolves one wire point, refusing a negative coordinate the agent wrote itself.
	 *
	 * <p>
	 * A symbolic endpoint ("A", "FA0.Cout") is exempt: it names a port that already
	 * exists, and refusing to wire to it because the user's own circuit happens to sit
	 * partly off the canvas would be wrong. The rule is about the agent's own arithmetic,
	 * which is what lands work out of sight.
	 * </p>
	 */
	private static Location readPoint(McpSession session, Circuit circuit, JsonElement element, String what) {
		Location loc = session.resolvePoint(circuit, element);
		if (isCoordinateLiteral(element) && (loc.getX() < 0 || loc.getY() < 0)) {
			throw new McpException(McpInstructions.negativeCoordinate(what, loc.getX(), loc.getY()));
		}
		return loc;
	}

	/** True when the agent gave numbers rather than the name of an existing port. */
	private static boolean isCoordinateLiteral(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return false;
		}
		if (element.isJsonArray() || element.isJsonObject()) {
			return true;
		}
		return element.isJsonPrimitive() && McpSession.tryParseLocation(element.getAsString()) != null;
	}

	/**
	 * Refuses a component whose position, or any part of its body, would be at a negative
	 * coordinate - off the canvas the user is looking at.
	 */
	private static void requireOnCanvas(Component component, String what) {
		Location loc = component.getLocation();
		if (loc.getX() < 0 || loc.getY() < 0) {
			throw new McpException(McpInstructions.negativeCoordinate(what, loc.getX(), loc.getY()));
		}
		com.cburch.logisim.data.Bounds bounds = component.getBounds();
		if (bounds.getX() < 0 || bounds.getY() < 0) {
			throw new McpException(McpInstructions.negativeCoordinate(what, bounds.getX(), bounds.getY(),
					"This component is " + bounds.getWidth() + " by " + bounds.getHeight()
							+ " and is anchored at its centre, so give x = " + (loc.getX() - bounds.getX())
							+ " and y = " + (loc.getY() - bounds.getY()) + " instead."));
		}
	}

	/**
	 * Turns any diagonal step into axis aligned steps.
	 *
	 * <p>
	 * A corner is inserted automatically, but only where it is harmless: a wire that
	 * ran along the far side of a component would pass straight through that
	 * component's other ports, and because Logisim splits a wire wherever another
	 * component has a port, such a route silently short circuits the ports together.
	 * So the two single corner routes are tried, then Z shaped routes through lanes
	 * that are clear of every port, and only if none of those work does the agent get
	 * asked for an explicit route. A broken circuit is never produced silently.
	 * </p>
	 */
	private static List<Location> expandPath(List<Location> path, java.util.Set<Location> ports,
			java.util.Set<Wire> existing) {
		List<Location> ret = new ArrayList<Location>();
		List<Wire> planned = new ArrayList<Wire>();
		ret.add(path.get(0));
		for (int i = 0; i + 1 < path.size(); i++) {
			Location a = path.get(i);
			Location b = path.get(i + 1);
			ret.addAll(routeStep(a, b, ports, existing, planned));
			ret.add(b);
		}
		return ret;
	}

	/** The intermediate points needed to get from {@code a} to {@code b} cleanly. */
	private static List<Location> routeStep(Location a, Location b, java.util.Set<Location> ports,
			java.util.Set<Wire> existing, List<Wire> planned) {
		List<Location> mid = new ArrayList<Location>();
		if (a.getX() == b.getX() || a.getY() == b.getY()) {
			planned.add(Wire.create(a, b));
			return mid;
		}
		Location[] corners = { Location.create(b.getX(), a.getY()), Location.create(a.getX(), b.getY()) };
		for (Location corner : corners) {
			if (clear(a, corner, ports, existing, planned) && clear(corner, b, ports, existing, planned)) {
				planned.add(Wire.create(a, corner));
				planned.add(Wire.create(corner, b));
				mid.add(corner);
				return mid;
			}
		}
		for (int lane : laneCandidates(ports, a, b)) {
			Location p1 = Location.create(lane, a.getY());
			Location p2 = Location.create(lane, b.getY());
			if (clear(a, p1, ports, existing, planned) && clear(p1, p2, ports, existing, planned)
					&& clear(p2, b, ports, existing, planned)) {
				planned.add(Wire.create(a, p1));
				planned.add(Wire.create(p1, p2));
				planned.add(Wire.create(p2, b));
				mid.add(p1);
				mid.add(p2);
				return mid;
			}
			Location q1 = Location.create(a.getX(), lane);
			Location q2 = Location.create(b.getX(), lane);
			if (clear(a, q1, ports, existing, planned) && clear(q1, q2, ports, existing, planned)
					&& clear(q2, b, ports, existing, planned)) {
				planned.add(Wire.create(a, q1));
				planned.add(Wire.create(q1, q2));
				planned.add(Wire.create(q2, b));
				mid.add(q1);
				mid.add(q2);
				return mid;
			}
		}
		throw new McpException("cannot route from " + a + " to " + b + " without either running through a "
				+ "component port or overlapping an existing wire (both of which would connect them); give an "
				+ "explicit 'points' list with a steering point, for example [[" + a.getX() + "," + a.getY()
				+ "],[" + (a.getX() + 20) + "," + a.getY() + "],[" + (a.getX() + 20) + "," + b.getY() + "],["
				+ b.getX() + "," + b.getY() + "]]");
	}

	/**
	 * True when a segment can be drawn without touching another port along the way and
	 * without lying on top of an existing wire. Logisim merges collinear overlapping
	 * wires, so such a segment would silently connect two nets.
	 */
	private static boolean clear(Location from, Location to, java.util.Set<Location> ports,
			java.util.Set<Wire> existing, List<Wire> planned) {
		if (from.equals(to)) {
			return true;
		}
		if (portInside(from, to, ports) != null) {
			return false;
		}
		Wire candidate = Wire.create(from, to);
		if (existing.contains(candidate)) {
			return true; // already there: adding it again changes nothing
		}
		for (Wire wire : existing) {
			if (candidate.overlaps(wire, false)) {
				return false;
			}
		}
		for (Wire wire : planned) {
			if (candidate.overlaps(wire, false)) {
				return false;
			}
		}
		return true;
	}


	/** Lanes just outside the region occupied by any port, tried in order. */
	private static int[] laneCandidates(java.util.Set<Location> ports, Location a, Location b) {
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (Location port : ports) {
			minX = Math.min(minX, port.getX());
			maxX = Math.max(maxX, port.getX());
			minY = Math.min(minY, port.getY());
			maxY = Math.max(maxY, port.getY());
		}
		java.util.TreeSet<Integer> lanes = new java.util.TreeSet<Integer>();
		if (!ports.isEmpty()) {
			lanes.add(Integer.valueOf(maxX + 20));
			lanes.add(Integer.valueOf(minX - 20));
			lanes.add(Integer.valueOf(maxY + 20));
			lanes.add(Integer.valueOf(minY - 20));
		}
		lanes.add(Integer.valueOf(Math.max(a.getX(), b.getX()) + 20));
		lanes.add(Integer.valueOf(Math.min(a.getX(), b.getX()) - 20));
		lanes.add(Integer.valueOf(Math.max(a.getY(), b.getY()) + 20));
		lanes.add(Integer.valueOf(Math.min(a.getY(), b.getY()) - 20));
		int[] ret = new int[lanes.size()];
		int i = 0;
		for (Integer lane : lanes) {
			ret[i++] = lane.intValue();
		}
		return ret;
	}

	/**
	 * The location of a port strictly inside a segment, or null when the segment is
	 * clear. Only the interior counts: a wire is supposed to end on a port.
	 */
	static Location portInside(Location from, Location to, java.util.Set<Location> ports) {
		if (ports.isEmpty()) {
			return null;
		}
		int x0 = Math.min(from.getX(), to.getX());
		int x1 = Math.max(from.getX(), to.getX());
		int y0 = Math.min(from.getY(), to.getY());
		int y1 = Math.max(from.getY(), to.getY());
		for (Location port : ports) {
			int px = port.getX();
			int py = port.getY();
			if (x0 == x1 && px == x0 && py > y0 && py < y1) {
				return port;
			}
			if (y0 == y1 && py == y0 && px > x0 && px < x1) {
				return port;
			}
		}
		return null;
	}

	public static McpTool removeWire(final McpSession session) {
		return new AbstractTool("logisim_wire_remove", McpInstructions.WIRE_REMOVE,
				Schemas.object()
						.opt("wire", Schemas.componentRef("The wire to remove."))
						.opt("loc", Schemas.any("Remove every wire segment that passes through this point. Use this "
								+ "when Logisim has split a wire at a port and the original 'from'/'to' no longer describes "
								+ "a real segment, which is what happens after a wire runs through a port by mistake"))
						.opt("from", Schemas.any("Start point of the segment, used with 'to'"))
						.opt("to", Schemas.any("End point of the segment, used with 'from'"))
						.opt("circuit", Schemas.string("Circuit to modify; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				if (ctx.has("loc")) {
					Location at = ctx.session().resolvePoint(circuit, ctx.optElement("loc"));
					List<Wire> hits = new ArrayList<Wire>();
					for (Wire candidate : circuit.getWires()) {
						if (onSegment(candidate.getEnd0(), candidate.getEnd1(), at)) {
							hits.add(candidate);
						}
					}
					if (hits.isEmpty()) {
						throw new McpException("no wire of " + circuit.getName() + " passes through " + at
								+ "; call logisim_circuit_describe to list the wires, with their ids");
					}
					session.requireHealthyWires(circuit);
					CircuitMutation mutation = new CircuitMutation(circuit);
					JsonArray removed = new JsonArray();
					for (Wire hit : hits) {
						mutation.remove(hit);
						removed.add(session.idOf(hit));
					}
					session.commit(mutation, "remove wire at " + at);
					JsonObject ret = new JsonObject();
					ret.addProperty("circuit", circuit.getName());
					ret.addProperty("loc", at.toString());
					ret.addProperty("wires_removed", Integer.valueOf(hits.size()));
					ret.add("wire_ids", removed);
					return ret;
				}
				Wire wire = null;
				if (ctx.has("wire")) {
					Component component = session.requireComponent(circuit, ctx.optElement("wire"));
					if (!(component instanceof Wire)) {
						throw new McpException("the referenced component is not a wire; use "
								+ "logisim_component_remove for components");
					}
					wire = (Wire) component;
				} else if (ctx.has("from") && ctx.has("to")) {
					Location a = ctx.session().resolvePoint(circuit, ctx.optElement("from"));
					Location b = ctx.session().resolvePoint(circuit, ctx.optElement("to"));
					Wire candidate = Wire.create(a, b);
					if (circuit.contains(candidate)) {
						wire = candidate;
					}
				} else {
					throw new McpException("give either 'wire' or both 'from' and 'to'");
				}
				if (wire == null) {
					throw new McpException("no wire found for that reference; call logisim_circuit_describe to list "
							+ "the wires of " + circuit.getName());
				}
				if (!session.skewedWires(circuit).contains(wire)) {
					session.requireHealthyWires(circuit);
				}
				String id = session.idOf(wire);
				CircuitMutation mutation = new CircuitMutation(circuit);
				mutation.remove(wire);
				session.commit(mutation, "remove wire");
				return Json.object("removed", id, "from", McpSession.point(wire.getEnd0()), "to",
						McpSession.point(wire.getEnd1()));
			}
		};
	}
}
