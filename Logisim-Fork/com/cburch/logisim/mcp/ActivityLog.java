/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A short, thread safe record of what the MCP server has been asked to do.
 *
 * <p>
 * The point of this is the user, not the agent: in GUI mode the user watches the
 * circuit change, and this is what a "MCP" window can show as a running commentary
 * ("logisim_wire_add ok (12 ms)"), together with the last errors. Headless servers
 * simply never read it.
 * </p>
 */
public final class ActivityLog {
	private static final int LIMIT = 200;
	private static final ConcurrentLinkedDeque<String> ENTRIES = new ConcurrentLinkedDeque<String>();
	private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");

	private ActivityLog() {
	}

	/** Records one line, trimmed to a single short line. Drops the oldest entries. */
	public static void record(String text) {
		if (text == null) {
			return;
		}
		String line = text.replace('\n', ' ').replace('\r', ' ').trim();
		if (line.length() > 200) {
			line = line.substring(0, 200) + "...";
		}
		ENTRIES.addLast(TIME.format(new Date()) + "  " + line);
		while (ENTRIES.size() > LIMIT) {
			ENTRIES.pollFirst();
		}
	}

	/** The most recent lines, oldest first, at most {@code count} of them. */
	public static List<String> recent(int count) {
		List<String> all = new ArrayList<String>(ENTRIES);
		if (count <= 0 || all.size() <= count) {
			return all;
		}
		return new ArrayList<String>(all.subList(all.size() - count, all.size()));
	}

	public static int size() {
		return ENTRIES.size();
	}

	/** Empties the log; the MCP window offers this to the user. */
	public static void clear() {
		ENTRIES.clear();
	}
}
