/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.cburch.draw.actions.ModelAddAction;
import com.cburch.draw.actions.ModelEditTextAction;
import com.cburch.draw.actions.ModelRemoveAction;
import com.cburch.draw.actions.ModelTranslateAction;
import com.cburch.draw.model.CanvasObject;
import com.cburch.draw.shapes.Text;
import com.cburch.draw.undo.Action;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.appear.AppearancePort;
import com.cburch.logisim.circuit.appear.CircuitAppearance;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.mcp.Appearance;
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

/**
 * The appearance of a circuit - the symbol it is drawn as when it is placed inside another
 * circuit - and the two tools that read and change it.
 *
 * <p>
 * Reading and writing are separate tools, and every kind of change is one <i>action</i> of the
 * writing tool, for two reasons. The surface is small and homogeneous: one kind of object,
 * one id space, one grid, one place where the ports of an instance come from - so five tools
 * with five nearly identical descriptions would cost an agent more reading than they save.
 * And a symbol is normally changed as one intention - "tidy these ports and put a frame
 * round it" - which should be one undo step, exactly as {@code logisim_wire_add}'s paths and
 * {@code logisim_component_add_many} are; separate tools would each be their own step in the
 * Edit menu.
 * </p>
 */
public final class AppearanceTools {
	/** How many shapes a read lists before it says it is truncating. */
	private static final int DEFAULT_SHAPE_LIMIT = 200;

	private AppearanceTools() {
	}

	public static McpTool describe(final McpSession session) {
		return new AbstractTool("logisim_circuit_appearance", McpInstructions.CIRCUIT_APPEARANCE,
				Schemas.object()
						.opt("circuit", Schemas.string("Circuit whose symbol to describe; defaults to the current "
								+ "circuit"))
						.opt("include_ports", Schemas.bool("List the symbol's ports, in the order they become the "
								+ "ports of an instance (default true)"))
						.opt("include_shapes", Schemas.bool("List the shapes and text drawn on the symbol "
								+ "(default true)"))
						.opt("limit", Schemas.integer("Most shapes to list (default " + DEFAULT_SHAPE_LIMIT + ")"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				int limit = ctx.optInt("limit", Integer.valueOf(DEFAULT_SHAPE_LIMIT)).intValue();
				if (limit < 1) {
					throw new McpException("'limit' must be at least 1");
				}
				return Appearance.describe(circuit, ctx.optBool("include_shapes", true),
						ctx.optBool("include_ports", true), limit);
			}
		};
	}

	public static McpTool edit(final McpSession session) {
		return new AbstractTool("logisim_circuit_appearance_edit", McpInstructions.CIRCUIT_APPEARANCE_EDIT,
				Schemas.object()
						.opt("circuit", Schemas.string("Circuit whose symbol to change; defaults to the current "
								+ "circuit"))
						.opt("move", Schemas.array(Schemas.freeObject("one move: {pin: \"A\", to: [x, y]} puts a port "
								+ "at that position, {id: \"a3\", to: [x, y]} puts a shape there, and "
								+ "{id: \"a3\", dx: 10, dy: -10} shifts one by a step; a port may also be named by "
								+ "{\"port\": \"A\"} or a location [x, y]"),
								"Moves to make, all in one undoable step"))
						.opt("add", Schemas.array(Schemas.freeObject("one shape: {kind: \"rect\", at: [x, y], "
								+ "size: [w, h]} or {kind: \"oval\", at: ..., size: ...} or {kind: \"line\", "
								+ "from: [x, y], to: [x, y]} or {kind: \"text\", at: [x, y], text: \"...\"}, each "
								+ "optionally with stroke, fill, stroke_width, font_size, paint_type "
								+ "(\"stroke\"/\"fill\"/\"both\") and align (\"left\"/\"center\"/\"right\")"),
								"Shapes to draw on the symbol, on top of what is already there"))
						.opt("remove", Schemas.array(Schemas.string("an id from logisim_circuit_appearance"),
								"Shapes to delete. A port cannot be deleted here: it belongs to a Pin, so remove "
										+ "the Pin from the circuit instead"))
						.opt("set_text", Schemas.array(Schemas.freeObject("one change: {id: \"a4\", "
								+ "text: \"new text\"}"), "Texts to rewrite"))
						.opt("reset", Schemas.bool("Throw the arrangement away and let Logisim lay the symbol out "
								+ "from the circuit's Pins again (default false)"))
						.opt("dry_run", Schemas.bool("Report what would change and change nothing (default false)"))
						.opt("label_ports", Schemas.bool("Write each port's Pin label into the symbol as a text shape, just "
								+ "inside the side the port sits on, which is what makes a symbol read like a block diagram: "
								+ "Logisim itself draws no pin name on an instance. A port whose name is already written is "
								+ "left alone, so asking twice adds nothing"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Circuit circuit = session.circuitByName(ctx.optString("circuit", null));
				CircuitAppearance appearance = circuit.getAppearance();
				boolean dryRun = ctx.optBool("dry_run", false);
				List<String> warnings = new ArrayList<String>();
				List<Action> steps = new ArrayList<Action>();
				List<CanvasObject> touched = new ArrayList<CanvasObject>();
				JsonArray moved = new JsonArray();
				JsonArray added = new JsonArray();
				JsonArray removed = new JsonArray();
				JsonArray rewritten = new JsonArray();
				JsonArray labels = new JsonArray();
				// The ports this call moves, kept as pin names and locations: moving a port moves that
				// Pin on every instance, so the check afterwards has to know what went where.
				List<String> movedPins = new ArrayList<String>();
				List<Location> movedFrom = new ArrayList<Location>();
				List<Location> movedTo = new ArrayList<Location>();
				// where the ports this call moves will be: nothing is applied until the whole call is
				// collected into one action, so anything that reads a port while the call is being
				// built - the port labels do - has to be told about the moves itself
				Map<AppearancePort, Location> pending = new IdentityHashMap<AppearancePort, Location>();
				List<String> orderBefore = Appearance.portOrder(circuit);
				boolean beforeDefault = appearance.isDefaultAppearance();

				if (ctx.optBool("reset", false)) {
					steps.add(Appearance.resetStep(circuit));
				}

				// ------------------------------------------------------------------ moves
				JsonArray moves = ctx.optArray("move");
				if (moves != null) {
					for (JsonElement element : moves) {
						if (!element.isJsonObject()) {
							throw new McpException("every 'move' entry must be an object such as "
									+ "{\"pin\": \"A\", \"to\": [40, 30]}");
						}
						JsonObject spec = element.getAsJsonObject();
						CanvasObject object = Appearance.find(circuit, reference(spec));
						Location from = Appearance.locationOf(object);
						Location to = target(spec, from);
						JsonObject item = new JsonObject();
						item.addProperty("id", Appearance.nameOf(circuit, object));
						item.addProperty("pin", Appearance.pinName(Appearance.pinOf(object)));
						item.add("from", McpSession.point(from));
						item.add("to", McpSession.point(to));
						moved.add(item);
						if (Appearance.pinOf(object) != null) {
							movedPins.add(Appearance.pinName(Appearance.pinOf(object)));
							movedFrom.add(from);
							movedTo.add(to);
							if (object instanceof AppearancePort) {
								pending.put((AppearancePort) object, to);
							}
						}
						steps.add(new ModelTranslateAction(appearance, single(object), to.getX() - from.getX(),
								to.getY() - from.getY()));
						touched.add(object);
					}
				}

				// ------------------------------------------------------------------ additions
				JsonArray adds = ctx.optArray("add");
				if (adds != null) {
					List<CanvasObject> shapes = new ArrayList<CanvasObject>();
					for (JsonElement element : adds) {
						if (!element.isJsonObject()) {
							throw new McpException("every 'add' entry must be an object such as "
									+ "{\"kind\": \"rect\", \"at\": [0, 0], \"size\": [100, 60]}");
						}
						shapes.add(Appearance.shape(element.getAsJsonObject(), warnings));
					}
					if (!shapes.isEmpty()) {
						steps.add(new ModelAddAction(appearance, shapes));
						touched.addAll(shapes);
						for (CanvasObject shape : shapes) {
							JsonObject item = new JsonObject();
							item.addProperty("kind", Appearance.kindOf(shape));
							item.add("bounds", boundsOf(shape));
							added.add(item);
						}
					}
				}

				// ------------------------------------------------------------------ removals
				JsonArray removes = ctx.optArray("remove");
				if (removes != null) {
					List<CanvasObject> objects = new ArrayList<CanvasObject>();
					for (JsonElement element : removes) {
						CanvasObject object = Appearance.find(circuit, element);
						if (!object.canRemove()) {
							throw new McpException(Appearance.nameOf(circuit, object) + " is "
									+ Appearance.kindOf(object) + " of the symbol and cannot be removed here: a port "
									+ "stands for a Pin, so remove the Pin from the circuit with "
									+ "logisim_component_remove, and an anchor is the symbol's origin");
						}
						objects.add(object);
						removed.add(Appearance.nameOf(circuit, object));
					}
					if (!objects.isEmpty()) {
						steps.add(new ModelRemoveAction(appearance, objects));
					}
				}

				// ------------------------------------------------------------------ texts
				JsonArray texts = ctx.optArray("set_text");
				if (texts != null) {
					for (JsonElement element : texts) {
						JsonObject spec = element.getAsJsonObject();
						CanvasObject object = Appearance.find(circuit, reference(spec));
						if (!(object instanceof Text)) {
							throw new McpException(Appearance.nameOf(circuit, object) + " is a "
									+ Appearance.kindOf(object) + ", not a text shape, so it has no text to set");
						}
						String value = Json.stringOrNull(spec, "text");
						if (value == null) {
							throw new McpException("a 'set_text' entry needs {id: ..., text: ...}");
						}
						steps.add(new ModelEditTextAction(appearance, (Text) object, value));
						JsonObject item = new JsonObject();
						item.addProperty("id", Appearance.nameOf(circuit, object));
						item.addProperty("was", ((Text) object).getText());
						item.addProperty("text", value);
						rewritten.add(item);
						touched.add(object);
					}
				}

				// ------------------------------------------------------------------ port labels
				boolean labelsRequested = ctx.optBool("label_ports", false);
				if (labelsRequested) {
					List<CanvasObject> labelsToAdd = Appearance.portLabels(circuit, warnings, pending);
					if (!labelsToAdd.isEmpty()) {
						steps.add(new ModelAddAction(appearance, labelsToAdd));
						touched.addAll(labelsToAdd);
						for (CanvasObject text : labelsToAdd) {
							JsonObject item = new JsonObject();
							item.addProperty("text", ((Text) text).getText());
							item.add("at", McpSession.point(Appearance.locationOf(text)));
							labels.add(item);
						}
					}
				}

				if (steps.isEmpty() && !labelsRequested) {
					throw new McpException("nothing to do: give 'move', 'add', 'remove', 'set_text' or "
							+ "'reset'. logisim_circuit_appearance reports what the symbol contains and the ids to "
							+ "use");
				}

				JsonObject ret = new JsonObject();
				ret.addProperty("circuit", circuit.getName());
				ret.addProperty("dry_run", Boolean.valueOf(dryRun));
				if (moved.size() > 0) {
					ret.add("moved", moved);
				}
				if (added.size() > 0) {
					ret.add("added", added);
				}
				if (removed.size() > 0) {
					ret.add("removed", removed);
				}
				if (rewritten.size() > 0) {
					ret.add("texts", rewritten);
				}
				if (labels.size() > 0) {
					ret.add("labels", labels);
				} else if (labelsRequested) {
					ret.addProperty("labels_note", "'label_ports' was asked for, but every port already has its "
							+ "name written beside it, so nothing was added");
				}
				if (!warnings.isEmpty()) {
					ret.add("warnings", Json.array(warnings));
					ret.addProperty("warnings_note", "those settings were skipped rather than losing the whole "
							+ "call");
				}

				if (dryRun) {
					ret.addProperty("changed", Integer.valueOf(0));
					ret.addProperty("dry_run_note", "nothing was changed; remove 'dry_run' to make this edit");
					ret.addProperty("is_default", Boolean.valueOf(beforeDefault));
				} else {
					Appearance.commit(session, circuit, steps, touched, "arrange the appearance of "
							+ circuit.getName());
					JsonObject after = Appearance.describe(circuit, true, false, DEFAULT_SHAPE_LIMIT);
					ret.addProperty("changed", Integer.valueOf(moved.size() + added.size() + removed.size()
							+ rewritten.size() + labels.size()
							+ (steps.size() > moved.size() + added.size() + removed.size()
									+ rewritten.size() + labels.size() ? 1 : 0)));
					ret.addProperty("is_default", Boolean.valueOf(appearance.isDefaultAppearance()));
					if (beforeDefault) {
						// The symbol was still automatic, so Logisim's own drawing of it - the box and the
						// notch mark - has just become ordinary shapes where they stood, which is worth
						// saying because they do not move with the ports.
						ret.addProperty("frozen_note", "the symbol was still laid out by Logisim, so this call froze "
								+ "that arrangement: the box and the notch Logisim drew round the automatic ports are "
								+ "ordinary shapes now, left where they were. Remove them with 'remove' if the ports have "
								+ "moved away from them, or 'reset' to go back to the automatic layout");
					}
					ret.addProperty("object_count", after.get("object_count").getAsInt());
					ret.add("shapes", after.get("shapes"));
					ret.addProperty("undoable", Boolean.TRUE);
					ret.addProperty("note", "one call is one undo step: logisim_undo takes the whole call back, "
							+ "which is also how a 'reset' and a new arrangement can be undone together");
					// A moved port moves that Pin on every instance of this circuit, and the port
					// numbers an instance hands out follow the order of the ports in the symbol - so
					// both consequences are reported rather than left for the caller to discover in a
					// parent circuit that no longer works.
					JsonArray impact = Appearance.movedParentWires(circuit, movedPins, movedFrom, movedTo);
					if (impact.size() > 0) {
						ret.add("parent_wiring", impact);
						ret.addProperty("parent_wiring_note", "those wires in the parent circuits end where this "
								+ "call moved a port, so they are dangling now: Logisim does not move wiring with a "
								+ "port. Re-draw them with logisim_wire_add to the 'now_at' positions");
					}
					List<String> orderAfter = Appearance.portOrder(circuit);
					if (!orderAfter.equals(orderBefore)) {
						ret.add("port_order_before", Json.array(orderBefore));
						ret.add("port_order_after", Json.array(orderAfter));
						ret.addProperty("port_order_note", "a port's number on an instance follows its position in "
								+ "the symbol (left to right, then top to bottom, since Logisim sorts them by x then "
								+ "y), so this call changed which pin 'U1.3' and the like mean. Anything wired by "
								+ "port number has to be redone");
					}
				}
				ret.addProperty("hint", "look at the symbol with logisim_screenshot {appearance: true} - a picture "
						+ "is the only way to see whether the ports read well - and remember that the position of a "
						+ "port here is where that Pin sits on every instance of this circuit, in the parent. Use "
						+ "'label_ports' to write the port names into the symbol, and 'dry_run' to see what a call "
						+ "would change first");
				return ret;
			}
		};
	}

	/** The reference of a 'move' or 'set_text' entry: 'pin', 'id', 'port' or 'loc'. */
	private static JsonElement reference(JsonObject spec) {
		for (String key : new String[] { "pin", "port", "id", "shape", "loc" }) {
			if (Json.isPresent(spec, key)) {
				return spec.get(key);
			}
		}
		throw new McpException("every entry needs to say what it refers to: 'pin' (the label of a Pin in the "
				+ "circuit), 'id' (an id from logisim_circuit_appearance) or 'loc' ([x, y])");
	}

	/** Where a move should put an object: 'to' if it is given, else the object's place plus 'dx'/'dy'. */
	private static Location target(JsonObject spec, Location from) {
		if (Json.isPresent(spec, "to")) {
			JsonElement element = spec.get("to");
			Location wanted = element.isJsonArray() ? McpSession.parseLocation(element)
					: McpSession.tryParseLocation(element.getAsString());
			if (wanted == null) {
				throw new McpException("'to' must be a point: [x, y], or \"x,y\"");
			}
			return Appearance.snap(wanted);
		}
		Integer dx = Json.isPresent(spec, "dx") ? Integer.valueOf(spec.get("dx").getAsInt()) : null;
		Integer dy = Json.isPresent(spec, "dy") ? Integer.valueOf(spec.get("dy").getAsInt()) : null;
		if (dx == null && dy == null) {
			throw new McpException("a move needs either 'to' ([x, y], where to put it) or 'dx'/'dy' (how far to "
					+ "shift it)");
		}
		return Appearance.snap(Location.create(from.getX() + (dx == null ? 0 : dx.intValue()),
				from.getY() + (dy == null ? 0 : dy.intValue())));
	}

	private static List<CanvasObject> single(CanvasObject object) {
		List<CanvasObject> ret = new ArrayList<CanvasObject>(1);
		ret.add(object);
		return ret;
	}

	private static JsonArray boundsOf(CanvasObject object) {
		com.cburch.logisim.data.Bounds bounds = object.getBounds();
		return Json.array(Integer.valueOf(bounds.getX()), Integer.valueOf(bounds.getY()),
				Integer.valueOf(bounds.getWidth()), Integer.valueOf(bounds.getHeight()));
	}
}
