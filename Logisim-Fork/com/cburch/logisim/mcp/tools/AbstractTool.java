/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp.tools;

import com.cburch.logisim.mcp.McpTool;
import com.cburch.logisim.mcp.Schemas;

import com.google.gson.JsonObject;

/** Common plumbing shared by the tool implementations. */
public abstract class AbstractTool implements McpTool {
	private final String name;
	private final String description;
	private final JsonObject schema;

	protected AbstractTool(String name, String description, Schemas schema) {
		this(name, description, schema.build());
	}

	protected AbstractTool(String name, String description, JsonObject schema) {
		this.name = name;
		this.description = description;
		this.schema = schema;
	}

	@Override
	public final String name() {
		return name;
	}

	@Override
	public final String description() {
		return description;
	}

	@Override
	public final JsonObject inputSchema() {
		return schema;
	}

	/** Convenience for the (very common) tool that takes no arguments at all. */
	protected static JsonObject noArguments() {
		return Schemas.object().allowAdditional(false).build();
	}
}
