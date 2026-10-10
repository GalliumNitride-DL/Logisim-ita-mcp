/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import com.cburch.logisim.mcp.tools.AnalyzeTools;
import com.cburch.logisim.mcp.tools.CatalogTools;
import com.cburch.logisim.mcp.tools.CircuitTools;
import com.cburch.logisim.mcp.tools.DiagTools;
import com.cburch.logisim.mcp.tools.DocTools;
import com.cburch.logisim.mcp.tools.EditTools;
import com.cburch.logisim.mcp.tools.FileTools;
import com.cburch.logisim.mcp.tools.GuiTools;
import com.cburch.logisim.mcp.tools.ImageTools;
import com.cburch.logisim.mcp.tools.LintTools;
import com.cburch.logisim.mcp.tools.MemoryTools;
import com.cburch.logisim.mcp.tools.NetTools;
import com.cburch.logisim.mcp.tools.SelfTools;
import com.cburch.logisim.mcp.tools.SessionTools;
import com.cburch.logisim.mcp.tools.SimTools;

/**
 * Entry point for the Logisim MCP server.
 *
 * <p>
 * It is started by {@code Main} when the command line contains {@code -mcp}, for
 * example:
 * </p>
 *
 * <pre>
 * java -jar Logisim.jar -mcp
 * java -jar Logisim.jar -mcp --transport tcp --port 7331
 * java -jar Logisim.jar -mcp --file path\to\design.circ
 * </pre>
 *
 * <p>
 * Two details matter for correctness:
 * </p>
 * <ul>
 * <li>The server runs headless, so Logisim's Swing dialogs (which would block
 * forever with no user to answer them) turn into exceptions that the agent sees
 * as ordinary tool errors.</li>
 * <li>MCP over stdio requires that stdout carries nothing but protocol messages.
 * Logisim prints diagnostics in many places, so {@code System.out} is redirected
 * to stderr and the protocol stream is kept aside.</li>
 * </ul>
 */
public final class McpMain {
	private static final String FLAG = "-mcp";
	private static final String GUI_FLAG = "--with-gui";

	private McpMain() {
	}

	/** True when this invocation asks for the MCP server rather than the GUI. */
	public static boolean wantsMcp(String[] args) {
		if (args == null) {
			return false;
		}
		for (String arg : args) {
			if (FLAG.equals(arg)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True when the server should run inside the normal Logisim window, which is what
	 * {@code --with-gui} asks for: the user keeps the window, watches every change, and
	 * an agent attaches over HTTP.
	 */
	public static boolean isGuiMode(String[] args) {
		if (args == null) {
			return false;
		}
		for (String arg : args) {
			if (GUI_FLAG.equals(arg) || "--gui".equals(arg)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Starts the server alongside the GUI and returns the command line Logisim itself
	 * should see: our own options are removed, and {@code --file} becomes a plain
	 * argument so the normal startup opens that file in a window.
	 *
	 * <p>
	 * The server runs on a thread of its own and Logisim's startup continues normally,
	 * so the window appears as it always would. HTTP is the only transport here: a
	 * window must not be the child of an MCP client (clients restart and kill what they
	 * started, which would take unsaved work with it), and an executable built with a
	 * Windows GUI header has no usable stdio at all.
	 * </p>
	 */
	public static String[] startWithGui(String[] args) {
		Map<String, String> options = parseOptions(args);
		String host = options.containsKey("host") ? options.get("host") : "127.0.0.1";
		int port = 7331;
		if (options.containsKey("port")) {
			try {
				port = Integer.parseInt(options.get("port").trim());
			} catch (NumberFormatException e) {
				System.err.println("[logisim-mcp] invalid --port value: " + options.get("port") + "; using " + port);
			}
		}
		if (options.containsKey("transport") && !"http".equalsIgnoreCase(options.get("transport"))) {
			System.err.println("[logisim-mcp] with a window the transport is http; '" + options.get("transport")
					+ "' is ignored (headless mode supports stdio and tcp)");
		}

		final String boundHost = host;
		final int boundPort = port;
		// the command line wins: the auto-start setting must not try a second bind while
		// this thread is still coming up
		guiServerRequested = true;
		Thread server = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					startServer(boundHost, boundPort);
					System.err.println("[logisim-mcp] watching this window over MCP on " + serverUrl());
				} catch (Throwable t) {
					System.err.println("[logisim-mcp] could not start the MCP server on " + boundHost + ":" + boundPort
							+ ": " + McpSession.message(t) + " (the window keeps working; set --port to another port)");
				}
			}
		}, "logisim-mcp-http");
		server.setDaemon(true);
		server.start();
		openWindowWhenReady = true;
		return stripMcpOptions(args, options);
	}

	// ------------------------------------------------------------------
	// the server, as the MCP window sees it
	// ------------------------------------------------------------------

	/** The HTTP server of a GUI run, or null while none is running. */
	private static volatile McpHttpServer lastHttp;

	/** The session the running server serves, so the window can flip its switches. */
	private static volatile McpSession lastSession;

	/** True when a window asked to be shown once the GUI exists. */
	private static volatile boolean openWindowWhenReady;

	/** True when the command line asked for a server, so the setting stays out of the way. */
	private static volatile boolean guiServerRequested;

	public static McpHttpServer httpServer() {
		return lastHttp;
	}

	public static McpSession session() {
		return lastSession;
	}

	public static boolean isServerRunning() {
		return lastHttp != null;
	}

	public static String serverUrl() {
		McpHttpServer http = lastHttp;
		return http == null ? null : http.url();
	}

	/**
	 * Starts the server for a running Logisim window. The session is a live one: it
	 * serves whichever window the user is in, and the switches in the MCP window
	 * (read-only, pause) apply to it.
	 */
	public static synchronized McpHttpServer startServer(String host, int port) throws Exception {
		McpHttpServer running = lastHttp;
		if (running != null) {
			return running;
		}
		McpSession session = new McpSession();
		session.setLive(true);
		session.setReadOnly(McpSettings.isReadOnlyAtStart());
		if (McpSettings.isPausedAtStart()) {
			session.setPaused(true, "paused at start by the settings", true);
		}
		ToolRegistry registry = registry(session, "http (gui)");
		McpHttpServer http = new McpHttpServer(session, registry, host, port, System.err);
		lastSession = session;
		lastHttp = http;
		ActivityLog.record("server started on " + http.url());
		return http;
	}

	public static McpHttpServer startServer(int port) throws Exception {
		return startServer("127.0.0.1", port);
	}

	public static synchronized void stopServer() {
		McpHttpServer running = lastHttp;
		lastHttp = null;
		lastSession = null;
		if (running != null) {
			running.close();
			System.err.println("[logisim-mcp] server stopped");
		}
	}

	/** Asks for the MCP window to be shown as soon as the GUI is up. */
	public static void requestWindow() {
		openWindowWhenReady = true;
	}

	/** True once, when a window should be shown now. */
	public static boolean consumeWindowRequest() {
		boolean value = openWindowWhenReady;
		openWindowWhenReady = false;
		return value;
	}

	/** Whether a plain GUI start has anything MCP to do once the windows exist. */
	public static boolean needsGuiHook() {
		return isServerRunning() || guiServerRequested || openWindowWhenReady || McpSettings.isAutoStart();
	}

	/**
	 * Called once the GUI exists: honours the "start the server when Logisim starts"
	 * setting. Quiet by design - the user opens the window to look.
	 */
	public static void maybeStartOnStartup() {
		if (isServerRunning() || guiServerRequested || !McpSettings.isAutoStart()) {
			return;
		}
		try {
			startServer(McpSettings.getPort());
			System.err.println("[logisim-mcp] serving this window on " + serverUrl()
					+ " (started by the MCP setting; open Help | MCP Server to see it)");
		} catch (Throwable t) {
			System.err.println("[logisim-mcp] could not start the MCP server on port " + McpSettings.getPort() + ": "
					+ McpSession.message(t));
		}
	}

	/** The command line with our own options taken out, keeping any file to open. */
	private static String[] stripMcpOptions(String[] args, Map<String, String> options) {
		java.util.List<String> ret = new java.util.ArrayList<String>();
		String file = options.get("file");
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg == null || arg.isEmpty() || FLAG.equals(arg) || GUI_FLAG.equals(arg) || "--gui".equals(arg)) {
				continue;
			}
			if (arg.equals("--transport") || arg.equals("--host") || arg.equals("--port") || arg.equals("--file")) {
				i++; // skip this option's value as well
				continue;
			}
			if (arg.startsWith("--transport=") || arg.startsWith("--host=") || arg.startsWith("--port=")
					|| arg.startsWith("--file=") || arg.equals("--once")) {
				continue;
			}
			if (file != null && !file.isEmpty() && arg.equals(file)) {
				continue; // added again below, where Logisim expects a file to open
			}
			ret.add(arg);
		}
		if (file != null && !file.isEmpty()) {
			ret.add(file);
		}
		return ret.toArray(new String[ret.size()]);
	}

	/** Runs the server; the return value is the process exit code. */
	public static int run(String[] args) {
		// Must happen before anything touches AWT or Swing.
		System.setProperty("java.awt.headless", "true");

		PrintStream log = System.err;
		PrintStream protocolOut = System.out;
		// Logisim writes to System.out from many places; none of that may reach the
		// protocol stream, so send it to stderr and keep the real stdout for ourselves.
		try {
			System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));
		} catch (Throwable t) {
			log.println("[logisim-mcp] warning: could not redirect System.out: " + t);
		}

		Map<String, String> options = parseOptions(args);
		if (options.containsKey("help")) {
			log.println(usage());
			return 0;
		}

		McpSession session = new McpSession();
		String file = options.get("file");
		if (file != null && !file.isEmpty()) {
			try {
				session.open(new File(file).getAbsoluteFile());
				log.println("[logisim-mcp] opened " + file);
			} catch (Throwable t) {
				// Starting without a file is friendlier than refusing to start: the agent
				// can call logisim_file_open itself and see the error.
				log.println("[logisim-mcp] could not open '" + file + "': " + McpSession.message(t));
			}
		}

		String transport = options.containsKey("transport") ? options.get("transport") : "stdio";
		try {
			if ("http".equalsIgnoreCase(transport)) {
				return serveHttp(session, options, log);
			}
			if ("tcp".equalsIgnoreCase(transport) || "socket".equalsIgnoreCase(transport)) {
				return serveTcp(session, options, log);
			}
			if (!"stdio".equalsIgnoreCase(transport)) {
				log.println("[logisim-mcp] unknown transport '" + transport + "'; use stdio, tcp or http");
				return 2;
			}
			return serveStdio(session, protocolOut, log);
		} catch (Throwable t) {
			log.println("[logisim-mcp] fatal: " + McpSession.message(t));
			t.printStackTrace(log);
			return 1;
		} finally {
			session.close();
		}
	}

	private static int serveStdio(McpSession session, PrintStream protocolOut, PrintStream log) {
		McpTransport transport = new StreamMcpTransport(System.in, protocolOut, "stdio");
		ToolRegistry registry = registry(session, "stdio");
		log.println("[logisim-mcp] serving MCP over stdio; " + registry.size() + " tools available");
		new McpServer(transport, session, registry, log).run();
		log.println("[logisim-mcp] client closed the connection; exiting");
		return 0;
	}

	/**
	 * Serves MCP over Streamable HTTP, which is how an already running Logisim is
	 * driven: the client connects to a URL instead of owning the process. Used by
	 * headless mode today and by GUI mode once that is enabled, which is why it is a
	 * method rather than part of the stdio path.
	 */
	private static int serveHttp(McpSession session, Map<String, String> options, PrintStream log) throws Exception {
		String host = options.containsKey("host") ? options.get("host") : "127.0.0.1";
		int port = 7331;
		if (options.containsKey("port")) {
			try {
				port = Integer.parseInt(options.get("port").trim());
			} catch (NumberFormatException e) {
				log.println("[logisim-mcp] invalid --port value: " + options.get("port"));
				return 2;
			}
		}
		ToolRegistry registry = registry(session, "http");
		McpHttpServer server = new McpHttpServer(session, registry, host, port, log);
		log.println("[logisim-mcp] serving MCP over http on " + server.url() + "; " + registry.size()
				+ " tools available (close stdin to end it, or stop the process)");
		if (options.containsKey("wait") && "false".equalsIgnoreCase(options.get("wait"))) {
			return 0;
		}
		try {
			// Stay alive until the caller closes stdin or the process is stopped. This
			// gives a client that spawned the process a way to end it, and lets a user
			// started server run as long as the window does.
			//
			// Careful: a launcher with a Windows GUI header (our own -Single exe, javaw,
			// a service, a scheduled task) hands us no stdin at all, so the very first
			// read says end-of-file. Treating that as a stop request made the packaged
			// exe exit the instant it started, silently serving nothing. An empty
			// end-of-file that arrives within a moment of starting to wait therefore
			// means "there is no stdin": serve until the process is stopped instead.
			long waited = System.currentTimeMillis();
			int first = System.in.read();
			if (first >= 0 || System.currentTimeMillis() - waited >= 2000) {
				while (first >= 0 && System.in.read() >= 0) {
					// any input is ignored; only the end of the stream matters
				}
			} else {
				log.println("[logisim-mcp] no stdin to watch; serving until the process is stopped");
				new java.util.concurrent.CountDownLatch(1).await();
			}
		} catch (Exception e) {
			// stdin cannot be read at all: keep serving until the process is stopped
			log.println("[logisim-mcp] stdin cannot be watched (" + McpSession.message(e)
					+ "); serving until the process is stopped");
			new java.util.concurrent.CountDownLatch(1).await();
		}
		server.close();
		return 0;
	}

	private static int serveTcp(McpSession session, Map<String, String> options, PrintStream log) throws Exception {
		String host = options.containsKey("host") ? options.get("host") : "127.0.0.1";
		int port = 0;
		if (options.containsKey("port")) {
			try {
				port = Integer.parseInt(options.get("port").trim());
			} catch (NumberFormatException e) {
				log.println("[logisim-mcp] invalid --port value: " + options.get("port"));
				return 2;
			}
		}
		ServerSocket server = new ServerSocket(port, 4, InetAddress.getByName(host));
		log.println("[logisim-mcp] listening on " + host + ":" + server.getLocalPort()
				+ " (pass this address to your MCP client, e.g. a stdio-to-tcp bridge)");
		boolean once = options.containsKey("once");
		try {
			while (true) {
				Socket socket = server.accept();
				socket.setTcpNoDelay(true);
				log.println("[logisim-mcp] client connected from " + socket.getRemoteSocketAddress());
				McpTransport transport = new StreamMcpTransport(socket.getInputStream(), socket.getOutputStream(),
						socket, "tcp:" + server.getLocalPort());
				try {
					new McpServer(transport, session, registry(session, "tcp"), log).run();
				} finally {
					try {
						transport.close();
					} catch (Exception e) {
						// the socket is going away anyway
					}
				}
				log.println("[logisim-mcp] client disconnected");
				if (once) {
					return 0;
				}
			}
		} finally {
			server.close();
		}
	}

	/**
	 * The whole tool set for one session. Package private because {@link SelfTest} builds a
	 * registry for its own scratch session, so that it drives exactly the tools an agent
	 * drives rather than a private copy of the same logic.
	 */
	static ToolRegistry registry(McpSession session, String transportDescription) {
		ToolRegistry registry = new ToolRegistry();
		registry.register(SessionTools.status(session, transportDescription, registry));
		registry.register(SessionTools.undo(session));
		registry.register(SessionTools.circuitDetails(session));

		registry.register(FileTools.newFile(session));
		registry.register(FileTools.open(session));
		registry.register(FileTools.recover(session));
		registry.register(FileTools.save(session));
		registry.register(FileTools.saveAs(session));
		registry.register(FileTools.close(session));
		registry.register(FileTools.info(session));

		registry.register(CatalogTools.libraryList(session));
		registry.register(CatalogTools.componentCatalog(session));
		// not tied to a session: the manual can be read before anything is opened
		registry.register(DocTools.help());
		// likewise: the self test drives a session of its own
		registry.register(SelfTools.selfTest());

		registry.register(CircuitTools.list(session));
		registry.register(CircuitTools.describe(session));
		registry.register(CircuitTools.create(session));
		registry.register(CircuitTools.remove(session));
		registry.register(CircuitTools.setMain(session));
		registry.register(CircuitTools.select(session));
		registry.register(CircuitTools.statistics(session));
		registry.register(NetTools.nets(session));
		registry.register(LintTools.lint(session));

		registry.register(EditTools.addComponent(session));
		registry.register(EditTools.addMany(session));
		registry.register(EditTools.duplicateComponent(session));
		registry.register(EditTools.removeComponent(session));
		registry.register(EditTools.moveComponent(session));
		registry.register(EditTools.setAttributes(session));
		registry.register(EditTools.addWire(session));
		registry.register(EditTools.removeWire(session));
		registry.register(MemoryTools.memory(session));

		registry.register(SimTools.reset(session));
		registry.register(SimTools.setInput(session));
		registry.register(SimTools.setInputs(session));
		registry.register(SimTools.read(session));
		registry.register(SimTools.readAll(session));
		registry.register(SimTools.propagate(session));
		registry.register(SimTools.tick(session));
		registry.register(SimTools.trace(session));
		registry.register(SimTools.status(session));
		registry.register(SimTools.runVectors(session));

		registry.register(AnalyzeTools.truthTable(session));
		registry.register(AnalyzeTools.expression(session));
		registry.register(AnalyzeTools.buildCircuit(session));
		registry.register(ImageTools.screenshot(session));
		registry.register(DiagTools.repairWires(session));

		registry.register(GuiTools.status(session));
		registry.register(GuiTools.windows(session));
		registry.register(GuiTools.focus(session));
		registry.register(GuiTools.select(session));
		registry.register(GuiTools.deselect(session));
		registry.register(GuiTools.readOnly(session));
		registry.register(GuiTools.pause(session));
		return registry;
	}

	/** Accepts {@code --key value} and {@code --key=value} style options. */
	private static Map<String, String> parseOptions(String[] args) {
		Map<String, String> ret = new HashMap<String, String>();
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg == null || arg.isEmpty() || FLAG.equals(arg)) {
				continue;
			}
			if (arg.equals("--") || arg.equals("-")) {
				continue;
			}
			if (!arg.startsWith("-")) {
				// a bare argument: treat it as the file to open
				if (!ret.containsKey("file")) {
					ret.put("file", arg);
				}
				continue;
			}
			String key = arg.startsWith("--") ? arg.substring(2) : arg.substring(1);
			String value;
			int equals = key.indexOf('=');
			if (equals >= 0) {
				value = key.substring(equals + 1);
				key = key.substring(0, equals);
			} else if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
				value = args[++i];
			} else {
				value = "";
			}
			ret.put(key.toLowerCase(), value);
		}
		return ret;
	}

	private static String usage() {
		return "Logisim MCP server\n"
				+ "  java -jar Logisim.jar -mcp [options] [file.circ]\n"
				+ "options:\n"
				+ "  --transport stdio|tcp|http  how to talk to the MCP client (default stdio)\n"
				+ "  --host ADDRESS              bind address (default 127.0.0.1)\n"
				+ "  --port NUMBER               port; 0 picks a free one for tcp (default 0, http 7331)\n"
				+ "  --once                      TCP mode: exit after the first client disconnects\n"
				+ "  --file PATH                 open this .circ file at startup\n"
				+ "  --help                      print this text\n"
				+ "\n"
				+ "http mode serves one JSON-RPC message per POST to http://host:port/mcp and\n"
				+ "stays up until stdin is closed or the process is stopped. It is the transport\n"
				+ "for letting an agent attach to a Logisim that the user started (GUI mode),\n"
				+ "because a window must not be the child of a client.";
	}
}
