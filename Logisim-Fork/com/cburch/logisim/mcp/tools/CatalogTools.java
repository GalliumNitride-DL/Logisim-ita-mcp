/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.mcp.Json;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;
import com.cburch.logisim.mcp.ToolContext;
import com.cburch.logisim.mcp.McpSession.CatalogEntry;
import com.cburch.logisim.mcp.McpInstructions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Tools that let an agent discover what it can build with. */
public final class CatalogTools {
	/** Said instead of a port list when the component cannot be built outside a circuit. */
	private static final String PORTS_UNKNOWN = "this component's pins cannot be listed without placing it";

	/** Said beside an empty port list, so that "no pins" is an answer rather than a silence. */
	private static final String PORTS_NONE = "this component has no pins";

	private CatalogTools() {
	}

	public static McpTool libraryList(final McpSession session) {
		return new AbstractTool("logisim_library_list", McpInstructions.LIBRARY_LIST,
				Schemas.object()) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
				for (CatalogEntry entry : session.catalog()) {
					Integer previous = counts.get(entry.library);
					counts.put(entry.library, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
				}
				JsonArray libraries = new JsonArray();
				for (Map.Entry<String, Integer> entry : counts.entrySet()) {
					JsonObject item = new JsonObject();
					item.addProperty("name", entry.getKey());
					item.addProperty("component_count", entry.getValue());
					libraries.add(item);
				}
				JsonObject ret = new JsonObject();
				ret.add("libraries", libraries);
				ret.addProperty("hint", "call logisim_component_catalog with a 'name' filter to see one "
						+ "component's attributes and ports");
				return ret;
			}
		};
	}

	public static McpTool componentCatalog(final McpSession session) {
		return new AbstractTool("logisim_component_catalog", McpInstructions.COMPONENT_CATALOG,
				Schemas.object()
						.opt("lib", Schemas.string("Restrict to one library, for example 'Wiring', 'Memory', "
								+ "'Gates' or 'Circuits'"))
						.opt("name", Schemas.string("Restrict to components whose name or display name contains "
								+ "this text (case insensitive)"))
						.opt("verbose", Schemas.bool("Always include the full attribute list (otherwise it is "
								+ "included only for small result sets)"))
						.opt("terse", Schemas.bool("Include attribute names and port names, directions and "
								+ "widths for every match, without defaults, option lists, hints or positions. "
								+ "This is the cheap way to ask \"does this component have a label?\" - a 9 "
								+ "component library answers in a couple of hundred characters instead of "
								+ "twelve thousand"))
						.opt("limit", Schemas.integer("Maximum number of components to return (default 200)"))) {
			@Override
			public JsonElement call(ToolContext ctx) {
				session.requireOpen();
				String libFilter = ctx.optString("lib", null);
				String nameFilter = ctx.optString("name", null);
				boolean verbose = ctx.optBool("verbose", false);
				int limit = ctx.optInt("limit", Integer.valueOf(200)).intValue();

				List<CatalogEntry> matches = new ArrayList<CatalogEntry>();
				for (CatalogEntry entry : session.catalog()) {
					if (libFilter != null && !libFilter.isEmpty()
							&& !entry.library.equalsIgnoreCase(libFilter.trim())) {
						continue;
					}
					if (nameFilter != null && !nameFilter.isEmpty()) {
						String needle = nameFilter.toLowerCase();
						if (!entry.name.toLowerCase().contains(needle)
								&& !entry.display.toLowerCase().contains(needle)) {
							continue;
						}
					}
					matches.add(entry);
				}
				boolean terse = ctx.optBool("terse", false);
				boolean withAttributes = verbose || terse || matches.size() <= 8;
				boolean withPorts = verbose || (!terse && matches.size() <= 8);

				JsonArray components = new JsonArray();
				int emitted = 0;
				for (CatalogEntry entry : matches) {
					if (emitted++ >= limit) {
						break;
					}
					JsonObject item = new JsonObject();
					item.addProperty("lib", entry.library);
					item.addProperty("type", entry.name);
					item.addProperty("display", entry.display);
					if (entry.subcircuit) {
						item.addProperty("subcircuit", Boolean.TRUE);
						JsonArray pins = new JsonArray();
						for (Instance pin : session.pins(session.circuitByName(entry.name))) {
							pins.add(session.describePin(pin));
						}
						item.add("pins", pins);
					} else {
						Integer count = portCount(entry.factory);
						if (count != null) {
							item.addProperty("port_count", count);
						}
						if (withPorts) {
							JsonArray ports = describeFactoryPorts(entry.factory, terse);
							if (ports == null) {
								item.addProperty("ports_note", PORTS_UNKNOWN);
							} else {
								item.add("ports", ports);
								if (ports.size() == 0) {
									item.addProperty("ports_note", PORTS_NONE);
								}
							}
						}
					}
					if (withAttributes) {
						AttributeSet defaults = null;
						try {
							defaults = entry.factory.createAttributeSet();
						} catch (Throwable t) {
							defaults = null;
						}
						if (defaults != null) {
							JsonArray attributes = new JsonArray();
							for (Attribute<?> attribute : defaults.getAttributes()) {
								if (!defaults.isToSave(attribute)) {
									continue;
								}
								attributes.add(session.describeAttributeSchema(attribute, defaults));
							}
							item.add("attributes", attributes);
						}
					}
					components.add(item);
				}

				JsonObject ret = new JsonObject();
				ret.addProperty("match_count", matches.size());
				ret.addProperty("returned", components.size());
				ret.addProperty("attributes_included", Boolean.valueOf(withAttributes));
				ret.addProperty("ports_included", Boolean.valueOf(withPorts));
				ret.addProperty("terse", Boolean.valueOf(terse));
				ret.add("components", components);
				return ret;
			}
		};
	}

	/** Number of ports, for factories whose components have them. */
	static Integer portCount(com.cburch.logisim.comp.ComponentFactory factory) {
		com.cburch.logisim.comp.Component probe = factoryProbe(factory);
		if (probe == null) {
			return null;
		}
		try {
			return Integer.valueOf(probe.getEnds().size());
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * A component of this type, built from the factory's default attributes and left off the
	 * canvas, so that the library works out its ports the way it does for a component the user
	 * placed: a flip-flop, a counter, a RAM and a splitter all compute their pins from their
	 * attributes, and the factory on its own has none to report.
	 *
	 * <p>
	 * Nothing is added to any circuit - the component is created, asked for its ends and
	 * dropped - and it is the same object the placed-component path describes, so the catalogue
	 * and a component on the canvas cannot disagree about a pin. Null when the factory cannot
	 * build one at all, which the caller reports instead of hiding.
	 * </p>
	 */
	private static com.cburch.logisim.comp.Component factoryProbe(
			com.cburch.logisim.comp.ComponentFactory factory) {
		try {
			AttributeSet defaults = factory.createAttributeSet();
			return factory.createComponent(com.cburch.logisim.data.Location.create(0, 0), defaults);
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * One attribute by name, for the terse catalogue mode: enough to answer "does this
	 * component have a label?" without the defaults, hints and option lists.
	 */
	private static JsonObject attributeName(Attribute<?> attribute) {
		JsonObject ret = new JsonObject();
		ret.addProperty("name", attribute.getName());
		return ret;
	}

	/**
	 * The ports a component of this type has, with the library's own name for each one.
	 *
	 * <p>
	 * This is the answer to "what does each pin do": a name such as "clock", "clear" or
	 * "carry out", the direction the value travels, the width, and where the port sits
	 * relative to the component's anchor. The ports come from the component the library
	 * would build with its default attributes, so a component whose shape depends on its
	 * attributes is described in that default shape - the component a tool actually places
	 * reports its own ports, read the same way, with the same names.
	 * </p>
	 */
	static JsonArray describeFactoryPorts(com.cburch.logisim.comp.ComponentFactory factory, boolean terse) {
		com.cburch.logisim.comp.Component probe = factoryProbe(factory);
		if (probe == null) {
			return null;
		}
		java.util.List<com.cburch.logisim.comp.EndData> ends;
		try {
			ends = probe.getEnds();
		} catch (Throwable t) {
			return null;
		}
		if (ends == null) {
			return null;
		}
		List<String> names = McpSession.portNames(probe);
		JsonArray ret = new JsonArray();
		for (int index = 0; index < ends.size(); index++) {
			com.cburch.logisim.comp.EndData end = ends.get(index);
			JsonObject item = new JsonObject();
			item.addProperty("index", Integer.valueOf(index));
			if (names != null && index < names.size()) {
				String name = names.get(index);
				if (name != null && !name.isEmpty()) {
					item.addProperty("name", name);
				}
			}
			if (end != null) {
				try {
					com.cburch.logisim.data.Location at = end.getLocation();
					if (at != null && !terse) {
						item.add("at", Json.array(Integer.valueOf(at.getX()), Integer.valueOf(at.getY())));
					}
					McpSession.addWidth(item, end.getWidth());
					item.addProperty("direction", McpSession.portDirection(factory, index, end));
					if (com.cburch.logisim.mcp.PortFixups.isCorrected(factory.getName(), index)) {
						item.addProperty("direction_note", com.cburch.logisim.mcp.PortFixups.NOTE);
					}
				} catch (Throwable t) {
					// a port whose width comes from an attribute that cannot be resolved here
				}
			}
			ret.add(item);
		}
		return ret;
	}

	/** Helper used by the edit tools to produce an actionable error message. */
	static String attributeNames(AttributeSet attrs) {		StringBuilder buf = new StringBuilder();
		for (Attribute<?> attribute : attrs.getAttributes()) {
			if (buf.length() > 0) {
				buf.append(", ");
			}
			buf.append(attribute.getName());
		}
		return buf.length() == 0 ? "(none)" : buf.toString();
	}

	static JsonObject attributesToJson(AttributeSet attrs) {
		JsonObject ret = new JsonObject();
		for (Attribute<?> attribute : attrs.getAttributes()) {
			if (!attrs.isToSave(attribute)) {
				continue;
			}
			ret.add(attribute.getName(), Json.element(String.valueOf(attrs.getValue(attribute))));
		}
		return ret;
	}
}

