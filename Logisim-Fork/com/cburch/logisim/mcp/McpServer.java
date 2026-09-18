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

	private static final String INSTRUCTIONS = "Controls the Logisim digital circuit simulator. "
			+ "Circuits are edited in memory: nothing is written to disk unless logisim_file_save or "
			+ "logisim_file_save_as is called. Typical workflow: logisim_file_new or logisim_file_open, "
			+ "logisim_component_catalog to learn the available components, logisim_component_add and "
			+ "logisim_wire_add to build, logisim_sim_set_input / logisim_sim_set_inputs plus logisim_sim_read / "
			+ "logisim_sim_read_all to test, logisim_analyze_truth_table for exhaustive verification, then "
			+ "logisim_file_save. Coordinates are integers.\n"
			+ "\n"
			+ "READ THIS FIRST - ignoring these rules can make a circuit, a whole session and the saved file "
			+ "unusable, with a minutes long hang ending in 'OutOfMemoryError: Java heap space':\n"
			+ "1. NEVER create a wire whose two ends differ in BOTH x and y (a diagonal wire). Logisim cannot "
			+ "iterate such a wire, so every later edit - and every attempt to load the saved file - spins until "
			+ "the Java heap is exhausted. Let logisim_wire_add do the routing: give it symbolic endpoints "
			+ "(\"A\", \"c3\", \"c3.1\", \"FA0.Cout\") or a 'from'/'to' pair, and it inserts a right angled corner; "
			+ "if a pair of points is diagonal, it routes or refuses - it never emits a diagonal. If it refuses, "
			+ "pass an explicit 'points' list with a steering point instead of forcing coordinates through.\n"
			+ "2. Keep every coordinate a multiple of 10, for components and for wire points. Logisim itself "
			+ "corrupts wires whose geometry is off this grid: it accepts a point up to 2 units off a wire's line "
			+ "and then splits the wire there, producing the diagonal wire of rule 1. A component port that lands "
			+ "a few units off an existing wire is enough to trigger it.\n"
			+ "3. Prefer ONE batched edit over many small ones: logisim_wire_add's 'paths' draws many wires as a "
			+ "single undoable edit, and Logisim repairs the whole circuit's wiring on every edit, so n calls cost "
			+ "n repairs and real time. Do not fire edits in parallel; they are serialised by per circuit locks.\n"
			+ "4. Watch for the symptom and act on it: if any tool replies 'contains N wire(s) whose ends are not "
			+ "axis aligned', call logisim_circuit_repair_wires for that circuit (it is safe in memory); if a file "
			+ "on disk has that problem, logisim_file_open refuses to hang on it - use logisim_file_recover, then "
			+ "logisim_file_save_as to write the repaired file.\n"
			+ "5. Check logisim_status before long builds and before saving: it reports heap usage under 'memory', "
			+ "'last_wire_repair', and which circuit is current. logisim_circuit_describe reports "
			+ "'skewed_wire_count' and marks each bad wire 'broken'. Save early, and keep circuits modest.\n"
			+ "6. Verify electrically after wiring: logisim_sim_propagate then logisim_sim_read_all. A net that "
			+ "reads 'x' (undriven, floating) or 'e' (error, conflicting drivers) usually means a wire endpoint "
			+ "missed a port, or two nets got joined. Component placement does not connect anything: a connection "
			+ "exists only where a wire end coincides exactly with a port, or where a wire end lands on another "
			+ "wire (that junction shorts them together).";

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
		ret.addProperty("instructions", INSTRUCTIONS);
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
		try {
			long started = System.currentTimeMillis();
			JsonElement result = tool.call(new ToolContext(session, name, arguments));
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

	private static JsonObject toolResult(JsonElement payload) {
		String text;
		if (payload == null || payload.isJsonNull()) {
			text = "ok";
		} else if (payload.isJsonPrimitive()) {
			text = payload.getAsString();
		} else {
			text = Json.pretty(payload);
		}
		JsonArray content = new JsonArray();
		content.add(Json.object("type", "text", "text", text));

		JsonObject ret = new JsonObject();
		ret.add("content", content);
		if (payload != null && payload.isJsonObject()) {
			ret.add("structuredContent", payload);
		}
		ret.addProperty("isError", Boolean.FALSE);
		return ret;
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
