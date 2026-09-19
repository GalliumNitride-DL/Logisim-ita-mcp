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
				boolean withAttributes = verbose || matches.size() <= 8;
				boolean withPorts = verbose || matches.size() <= 8;

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
						item.addProperty("port_count", portCount(entry.factory));
						if (withPorts) {
							JsonArray ports = describeFactoryPorts(entry.factory);
							if (ports != null) {
								item.add("ports", ports);
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
				ret.add("components", components);
				return ret;
			}
		};
	}

	/** Number of ports, for factories whose components have them. */
	static Integer portCount(com.cburch.logisim.comp.ComponentFactory factory) {
		java.util.List<com.cburch.logisim.instance.Port> ports = factoryPorts(factory);
		return ports == null ? null : Integer.valueOf(ports.size());
	}

	/**
	 * The ports of a component of this type, built from a throwaway instance so that the
	 * library computes them the same way it does for a component on the canvas: many
	 * components (a flip-flop, a counter, a RAM) work out their ports from their
	 * attributes, and their factory on its own has none to report.
	 */
	private static java.util.List<com.cburch.logisim.instance.Port> factoryPorts(
			com.cburch.logisim.comp.ComponentFactory factory) {
		if (!(factory instanceof com.cburch.logisim.instance.InstanceFactory)) {
			return null;
		}
		try {
			AttributeSet defaults = factory.createAttributeSet();
			com.cburch.logisim.comp.Component probe = factory.createComponent(
					com.cburch.logisim.data.Location.create(0, 0), defaults);
			return com.cburch.logisim.instance.Instance.getInstanceFor(probe).getPorts();
		} catch (Throwable t) {
			return null;
		}
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
	 * reports its own ports, with the same names.
	 * </p>
	 */
	static JsonArray describeFactoryPorts(com.cburch.logisim.comp.ComponentFactory factory) {
		java.util.List<com.cburch.logisim.instance.Port> ports = factoryPorts(factory);
		if (ports == null) {
			return null;
		}
		AttributeSet defaults;
		try {
			defaults = factory.createAttributeSet();
		} catch (Throwable t) {
			return null;
		}
		JsonArray ret = new JsonArray();
		int index = 0;
		for (com.cburch.logisim.instance.Port port : ports) {
			final int portIndex = index++;
			JsonObject item = new JsonObject();
			item.addProperty("index", Integer.valueOf(portIndex));
			String name = port.getToolTip();
			if (name != null && !name.isEmpty()) {
				item.addProperty("name", name);
			}
			try {
				com.cburch.logisim.comp.EndData end = port.toEnd(com.cburch.logisim.data.Location.create(0, 0),
						defaults);
				item.add("at", Json.array(Integer.valueOf(end.getLocation().getX()),
						Integer.valueOf(end.getLocation().getY())));
				item.addProperty("width", Integer.valueOf(end.getWidth().getWidth()));
				String corrected = com.cburch.logisim.mcp.PortFixups.correction(factory.getName(), portIndex);
				item.addProperty("direction",
						corrected != null ? corrected : McpSession.endType(end.getType()));
				if (corrected != null) {
					item.addProperty("direction_note", com.cburch.logisim.mcp.PortFixups.NOTE);
				}
			} catch (Throwable t) {
				// a port whose width comes from an attribute that cannot be resolved here
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

