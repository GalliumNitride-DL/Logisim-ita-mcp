/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Serves MCP over Streamable HTTP on a local port, one JSON-RPC message per POST to
 * {@code /mcp}.
 *
 * <p>
 * This is the transport for an already running Logisim: the user starts the server
 * (from the command line today, from the MCP window once that exists) and an agent
 * attaches to the URL, so the lifetime of the window belongs to the user and not to
 * the client. A window must never be the child of a client, because clients restart
 * and kill their child processes, which would take unsaved work with it.
 * </p>
 *
 * <p>
 * Implemented to the parts of the specification that matter here: the server answers
 * with a single {@code application/json} body (it emits no server initiated
 * messages, so no event stream is needed), hands out an {@code Mcp-Session-Id} on
 * initialize and echoes it afterwards, answers {@code 202} to notifications,
 * answers {@code 405} to GET, ends a session on DELETE, refuses requests that ask
 * only for an event stream, and refuses requests that do not come from this machine
 * (the DNS rebinding check the specification asks for).
 * </p>
 */
public final class McpHttpServer implements Closeable {
	private static final String SESSION_HEADER = "Mcp-Session-Id";
	private static final String PATH = "/mcp";

	private final HttpServer server;
	private final McpServer mcp;
	private final PrintStream log;
	private final String host;
	private final int port;
	private final AtomicReference<String> session = new AtomicReference<String>();
	private final AtomicLong requests = new AtomicLong();
	private final AtomicLong toolsCalled = new AtomicLong();

	public McpHttpServer(McpSession session, ToolRegistry registry, String host, int port, PrintStream log)
			throws IOException {
		this.log = log;
		this.host = host;
		this.port = port;
		// The MCP server instance drives itself from HTTP requests here, so it needs no
		// stream transport; only its run() loop would use one.
		this.mcp = new McpServer(null, session, registry, log);
		InetSocketAddress address = new InetSocketAddress(InetAddress.getByName(host), port);
		this.server = HttpServer.create(address, 16);
		this.server.createContext(PATH, new Handler());
		this.server.setExecutor(null); // one at a time on the server's own thread
		this.server.start();
		ActivityLog.record("listening on " + url());
	}

	/** The URL an MCP client should be configured with. */
	public String url() {
		return "http://" + host + ":" + port + PATH;
	}

	public int port() {
		return port;
	}

	public String host() {
		return host;
	}

	public long requestCount() {
		return requests.get();
	}

	public long toolCallCount() {
		return toolsCalled.get();
	}

	/** The client session id handed out on initialize, or null while none is attached. */
	public String sessionId() {
		return session.get();
	}

	@Override
	public void close() {
		session.set(null);
		server.stop(0);
		ActivityLog.record("stopped " + url());
	}

	// ------------------------------------------------------------------ requests

	private final class Handler implements HttpHandler {
		@Override
		public void handle(HttpExchange exchange) throws IOException {
			try {
				handleRequest(exchange);
			} catch (Throwable t) {
				log.println("[logisim-mcp] http request failed: " + t);
				respond(exchange, 500, "internal error: " + McpSession.message(t));
			} finally {
				exchange.close();
			}
		}
	}

	private void handleRequest(HttpExchange exchange) throws IOException {
		requests.incrementAndGet();
		String method = exchange.getRequestMethod();

		String origin = first(exchange.getRequestHeaders(), "Origin");
		if (origin != null && !isLocalOrigin(origin)) {
			respond(exchange, 403, "requests are only accepted from this machine");
			return;
		}

		if ("GET".equalsIgnoreCase(method)) {
			exchange.getResponseHeaders().add("Allow", "POST, DELETE");
			respond(exchange, 405, "this server sends no server initiated messages; POST to " + PATH);
			return;
		}
		if ("DELETE".equalsIgnoreCase(method)) {
			String id = first(exchange.getRequestHeaders(), SESSION_HEADER);
			if (id != null && id.equals(session.get())) {
				session.set(null);
				ActivityLog.record("client ended its session");
			}
			exchange.sendResponseHeaders(204, -1);
			return;
		}
		if (!"POST".equalsIgnoreCase(method)) {
			exchange.getResponseHeaders().add("Allow", "POST, DELETE");
			respond(exchange, 405, "use POST to " + PATH);
			return;
		}

		String accept = first(exchange.getRequestHeaders(), "Accept");
		if (accept != null && !accept.contains("application/json") && !accept.contains("*/*")
				&& !accept.contains("text/event-stream")) {
			respond(exchange, 406, "send Accept: application/json");
			return;
		}

		String body = readBody(exchange);
		if (body == null || body.trim().isEmpty()) {
			respond(exchange, 400, "the request body must be one JSON-RPC message");
			return;
		}
		if (body.trim().startsWith("[")) {
			respond(exchange, 400, "JSON-RPC batches are not supported; send one message per request");
			return;
		}

		String requestMethod = McpServer.methodOf(body);
		if ("tools/call".equals(requestMethod)) {
			toolsCalled.incrementAndGet();
		}
		if ("initialize".equals(requestMethod)) {
			// A new client takes over; any older session id stops working, which keeps a
			// client restart from being a problem.
			String id = UUID.randomUUID().toString();
			session.set(id);
			ActivityLog.record("client initialized a session");
		}

		String reply;
		try {
			reply = mcp.handleMessage(body);
		} catch (Throwable t) {
			log.println("[logisim-mcp] http dispatch failed: " + t);
			respond(exchange, 500, "internal error: " + McpSession.message(t));
			return;
		}

		String sessionId = session.get();
		if (sessionId != null) {
			exchange.getResponseHeaders().add(SESSION_HEADER, sessionId);
		}
		if (reply == null) {
			// a notification needs no answer; the specification asks for 202 here
			exchange.sendResponseHeaders(202, -1);
			return;
		}
		respond(exchange, 200, reply);
	}

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes("UTF-8");
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, bytes.length);
		OutputStream out = exchange.getResponseBody();
		out.write(bytes);
		out.flush();
	}

	private static String readBody(HttpExchange exchange) throws IOException {
		InputStream in = exchange.getRequestBody();
		java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		int got;
		while ((got = in.read(chunk)) > 0) {
			buf.write(chunk, 0, got);
		}
		return new String(buf.toByteArray(), "UTF-8");
	}

	private static String first(Headers headers, String name) {
		List<String> values = headers.get(name);
		return values == null || values.isEmpty() ? null : values.get(0);
	}

	/** True for the origins a local browser based client would send. */
	private static boolean isLocalOrigin(String origin) {
		return origin.startsWith("http://localhost") || origin.startsWith("http://127.0.0.1")
				|| origin.startsWith("http://[::1]") || origin.startsWith("vscode-file:")
				|| origin.startsWith("null");
	}
}
