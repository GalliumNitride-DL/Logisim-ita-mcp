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
import com.cburch.logisim.mcp.tools.EditTools;
import com.cburch.logisim.mcp.tools.FileTools;
import com.cburch.logisim.mcp.tools.NetTools;
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
			if ("tcp".equalsIgnoreCase(transport) || "socket".equalsIgnoreCase(transport)) {
				return serveTcp(session, options, log);
			}
			if (!"stdio".equalsIgnoreCase(transport)) {
				log.println("[logisim-mcp] unknown transport '" + transport + "'; use stdio or tcp");
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

	private static ToolRegistry registry(McpSession session, String transportDescription) {
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

		registry.register(CircuitTools.list(session));
		registry.register(CircuitTools.describe(session));
		registry.register(CircuitTools.create(session));
		registry.register(CircuitTools.remove(session));
		registry.register(CircuitTools.setMain(session));
		registry.register(CircuitTools.select(session));
		registry.register(CircuitTools.statistics(session));
		registry.register(NetTools.nets(session));

		registry.register(EditTools.addComponent(session));
		registry.register(EditTools.removeComponent(session));
		registry.register(EditTools.moveComponent(session));
		registry.register(EditTools.setAttributes(session));
		registry.register(EditTools.addWire(session));
		registry.register(EditTools.removeWire(session));

		registry.register(SimTools.reset(session));
		registry.register(SimTools.setInput(session));
		registry.register(SimTools.setInputs(session));
		registry.register(SimTools.read(session));
		registry.register(SimTools.readAll(session));
		registry.register(SimTools.propagate(session));
		registry.register(SimTools.tick(session));
		registry.register(SimTools.status(session));

		registry.register(AnalyzeTools.truthTable(session));
		registry.register(AnalyzeTools.expression(session));
		registry.register(AnalyzeTools.buildCircuit(session));
		registry.register(DiagTools.repairWires(session));
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
				+ "  --transport stdio|tcp   how to talk to the MCP client (default stdio)\n"
				+ "  --host ADDRESS          TCP bind address (default 127.0.0.1)\n"
				+ "  --port NUMBER           TCP port, 0 picks a free one (default 0)\n"
				+ "  --once                  TCP mode: exit after the first client disconnects\n"
				+ "  --file PATH             open this .circ file at startup\n"
				+ "  --help                  print this text";
	}
}
