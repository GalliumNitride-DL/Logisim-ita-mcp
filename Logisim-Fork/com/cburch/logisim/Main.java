/*
 * Copyright (c) 2011, Carl Burch.
 * 
 * This file is part of the Logisim source code. The latest
 * version is available at http://www.cburch.com/logisim/.
 *
 * Logisim is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * Logisim is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Logisim; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301  USA
 */

package com.cburch.logisim;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Calendar;

import javax.swing.JOptionPane;

import com.cburch.logisim.gui.start.Startup;
import com.cburch.logisim.prefs.AppPreferences;

public class Main {
	// current version
	public static final LogisimVersion VERSION = LogisimVersion.get(2, 15, 0, 1, LogisimVersion.getVariantFromFile());
	// the version of the file you're using, equals to current version if new file
	public static LogisimVersion FILE_VERSION;

	public static final String VERSION_NAME = VERSION.toString();

	public static final short COPYRIGHT_YEAR = (short) Calendar.getInstance().get(Calendar.YEAR);

	/* URL for the automatic updater */
	public static final String UPDATE_URL = "https://raw.githubusercontent.com/LogisimIt/Logisim/master/version.xml";

	public static final double JAVA_VERSION = getVersion();

	// here will be saved the file in use to reopen when restarting
	public static ArrayList<String> OpenedFiles = new ArrayList<String>();

	// get the runtinme java version
	private final static double getVersion() {
		// java.version looks like "1.8.0_451", "9", "11.0.2+9" or "23-ea", so only the
		// leading major.minor is read: that is what the callers need, since every one of
		// them asks whether the runtime is Java 10 or newer to decide between a method
		// added in Java 10 and its older equivalent. Feeding the whole dotted version to
		// Double.parseDouble would throw ("multiple points") for any runtime that reports
		// three components, which is every one of them today.
		String version = System.getProperty("java.version", "8");
		StringBuilder number = new StringBuilder();
		for (int i = 0; i < version.length(); i++) {
			char c = version.charAt(i);
			if (c >= '0' && c <= '9') {
				number.append(c);
			} else if (c == '.' && number.length() > 0) {
				number.append(c);
			} else {
				break;
			}
		}
		String[] parts = number.toString().split("\\.");
		String major = parts.length > 0 && parts[0].length() > 0 ? parts[0] : "8";
		String minor = parts.length > 1 && parts[1].length() > 0 ? parts[1] : "0";
		return Double.parseDouble(major + "." + minor);
	}

	public static void main(String[] args) throws Exception {
		// MCP (Model Context Protocol) mode: an AI agent drives Logisim over JSON-RPC
		// instead of the GUI. Headless is handled before anything else so that the GUI,
		// the splash screen, the auto-updater and the online-user reporting never start;
		// with --with-gui the server is started next to the normal startup instead, and
		// the window opens as it always would, for the user to watch the agent work.
		if (com.cburch.logisim.mcp.McpMain.wantsMcp(args)) {
			if (com.cburch.logisim.mcp.McpMain.isGuiMode(args)) {
				args = com.cburch.logisim.mcp.McpMain.startWithGui(args);
			} else {
				System.exit(com.cburch.logisim.mcp.McpMain.run(args));
			}
		}
		Startup startup = Startup.parseArgs(args);
		if (startup != null) {
			// if it's not command line
			if (!startup.isTty()) {
				// remove online user when runtime stopped
				Runtime.getRuntime().addShutdownHook(new Thread() {
					@Override
					public void run() {
						if (AppPreferences.SEND_DATA.getBoolean())
							Startup.runRemotePhpCode(
									"http://logisim.altervista.org/LogisimData/OnlineUsers/online.php?val=0");
					}
				});
				// add online user
				if (AppPreferences.SEND_DATA.getBoolean())
					Startup.runRemotePhpCode("http://logisim.altervista.org/LogisimData/OnlineUsers/online.php?val=1");
			}
			// search for updates, if true update and restart, else run logisim
			if (!startup.autoUpdate(true, null)) {
				try {
					startup.run();
				} catch (Throwable e) {
					Writer result = new StringWriter();
					PrintWriter printWriter = new PrintWriter(result);
					e.printStackTrace(printWriter);
					JOptionPane.showMessageDialog(null, result.toString());
					System.exit(-1);
				}
				// MCP: the windows exist now, so a server asked for on the command line
				// (--with-gui) or configured to start automatically can be shown/listened on.
				if (com.cburch.logisim.mcp.McpMain.needsGuiHook()) {
					try {
						com.cburch.logisim.gui.mcp.McpFrame.onGuiReady();
					} catch (Throwable t) {
						System.err.println("[logisim-mcp] could not finish MCP startup: " + t);
					}
				}
			} else {
				if (AppPreferences.SEND_DATA.getBoolean())
					Startup.runRemotePhpCode(
							"http://logisim.altervista.org/LogisimData/Autoupdates/autoupdates.php?val=1");
				Startup.restart(args);
			}
		} else
			System.exit(-1);
	}
}
