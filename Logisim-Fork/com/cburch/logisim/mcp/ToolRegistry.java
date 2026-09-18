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
	private final Map<String, McpTool> tools = new LinkedHashMap<String, McpTool>();

	public void register(McpTool tool) {
		tools.put(tool.name(), tool);
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
