/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.gui.mcp;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.WindowConstants;

import com.cburch.logisim.gui.generic.LFrame;
import com.cburch.logisim.mcp.ActivityLog;
import com.cburch.logisim.mcp.McpMain;
import com.cburch.logisim.mcp.McpSession;
import com.cburch.logisim.mcp.McpSettings;
import com.cburch.logisim.mcp.McpHttpServer;

/**
 * The one place MCP lives on screen: a window with a tab for the server itself, a tab
 * for the switches the user holds (read-only, pause), and a tab showing what the agent
 * has been doing.
 *
 * <p>
 * Everything about the MCP feature is here on purpose. Logisim itself only grows a menu
 * item that opens this window and a call at startup; its preferences machinery, its
 * menus and its resource bundles are left alone, and the settings this window edits live
 * in the platform preference store under the MCP package (see
 * {@link com.cburch.logisim.mcp.McpSettings}).
 * </p>
 *
 * <p>
 * Strings are literal English rather than the usual {@code Strings.get} bundle, on the
 * same principle: adding a feature should not mean editing Logisim's resources.
 * </p>
 */
public class McpFrame extends LFrame {
	private static final long serialVersionUID = 1L;

	private static McpFrame instance;

	/** Opens the window (creating it the first time), or brings it to the front. */
	public static void showWindow() {
		if (instance == null) {
			instance = new McpFrame();
			ActivityLog.record("MCP window opened");
		}
		instance.setVisible(true);
		instance.toFront();
		instance.refresh();
	}

	/**
	 * Called once the GUI exists. Shows the window when something asked for it (the
	 * {@code --with-gui} command line) and honours the "start when Logisim starts"
	 * setting.
	 */
	public static void onGuiReady() {
		McpMain.maybeStartOnStartup();
		if (McpMain.consumeWindowRequest()) {
			showWindow();
		}
	}

	private final JLabel state = new JLabel();
	private final JLabel client = new JLabel();
	private final JLabel counters = new JLabel();
	private final JTextField port = new JTextField(6);
	private final JTextField host = new JTextField(12);
	private final JButton start = new JButton("Start server");
	private final JButton stop = new JButton("Stop server");
	private final JButton copyUrl = new JButton("Copy URL");
	private final JCheckBox autoStart = new JCheckBox("Start the server when Logisim starts");
	private final JCheckBox readOnly = new JCheckBox("Read-only: refuse everything that changes the project");
	private final JCheckBox paused = new JCheckBox("Paused: refuse the agent's calls (except this window's own)");
	private final JTextField pauseReason = new JTextField(24);
	private final JLabel switchState = new JLabel();
	private final JTextArea activity = new JTextArea(14, 72);

	// Connect tab
	private static final String KIND_VSCODE = "VS Code (Streamable HTTP)";
	private static final String KIND_CLAUDE_DESKTOP = "Claude Desktop (bridged over stdio)";
	private static final String KIND_GENERIC = "Any MCP client (JSON)";
	private static final String KIND_HEADLESS = "A client that launches Logisim (headless)";
	private static final String KIND_TEST = "Prove the server answers (command line)";
	private final JComboBox<String> clientKind = new JComboBox<String>(new String[] { KIND_VSCODE, KIND_CLAUDE_DESKTOP,
			KIND_GENERIC, KIND_HEADLESS, KIND_TEST });
	private final JTextArea snippet = new JTextArea(14, 76);
	private final JLabel snippetWhere = new JLabel();
	private final JButton copyConfig = new JButton("Copy");
	private final JButton saveConfig = new JButton("Save...");
	/** The address the snippet was built for, so it is only rebuilt when it changes. */
	private String snippetAddress;

	public McpFrame() {
		super();
		setTitle("MCP Server");
		setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);

		port.setText(String.valueOf(McpSettings.getPort()));
		host.setText("127.0.0.1");
		autoStart.setSelected(McpSettings.isAutoStart());
		pauseReason.setText("the user is looking at the circuit");

		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("Server", buildServerTab());
		tabs.addTab("Connect", buildConnectTab());
		tabs.addTab("Control", buildControlTab());
		tabs.addTab("Activity", buildActivityTab());
		tabs.setPreferredSize(new Dimension(720, 430));
		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(tabs, BorderLayout.CENTER);

		ActionListener listener = new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				Object src = e.getSource();
				if (src == start) {
					startServer();
				} else if (src == stop) {
					McpMain.stopServer();
				} else if (src == copyUrl) {
					copyToClipboard(McpMain.serverUrl(), "the server URL");
				} else if (src == copyConfig) {
					copyToClipboard(snippet.getText(), "the client configuration");
				} else if (src == saveConfig) {
					saveSnippet();
				} else if (src == clientKind) {
					updateSnippet();
				} else if (src == autoStart) {
					McpSettings.setAutoStart(autoStart.isSelected());
				} else if (src == readOnly) {
					McpSettings.setReadOnlyAtStart(readOnly.isSelected());
					McpSession session = McpMain.session();
					if (session != null) {
						session.setReadOnly(readOnly.isSelected());
					}
				} else if (src == paused) {
					McpSettings.setPausedAtStart(paused.isSelected());
					McpSession session = McpMain.session();
					if (session != null) {
						session.setPaused(paused.isSelected(), pauseReason.getText(), true);
					}
				} else if (src == pauseReason) {
					McpSession session = McpMain.session();
					if (session != null && session.isPaused()) {
						session.setPaused(true, pauseReason.getText(), session.isPausedByUser());
					}
				} else {
					refresh();
				}
			}
		};
		start.addActionListener(listener);
		stop.addActionListener(listener);
		copyUrl.addActionListener(listener);
		copyConfig.addActionListener(listener);
		saveConfig.addActionListener(listener);
		clientKind.addActionListener(listener);
		autoStart.addActionListener(listener);
		readOnly.addActionListener(listener);
		paused.addActionListener(listener);
		pauseReason.addActionListener(listener);

		Timer tick = new Timer(1000, listener);
		tick.start();

		pack();
		setLocationRelativeTo(null);
		refresh();
	}

	private JPanel buildServerTab() {
		JPanel ret = new JPanel(new BorderLayout(0, 8));
		ret.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

		JPanel form = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(3, 3, 3, 3);
		c.anchor = GridBagConstraints.WEST;
		int row = 0;
		addRow(form, c, row++, new JLabel("State:"), state);
		addRow(form, c, row++, new JLabel("Client:"), client);
		addRow(form, c, row++, new JLabel("Since start:"), counters);
		addRow(form, c, row++, new JLabel("Address:"), host);
		addRow(form, c, row++, new JLabel("Port:"), port);
		addRow(form, c, row++, new JLabel(""), autoStart);

		JPanel buttons = new JPanel();
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.LINE_AXIS));
		buttons.add(start);
		buttons.add(Box.createHorizontalStrut(6));
		buttons.add(stop);
		buttons.add(Box.createHorizontalStrut(6));
		buttons.add(copyUrl);
		buttons.add(Box.createHorizontalGlue());
		c.gridx = 0;
		c.gridy = row;
		c.gridwidth = 2;
		form.add(buttons, c);

		ret.add(form, BorderLayout.NORTH);
		ret.add(new JLabel("<html><body style='width:560px'>A client attaches to "
				+ "<b>http://127.0.0.1:7331/mcp</b> with <code>{\"type\":\"http\",\"url\":\"...\"}</code>. "
				+ "The server runs inside this Logisim process, so the agent works on the project you have open: "
				+ "edits appear on the canvas, the parts a call touched become the selection, and every call is an "
				+ "undo step in the Edit menu.</body></html>"), BorderLayout.CENTER);
		return ret;
	}

	private JPanel buildConnectTab() {
		JPanel ret = new JPanel(new BorderLayout(0, 8));
		ret.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

		JPanel top = new JPanel();
		top.setLayout(new BoxLayout(top, BoxLayout.LINE_AXIS));
		top.add(new JLabel("Client:"));
		top.add(Box.createHorizontalStrut(6));
		top.add(clientKind);
		top.add(Box.createHorizontalStrut(10));
		top.add(copyConfig);
		top.add(Box.createHorizontalStrut(6));
		top.add(saveConfig);
		top.add(Box.createHorizontalGlue());

		snippet.setEditable(false);
		snippet.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		snippetWhere.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		ret.add(top, BorderLayout.NORTH);
		ret.add(new JScrollPane(snippet), BorderLayout.CENTER);
		ret.add(snippetWhere, BorderLayout.SOUTH);
		return ret;
	}

	/**
	 * Rebuilds the snippet for the selected client and the address the server answers
	 * on (its real URL while it is running, otherwise the address it would use).
	 */
	private void updateSnippet() {
		String address = address();
		snippetAddress = address;
		String kind = (String) clientKind.getSelectedItem();
		if (KIND_CLAUDE_DESKTOP.equals(kind)) {
			snippet.setText("{\n  \"mcpServers\": {\n    \"logisim\": {\n      \"command\": \"npx\",\n"
					+ "      \"args\": [\"-y\", \"mcp-remote\", \"" + address + "\"]\n    }\n  }\n}\n");
			snippetWhere.setText("<html><body style='width:640px'>Claude Desktop speaks stdio, so this bridges it to "
					+ "the HTTP server with <code>mcp-remote</code> (Node.js has to be installed). It goes in "
					+ "<code>claude_desktop_config.json</code>, reached from Settings ▸ Developer ▸ Edit Config. "
					+ "Restart the client afterwards.</body></html>");
		} else if (KIND_GENERIC.equals(kind)) {
			snippet.setText("{\n  \"mcpServers\": {\n    \"logisim\": {\n      \"type\": \"http\",\n      \"url\": \""
					+ address + "\"\n    }\n  }\n}\n");
			snippetWhere.setText("<html><body style='width:640px'>The same configuration under the "
					+ "<code>mcpServers</code> key for clients that follow the original convention; VS Code uses "
					+ "<code>servers</code> instead. This is Streamable HTTP, so the client needs no bridge - it "
					+ "talks to the running Logisim directly.</body></html>");
		} else if (KIND_HEADLESS.equals(kind)) {
			String jar = launcherJar();
			String args = jar != null
					? "\"-jar\",\n        \"" + jar + "\",\n        \"-mcp\""
					: "\"-cp\",\n        \"" + classPath() + "\",\n        \"com.cburch.logisim.Main\",\n        \"-mcp\"";
			snippet.setText("{\n  \"servers\": {\n    \"logisim-headless\": {\n      \"type\": \"stdio\",\n"
					+ "      \"command\": \"java\",\n      \"args\": [\n        " + args + "\n      ]\n    }\n  }\n}\n");
			snippetWhere.setText("<html><body style='width:640px'>For a client that starts the server itself: this "
					+ "runs Logisim <b>headless</b>, so the agent works on a project of its own instead of on the "
					+ "window you are looking at. "
					+ (jar == null
							? "Paths are relative to the working directory of the client. "
							: "Use the packaged jar (build with <code>-Single</code> or <code>-Jar</code>). ")
					+ "The <code>README</code> for MCP of this build is <code>Logisim-Fork/MCP.md</code>.</body></html>");
		} else if (KIND_TEST.equals(kind)) {
			snippet.setText("curl -s -H \"Content-Type: application/json\" -H \"Accept: application/json, "
					+ "text/event-stream\" -d \"{\\\"jsonrpc\\\":\\\"2.0\\\",\\\"id\\\":1,\\\"method\\\":"
					+ "\\\"initialize\\\",\\\"params\\\":{\\\"protocolVersion\\\":\\\"2025-06-18\\\","
					+ "\\\"capabilities\\\":{},\\\"clientInfo\\\":{\\\"name\\\":\\\"test\\\",\\\"version\\\":"
					+ "\\\"1\\\"}}}\" " + address + "\n");
			snippetWhere.setText("<html><body style='width:640px'>A one-liner that proves the server answers: run it "
					+ "in Git Bash, WSL or a POSIX shell and expect an <code>initialize</code> reply with the server "
					+ "name and instructions.</body></html>");
		} else {
			snippet.setText("{\n  \"servers\": {\n    \"logisim\": {\n      \"type\": \"http\",\n      \"url\": \""
					+ address + "\"\n    }\n  }\n}\n");
			snippetWhere.setText("<html><body style='width:640px'>VS Code: Command Palette ▸ <b>MCP: Open User "
					+ "Configuration</b> for all your projects, or put it in <code>.vscode/mcp.json</code> for one "
					+ "project. Restart the client (or use the MCP view's restart button) afterwards. Keep the "
					+ "server running: use auto-start or leave this window open.</body></html>");
		}
		snippet.setCaretPosition(0);
	}

	/** The address a client should use: the live URL, else what the fields say. */
	private String address() {
		McpHttpServer http = McpMain.httpServer();
		if (http != null) {
			return http.url();
		}
		String hostText = host.getText().trim();
		if (hostText.length() == 0) {
			hostText = "127.0.0.1";
		}
		String portText = port.getText().trim();
		int portNumber = McpSettings.getPort();
		try {
			portNumber = Integer.parseInt(portText);
		} catch (NumberFormatException e) {
			// the field is mid-edit; the saved port is the honest answer
		}
		return "http://" + hostText + ":" + portNumber + "/mcp";
	}

	/** The jar this Logisim runs from, or null when it runs from build/classes. */
	private static String launcherJar() {
		for (String part : classPath().split(File.pathSeparator)) {
			String value = part.trim();
			if (value.toLowerCase(java.util.Locale.ENGLISH).endsWith(".jar")) {
				return value.replace('\\', '/');
			}
		}
		return null;
	}

	private static String classPath() {
		return System.getProperty("java.class.path", "build\\classes;.;libs/*");
	}

	/** Writes the snippet where the user asks, so it can be dropped into a project. */
	private void saveSnippet() {
		String kind = (String) clientKind.getSelectedItem();
		String name = KIND_CLAUDE_DESKTOP.equals(kind) ? "claude_desktop_config.json" : "mcp.json";
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Save " + name);
		chooser.setSelectedFile(new File(name));
		if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
			return;
		}
		File target = chooser.getSelectedFile();
		try {
			FileWriter writer = new FileWriter(target);
			try {
				writer.write(snippet.getText());
			} finally {
				writer.close();
			}
			JOptionPane.showMessageDialog(this, "Written to " + target, "MCP Server",
					JOptionPane.INFORMATION_MESSAGE);
		} catch (IOException e) {
			JOptionPane.showMessageDialog(this, "Could not write " + target + ": " + e.getMessage(), "MCP Server",
					JOptionPane.ERROR_MESSAGE);
		}
	}

	private void copyToClipboard(String text, String what) {
		if (text == null) {
			state.setText("Nothing to copy: the " + what + " does not exist while the server is stopped.");
			return;
		}
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	private JPanel buildControlTab() {
		JPanel ret = new JPanel(new BorderLayout(0, 8));
		ret.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

		JPanel form = new JPanel();
		form.setLayout(new BoxLayout(form, BoxLayout.PAGE_AXIS));
		form.add(readOnly);
		form.add(Box.createVerticalStrut(8));
		form.add(paused);
		JPanel reason = new JPanel();
		reason.setLayout(new BoxLayout(reason, BoxLayout.LINE_AXIS));
		reason.add(new JLabel("Reason shown to the agent:"));
		reason.add(Box.createHorizontalStrut(6));
		reason.add(pauseReason);
		reason.add(Box.createHorizontalGlue());
		form.add(reason);
		form.add(Box.createVerticalStrut(10));
		form.add(switchState);

		ret.add(form, BorderLayout.NORTH);
		ret.add(new JLabel("<html><body style='width:560px'>These two switches are yours. Read-only refuses the ten "
				+ "tools that change a project or write a file; pause refuses every call except the status read and "
				+ "this window. Both are remembered for the next session, both can be changed while the agent is "
				+ "working, and a pause set here cannot be released by the agent - it has to be released here."
				+ "</body></html>"), BorderLayout.CENTER);
		return ret;
	}

	private JPanel buildActivityTab() {
		JPanel ret = new JPanel(new BorderLayout(0, 8));
		ret.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		activity.setEditable(false);
		activity.setLineWrap(false);
		ret.add(new JScrollPane(activity), BorderLayout.CENTER);
		JButton clear = new JButton("Clear");
		clear.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				ActivityLog.clear();
				refresh();
			}
		});
		JPanel south = new JPanel();
		south.setLayout(new BoxLayout(south, BoxLayout.LINE_AXIS));
		south.add(clear);
		south.add(Box.createHorizontalGlue());
		south.add(new JLabel("Every tool call the agent makes, newest last (refreshed once a second)."));
		ret.add(south, BorderLayout.SOUTH);
		return ret;
	}

	private static void addRow(JPanel form, GridBagConstraints c, int row, JLabel label, java.awt.Component field) {
		c.gridx = 0;
		c.gridy = row;
		c.gridwidth = 1;
		form.add(label, c);
		c.gridx = 1;
		form.add(field, c);
	}

	private void startServer() {
		int chosen;
		try {
			chosen = Integer.parseInt(port.getText().trim());
		} catch (NumberFormatException e) {
			state.setText("The port must be a number.");
			return;
		}
		if (chosen < 0 || chosen > 65535) {
			state.setText("The port must be between 0 and 65535.");
			return;
		}
		McpSettings.setPort(chosen);
		try {
			McpMain.startServer(host.getText().trim(), chosen);
		} catch (Throwable t) {
			state.setText("Could not start: " + t.getMessage());
		}
		refresh();
	}

	/** Brings the labels in line with the real state; called by the timer as well. */
	private void refresh() {
		McpHttpServer http = McpMain.httpServer();
		boolean running = http != null;
		state.setText(running ? "Running, listening on " + http.url() : "Stopped.");
		client.setText(running
				? (http.sessionId() == null ? "none attached yet" : "one client attached (session " + http.sessionId() + ")")
				: "-");
		counters.setText(running
				? http.requestCount() + " requests, " + http.toolCallCount() + " tool calls"
				: "-");
		start.setEnabled(!running);
		stop.setEnabled(running);
		copyUrl.setEnabled(running);
		port.setEnabled(!running);
		host.setEnabled(!running);

		// keep the Connect tab honest: a snippet is only rebuilt when the address it was
		// built for changes, so the user can read and copy it undisturbed
		if (!address().equals(snippetAddress)) {
			updateSnippet();
		}

		McpSession session = McpMain.session();
		boolean live = session != null;
		if (!live) {
			readOnly.setSelected(McpSettings.isReadOnlyAtStart());
			paused.setSelected(McpSettings.isPausedAtStart());
			switchState.setText(running ? "" : "Start the server to use these.");
		} else {
			readOnly.setSelected(session.isReadOnly());
			paused.setSelected(session.isPaused());
			String reason = session.pausedReason();
			switchState.setText("Read-only " + (session.isReadOnly() ? "ON" : "off") + ", "
					+ (session.isPaused() ? "paused" + (reason == null ? "" : " (" + reason + ")") : "agent running"));
		}

		StringBuilder text = new StringBuilder();
		for (String line : ActivityLog.recent(120)) {
			text.append(line).append('\n');
		}
		if (activity.getText().length() != text.length()) {
			activity.setText(text.toString());
			activity.setCaretPosition(activity.getDocument().getLength());
		}
	}
}
