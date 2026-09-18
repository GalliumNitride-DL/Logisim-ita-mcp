/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One callable MCP tool.
 *
 * <p>
 * The description and the input schema are what an AI agent reads before it
 * decides to call the tool, so both are deliberately written as documentation
 * for a reader that has never seen Logisim.
 * </p>
 */
public interface McpTool {
	/** Unique tool name, as exposed to the agent (for example "logisim_status"). */
	String name();

	/** One paragraph explaining what the tool does and when to use it. */
	String description();

	/** JSON schema of the arguments object. */
	JsonObject inputSchema();

	/**
	 * Runs the tool. Throwing {@link McpException} (or anything else) results in a
	 * tool error result carrying the message, never in a crashed server.
	 */
	JsonElement call(ToolContext context) throws Exception;
}
