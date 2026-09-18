/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** The set of tools this server exposes, in registration order. */
public class ToolRegistry {
	/**
	 * The tools that change the project or write to disk. Read-only mode refuses these
	 * and allows everything else, so inspection, simulation, the netlist and
	 * highlighting all keep working while the user has the agent on a leash.
	 *
	 * <p>
	 * Keep this in step when a tool that changes something is added: it is the one
	 * place the decision lives, deliberately, so that it can be read at a glance.
	 * </p>
	 */
	private static final java.util.Set<String> MUTATING = new java.util.HashSet<String>(java.util.Arrays.asList(
			"logisim_component_add", "logisim_component_remove", "logisim_component_move",
			"logisim_component_set_attributes", "logisim_wire_add", "logisim_wire_remove",
			"logisim_circuit_build", "logisim_circuit_repair_wires", "logisim_file_save", "logisim_file_save_as"));

	/**
	 * The tools that stay callable while the agent is paused: the status read, and the two
	 * switches themselves. Without this a pause would be a trap - the agent could not even
	 * report what it was doing, and (until the Logisim window exists) nothing could release
	 * it - which is not what a brake is for.
	 */
	private static final java.util.Set<String> PAUSE_EXEMPT = new java.util.HashSet<String>(java.util.Arrays.asList(
			"logisim_gui_status", "logisim_gui_pause", "logisim_gui_read_only"));

	private final Map<String, McpTool> tools = new LinkedHashMap<String, McpTool>();

	public void register(McpTool tool) {
		tools.put(tool.name(), tool);
	}

	/** True when this tool changes the project or writes a file. */
	public static boolean isMutating(String name) {
		return name != null && MUTATING.contains(name);
	}

	/** True when this tool may run even while the user has the agent paused. */
	public static boolean isPauseExempt(String name) {
		return name != null && PAUSE_EXEMPT.contains(name);
	}

	public McpTool find(String name) {
		return name == null ? null : tools.get(name);
	}

	public Collection<McpTool> all() {
		return tools.values();
	}

	public int size() {
		return tools.size();
	}

	/** The {@code tools/list} payload. */
	public JsonArray definitions() {
		JsonArray ret = new JsonArray();
		for (McpTool tool : tools.values()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("name", tool.name());
			entry.addProperty("description", tool.description());
			entry.add("inputSchema", tool.inputSchema());
			ret.add(entry);
		}
		return ret;
	}

	/** Comma separated list of tool names, used in error messages. */
	public String names() {
		StringBuilder buf = new StringBuilder();
		for (String name : tools.keySet()) {
			if (buf.length() > 0) {
				buf.append(", ");
			}
			buf.append(name);
		}
		return buf.toString();
	}
}
