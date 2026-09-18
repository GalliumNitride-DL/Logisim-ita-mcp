/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.io.Closeable;
import java.io.IOException;

/**
 * A message oriented transport carrying one JSON-RPC message per call.
 *
 * <p>
 * MCP over stdio (and the widely used socket variant of it) frames messages as
 * single lines of UTF-8 JSON without embedded newlines, which is exactly what
 * {@link #readMessage()} and {@link #writeMessage(String)} expose.
 * </p>
 *
 * <p>
 * The abstraction exists so that the very same tool layer can serve a headless
 * process (stdio, used today) and, later, an already running Logisim window over
 * a local socket.
 * </p>
 */
public interface McpTransport extends Closeable {
	/** Returns the next message, or null once the peer has closed the transport. */
	String readMessage() throws IOException;

	/** Sends one message. Implementations must not add line breaks to the payload. */
	void writeMessage(String message) throws IOException;

	@Override
	void close() throws IOException;

	/** Human readable description, reported by the status tool. */
	String describe();
}
