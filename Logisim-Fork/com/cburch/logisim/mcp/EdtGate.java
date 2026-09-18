/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.awt.EventQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs work on Swing's event dispatch thread, which is where every touch of a
 * {@code Project}, a {@code Circuit} or a window has to happen once a GUI exists.
 *
 * <p>
 * The transport thread may not do that work itself, and it must not wait forever
 * either: a modal dialog (a file chooser, an error box, or the user's own dialog) stops
 * the dispatch thread from getting to the queued work, and a server that simply blocked
 * would look like a hang to the agent and to the user. So the work is queued and waited
 * for with a deadline, and the timeout is reported as a normal tool error that says what
 * to do about it.
 * </p>
 *
 * <p>
 * Headless mode never uses this: there is no dispatch thread, and the session's own
 * project is driven from the calling thread exactly as before.
 * </p>
 */
public final class EdtGate {
	/** How long a single tool call may wait for the window to become free. */
	public static final long DEFAULT_TIMEOUT_MS = 30000;

	private EdtGate() {
	}

	public static <T> T call(Callable<T> work, long timeoutMs, String what) throws Exception {
		if (EventQueue.isDispatchThread()) {
			return work.call();
		}
		FutureTask<T> task = new FutureTask<T>(work);
		EventQueue.invokeLater(task);
		try {
			return task.get(timeoutMs, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new McpException("the Logisim window did not respond within " + (timeoutMs / 1000) + " s while running '"
					+ what + "'. A dialog is probably open: the user has to answer or close it, then the call can be "
					+ "retried. A very large circuit can also take that long (a truth table enumerates every row).");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception) {
				throw (Exception) cause;
			}
			if (cause instanceof Error) {
				throw (Error) cause;
			}
			throw new McpException(McpSession.message(cause));
		}
	}
}
