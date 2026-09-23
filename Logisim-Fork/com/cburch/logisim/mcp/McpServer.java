/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.io.PrintStream;

import com.cburch.logisim.Main;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The MCP protocol loop: reads JSON-RPC messages, answers {@code initialize},
 * {@code tools/list} and {@code tools/call}, and reports every failure in a way
 * the calling agent can act upon.
 *
 * <p>
 * Only the subset of MCP needed to expose tools is advertised, which keeps the
 * server easy to audit. Nothing is ever written to the transport except
 * well-formed JSON-RPC: Logisim's own diagnostics are redirected to stderr by
 * {@link McpMain}.
 * </p>
 */
public class McpServer {
	/** Protocol revisions understood by this server, newest first. */
	private static final String[] SUPPORTED_PROTOCOLS = { "2025-06-18", "2025-03-26", "2024-11-05", "2024-10-07" };

	private final McpTransport transport;
	private final McpSession session;
	private final ToolRegistry registry;
	private final PrintStream log;

	private String protocolVersion = SUPPORTED_PROTOCOLS[0];
	private boolean initialized;

	public McpServer(McpTransport transport, McpSession session, ToolRegistry registry, PrintStream log) {
		this.transport = transport;
		this.session = session;
		this.registry = registry;
		this.log = log;
	}

	/** Serves messages until the peer closes the transport. */
	public void run() {
		while (true) {
			String message;
			try {
				message = transport.readMessage();
			} catch (Exception e) {
				log.println("[logisim-mcp] transport read failed: " + e);
				return;
			}
			if (message == null) {
				return;
			}
			if (message.trim().isEmpty()) {
				continue;
			}
			String reply = handleMessage(message);
			if (reply != null) {
				send(reply);
			}
		}
	}

	/**
	 * Handles one JSON-RPC message and returns the reply that should be sent, or null
	 * when the message needs no reply (a notification). Stream transports call this
	 * through {@link #run()}; a request driven transport such as HTTP calls it once per
	 * request, which is why it returns the reply instead of writing it.
	 */
	public String handleMessage(String message) {
		JsonObject request;
		try {
			request = Json.parseObject(message);
		} catch (Exception e) {
			return Json.compact(JsonRpc.error(null, JsonRpc.PARSE_ERROR, "invalid JSON: " + e.getMessage()));
		}

		JsonElement id = Json.isPresent(request, "id") ? request.get("id") : null;
		String method = Json.stringOrNull(request, "method");
		if (method == null) {
			if (id != null) {
				return Json.compact(JsonRpc.error(id, JsonRpc.INVALID_REQUEST, "message has no 'method' member"));
			}
			return null;
		}

		JsonObject params = null;
		if (Json.isPresent(request, "params")) {
			JsonElement raw = request.get("params");
			if (raw.isJsonObject()) {
				params = raw.getAsJsonObject();
			}
		}

		if (id == null) {
			handleNotification(method);
			return null;
		}

		try {
			JsonElement result = dispatch(method, params);
			return Json.compact(JsonRpc.success(id, result));
		} catch (McpException e) {
			return Json.compact(JsonRpc.error(id, e.getCode(), e.getMessage()));
		} catch (Throwable t) {
			log.println("[logisim-mcp] internal error handling " + method + ": " + t);
			t.printStackTrace(log);
			return Json.compact(JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, McpSession.message(t)));
		}
	}

	/** The name of the method in a request, for logging; null when it cannot be read. */
	public static String methodOf(String message) {
		try {
			return Json.stringOrNull(Json.parseObject(message), "method");
		} catch (Exception e) {
			return null;
		}
	}

	private void handleNotification(String method) {
		// notifications/initialized, notifications/cancelled and friends need no reply
		if ("notifications/initialized".equals(method)) {
			initialized = true;
		}
	}

	private JsonElement dispatch(String method, JsonObject params) throws Exception {
		if ("initialize".equals(method)) {
			return initialize(params);
		}
		if ("ping".equals(method)) {
			return new JsonObject();
		}
		if ("tools/list".equals(method)) {
			return Json.object("tools", registry.definitions());
		}
		if ("tools/call".equals(method)) {
			return callTool(params);
		}
		if ("logging/setLevel".equals(method)) {
			return new JsonObject();
		}
		if ("resources/list".equals(method) || "prompts/list".equals(method)) {
			throw McpException.invalidParams("this Logisim MCP server exposes tools only");
		}
		throw new McpException(JsonRpc.METHOD_NOT_FOUND, "unknown method '" + method + "'");
	}

	private JsonElement initialize(JsonObject params) {
		String requested = Json.stringOrNull(params, "protocolVersion");
		if (requested != null) {
			boolean supported = false;
			for (String candidate : SUPPORTED_PROTOCOLS) {
				if (candidate.equals(requested)) {
					supported = true;
					break;
				}
			}
			protocolVersion = supported ? requested : SUPPORTED_PROTOCOLS[0];
		}
		JsonObject toolsCapability = new JsonObject();
		toolsCapability.addProperty("listChanged", Boolean.FALSE);
		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", toolsCapability);

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", "logisim-mcp");
		serverInfo.addProperty("title", "Logisim");
		serverInfo.addProperty("version", Main.VERSION_NAME);

		JsonObject ret = new JsonObject();
		ret.addProperty("protocolVersion", protocolVersion);
		ret.add("capabilities", capabilities);
		ret.add("serverInfo", serverInfo);
		ret.addProperty("instructions", McpInstructions.server(session.isLive()));
		return ret;
	}

	private JsonElement callTool(JsonObject params) {
		String name = Json.stringOrNull(params, "name");
		if (name == null) {
			throw McpException.invalidParams("tools/call requires a 'name'");
		}
		McpTool tool = registry.find(name);
		if (tool == null) {
			throw McpException.invalidParams("unknown tool '" + name + "'; available tools: " + registry.names());
		}
		JsonObject arguments = new JsonObject();
		if (Json.isPresent(params, "arguments")) {
			JsonElement raw = params.get("arguments");
			if (raw.isJsonObject()) {
				arguments = raw.getAsJsonObject();
			} else if (!raw.isJsonNull()) {
				throw McpException.invalidParams("'arguments' must be an object");
			}
		}
		final McpTool target = tool;
		final JsonObject args = arguments;
		// One call, one trip to the event dispatch thread in live mode: adopt whatever the
		// user is looking at, refuse when the user has paused or read-only mode is on, and
		// highlight what the call changed when it is done.
		java.util.concurrent.Callable<JsonElement> body = new java.util.concurrent.Callable<JsonElement>() {
			@Override
			public JsonElement call() throws Exception {
				session.refresh();
				if (session.isPaused() && !ToolRegistry.isPauseExempt(target.name())) {
					String reason = session.pausedReason();
					throw new McpException("the user has paused the agent"
							+ (reason == null || reason.isEmpty() ? "" : " (" + reason + ")")
							+ "; logisim_gui_status still works, and the work can be resumed from the Logisim window");
				}
				if (session.isReadOnly() && ToolRegistry.isMutating(target.name())) {
					throw new McpException("the user has put this session in read-only mode, so '" + target.name()
							+ "' is refused; the Logisim window can turn editing back on");
				}
				JsonElement result = target.call(new ToolContext(session, target.name(), args));
				String hint = session.finishCall();
				return withHint(result, hint);
			}
		};
		try {
			long started = System.currentTimeMillis();
			JsonElement result = session.isLive() ? EdtGate.call(body, EdtGate.DEFAULT_TIMEOUT_MS, name) : body.call();
			ActivityLog.record(name + " ok (" + (System.currentTimeMillis() - started) + " ms)");
			return toolResult(result);
		} catch (Throwable t) {
			if (!(t instanceof McpException)) {
				log.println("[logisim-mcp] tool " + name + " failed: " + t);
				t.printStackTrace(log);
			}
			ActivityLog.record(name + " failed: "
					+ (t instanceof McpException ? t.getMessage() : McpSession.message(t)));
			return errorResult(t);
		}
	}

	/**
	 * Adds a line to whatever a tool returned, for something only the session knows - such as
	 * "the edits are in a circuit the window is not showing". An existing hint is extended
	 * rather than replaced, because the tool's own hint is usually the more specific one.
	 */
	private static JsonElement withHint(JsonElement result, String hint) {
		if (hint == null || hint.isEmpty()) {
			return result;
		}
		if (result != null && result.isJsonObject()) {
			JsonObject obj = result.getAsJsonObject();
			if (Json.isPresent(obj, "hint")) {
				obj.addProperty("hint", obj.get("hint").getAsString() + " " + hint);
			} else {
				obj.addProperty("hint", hint);
			}
			return obj;
		}
		JsonObject ret = new JsonObject();
		ret.addProperty("hint", hint);
		if (result != null) {
			ret.add("result", result);
		}
		return ret;
	}

	private static JsonObject toolResult(JsonElement payload) {
		JsonObject image = imageBlock(payload);
		String text;
		if (payload == null || payload.isJsonNull()) {
			text = "ok";
		} else if (payload.isJsonPrimitive()) {
			text = payload.getAsString();
		} else {
			text = Json.pretty(payload);
		}
		JsonArray content = new JsonArray();
		if (image != null) {
			content.add(Json.object("type", "image", "data", image.get("data").getAsString(), "mimeType",
					image.has("mimeType") ? image.get("mimeType").getAsString() : "image/png"));
		}
		content.add(Json.object("type", "text", "text", text));

		JsonObject ret = new JsonObject();
		ret.add("content", content);
		if (payload != null && payload.isJsonObject()) {
			ret.add("structuredContent", payload);
		}
		ret.addProperty("isError", Boolean.FALSE);
		return ret;
	}

	/**
	 * Takes the image out of a tool's result, if it has one, and leaves a note in its place.
	 *
	 * <p>
	 * A tool that draws something returns its PNG as base64 under {@code image}. That belongs
	 * in an MCP {@code image} content block, which is what a client can actually show; left in
	 * the JSON it would also be repeated in the text form and in the structured copy, tripling
	 * an answer that is often a few hundred kilobytes. So the data is lifted out and replaced
	 * by a note saying where it went - the size and kind stay visible, and nothing is lost.
	 * </p>
	 */
	private static JsonObject imageBlock(JsonElement payload) {
		if (payload == null || !payload.isJsonObject()) {
			return null;
		}
		JsonObject obj = payload.getAsJsonObject();
		JsonElement block = obj.get("image");
		if (block == null || !block.isJsonObject()) {
			return null;
		}
		JsonObject image = block.getAsJsonObject();
		if (!image.has("data")) {
			return null;
		}
		JsonObject slim = new JsonObject();
		java.util.Iterator<java.util.Map.Entry<String, JsonElement>> entries = image.entrySet().iterator();
		while (entries.hasNext()) {
			java.util.Map.Entry<String, JsonElement> entry = entries.next();
			if (!"data".equals(entry.getKey())) {
				slim.add(entry.getKey(), entry.getValue());
			}
		}
		slim.addProperty("data", "sent as the image part of this reply");
		obj.add("image", slim);
		return image;
	}

	private static JsonObject errorResult(Throwable t) {
		String message = t instanceof McpException ? t.getMessage() : McpSession.message(t);
		if (message == null || message.isEmpty()) {
			message = t.getClass().getName();
		}
		JsonArray content = new JsonArray();
		content.add(Json.object("type", "text", "text", message));
		JsonObject ret = new JsonObject();
		ret.add("content", content);
		ret.addProperty("isError", Boolean.TRUE);
		return ret;
	}

	private void send(String reply) {
		try {
			transport.writeMessage(reply);
		} catch (Exception e) {
			log.println("[logisim-mcp] could not send a response: " + e);
		}
	}

	public boolean isInitialized() {
		return initialized;
	}

	public String protocolVersion() {
		return protocolVersion;
	}
}
