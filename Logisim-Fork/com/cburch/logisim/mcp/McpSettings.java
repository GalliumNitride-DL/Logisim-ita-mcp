/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * The handful of settings the MCP window edits, kept in the platform's own preference
 * store under this package's node.
 *
 * <p>
 * Deliberately not in {@code AppPreferences}: that class is intricate (its own
 * preference types, listeners and resource bundles), and adding to it would mean
 * touching Logisim's settings machinery for something that is entirely this layer's
 * business. Everything MCP related therefore lives in the MCP layer - the settings
 * here, the window in {@code com.cburch.logisim.gui.mcp} - and Logisim itself only
 * grows a way to open that window and a call at startup.
 * </p>
 */
public final class McpSettings {
	private static final String AUTO_START = "autoStart";
	private static final String PORT = "port";
	private static final String READ_ONLY = "readOnlyAtStart";
	private static final String PAUSED = "pausedAtStart";

	private McpSettings() {
	}

	private static Preferences node() {
		return Preferences.userNodeForPackage(McpMain.class);
	}

	/** Whether the server should start by itself when a Logisim window opens. */
	public static boolean isAutoStart() {
		return node().getBoolean(AUTO_START, false);
	}

	public static void setAutoStart(boolean value) {
		node().putBoolean(AUTO_START, value);
		flush();
	}

	/** The port to listen on; 7331 unless the user picked another one. */
	public static int getPort() {
		return node().getInt(PORT, 7331);
	}

	public static void setPort(int value) {
		node().putInt(PORT, value);
		flush();
	}

	/** Whether a session starts with editing already refused. */
	public static boolean isReadOnlyAtStart() {
		return node().getBoolean(READ_ONLY, false);
	}

	public static void setReadOnlyAtStart(boolean value) {
		node().putBoolean(READ_ONLY, value);
		flush();
	}

	/** Whether a session starts paused (useful when the user wants to look first). */
	public static boolean isPausedAtStart() {
		return node().getBoolean(PAUSED, false);
	}

	public static void setPausedAtStart(boolean value) {
		node().putBoolean(PAUSED, value);
		flush();
	}

	private static void flush() {
		try {
			node().flush();
		} catch (BackingStoreException e) {
			// not being able to remember a setting must never break the window
		} catch (SecurityException e) {
			// same
		}
	}
}
