/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Typed access to the arguments of one tool call, with the validation errors an
 * agent can recover from ("argument 'x' is required and must be an integer").
 */
public class ToolContext {
	private final McpSession session;
	private final JsonObject arguments;
	private final String toolName;

	public ToolContext(McpSession session, String toolName, JsonObject arguments) {
		this.session = session;
		this.toolName = toolName;
		this.arguments = arguments == null ? new JsonObject() : arguments;
	}

	public McpSession session() {
		return session;
	}

	public JsonObject arguments() {
		return arguments;
	}

	public String toolName() {
		return toolName;
	}

	public boolean has(String name) {
		return Json.isPresent(arguments, name);
	}

	private McpException failure(String name, String expectation) {
		return new McpException("argument '" + name + "' of " + toolName + " must be " + expectation);
	}

	public String requireString(String name) {
		String ret = Json.stringOrNull(arguments, name);
		if (ret == null || ret.isEmpty()) {
			throw failure(name, "a non-empty string");
		}
		return ret;
	}

	public String optString(String name, String fallback) {
		String ret = Json.stringOrNull(arguments, name);
		return ret == null ? fallback : ret;
	}

	public int requireInt(String name) {
		Integer ret = optInt(name, null);
		if (ret == null) {
			throw failure(name, "an integer");
		}
		return ret.intValue();
	}

	public Integer optInt(String name, Integer fallback) {
		if (!has(name)) {
			return fallback;
		}
		JsonElement element = arguments.get(name);
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			return Integer.valueOf(element.getAsInt());
		}
		if (element.isJsonPrimitive()) {
			try {
				return Integer.valueOf(Integer.parseInt(element.getAsString().trim()));
			} catch (NumberFormatException e) {
				throw failure(name, "an integer");
			}
		}
		throw failure(name, "an integer");
	}

	public Double optDouble(String name, Double fallback) {
		if (!has(name)) {
			return fallback;
		}
		JsonElement element = arguments.get(name);
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			return Double.valueOf(element.getAsDouble());
		}
		if (element.isJsonPrimitive()) {
			try {
				return Double.valueOf(Double.parseDouble(element.getAsString().trim()));
			} catch (NumberFormatException e) {
				throw failure(name, "a number");
			}
		}
		throw failure(name, "a number");
	}

	public boolean optBool(String name, boolean fallback) {
		if (!has(name)) {
			return fallback;
		}
		JsonElement element = arguments.get(name);
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
			return element.getAsBoolean();
		}
		if (element.isJsonPrimitive()) {
			String text = element.getAsString().trim();
			if (text.equalsIgnoreCase("true")) {
				return true;
			}
			if (text.equalsIgnoreCase("false")) {
				return false;
			}
		}
		throw failure(name, "a boolean");
	}

	public JsonObject optObject(String name) {
		if (!has(name)) {
			return null;
		}
		JsonElement element = arguments.get(name);
		if (!element.isJsonObject()) {
			throw failure(name, "an object");
		}
		return element.getAsJsonObject();
	}

	public JsonObject requireObject(String name) {
		JsonObject ret = optObject(name);
		if (ret == null) {
			throw failure(name, "an object");
		}
		return ret;
	}

	public JsonArray optArray(String name) {
		if (!has(name)) {
			return null;
		}
		JsonElement element = arguments.get(name);
		if (!element.isJsonArray()) {
			throw failure(name, "an array");
		}
		return element.getAsJsonArray();
	}

	public JsonArray requireArray(String name) {
		JsonArray ret = optArray(name);
		if (ret == null) {
			throw failure(name, "an array");
		}
		return ret;
	}

	/** Returns the raw value of a named member, or null when it is absent. */
	public JsonElement optElement(String name) {
		return has(name) ? arguments.get(name) : null;
	}
}
