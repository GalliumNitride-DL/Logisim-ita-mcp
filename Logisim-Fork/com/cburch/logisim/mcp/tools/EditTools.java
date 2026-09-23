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

	/** How many components one batch call may place, copy or move. */
	private static final int MAX_BATCH = 200;

	/** How many joins one wire call describes before it summarises the rest. */
	private static final int MAX_JOINS = 20;

	/** Logisim's canvas grid, in pixels. Its own wire repair assumes it. */
	private static final int GRID = 10;

	/** What to do with a coordinate that is not on the grid. */
	private static final int GRID_FAIL = 0;
	private static final int GRID_WARN = 1;
	private static final int GRID_ALLOW = 2;

	private EditTools() {
	}

	/**
	 * The 'on_off_grid' argument.
	 *
	 * <p>
	 * An off-grid coordinate is how the worst damage starts: Logisim's wire repair accepts a
	 * point up to 2 units off a wire's line, splits the wire there and keeps that off-line
	 * location as an end, which leaves a wire whose ends differ in both x and y - and Logisim
	 * cannot iterate such a wire, so every later edit and every attempt to load the saved
	 * file spins until the heap is gone.
	 * </p>
	 *
	 * <p>
	 * A wire point defaults to 'fail', because a coordinate the agent wrote is one it can
	 * correct immediately. A component position defaults to 'warn' in the callers that place
	 * things, because there are legitimate reasons to sit off the grid and the damage only
	 * happens when a port lands near existing wiring.
	 * </p>
	 */
	private static int gridPolicy(ToolContext ctx, int fallback) {
		String mode = ctx.optString("on_off_grid", null);
		if (mode == null) {
			return fallback;
		}
		if ("fail".equalsIgnoreCase(mode)) {
			return GRID_FAIL;
		}
		if ("warn".equalsIgnoreCase(mode)) {
			return GRID_WARN;
		}
		if ("allow".equalsIgnoreCase(mode) || "ignore".equalsIgnoreCase(mode)) {
			return GRID_ALLOW;
		}
		throw new McpException("'on_off_grid' is 'fail', 'warn' or 'allow'; '" + mode + "' does not fit that");
	}

	private static boolean onGrid(int value) {
		return value % GRID == 0;
	}

	private static int nearestGrid(int value) {
		return Math.round(value / (float) GRID) * GRID;
	}

	/** What to say about an off-grid position, naming the grid point beside it. */
	private static String offGridMessage(String what, Location loc) {
		return what + " at " + loc + " is not on Logisim's " + GRID + " pixel grid. Logisim splits a wire that "
				+ "passes within 2 units of a port and keeps the off-line point, so a coordinate like this can leave "
				+ "a wire whose ends differ in both x and y, which Logisim cannot iterate at all; from then on the "
				+ "circuit and the file it is saved in are unusable. Use (" + nearestGrid(loc.getX()) + ", "
				+ nearestGrid(loc.getY()) + "), or pass on_off_grid: \"warn\" to keep this position anyway";
	}

	/** Refuses, reports or ignores an off-grid position, according to the policy. */
	private static void applyGrid(int policy, String what, Location loc, JsonArray warnings) {
		if (policy == GRID_ALLOW || (onGrid(loc.getX()) && onGrid(loc.getY()))) {
			return;
		}
		String message = offGridMessage(what, loc);
		if (policy == GRID_FAIL) {
			throw new McpException(message);
		}
		if (warnings != null) {
			warnings.add(message);
		}
	}

	/**
	 * Refuses a position that would make Logisim split a wire off its own line.
	 *
	 * <p>
	 * Logisim accepts a point within 2 units of a wire's line as lying on that wire
	 * ({@code Wire.contains}), and its wire repair splits the wire there and keeps the off-line
	 * point as an end. The result is a wire whose ends differ in both x and y, which nothing
	 * can iterate: from then on every edit, every undo and every attempt to load the saved
	 * file runs until the heap is exhausted. Reproducing it took one call - a wire ending 2
	 * units above an existing wire - and the damage survived the automatic repair, because the
	 * next transaction split the wire again.
	 * </p>
	 *
	 * <p>
	 * So this is refused whatever {@code on_off_grid} says: the tool can see the exact geometry
	 * that will break the file, and there is no way to put it back. The message names the wire,
	 * because that is the thing to move or to join properly.
	 * </p>
	 */
	private static void requireNotInexactlyOnAWire(Circuit circuit, Location loc, String what) {
		if (onGrid(loc.getX()) && onGrid(loc.getY())) {
			return;
		}
		for (Wire wire : circuit.getWires()) {
			if (!wire.contains(loc) || onSegment(wire.getEnd0(), wire.getEnd1(), loc)) {
				// not near it, or exactly on it: an exact landing point is an ordinary connection
				continue;
			}
			throw new McpException(what + " at " + loc + " is off the " + GRID + " pixel grid and within 2 units "
					+ "of the wire " + wire.getEnd0() + " -> " + wire.getEnd1() + ", but not exactly on it. Logisim "
					+ "would split that wire here and keep this off-line point, which leaves a wire whose ends differ "
					+ "in both x and y - nothing can iterate it, so every later edit, every undo and every attempt to "
					+ "load the saved file runs until the Java heap is exhausted. This is refused whatever "
					+ "on_off_grid says. Put the point on the grid (" + nearestGrid(loc.getX()) + ", "
					+ nearestGrid(loc.getY()) + "), or move the wire it is near");
		}
	}

	/**
	 * Refuses to place a circuit inside itself.
	 *
	 * <p>
	 * Logisim's own editor checks this when a subcircuit is dropped on the canvas - AddTool
	 * asks Dependencies.canAdd before it puts anything down - but an edit made through the API
	 * went straight into the circuit. A circuit that contains itself cannot be simulated: the
	 * propagator recurses through the subcircuit until the stack overflows, the call never
	 * returns, and the session stays unusable until the server is restarted. The check below
	 * is Logisim's own, so this refuses exactly what the editor refuses.
	 * </p>
	 */
	private static void requireNotCircular(McpSession session, Circuit circuit, ComponentFactory factory) {
		if (!(factory instanceof com.cburch.logisim.circuit.SubcircuitFactory)) {
			return;
		}
		Circuit sub = ((com.cburch.logisim.circuit.SubcircuitFactory) factory).getSubcircuit();
		if (!session.dependencies().canAdd(circuit, sub)) {
			throw new McpException("circuit '" + sub.getName() + "' cannot go inside circuit '" + circuit.getName()
					+ "': it is that circuit, or it already contains it. Logisim's own editor refuses this, because "
					+ "a circuit that contains itself cannot be simulated - the propagator recurses until the stack "
					+ "overflows and the session hangs. Put the instance in another circuit, or draw the logic here");
		}
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
						.opt("on_off_grid", Schemas.stringEnum("What to do when x or y is not a multiple of 10: "
								+ "'warn' (default) places it and says so, 'fail' refuses, 'allow' says nothing",
								"fail", "warn", "allow"))
						.opt("dry_run", Schemas.bool("Validate and report, but place nothing: the same ports, warnings "
								+ "and errors come back, and the circuit is left untouched"))
						.opt("circuit", Schemas.string("Circuit to add into; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				session.requireHealthyWires(circuit);
				boolean dryRun = ctx.optBool("dry_run", false);
				List<String> ignored = new ArrayList<String>();
				JsonArray warnings = new JsonArray();
				CircuitMutation mutation = new CircuitMutation(circuit);
				Component component = placeOne(session, circuit, mutation, ctx, ignored, warnings,
						gridPolicy(ctx, GRID_WARN));
				if (!dryRun) {
					session.commit(mutation, "add " + component.getFactory().getName());
					session.noteTouched(component);
				}

				JsonObject ret = session.describeComponent(component, true, true);
				ret.addProperty("circuit", circuit.getName());
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
					ret.addProperty("ignored_note", "these attributes do not exist on a "
							+ component.getFactory().getName()
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

	/**
	 * Records that new wiring joins an existing net, naming the net, its labels and how many
	 * ports drive it: the answer to "what am I about to connect together?".
	 */
	private static void addJoin(McpSession session, Circuit circuit, JsonArray joins, java.util.Set<String> seen,
			Location point, Wire wire, String why) {
		String key = point + "|" + session.idOf(wire);
		if (!seen.add(key) || joins.size() >= MAX_JOINS) {
			return;
		}
		JsonObject join = new JsonObject();
		join.addProperty("point", point.toString());
		join.addProperty("wire", session.idOf(wire));
		JsonObject net = session.describeNetAt(circuit, point);
		if (net != null) {
			join.add("net", net);
		}
		join.addProperty("note", why + ", so from now on they are one net");
		joins.add(join);
	}

	/**
	 * Builds one component from a description - the arguments logisim_component_add takes -
	 * and adds it to the mutation.
	 *
	 * <p>
	 * The single tool and the batch tool both come through here, so a component placed in a
	 * batch cannot behave differently from one placed on its own.
	 * </p>
	 *
	 * @param ignored
	 *            collects the names of attributes this component does not have
	 * @param warnings
	 *            collects an off-grid position, and anything else worth saying at once
	 * @return the component, already added to {@code mutation} (nothing is committed here)
	 */
	private static Component placeOne(McpSession session, Circuit circuit, CircuitMutation mutation, ToolContext spec,
			List<String> ignored, JsonArray warnings, int policy) {
		ComponentFactory factory = session.factoryFor(spec.optString("lib", null), spec.requireString("type"));
		AttributeSet attrs = factory.createAttributeSet();
		boolean lenient = !"fail".equalsIgnoreCase(spec.optString("on_unknown_attribute", "warn"));

		JsonObject extra = spec.optObject("attributes");
		JsonElement ranges = extra == null ? null : extra.get("bit_ranges");
		if (ranges != null && ranges.isJsonObject()) {
			expandBitRanges(session, attrs, factory, ranges.getAsJsonObject(), ignored, lenient);
			extra.remove("bit_ranges");
		}
		if (extra != null) {
			for (Map.Entry<String, JsonElement> entry : extra.entrySet()) {
				String ignoredName = applyAttribute(session, attrs, factory, entry.getKey(), entry.getValue(), lenient);
				if (ignoredName != null) {
					ignored.add(ignoredName);
				}
			}
		}
		if (spec.has("label")) {
			String ignoredName = applyAttribute(session, attrs, factory, StdAttr.LABEL.getName(),
					new JsonPrimitive(spec.requireString("label")), lenient);
			if (ignoredName != null) {
				ignored.add("label (this component has no label)");
			}
		}
		if (spec.has("facing")) {
			String ignoredName = applyAttribute(session, attrs, factory, StdAttr.FACING.getName(),
					new JsonPrimitive(spec.requireString("facing")), lenient);
			if (ignoredName != null) {
				ignored.add("facing (this component has a fixed orientation)");
			}
		}
		if (spec.has("width")) {
			String ignoredName = applyAttribute(session, attrs, factory, StdAttr.WIDTH.getName(),
					new JsonPrimitive(Integer.valueOf(spec.requireInt("width"))), lenient);
			if (ignoredName != null) {
				ignored.add("width (this component has a fixed width)");
			}
		}

		Location loc = Location.create(spec.requireInt("x"), spec.requireInt("y"));
		requireNotCircular(session, circuit, factory);
		Component component = factory.createComponent(loc, attrs);
		requireOnCanvas(component, "the position of the new " + factory.getName());
		applyGrid(policy, "the position of the new " + factory.getName(), loc, warnings);
		// A port that lands within 2 units of a wire but not on it is the same hazard as an
		// off-grid wire point: Logisim splits the wire there and leaves a diagonal behind.
		// Only that case is refused - a port merely sitting off the grid harms nothing on its
		// own, and some components have port offsets that are not multiples of ten.
		int portIndex = 0;
		for (com.cburch.logisim.comp.EndData end : component.getEnds()) {
			if (end != null) {
				Location port = end.getLocation();
				requireNotInexactlyOnAWire(circuit, port,
						"port " + portIndex + " (" + port + ") of the new " + factory.getName());
			}
			portIndex++;
		}
		mutation.add(component);
		return component;
	}

	/**
	 * Many components in one call, committed as one undoable edit.
	 *
	 * <p>
	 * This is the component side twin of logisim_wire_add's 'paths'. A circuit of seventy
	 * components built one call at a time costs seventy replies, each carrying a full
	 * attribute table, a port list and a warning that its ports are not connected yet; here
	 * the same work is one call, one undo step and one compact result.
	 * </p>
	 */
	public static McpTool addMany(final McpSession session) {
		return new AbstractTool("logisim_component_add_many", McpInstructions.COMPONENT_ADD_MANY,
				Schemas.object()
						.req("components", Schemas.array(Schemas.freeObject("one component: {type, x, y, lib?, label?, "
								+ "facing?, width?, attributes?} - the arguments logisim_component_add takes, for one "
								+ "component each"),
								"The components to place, in order; all of them are one undoable edit"))
						.opt("include_ports", Schemas.bool("Report each new component's ports (default true); false "
								+ "returns just the ids, which is much smaller"))
						.opt("on_unknown_attribute", Schemas.stringEnum("'warn' (default) skips an attribute the "
								+ "component does not have and lists it, 'fail' refuses the call", "warn", "fail"))
						.opt("on_off_grid", Schemas.stringEnum("'fail', 'warn' (default) or 'allow' for a component "
								+ "whose x or y is not a multiple of 10", "fail", "warn", "allow"))
						.opt("dry_run", Schemas.bool("Report what would be placed, and change nothing"))
						.opt("circuit", Schemas.string("Circuit to add into; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				session.requireHealthyWires(circuit);
				JsonArray specs = ctx.requireArray("components");
				if (specs.size() == 0) {
					throw new McpException("'components' is empty; give at least one component");
				}
				if (specs.size() > MAX_BATCH) {
					throw new McpException("'components' has " + specs.size() + " entries; the maximum is " + MAX_BATCH
							+ ". Split it into several calls; each one is still a single undo step");
				}
				boolean dryRun = ctx.optBool("dry_run", false);
				boolean includePorts = ctx.optBool("include_ports", true);
				int policy = gridPolicy(ctx, GRID_WARN);
				CircuitMutation mutation = new CircuitMutation(circuit);
				List<Component> added = new ArrayList<Component>();
				JsonArray placed = new JsonArray();
				int index = 0;
				for (JsonElement element : specs) {
					index++;
					if (!element.isJsonObject()) {
						throw new McpException("'components[" + (index - 1) + "]' must be an object such as "
								+ "{\"type\": \"Tunnel\", \"x\": 300, \"y\": 90, \"label\": \"A\"}");
					}
					ToolContext spec = new ToolContext(session, ctx.toolName(), element.getAsJsonObject());
					List<String> ignored = new ArrayList<String>();
					JsonArray warnings = new JsonArray();
					Component component;
					try {
						component = placeOne(session, circuit, mutation, spec, ignored, warnings, policy);
					} catch (McpException e) {
						throw new McpException("component " + index + " of " + specs.size() + ": " + e.getMessage());
					}
					added.add(component);
					JsonObject item = new JsonObject();
					item.addProperty("index", Integer.valueOf(index));
					item.addProperty("id", session.idOf(component));
					item.addProperty("type", component.getFactory().getName());
					item.add("loc", Json.array(Integer.valueOf(component.getLocation().getX()),
							Integer.valueOf(component.getLocation().getY())));
					if (includePorts) {
						item.add("ports", session.describeComponent(component, false, true).get("ports"));
					}
					if (ignored.size() > 0) {
						item.add("ignored_attributes", stringArray(ignored));
					}
					if (warnings.size() > 0) {
						item.add("warnings", warnings);
					}
					placed.add(item);
				}
				if (!dryRun) {
					session.commit(mutation, "add " + added.size() + " components");
					for (Component component : added) {
						session.noteTouched(component);
					}
				}

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("placed_count", Integer.valueOf(added.size()));
				ret.add("placed", placed);
				ret.addProperty("hint", "wire to a port by its 'ref' (for example " + session.idOf(added.get(0)) + ".0). "
						+ "Nothing is connected yet, which is why no unconnected-port warning is listed per component: "
						+ "check the wiring with logisim_circuit_nets once it is drawn");
				if (dryRun) {
					ret.addProperty("dry_run", Boolean.TRUE);
					ret.addProperty("dry_run_note", "nothing was changed and no id was assigned: this is what the "
							+ "call would place, with the same warnings. Drop dry_run to do it");
				}
				return ret;
			}
		};
	}

	/**
	 * Copies a component (or a wire) some number of times, each copy offset by a fixed step.
	 *
	 * <p>
	 * The pattern an agent hits most is a row or a column of the same thing: a bank of tunnels,
	 * a row of gates, a set of pins. Drawn one at a time that is one call each; this is one
	 * call and one undo step.
	 * </p>
	 */
	public static McpTool duplicateComponent(final McpSession session) {
		return new AbstractTool("logisim_component_duplicate", McpInstructions.COMPONENT_DUPLICATE,
				Schemas.object()
						.req("component", Schemas.componentRef("The component or wire to copy."))
						.req("dx", Schemas.integer("How far right of the original each copy sits"))
						.req("dy", Schemas.integer("How far below the original each copy sits"))
						.opt("count", Schemas.integer("How many copies to make (default 1, maximum " + MAX_BATCH + ")"))
						.opt("attributes", Schemas.freeObject("Attributes to change on the copies, by name; the rest "
								+ "is copied as it is. Useful for a row of tunnels, which need different labels"))
						.opt("include_ports", Schemas.bool("Report each copy's ports (default true)"))
						.opt("on_off_grid", Schemas.stringEnum("'fail', 'warn' (default) or 'allow' for a copy whose "
								+ "x or y is not a multiple of 10", "fail", "warn", "allow"))
						.opt("dry_run", Schemas.bool("Report what would be copied, and change nothing"))
						.opt("circuit", Schemas.string("Circuit to modify; defaults to the current circuit"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				session.requireHealthyWires(circuit);
				Component source = session.requireComponent(circuit, ctx.optElement("component"));
				int dx = ctx.requireInt("dx");
				int dy = ctx.requireInt("dy");
				int count = ctx.optInt("count", Integer.valueOf(1)).intValue();
				if (count < 1 || count > MAX_BATCH) {
					throw new McpException("'count' must be between 1 and " + MAX_BATCH);
				}
				if (dx == 0 && dy == 0) {
					throw new McpException("'dx' and 'dy' are both zero, so every copy would sit exactly on the "
							+ "original and on each other");
				}
				boolean dryRun = ctx.optBool("dry_run", false);
				boolean includePorts = ctx.optBool("include_ports", true);
				int policy = gridPolicy(ctx, GRID_WARN);
				JsonObject changes = ctx.optObject("attributes");
				CircuitMutation mutation = new CircuitMutation(circuit);
				JsonArray copied = new JsonArray();
				JsonArray warnings = new JsonArray();
				Location origin = source.getLocation();

				for (int k = 1; k <= count; k++) {
					Location loc = Location.create(origin.getX() + k * dx, origin.getY() + k * dy);
					JsonObject item = new JsonObject();
					item.addProperty("copy", Integer.valueOf(k));
					item.add("loc", Json.array(Integer.valueOf(loc.getX()), Integer.valueOf(loc.getY())));
					if (source instanceof Wire) {
						Wire wire = (Wire) source;
						Location a = Location.create(wire.getEnd0().getX() + k * dx, wire.getEnd0().getY() + k * dy);
						Location b = Location.create(wire.getEnd1().getX() + k * dx, wire.getEnd1().getY() + k * dy);
						applyGrid(policy, "copy " + k, a, warnings);
						Wire copy = Wire.create(a, b);
						mutation.add(copy);
						item.addProperty("id", session.idOf(copy));
						item.addProperty("type", "Wire");
						copied.add(item);
						continue;
					}
					ComponentFactory factory = source.getFactory();
					requireNotCircular(session, circuit, factory);
					AttributeSet attrs = (AttributeSet) source.getAttributeSet().clone();
					if (changes != null) {
						for (Map.Entry<String, JsonElement> entry : changes.entrySet()) {
							String skipped = applyAttribute(session, attrs, factory, entry.getKey(), entry.getValue(), true);
							if (skipped != null) {
								item.addProperty("attribute_skipped", skipped
										+ " (this component has no such attribute)");
							}
						}
					}
					Component copy = factory.createComponent(loc, attrs);
					requireOnCanvas(copy, "copy " + k + " of this " + factory.getName());
					applyGrid(policy, "copy " + k + " of this " + factory.getName(), loc, warnings);
					mutation.add(copy);
					item.addProperty("id", session.idOf(copy));
					item.addProperty("type", factory.getName());
					if (includePorts) {
						item.add("ports", session.describeComponent(copy, false, true).get("ports"));
					}
					copied.add(item);
				}

				String label = source.getAttributeSet().getValue(StdAttr.LABEL);
				boolean relabelled = changes != null && changes.has("label");
				if (label != null && !label.isEmpty() && !relabelled) {
					warnings.add("every copy keeps the label \"" + label + "\". For a tunnel that is usually what "
							+ "you want, because tunnels with the same label are one signal; for a pin or anything else "
							+ "it means the copies are the same net, so give each one its own label with 'attributes'");
				}
				if (!dryRun) {
					session.commit(mutation, "duplicate " + source.getFactory().getName());
				}

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("source", session.idOf(source));
				ret.addProperty("copied_count", Integer.valueOf(count));
				ret.add("copied", copied);
				if (warnings.size() > 0) {
					ret.add("warnings", warnings);
				}
				if (dryRun) {
					ret.addProperty("dry_run", Boolean.TRUE);
					ret.addProperty("dry_run_note", "nothing was changed and no id was assigned: this is what the "
							+ "call would copy. Drop dry_run to do it");
				}
				return ret;
			}
		};
	}

	private static JsonArray stringArray(List<String> values) {
		JsonArray ret = new JsonArray();
		for (String value : values) {
			ret.add(value);
		}
		return ret;
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
			if (wire.contains(loc)) {
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
				JsonArray warnings = new JsonArray();
				applyGrid(gridPolicy(ctx, GRID_WARN), "the new position of this " + component.getFactory().getName(),
						Location.create(x, y), warnings);
				if (attachedBefore > 0) {
					ret.addProperty("detached_wire_count", attachedBefore);
					warnings.add("Logisim does not move wires with a component, so " + attachedBefore
							+ " wire(s) that used to touch this component are now dangling. Re-add them with "
							+ "logisim_wire_add, or check with logisim_circuit_describe.");
				}
				if (warnings.size() > 0) {
					ret.add("warnings", warnings);
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
						.opt("on_off_grid", Schemas.stringEnum("What to do when a point is not a multiple of 10 (the "
								+ "grid Logisim's wire repair assumes): 'fail' (default) refuses and names the nearest "
								+ "point, 'warn' draws it and says so, 'allow' draws it silently",
								"fail", "warn", "allow"))
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
				int policy = gridPolicy(ctx, GRID_FAIL);
				JsonArray warnings = new JsonArray();
				JsonArray rawPaths = ctx.optArray("paths");
				if (rawPaths != null) {
					int index = 0;
					for (JsonElement element : rawPaths) {
						if (!element.isJsonArray()) {
							throw new McpException("'paths[" + index + "]' must be a list of points");
						}
						paths.add(readPoints(ctx.session(), circuit, element.getAsJsonArray(),
								"'paths[" + index + "]'", policy, warnings));
						index++;
					}
					if (paths.isEmpty()) {
						throw new McpException("'paths' must contain at least one path");
					}
				} else {
					paths.add(readPath(ctx, circuit, policy, warnings));
				}

				java.util.Set<Location> ports = session.portLocations(circuit);
				java.util.Set<Wire> existing = new java.util.HashSet<Wire>(circuit.getWires());
				// The wires as they are now, before this call adds any: an endpoint that only the
				// wire we are about to draw touches is still an endpoint that connects nothing.
				List<Wire> wiresBefore = new ArrayList<Wire>(circuit.getWires());
				CircuitMutation mutation = new CircuitMutation(circuit);
				JsonArray ids = new JsonArray();
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
							warnings.add("the segment " + a + " -> " + b + " (wire " + session.idOf(wire)
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
					warnIfConnectsNothing(session, circuit, ports, wiresBefore, entry.getKey(), warnings,
							entry.getValue().intValue() > 1);
				}

				// What the new wiring joins. An endpoint that lands on an existing wire - or within
				// the 2 units Logisim's own wire test accepts - merges two nets, and so does a segment
				// that lies along an existing wire. That is usually intended, and it is also the
				// mistake that silently connects two things that were meant to stay apart, so each one
				// is named together with the net it becomes part of.
				JsonArray joins = new JsonArray();
				java.util.Set<String> seenJoins = new java.util.HashSet<String>();
				for (List<Location> path : paths) {
					for (Location point : new Location[] { path.get(0), path.get(path.size() - 1) }) {
						for (Wire wire : wiresBefore) {
							if (wire.contains(point)) {
								addJoin(session, circuit, joins, seenJoins, point, wire,
										"an endpoint lands on wire " + session.idOf(wire));
							}
						}
					}
				}
				for (Wire createdWire : createdWires) {
					for (Wire wire : wiresBefore) {
						if (createdWire.overlaps(wire, false)) {
							addJoin(session, circuit, joins, seenJoins, createdWire.getEnd0(), wire,
									"the new segment " + createdWire.getEnd0() + " -> " + createdWire.getEnd1()
												+ " lies on wire " + session.idOf(wire));
						}
					}
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
				if (joins.size() > 0) {
					ret.add("joins", joins);
					ret.addProperty("join_count", Integer.valueOf(seenJoins.size()));
					if (seenJoins.size() > joins.size()) {
						ret.addProperty("joins_omitted", Integer.valueOf(seenJoins.size() - joins.size()));
					}
					ret.addProperty("joins_note", "each entry is an existing wire the new wiring lands on or lies "
							+ "along: after this call the two are one net. Ignore it when that is what you meant, and "
							+ "otherwise move the point or use logisim_wire_remove");
				}
				if (warnings.size() > 0) {
					ret.add("warnings", warnings);
				}
				return ret;
			}
		};
	}

	private static List<Location> readPath(ToolContext ctx, Circuit circuit, int policy, JsonArray warnings) {
		JsonArray points = ctx.optArray("points");
		List<Location> ret = new ArrayList<Location>();
		if (points != null) {
			return readPoints(ctx.session(), circuit, points, "'points'", policy, warnings);
		}
		if (!ctx.has("from") || !ctx.has("to")) {
			throw new McpException("give 'points' (a list of points), 'paths' (a list of such lists), or both "
					+ "'from' and 'to'");
		}
		ret.add(readPoint(ctx.session(), circuit, ctx.optElement("from"), "'from'", policy, warnings));
		ret.add(readPoint(ctx.session(), circuit, ctx.optElement("to"), "'to'", policy, warnings));
		if (ret.size() < 2) {
			throw new McpException("a wire needs at least two points");
		}
		return ret;
	}

	private static List<Location> readPoints(McpSession session, Circuit circuit, JsonArray points, String what,
			int policy, JsonArray warnings) {
		if (points.size() > MAX_PATH_POINTS) {
			throw new McpException(what + " has " + points.size() + " entries; the maximum is " + MAX_PATH_POINTS);
		}
		List<Location> ret = new ArrayList<Location>();
		for (JsonElement element : points) {
			ret.add(readPoint(session, circuit, element, what + " entry", policy, warnings));
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
	private static Location readPoint(McpSession session, Circuit circuit, JsonElement element, String what,
			int policy, JsonArray warnings) {
		Location loc = session.resolvePoint(circuit, element);
		if (isCoordinateLiteral(element)) {
			if (loc.getX() < 0 || loc.getY() < 0) {
				throw new McpException(McpInstructions.negativeCoordinate(what, loc.getX(), loc.getY()));
			}
			requireNotInexactlyOnAWire(circuit, loc, what);
			applyGrid(policy, what, loc, warnings);
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
