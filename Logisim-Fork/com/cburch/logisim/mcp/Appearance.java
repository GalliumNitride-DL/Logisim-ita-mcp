/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.cburch.draw.model.CanvasObject;
import com.cburch.draw.shapes.DrawAttr;
import com.cburch.draw.shapes.Line;
import com.cburch.draw.shapes.Oval;
import com.cburch.draw.shapes.Poly;
import com.cburch.draw.shapes.Rectangle;
import com.cburch.draw.shapes.Text;
import com.cburch.draw.undo.Action;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.appear.AppearanceAnchor;
import com.cburch.logisim.circuit.appear.AppearanceElement;
import com.cburch.logisim.circuit.appear.AppearancePort;
import com.cburch.logisim.circuit.appear.CircuitAppearance;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.Pin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * A circuit's <i>appearance</i>: the symbol it is drawn as when it is placed inside another
 * circuit.
 *
 * <p>
 * Logisim calls this the "circuit appearance" or the "layout", and the GUI edits it in
 * Project | Edit Circuit Appearance, in a view of its own. It is a drawing - the
 * {@link CircuitAppearance} is a {@code com.cburch.draw.model.Drawing}, i.e. an ordered list
 * of shapes - plus two kinds of element that are not decoration: one
 * {@link AppearanceAnchor}, which is the symbol's origin, and one {@link AppearancePort} per
 * Pin in the circuit, whose position decides where that pin sits on every instance of the
 * circuit.
 * </p>
 *
 * <p>
 * This class is where the MCP layer reads and changes it. Everything it does is public
 * Logisim API, and every change is an ordinary undoable action: the draw actions the GUI's
 * appearance view uses ({@code com.burch.draw.actions.*}) are combined with
 * {@code com.burch.draw.undo.Action.append} so that one tool call is one entry in the Edit
 * menu, and wrapped in the very same {@code CanvasActionAdapter} the GUI wraps them in, so
 * that the port bookkeeping Logisim does around an appearance change happens here too.
 * </p>
 */
public final class Appearance {
	/** The grid every coordinate of a symbol is kept on, exactly as the GUI snaps them. */
	public static final int GRID = 10;

	private Appearance() {
	}

	/** Every object of an appearance, from the bottom of the drawing to the top. */
	public static List<CanvasObject> objects(Circuit circuit) {
		return new ArrayList<CanvasObject>(circuit.getAppearance().getObjectsFromBottom());
	}

	/**
	 * A readable name for each object, in the order {@link #objects} returns them: {@code a1},
	 * {@code a2}, and so on. The names are only as stable as the drawing - an object keeps its
	 * name until something is added or removed below it - which is why a reply always reports
	 * the names it is using and why a port can also be addressed by its pin's label.
	 */
	public static Map<CanvasObject, String> names(Circuit circuit) {
		Map<CanvasObject, String> ret = new IdentityHashMap<CanvasObject, String>();
		int index = 0;
		for (CanvasObject object : objects(circuit)) {
			ret.put(object, "a" + (++index));
		}
		return ret;
	}

	/** The id of one object, or null when it is not part of this appearance. */
	public static String nameOf(Circuit circuit, CanvasObject object) {
		return names(circuit).get(object);
	}

	/** True while Logisim still lays the symbol out by itself. */
	public static boolean isDefault(Circuit circuit) {
		return circuit.getAppearance().isDefaultAppearance();
	}

	/** The location of the symbol's anchor. */
	public static Location anchor(Circuit circuit) {
		for (CanvasObject object : objects(circuit)) {
			if (object instanceof AppearanceAnchor) {
				return ((AppearanceAnchor) object).getLocation();
			}
		}
		return Location.create(0, 0);
	}

	/** The pin names of a symbol's ports, in the order an instance numbers them. */
	public static List<String> portOrder(Circuit circuit) {
		List<String> ret = new ArrayList<String>();
		for (AppearancePort port : ports(circuit)) {
			String name = pinName(port.getPin());
			ret.add(name == null ? "?" : name);
		}
		return ret;
	}

	/**
	 * The wiring in the circuits that use this one that ends where a moved port used to be.
	 *
	 * <p>
	 * Moving a port moves that Pin on <i>every</i> instance of the circuit, because an instance's
	 * port positions are the symbol's port positions offset by the instance's anchor, and Logisim
	 * does not move the wiring with them: a wire that met the port before is left dangling. The
	 * symbol looked fine in the picture, so the agent has to be told which wires it broke.
	 * </p>
	 *
	 * @return one entry per parent instance and pin, with the wires left behind at the old spot
	 */
	public static JsonArray movedParentWires(Circuit circuit, List<String> pins, List<Location> from,
			List<Location> to) {
		JsonArray ret = new JsonArray();
		java.util.Collection<Circuit> users = circuit.getCircuitsUsingThis();
		if (users == null || pins.isEmpty()) {
			return ret;
		}
		Location anchor = anchor(circuit);
		for (Circuit user : users) {
			for (com.cburch.logisim.comp.Component comp : user.getNonWires()) {
				if (!(comp.getFactory() instanceof com.cburch.logisim.circuit.SubcircuitFactory)) {
					continue;
				}
				com.cburch.logisim.circuit.SubcircuitFactory factory = (com.cburch.logisim.circuit.SubcircuitFactory) comp
						.getFactory();
				if (factory.getSubcircuit() != circuit) {
					continue;
				}
				for (int i = 0; i < pins.size(); i++) {
					Location wasAt = parentPoint(comp.getLocation(), anchor, from.get(i));
					Location nowAt = parentPoint(comp.getLocation(), anchor, to.get(i));
					int wires = 0;
					for (com.cburch.logisim.circuit.Wire wire : user.getWires()) {
						if (wire.getEnd0().equals(wasAt) || wire.getEnd1().equals(wasAt)) {
							wires++;
						}
					}
					if (wires == 0) {
						continue;
					}
					JsonObject item = new JsonObject();
					item.addProperty("circuit", user.getName());
					item.add("instance", McpSession.point(comp.getLocation()));
					item.addProperty("pin", pins.get(i));
					item.add("was_at", McpSession.point(wasAt));
					item.add("now_at", McpSession.point(nowAt));
					item.addProperty("wires", Integer.valueOf(wires));
					ret.add(item);
				}
			}
		}
		return ret;
	}

	/** Where a port of an instance sits in the parent: the instance's anchor plus the offset. */
	private static Location parentPoint(Location instanceLoc, Location anchor, Location portLoc) {
		return Location.create(instanceLoc.getX() + portLoc.getX() - anchor.getX(),
				instanceLoc.getY() + portLoc.getY() - anchor.getY());
	}

	/**
	 * Text shapes that name the ports of the symbol, one per port, written just inside the side
	 * the port sits on: a port on the left edge gets its name to its right, one on the right edge
	 * gets it to its left, and one on the top or bottom edge gets it below or above. Inside
	 * rather than outside because the wire a parent draws arrives from outside, and because a
	 * label outside would widen the symbol and so change the rectangle every instance of it
	 * occupies.
	 *
	 * <p>
	 * Logisim draws no pin name on an instance - only an appearance's own text is drawn - so a
	 * symbol that should read like the block diagram it stands for needs these, and every author
	 * otherwise discovers that by rendering the parent and finding the ports bare. A port whose
	 * name already appears as text is left alone, so asking twice does nothing the second time
	 * (the check is by name, because an unpainted text shape has no size to compare positions
	 * with).
	 * </p>
	 *
	 * @param pending where a port will be once the moves of the same call have been applied: the
	 *                ports are still where they were when this runs, because a tool call is
	 *                collected into one action and applied afterwards
	 * @param warnings collects anything a Text shape could not be given
	 */
	public static List<CanvasObject> portLabels(Circuit circuit, List<String> warnings,
			Map<AppearancePort, Location> pending) {
		List<CanvasObject> ret = new ArrayList<CanvasObject>();
		List<AppearancePort> ports = ports(circuit);
		if (ports.isEmpty()) {
			return ret;
		}
		Map<AppearancePort, Location> where = new IdentityHashMap<AppearancePort, Location>();
		int minX = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (AppearancePort port : ports) {
			Location loc = pending == null ? null : pending.get(port);
			if (loc == null) {
				loc = port.getLocation();
			}
			where.put(port, loc);
			minX = Math.min(minX, loc.getX());
			maxX = Math.max(maxX, loc.getX());
			minY = Math.min(minY, loc.getY());
			maxY = Math.max(maxY, loc.getY());
		}
		for (AppearancePort port : ports) {
			String name = pinName(port.getPin());
			if (name == null || name.isEmpty()) {
				continue;
			}
			LabelPlacement place = placement(where.get(port), minX, maxX, minY, maxY);
			if (labelled(circuit, name)) {
				continue;
			}
			JsonObject spec = new JsonObject();
			spec.addProperty("kind", "text");
			spec.addProperty("text", name);
			spec.addProperty("align", place.align);
			spec.add("at", Json.array(Integer.valueOf(place.x), Integer.valueOf(place.y)));
			CanvasObject shape = shape(spec, warnings);
			if (shape != null) {
				ret.add(shape);
			}
		}
		return ret;
	}

	/** Where a port's name goes, and how a text of it is aligned there. */
	static final class LabelPlacement {
		final int x;
		final int y;
		final String align;

		LabelPlacement(int x, int y, String align) {
			this.x = x;
			this.y = y;
			this.align = align;
		}
	}

	/**
	 * The point a port's name goes to and the alignment it takes there: just inside the side the
	 * port sits on, worked out from where the ports are. Inside rather than outside because the
	 * wire a parent draws arrives from outside, and because a name outside would widen the symbol
	 * and so change the rectangle every instance of it occupies. A port that is neither on an
	 * edge of the arrangement nor on a top or bottom row is put to its right, which is the side
	 * that at least keeps the name next to the port.
	 */
	static LabelPlacement placement(Location loc, int minX, int maxX, int minY, int maxY) {
		if (minX < maxX && loc.getX() <= minX) {
			return new LabelPlacement(loc.getX() + 10, loc.getY(), "left");
		}
		if (minX < maxX && loc.getX() >= maxX) {
			return new LabelPlacement(loc.getX() - 10, loc.getY(), "right");
		}
		if (minY < maxY && loc.getY() <= minY) {
			return new LabelPlacement(loc.getX(), loc.getY() + 10, "center");
		}
		if (minY < maxY && loc.getY() >= maxY) {
			return new LabelPlacement(loc.getX(), loc.getY() - 10, "center");
		}
		return new LabelPlacement(loc.getX() + 10, loc.getY(), "left");
	}

	/** True when the symbol already carries this text, so labelling it again would duplicate it. */
	private static boolean labelled(Circuit circuit, String name) {
		for (CanvasObject object : objects(circuit)) {
			if (object instanceof Text && name.equals(((Text) object).getText())) {
				return true;
			}
		}
		return false;
	}

	/** The rectangle every object of the symbol occupies. */
	public static Bounds bounds(Circuit circuit) {
		return circuit.getAppearance().getAbsoluteBounds();
	}

	/**
	 * The ports of a symbol in the order they become the ports of an instance of the circuit:
	 * an instance's port <i>i</i> is this list's entry <i>i</i>, which is why the order matters
	 * (it is what a "c3.1" reference resolves to).
	 */
	public static List<AppearancePort> ports(Circuit circuit) {
		List<AppearancePort> ret = new ArrayList<AppearancePort>();
		java.util.Map<Location, Instance> ordered = circuit.getAppearance()
				.getPortOffsets(circuit.getAppearance().getFacing());
		for (Instance pin : ordered.values()) {
			AppearancePort port = portOf(circuit, pin);
			if (port != null) {
				ret.add(port);
			}
		}
		return ret;
	}

	/** The appearance port that stands for one pin, or null when the pin has none yet. */
	public static AppearancePort portOf(Circuit circuit, Instance pin) {
		for (CanvasObject object : objects(circuit)) {
			if (object instanceof AppearancePort && ((AppearancePort) object).getPin() == pin) {
				return (AppearancePort) object;
			}
		}
		return null;
	}

	/** The pin an appearance port stands for, or null for anything else. */
	public static Instance pinOf(CanvasObject object) {
		return object instanceof AppearancePort ? ((AppearancePort) object).getPin() : null;
	}

	/** The label of a pin, or a stand-in when it has none. */
	public static String pinName(Instance pin) {
		if (pin == null) {
			return null;
		}
		String label = pin.getAttributeValue(StdAttr.LABEL);
		if (label != null && !label.trim().isEmpty()) {
			return label.trim();
		}
		return "pin at " + pin.getLocation();
	}

	/** Keeps a coordinate on the grid, as the GUI's appearance view does when it drags. */
	public static Location snap(Location loc) {
		int x = Math.round(loc.getX() / (float) GRID) * GRID;
		int y = Math.round(loc.getY() / (float) GRID) * GRID;
		return Location.create(x, y);
	}

	// ------------------------------------------------------------------ reading

	public static JsonObject describe(Circuit circuit, boolean includeShapes, boolean includePorts, int limit) {
		CircuitAppearance appearance = circuit.getAppearance();
		List<CanvasObject> all = objects(circuit);
		Map<CanvasObject, String> names = names(circuit);
		JsonObject ret = new JsonObject();
		ret.addProperty("circuit", circuit.getName());
		ret.addProperty("what", "the appearance (the symbol this circuit is drawn as when it is placed "
				+ "inside another circuit): its anchor, one port per Pin, and any shapes and text drawn around "
				+ "them");
		ret.addProperty("is_default", Boolean.valueOf(appearance.isDefaultAppearance()));
		ret.addProperty("default_note", appearance.isDefaultAppearance()
				? "Logisim is still laying this symbol out by itself, so adding, moving, renaming or facing the "
						+ "circuit's Pins rearranges it. The first edit that goes through "
						+ "logisim_circuit_appearance_edit freezes the arrangement as it is then, and "
						+ "'reset' gives the automatic layout back"
				: "this symbol has been arranged by hand (or by an agent), so Logisim no longer moves its ports "
						+ "by itself: adding a Pin adds a port at a default position and leaves the rest alone");
		Location anchor = anchor(circuit);
		ret.add("anchor", McpSession.point(anchor));
		ret.addProperty("anchor_id", nameOf(circuit, anchorObject(circuit)));
		ret.add("bounds", boundsArray(bounds(circuit)));
		ret.add("instance_bounds", boundsArray(appearance.getOffsetBounds()));
		ret.addProperty("instance_bounds_note", "'instance_bounds' is the rectangle an instance of this circuit "
				+ "occupies in the parent, in circuit coordinates relative to the anchor: what the parent reserves "
				+ "for this symbol, and what 'bounds' in logisim_circuit_describe reports for a placed instance");
		ret.addProperty("object_count", Integer.valueOf(all.size()));

		JsonArray ports = new JsonArray();
		if (includePorts) {
			ports = portList(circuit);
			ret.add("ports", ports);
			ret.addProperty("port_count", Integer.valueOf(ports.size()));
			if (ports.size() == 0) {
				ret.addProperty("ports_note", "this circuit has no Pins, so its symbol has no ports: place Pins "
						+ "in the circuit and they appear here");
			}
		}

		if (includeShapes) {
			JsonArray shapes = new JsonArray();
			int emitted = 0;
			for (CanvasObject object : all) {
				if (object instanceof AppearanceElement) {
					continue;
				}
				if (emitted++ >= limit) {
					ret.addProperty("shapes_truncated", Boolean.TRUE);
					break;
				}
				JsonObject item = new JsonObject();
				item.addProperty("id", names.get(object));
				item.addProperty("kind", kindOf(object));
				item.add("bounds", boundsArray(object.getBounds()));
				if (object instanceof Text) {
					item.addProperty("text", ((Text) object).getText());
				}
				shapes.add(item);
			}
			ret.add("shapes", shapes);
			ret.addProperty("shape_count", Integer.valueOf(shapes.size()));
		}

		ret.addProperty("hint", "arrange it with logisim_circuit_appearance_edit: {move: [{pin: \"A\", to: [x, y]}]} "
				+ "to place a port, {add: [{kind: \"rect\", at: [x, y], size: [w, h]}]} or a text shape to decorate "
				+ "it, {reset: true} to let Logisim lay it out again. Then look at it with "
				+ "logisim_screenshot {appearance: true}. One call is one undo step");
		return ret;
	}

	private static CanvasObject anchorObject(Circuit circuit) {
		for (CanvasObject object : objects(circuit)) {
			if (object instanceof AppearanceAnchor) {
				return object;
			}
		}
		return null;
	}

	/**
	 * The symbol's ports as JSON, in the order an instance of the circuit gets them: entry
	 * <i>i</i> is port number <i>i</i> of a placement, which is what a "c3.1" reference means.
	 */
	public static JsonArray portList(Circuit circuit) {
		Map<CanvasObject, String> names = names(circuit);
		JsonArray ports = new JsonArray();
		int number = 0;
		for (AppearancePort port : ports(circuit)) {
			Instance pin = port.getPin();
			JsonObject item = new JsonObject();
			item.addProperty("index", Integer.valueOf(number));
			item.addProperty("id", names.get(port));
			item.addProperty("pin", pinName(pin));
			item.add("loc", McpSession.point(port.getLocation()));
			item.addProperty("facing", facingName(pin));
			item.addProperty("direction", Pin.FACTORY.isInputPin(pin) ? "input" : "output");
			JsonElement width = widthOf(pin);
			if (width != null) {
				item.add("width", width);
			}
			item.addProperty("note", "this is port number " + number + " of an instance, so a wire to "
					+ "\"<instance>." + number + "\" lands here");
			ports.add(item);
			number++;
		}
		return ports;
	}

	private static String facingName(Instance pin) {
		Direction facing = pin == null ? null : pin.getAttributeValue(StdAttr.FACING);
		return facing == null ? null : facing.toString().toLowerCase();
	}

	private static JsonElement widthOf(Instance pin) {
		if (pin == null) {
			return null;
		}
		com.cburch.logisim.data.BitWidth width = pin.getAttributeValue(StdAttr.WIDTH);
		return width == null ? null : new JsonPrimitive(Integer.valueOf(width.getWidth()));
	}

	private static JsonArray boundsArray(Bounds bounds) {
		return Json.array(Integer.valueOf(bounds.getX()), Integer.valueOf(bounds.getY()),
				Integer.valueOf(bounds.getWidth()), Integer.valueOf(bounds.getHeight()));
	}

	/** What a shape is, in the words this tool's 'add' accepts. */
	public static String kindOf(CanvasObject object) {
		if (object instanceof AppearancePort) {
			return "port";
		}
		if (object instanceof AppearanceAnchor) {
			return "anchor";
		}
		if (object instanceof Line) {
			return "line";
		}
		if (object instanceof Poly) {
			return ((Poly) object).isClosed() ? "polygon" : "polyline";
		}
		if (object instanceof Rectangle) {
			return "rect";
		}
		if (object instanceof Oval) {
			return "oval";
		}
		if (object instanceof Text) {
			return "text";
		}
		return object.getClass().getSimpleName().toLowerCase();
	}

	// ------------------------------------------------------------------ resolving a reference

	/**
	 * Finds one object of an appearance: by the id a reply reported ({@code "a3"}), by the label
	 * of the Pin a port stands for ({@code "A"}), or by a location ({@code [x, y]} or
	 * {@code "130,200"}, which finds the port or shape drawn there).
	 */
	public static CanvasObject find(Circuit circuit, JsonElement reference) {
		if (reference == null || reference.isJsonNull()) {
			throw new McpException("a reference to an appearance object is required: an id from "
					+ "logisim_circuit_appearance (\"a3\"), a pin label (\"A\"), or a location [x, y]");
		}
		if (reference.isJsonObject()) {
			JsonObject object = reference.getAsJsonObject();
			if (Json.isPresent(object, "id")) {
				return find(circuit, object.get("id"));
			}
			if (Json.isPresent(object, "pin")) {
				return find(circuit, object.get("pin"));
			}
			if (Json.isPresent(object, "loc")) {
				return find(circuit, object.get("loc"));
			}
			throw new McpException("a reference object takes 'id', 'pin' or 'loc'");
		}
		if (reference.isJsonArray()) {
			Location loc = McpSession.parseLocation(reference);
			for (CanvasObject object : objects(circuit)) {
				if (object instanceof AppearanceElement && ((AppearanceElement) object).getLocation().equals(loc)) {
					return object;
				}
			}
			for (CanvasObject object : objects(circuit)) {
				if (!(object instanceof AppearanceElement) && object.contains(loc, false)) {
					return object;
				}
			}
			throw new McpException("nothing in the appearance of '" + circuit.getName() + "' is drawn at " + loc
					+ "; call logisim_circuit_appearance for the ids and positions it does have");
		}
		String text = reference.getAsString().trim();
		for (Map.Entry<CanvasObject, String> entry : names(circuit).entrySet()) {
			if (text.equals(entry.getValue())) {
				return entry.getKey();
			}
		}
		for (AppearancePort port : ports(circuit)) {
			String name = pinName(port.getPin());
			if (text.equals(name)) {
				return port;
			}
		}
		Location loc = McpSession.tryParseLocation(text);
		if (loc != null) {
			return find(circuit, McpSession.point(loc));
		}
		StringBuilder available = new StringBuilder();
		for (CanvasObject object : objects(circuit)) {
			if (available.length() > 0) {
				available.append(", ");
			}
			String pin = pinName(pinOf(object));
			available.append(names(circuit).get(object)).append(pin == null ? "" : " (" + pin + ")");
		}
		throw new McpException("'" + text + "' is not an object of the appearance of '" + circuit.getName()
				+ "'; it has " + available);
	}

	// ------------------------------------------------------------------ changing it

	/** Builds one shape from the arguments of an 'add' entry. */
	public static CanvasObject shape(JsonObject spec, List<String> warnings) {
		String kind = Json.stringOrNull(spec, "kind");
		if (kind == null) {
			throw new McpException("every 'add' entry needs a 'kind': \"line\", \"rect\", \"oval\" or \"text\"");
		}
		kind = kind.trim().toLowerCase();
		CanvasObject ret;
		if ("line".equals(kind) || "polyline".equals(kind) || "poly".equals(kind)) {
			Location from = point(spec, "from", null);
			Location to = point(spec, "to", null);
			if (from == null || to == null) {
				throw new McpException("a line needs 'from' and 'to'");
			}
			// the GUI's own line tool draws a two point open Poly rather than a Line: Line.paint
			// insists on a drag gesture, so a Line cannot be drawn into a picture at all
			List<Location> points = new ArrayList<Location>();
			points.add(from);
			points.add(to);
			ret = new Poly(false, points);
		} else if ("rect".equals(kind) || "rectangle".equals(kind)) {
			Bounds area = area(spec);
			ret = new Rectangle(area.getX(), area.getY(), area.getWidth(), area.getHeight());
		} else if ("oval".equals(kind) || "circle".equals(kind) || "ellipse".equals(kind)) {
			Bounds area = area(spec);
			ret = new Oval(area.getX(), area.getY(), area.getWidth(), area.getHeight());
		} else if ("text".equals(kind) || "label".equals(kind)) {
			Location at = point(spec, "at", null);
			String value = Json.stringOrNull(spec, "text");
			if (at == null || value == null) {
				throw new McpException("a text shape needs 'at' and 'text'");
			}
			ret = new Text(at.getX(), at.getY(), value);
		} else {
			throw new McpException("'" + kind + "' is not a shape this tool draws; use \"line\", \"rect\", \"oval\" "
					+ "or \"text\"");
		}
		applyStyle(ret, spec, warnings);
		return ret;
	}

	/** Sets the colour, width and font an 'add' entry asked for, skipping what does not apply. */
	private static void applyStyle(CanvasObject shape, JsonObject spec, List<String> warnings) {
		set(shape, spec, "stroke", DrawAttr.STROKE_COLOR, warnings);
		set(shape, spec, "fill", DrawAttr.FILL_COLOR, warnings);
		set(shape, spec, "stroke_width", DrawAttr.STROKE_WIDTH, warnings);
		set(shape, spec, "font_size", DrawAttr.FONT, warnings);
		if (Json.isPresent(spec, "paint_type")) {
			String type = spec.get("paint_type").getAsString().trim().toLowerCase();
			com.cburch.logisim.data.AttributeOption option = "stroke".equals(type) ? DrawAttr.PAINT_STROKE
					: "fill".equals(type) ? DrawAttr.PAINT_FILL
							: "both".equals(type) ? DrawAttr.PAINT_STROKE_FILL : null;
			if (option == null) {
				warnings.add("paint_type '" + type + "' (use \"stroke\", \"fill\" or \"both\")");
			} else if (shape.getAttributeSet().containsAttribute(DrawAttr.PAINT_TYPE)) {
				shape.setValue(DrawAttr.PAINT_TYPE, option);
			} else {
				warnings.add("paint_type (a " + kindOf(shape) + " has no filled/stroked choice)");
			}
		}
		if (Json.isPresent(spec, "align")) {
			String align = spec.get("align").getAsString().trim().toLowerCase();
			com.cburch.logisim.data.AttributeOption option = "left".equals(align) ? DrawAttr.ALIGN_LEFT
					: "center".equals(align) ? DrawAttr.ALIGN_CENTER
							: "right".equals(align) ? DrawAttr.ALIGN_RIGHT : null;
			if (option == null) {
				warnings.add("align '" + align + "' (use \"left\", \"center\" or \"right\")");
			} else if (shape.getAttributeSet().containsAttribute(DrawAttr.ALIGNMENT)) {
				shape.setValue(DrawAttr.ALIGNMENT, option);
			} else {
				warnings.add("align (only a text shape has an alignment)");
			}
		}
	}

	/** One attribute of a shape, if it has it. */
	@SuppressWarnings("unchecked")
	private static void set(CanvasObject shape, JsonObject spec, String key, Attribute<?> attribute,
			List<String> warnings) {
		if (!Json.isPresent(spec, key)) {
			return;
		}
		if (!shape.getAttributeSet().containsAttribute(attribute)) {
			warnings.add(key + " (a " + kindOf(shape) + " has no such attribute)");
			return;
		}
		Object value = jsonValue(spec.get(key), attribute);
		if (value == null) {
			warnings.add(key + " (could not read '" + spec.get(key).getAsString() + "')");
			return;
		}
		((CanvasObject) shape).setValue((Attribute<Object>) attribute, value);
	}

	/** Turns a JSON value into the kind of value an attribute holds. */
	private static Object jsonValue(JsonElement element, Attribute<?> attribute) {
		if (attribute == DrawAttr.STROKE_WIDTH || attribute == DrawAttr.CORNER_RADIUS) {
			return element.isJsonPrimitive() ? Integer.valueOf(element.getAsInt()) : null;
		}
		if (attribute == DrawAttr.FONT) {
			if (!element.isJsonPrimitive()) {
				return null;
			}
			if (element.getAsJsonPrimitive().isNumber()) {
				return new Font("Dialog", Font.PLAIN, element.getAsInt());
			}
			return new Font(element.getAsString(), Font.PLAIN, 12);
		}
		if (attribute == DrawAttr.STROKE_COLOR || attribute == DrawAttr.FILL_COLOR) {
			if (!element.isJsonPrimitive()) {
				return null;
			}
			return color(element.getAsString());
		}
		return null;
	}

	private static Color color(String text) {
		String value = text.trim();
		if (value.startsWith("#")) {
			value = value.substring(1);
		}
		try {
			if (value.length() == 6) {
				return new Color(Integer.parseInt(value, 16));
			}
			if (value.length() == 3) {
				int r = Integer.parseInt(value.substring(0, 1), 16);
				int g = Integer.parseInt(value.substring(1, 2), 16);
				int b = Integer.parseInt(value.substring(2, 3), 16);
				return new Color(r * 17, g * 17, b * 17);
			}
		} catch (NumberFormatException e) {
			// fall through to the named colours
		}
		if ("black".equalsIgnoreCase(value)) {
			return Color.BLACK;
		}
		if ("white".equalsIgnoreCase(value)) {
			return Color.WHITE;
		}
		if ("red".equalsIgnoreCase(value)) {
			return Color.RED;
		}
		if ("green".equalsIgnoreCase(value)) {
			return Color.GREEN;
		}
		if ("blue".equalsIgnoreCase(value)) {
			return Color.BLUE;
		}
		if ("grey".equalsIgnoreCase(value) || "gray".equalsIgnoreCase(value)) {
			return Color.GRAY;
		}
		if ("lightgrey".equalsIgnoreCase(value) || "lightgray".equalsIgnoreCase(value)) {
			return Color.LIGHT_GRAY;
		}
		return null;
	}

	/** A point argument: {@code [x, y]} or {@code "120,80"}, snapped to the grid. */
	static Location point(JsonObject spec, String key, Location fallback) {
		if (!Json.isPresent(spec, key)) {
			return fallback;
		}
		JsonElement element = spec.get(key);
		Location ret = element.isJsonArray() ? McpSession.parseLocation(element)
				: McpSession.tryParseLocation(element.getAsString());
		if (ret == null) {
			throw new McpException("'" + key + "' must be a point: [x, y], or \"x,y\"");
		}
		return snap(ret);
	}

	/** The area of a rect or an oval: 'at' plus 'size', or 'bounds'. */
	private static Bounds area(JsonObject spec) {
		Location at = point(spec, "at", null);
		JsonElement size = Json.isPresent(spec, "size") ? spec.get("size") : null;
		if (at != null && size != null) {
			if (!size.isJsonArray() || size.getAsJsonArray().size() != 2) {
				throw new McpException("'size' must be [width, height]");
			}
			int width = size.getAsJsonArray().get(0).getAsInt();
			int height = size.getAsJsonArray().get(1).getAsInt();
			if (width <= 0 || height <= 0) {
				throw new McpException("'size' must be positive, but was " + width + " by " + height);
			}
			return Bounds.create(at.getX(), at.getY(), width, height);
		}
		JsonElement box = Json.isPresent(spec, "bounds") ? spec.get("bounds") : null;
		if (box != null && box.isJsonArray() && box.getAsJsonArray().size() == 4) {
			JsonArray values = box.getAsJsonArray();
			return Bounds.create(values.get(0).getAsInt(), values.get(1).getAsInt(), values.get(2).getAsInt(),
					values.get(3).getAsInt());
		}
		throw new McpException("a rect or oval needs 'at' ([x, y]) and 'size' ([width, height]), or 'bounds' "
				+ "([x, y, width, height])");
	}

	/**
	 * Combines the changes of one call into a single undoable step and commits it.
	 *
	 * <p>
	 * The draw actions are joined with {@code Action.append}, so however many objects a call
	 * touched, the Edit menu shows one entry and one {@code logisim_undo} takes it all back.
	 * </p>
	 */
	public static void commit(McpSession session, Circuit circuit, List<Action> steps,
			List<CanvasObject> touched, String name) {
		if (steps.isEmpty()) {
			return;
		}
		Action combined = steps.get(0);
		for (int i = 1; i < steps.size(); i++) {
			combined = combined.append(steps.get(i));
		}
		session.commitAppearance(circuit, new AppearanceChange(circuit, combined, name), touched);
	}

	/**
	 * The position an object is moved <i>from</i> and <i>to</i>: the location of a port or the
	 * anchor, and the top left corner of a shape.
	 */
	public static Location locationOf(CanvasObject object) {
		if (object instanceof AppearanceElement) {
			return ((AppearanceElement) object).getLocation();
		}
		Bounds bounds = object.getBounds();
		return Location.create(bounds.getX(), bounds.getY());
	}

	/** One undoable step that hands the symbol back to Logisim's automatic layout. */
	public static Action resetStep(Circuit circuit) {
		return new ResetStep(circuit);
	}

	private static final class ResetStep extends Action {
		private final Circuit circuit;
		private List<CanvasObject> old;
		private boolean wasDefault;

		ResetStep(Circuit circuit) {
			this.circuit = circuit;
		}

		@Override
		public void doIt() {
			CircuitAppearance appearance = circuit.getAppearance();
			wasDefault = appearance.isDefaultAppearance();
			old = new ArrayList<CanvasObject>(appearance.getObjectsFromBottom());
			appearance.setDefaultAppearance(true);
		}

		@Override
		public void undo() {
			CircuitAppearance appearance = circuit.getAppearance();
			appearance.setObjectsForce(old);
			appearance.setDefaultAppearance(wasDefault);
		}

		@Override
		public String getName() {
			return "let Logisim lay out the appearance";
		}
	}

	/**
	 * One change to a symbol, as exactly one entry in Logisim's undo stack.
	 *
	 * <p>
	 * The GUI's appearance view wraps its draw actions in
	 * {@code gui.appear.CanvasActionAdapter}, which runs a change that touches a port inside a
	 * {@link com.cburch.logisim.circuit.CircuitTransaction} over the circuits <i>using</i> this
	 * one - that is what keeps the ports of every placed instance in step, because a port's
	 * position here is a port's position there. That adapter asks whether the action it was
	 * given <i>is</i> a {@code ModelAction} touching an appearance element; a call here is a
	 * union of several actions, so the question has to be asked of each step, and this class
	 * asks it by always taking the transaction path. That is the only reason it exists rather
	 * than handing the work to the GUI's own adapter.
	 * </p>
	 */
	private static final class AppearanceChange extends com.cburch.logisim.proj.Action {
		private final Circuit circuit;
		private final Action step;
		private final String name;
		private boolean wasDefault;

		AppearanceChange(Circuit circuit, Action step, String name) {
			this.circuit = circuit;
			this.step = step;
			this.name = name;
		}

		private final class StepTransaction extends com.cburch.logisim.circuit.CircuitTransaction {
			private final boolean forward;

			StepTransaction(boolean forward) {
				this.forward = forward;
			}

			@Override
			protected java.util.Map<Circuit, Integer> getAccessedCircuits() {
				java.util.Map<Circuit, Integer> access = new java.util.HashMap<Circuit, Integer>();
				java.util.Collection<Circuit> users = circuit.getCircuitsUsingThis();
				if (users != null) {
					for (Circuit user : users) {
						access.put(user, Integer.valueOf(READ_WRITE));
					}
				}
				return access;
			}

			@Override
			protected void run(com.cburch.logisim.circuit.CircuitMutator mutator,
					com.cburch.logisim.proj.Project proj) {
				if (forward) {
					step.doIt();
				} else {
					step.undo();
				}
			}
		}

		@Override
		public void doIt(com.cburch.logisim.proj.Project proj) {
			wasDefault = circuit.getAppearance().isDefaultAppearance();
			new StepTransaction(true).execute(proj);
		}

		@Override
		public void undo(com.cburch.logisim.proj.Project proj) {
			new StepTransaction(false).execute(proj);
			circuit.getAppearance().setDefaultAppearance(wasDefault);
		}

		@Override
		public String getName() {
			return name;
		}
	}

	// ------------------------------------------------------------------ drawing the symbol

	/**
	 * Draws the symbol into a graphics, which is how the agent gets to look at it: the same
	 * objects Logisim would draw, plus the label of each port and the rectangle an instance
	 * occupies. The appearance view draws the ports in its own way; this is a plain picture of
	 * the same thing, drawn with no display.
	 */
	public static void paint(Circuit circuit, Graphics g, java.util.List<String> problems, boolean portLabels) {
		CircuitAppearance appearance = circuit.getAppearance();
		for (CanvasObject object : objects(circuit)) {
			Graphics dup = g.create();
			try {
				object.paint(dup, null);
			} catch (Throwable t) {
				// a shape that insists on a drag gesture - Logisim's own Line does - cannot be drawn
				// without one, so it is skipped and named rather than losing the whole picture
				problems.add(nameOf(circuit, object) + " (" + kindOf(object) + ") could not be drawn: "
						+ McpSession.message(t));
			} finally {
				dup.dispose();
			}
		}
		if (portLabels) {
			Font font = new Font("Dialog", Font.PLAIN, 10);
			g.setFont(font);
			int minX = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE;
			int minY = Integer.MAX_VALUE;
			int maxY = Integer.MIN_VALUE;
			for (CanvasObject object : objects(circuit)) {
				if (!(object instanceof AppearancePort)) {
					continue;
				}
				Location loc = ((AppearancePort) object).getLocation();
				minX = Math.min(minX, loc.getX());
				maxX = Math.max(maxX, loc.getX());
				minY = Math.min(minY, loc.getY());
				maxY = Math.max(maxY, loc.getY());
			}
			for (CanvasObject object : objects(circuit)) {
				if (!(object instanceof AppearancePort)) {
					continue;
				}
				AppearancePort port = (AppearancePort) object;
				Location loc = port.getLocation();
				String label = pinName(port.getPin());
				if (label == null) {
					label = "?";
				}
				if (labelled(circuit, label)) {
					// the name was written into the symbol as a text shape, so drawing it here as well
					// would put it on the picture twice, a few units apart
					continue;
				}
				LabelPlacement place = placement(loc, minX, maxX, minY, maxY);
				int width = g.getFontMetrics().stringWidth(label);
				int dx = "right".equals(place.align) ? -width : "center".equals(place.align) ? -width / 2 : 0;
				// the shapes label_ports writes sit on the port's own row; a drawn string is given a
				// few units more so that its ink, which hangs from its baseline, looks level with it
				g.setColor(Color.DARK_GRAY);
				g.drawString(label, place.x + dx, place.y + 4);
			}
		}
		Bounds offset = appearance.getOffsetBounds();
		g.setColor(new Color(150, 150, 150));
		g.drawRect(offset.getX(), offset.getY(), offset.getWidth(), offset.getHeight());
	}

	/**
	 * The rectangle to draw for a symbol picture: everything it uses, with a margin. The
	 * offset bounds are always included, because that is the space a parent reserves for it.
	 */
	public static Bounds pictureArea(Circuit circuit) {
		Bounds used = bounds(circuit).add(circuit.getAppearance().getOffsetBounds());
		if (used.getWidth() <= 0 || used.getHeight() <= 0) {
			return Bounds.create(0, 0, 100, 100);
		}
		// a text shape reports no size until it has been painted once (its label measures itself
		// then), so the picture is given room for a label beside every edge it draws
		return used.expand(40);
	}
}
