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
		return new AbstractTool("logisim_component_add",
				"Places one component (gate, pin, flip-flop, multiplexer, memory, subcircuit, ...) at a position in "
						+ "a circuit. Returns the new component's session id, its ports and their positions. "
						+ "Components are not connected by placement: add wires with logisim_wire_add so that a wire "
						+ "endpoint lands exactly on a port position. Use 'label' to name pins and signals, "
						+ "'facing' to orient the component, and 'width' for bus width. A new Pin is tristate, so an "
						+ "undriven input reads as x until you set it; pass attributes {\"tristate\": false} if an "
						+ "undriven input should read 0 instead. IMPORTANT: keep x and y multiples of 10. A port placed "
						+ "within 2 units of an existing wire makes Logisim split that wire into a diagonal wire, which "
						+ "makes every further edit and every later load of the file hang until the Java heap is exhausted.",
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
						.opt("circuit", Schemas.string("Circuit to add into; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				session.requireHealthyWires(circuit);
				ComponentFactory factory = session.factoryFor(ctx.optString("lib", null), ctx.requireString("type"));
				AttributeSet attrs = factory.createAttributeSet();

				JsonObject extra = ctx.optObject("attributes");
				if (extra != null) {
					for (Map.Entry<String, JsonElement> entry : extra.entrySet()) {
						applyAttribute(session, attrs, factory, entry.getKey(), entry.getValue());
					}
				}
				if (ctx.has("label")) {
					applyAttribute(session, attrs, factory, StdAttr.LABEL.getName(),
							new JsonPrimitive(ctx.requireString("label")));
				}
				if (ctx.has("facing")) {
					applyAttribute(session, attrs, factory, StdAttr.FACING.getName(),
							new JsonPrimitive(ctx.requireString("facing")));
				}
				if (ctx.has("width")) {
					applyAttribute(session, attrs, factory, StdAttr.WIDTH.getName(),
							new JsonPrimitive(Integer.valueOf(ctx.requireInt("width"))));
				}

				Location loc = Location.create(ctx.requireInt("x"), ctx.requireInt("y"));
				Component component = factory.createComponent(loc, attrs);
				CircuitMutation mutation = new CircuitMutation(circuit);
				mutation.add(component);
				session.commit(mutation, "add " + factory.getName());

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
				return ret;
			}
		};
	}

	/** True when nothing about a segment would connect two unintended nets. */
	static boolean isClear(Location from, Location to, java.util.Set<Location> ports) {
		return portInside(from, to, ports) == null;
	}
	private static void applyAttribute(McpSession session, AttributeSet attrs, ComponentFactory factory, String name,
			JsonElement value) {
		Attribute<?> attribute = attrs.getAttribute(name);
		if (attribute == null) {
			throw new McpException("a " + factory.getName() + " has no attribute '" + name + "'; its attributes are: "
					+ CatalogTools.attributeNames(attrs));
		}
		if (attrs.isReadOnly(attribute)) {
			throw new McpException("attribute '" + name + "' of a " + factory.getName() + " is read only");
		}
		setValue(attrs, attribute, session.attributeFromJson(attrs, attribute, value));
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static void setValue(AttributeSet attrs, Attribute attribute, Object value) {
		attrs.setValue(attribute, value);
	}

	public static McpTool removeComponent(final McpSession session) {
		return new AbstractTool("logisim_component_remove",
				"Removes a component or a single wire from a circuit. Attached wires are left in place, so they may "
						+ "end up dangling; remove them separately with logisim_wire_remove if that is not wanted.",
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
		return new AbstractTool("logisim_component_move",
				"Moves a component to a new position, given either absolute 'x'/'y' or a relative 'dx'/'dy'. "
						+ "Logisim does not drag wires along, so any wire that ended on the component's old port "
						+ "positions will be left behind: the result reports how many, and you should re-add those "
						+ "connections with logisim_wire_add. Consider removing and re-adding a component instead if "
						+ "it is already wired.",
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
				CircuitMutation mutation = new CircuitMutation(circuit);
				mutation.replace(component, replacement);
				session.commit(mutation, "move " + component.getFactory().getName());
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
		return new AbstractTool("logisim_component_set_attributes",
				"Changes one or more attributes of an existing component, for example renaming a pin (label), "
						+ "switching a pin between input and output (output = true/false), widening a bus (width), or "
						+ "changing a memory's address size. Attribute names and legal values are listed by "
						+ "logisim_component_catalog.",
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
				JsonObject ret = session.describeComponent(component, true, true);
				ret.add("updated", applied);
				return ret;
			}
		};
	}

	public static McpTool addWire(final McpSession session) {
		return new AbstractTool("logisim_wire_add",
				"Draws wires, which are what actually connect component ports. Give a list of points in 'points'; "
						+ "consecutive points must share either the x or the y coordinate, and a diagonal step between "
						+ "two points is replaced by a right angled route automatically (choosing whichever corner does "
						+ "not run through another component's port). Alternatively give just 'from' and 'to' for a single "
						+ "connection, or 'paths' to draw many connections in one undoable edit. A wire endpoint must "
						+ "coincide exactly with a port position (see the "
						+ "'ports' entries of logisim_component_add / logisim_circuit_describe) for the connection to "
						+ "count; use symbolic endpoints such as \"A\" or \"FA0.Cout\" to avoid coordinate arithmetic. "
						+ "Logisim splits wires automatically where a third wire meets them. IMPORTANT: never try to "
						+ "produce a wire whose ends differ in both x and y - Logisim cannot iterate such a wire and the "
						+ "whole session then hangs until the Java heap is exhausted. Let this tool route, keep coordinates "
						+ "on the 10 pixel grid, and give an explicit steering point rather than forcing an alignment that "
						+ "the router refuses.",
				Schemas.object()
						.opt("points", Schemas.array(Schemas.any("a point: [x, y], or a name such as \"A\" (pin or "
								+ "component label), \"c3\" (component id) or \"c3.1\" (component id and port index) - "
								+ "coordinates prefer the first form"),
								"The path to draw, in order; at least two points"))
						.opt("paths", Schemas.array(Schemas.any("a list of points, as for 'points'"),
								"Several paths to draw in one call. All of them are committed as a single undoable edit, "
										+ "which is much faster than one call per wire because Logisim repairs the whole "
										+ "circuit's wiring on every edit"))
						.opt("from", Schemas.any("Start point, used with 'to'"))
						.opt("to", Schemas.any("End point, used with 'from'"))
						.opt("circuit", Schemas.string("Circuit to modify; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				session.requireHealthyWires(circuit);
				List<List<Location>> paths = new ArrayList<List<Location>>();
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
				CircuitMutation mutation = new CircuitMutation(circuit);
				JsonArray ids = new JsonArray();
				JsonArray throughPorts = new JsonArray();
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
						ids.add(session.idOf(wire));
						created++;
						Location blocked = portInside(a, b, ports);
						if (blocked != null) {
							throughPorts.add("the segment " + a + " -> " + b + " passes through the port at " + blocked
									+ ", so the two nets are now connected");
						}
					}
				}
				session.commit(mutation, "add wire");

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("path_count", paths.size());
				ret.addProperty("segments_created", created);
				ret.add("wire_ids", ids);
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
		ret.add(ctx.session().resolvePoint(circuit, ctx.optElement("from")));
		ret.add(ctx.session().resolvePoint(circuit, ctx.optElement("to")));
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
			ret.add(session.resolvePoint(circuit, element));
		}
		if (ret.size() < 2) {
			throw new McpException(what + " needs at least two points");
		}
		return ret;
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
		return new AbstractTool("logisim_wire_remove",
				"Removes one wire segment, either by its id (as reported by logisim_circuit_describe) or by giving "
						+ "its exact 'from' and 'to' points.",
				Schemas.object()
						.opt("wire", Schemas.componentRef("The wire to remove."))
						.opt("from", Schemas.any("Start point of the segment, used with 'to'"))
						.opt("to", Schemas.any("End point of the segment, used with 'from'"))
						.opt("circuit", Schemas.string("Circuit to modify; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
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
