/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tiny fluent JSON-Schema builder used to describe tool arguments. Only the
 * keywords needed by the Logisim tools are supported.
 */
public final class Schemas {
	private final JsonObject properties = new JsonObject();
	private final JsonArray required = new JsonArray();
	private boolean additionalProperties = false;

	private Schemas() {
	}

	public static Schemas object() {
		return new Schemas();
	}

	/** Declares a required property. */
	public Schemas req(String name, JsonObject schema) {
		properties.add(name, schema);
		required.add(name);
		return this;
	}

	/** Declares an optional property. */
	public Schemas opt(String name, JsonObject schema) {
		properties.add(name, schema);
		return this;
	}

	public Schemas allowAdditional(boolean allow) {
		this.additionalProperties = allow;
		return this;
	}

	public JsonObject build() {
		JsonObject ret = new JsonObject();
		ret.addProperty("type", "object");
		ret.add("properties", properties);
		if (required.size() > 0) {
			ret.add("required", required);
		}
		ret.addProperty("additionalProperties", Boolean.valueOf(additionalProperties));
		return ret;
	}

	public static JsonObject string(String description) {
		return type("string", description);
	}

	public static JsonObject integer(String description) {
		return type("integer", description);
	}

	public static JsonObject number(String description) {
		return type("number", description);
	}

	public static JsonObject bool(String description) {
		return type("boolean", description);
	}

	public static JsonObject any(String description) {
		JsonObject ret = new JsonObject();
		ret.addProperty("description", description);
		return ret;
	}

	/** An object with free-form, agent supplied members. */
	public static JsonObject freeObject(String description) {
		JsonObject ret = new JsonObject();
		ret.addProperty("type", "object");
		ret.addProperty("description", description);
		ret.addProperty("additionalProperties", Boolean.TRUE);
		return ret;
	}

	public static JsonObject stringEnum(String description, String... values) {
		JsonObject ret = type("string", description);
		JsonArray options = new JsonArray();
		for (String value : values) {
			options.add(value);
		}
		ret.add("enum", options);
		return ret;
	}

	public static JsonObject array(JsonObject items, String description) {
		JsonObject ret = new JsonObject();
		ret.addProperty("type", "array");
		ret.addProperty("description", description);
		ret.add("items", items);
		return ret;
	}

	/** A {@code [x, y]} pair in Logisim coordinates. */
	public static JsonObject point(String description) {
		JsonObject ret = new JsonObject();
		ret.addProperty("type", "array");
		ret.addProperty("description", description);
		JsonArray items = new JsonArray();
		items.add(type("integer", "coordinate"));
		ret.add("items", items);
		ret.addProperty("minItems", 2);
		ret.addProperty("maxItems", 2);
		return ret;
	}

	/**
	 * A component reference: either an id string ("c3"), a label ("A") or an object
	 * with one of the members {@code id}, {@code label} or {@code loc}.
	 */
	public static JsonObject componentRef(String description) {
		JsonObject ret = new JsonObject();
		ret.addProperty("description", description + " Use the component id returned by "
				+ "logisim_circuit_describe / logisim_component_add, a component label, or "
				+ "{\"loc\": [x, y]}.");
		JsonArray types = new JsonArray();
		types.add("string");
		types.add("object");
		ret.add("type", types);
		return ret;
	}

	private static JsonObject type(String type, String description) {
		JsonObject ret = new JsonObject();
		ret.addProperty("type", type);
		if (description != null) {
			ret.addProperty("description", description);
		}
		return ret;
	}
}
