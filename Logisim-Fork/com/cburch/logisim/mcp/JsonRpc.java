/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * JSON-RPC 2.0 message construction, as used by MCP. Only the subset needed for
 * a tools-only MCP server is implemented, but the wire format is complete and
 * spec compliant.
 */
public final class JsonRpc {
	public static final String VERSION = "2.0";

	// Standard JSON-RPC 2.0 error codes.
	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;
	/** Implementation defined server error (JSON-RPC reserved range). */
	public static final int SERVER_ERROR = -32000;

	private JsonRpc() {
	}

	public static JsonElement normalizeId(JsonElement id) {
		return id == null ? JsonNull.INSTANCE : id;
	}

	public static JsonObject success(JsonElement id, JsonElement result) {
		JsonObject ret = new JsonObject();
		ret.addProperty("jsonrpc", VERSION);
		ret.add("id", normalizeId(id));
		ret.add("result", result == null ? new JsonObject() : result);
		return ret;
	}

	public static JsonObject error(JsonElement id, int code, String message) {
		return error(id, code, message, null);
	}

	public static JsonObject error(JsonElement id, int code, String message, JsonElement data) {
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message == null ? "error" : message);
		if (data != null) {
			err.add("data", data);
		}
		JsonObject ret = new JsonObject();
		ret.addProperty("jsonrpc", VERSION);
		ret.add("id", normalizeId(id));
		ret.add("error", err);
		return ret;
	}

	public static JsonObject notification(String method, JsonElement params) {
		JsonObject ret = new JsonObject();
		ret.addProperty("jsonrpc", VERSION);
		ret.addProperty("method", method);
		if (params != null) {
			ret.add("params", params);
		}
		return ret;
	}
}
