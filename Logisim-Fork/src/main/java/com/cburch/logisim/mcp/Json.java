/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.util.Collection;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Small helpers around Gson, used by the MCP layer only. Keeping every JSON
 * construction in one place makes the tool implementations much easier to read.
 *
 * Note: Logisim already depends on Gson (it is used by
 * {@code com.cburch.logisim.plugin.Json}), so the MCP layer introduces no new
 * third-party dependency at all.
 */
public final class Json {
	private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();
	private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	private Json() {
	}

	public static JsonObject object() {
		return new JsonObject();
	}

	/** Builds an object from alternating key/value arguments. */
	public static JsonObject object(Object... keyValues) {
		if (keyValues.length % 2 != 0) {
			throw new IllegalArgumentException("expected an even number of arguments");
		}
		JsonObject ret = new JsonObject();
		for (int i = 0; i < keyValues.length; i += 2) {
			ret.add(String.valueOf(keyValues[i]), element(keyValues[i + 1]));
		}
		return ret;
	}

	public static JsonArray array() {
		return new JsonArray();
	}

	public static JsonArray array(Collection<?> values) {
		JsonArray ret = new JsonArray();
		if (values != null) {
			for (Object value : values) {
				ret.add(element(value));
			}
		}
		return ret;
	}

	public static JsonArray array(int... values) {
		JsonArray ret = new JsonArray();
		for (int value : values) {
			ret.add(Integer.valueOf(value));
		}
		return ret;
	}

	public static JsonArray array(String... values) {
		JsonArray ret = new JsonArray();
		for (String value : values) {
			ret.add(value);
		}
		return ret;
	}

	public static JsonArray array(JsonElement... values) {
		JsonArray ret = new JsonArray();
		for (JsonElement value : values) {
			ret.add(value);
		}
		return ret;
	}

	/** Converts a plain Java value into a JSON element, recursively. */
	public static JsonElement element(Object value) {
		if (value == null) {
			return JsonNull.INSTANCE;
		}
		if (value instanceof JsonElement) {
			return (JsonElement) value;
		}
		if (value instanceof Boolean) {
			return new JsonPrimitive((Boolean) value);
		}
		if (value instanceof Number) {
			return new JsonPrimitive((Number) value);
		}
		if (value instanceof Character) {
			return new JsonPrimitive(String.valueOf(value));
		}
		if (value instanceof String) {
			return new JsonPrimitive((String) value);
		}
		if (value instanceof Map) {
			JsonObject ret = new JsonObject();
			for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
				ret.add(String.valueOf(entry.getKey()), element(entry.getValue()));
			}
			return ret;
		}
		if (value instanceof Collection) {
			return array((Collection<?>) value);
		}
		if (value instanceof Object[]) {
			return array(java.util.Arrays.asList((Object[]) value));
		}
		return new JsonPrimitive(String.valueOf(value));
	}

	public static JsonElement parse(String text) {
		return JsonParser.parseString(text);
	}

	public static JsonObject parseObject(String text) {
		JsonElement ret = parse(text);
		if (!ret.isJsonObject()) {
			throw new IllegalArgumentException("expected a JSON object");
		}
		return ret.getAsJsonObject();
	}

	public static String compact(JsonElement element) {
		return COMPACT.toJson(element);
	}

	public static String pretty(JsonElement element) {
		return PRETTY.toJson(element);
	}

	/** Reads a JSON primitive as a String, returning null when absent/null. */
	public static String stringOrNull(JsonObject obj, String member) {
		if (obj == null || !obj.has(member)) {
			return null;
		}
		JsonElement element = obj.get(member);
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (element.isJsonPrimitive()) {
			return element.getAsString();
		}
		return element.toString();
	}

	public static boolean isPresent(JsonObject obj, String member) {
		return obj != null && obj.has(member) && !obj.get(member).isJsonNull();
	}
}
