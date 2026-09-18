/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;

/**
 * Line delimited JSON transport over an arbitrary pair of streams. This is used
 * both for stdio (the MCP default) and for a local TCP socket, so that both
 * modes share exactly the same framing.
 */
public class StreamMcpTransport implements McpTransport {
	private static final Charset UTF8 = Charset.forName("UTF-8");

	private final InputStream in;
	private final OutputStream out;
	private final Socket socket; // null for stdio
	private final String description;

	public StreamMcpTransport(InputStream in, OutputStream out, String description) {
		this(in, out, null, description);
	}

	public StreamMcpTransport(InputStream in, OutputStream out, Socket socket, String description) {
		this.in = in;
		this.out = out;
		this.socket = socket;
		this.description = description;
	}

	@Override
	public String readMessage() throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
		int c;
		while (true) {
			c = in.read();
			if (c == -1) {
				if (buffer.size() == 0) {
					return null;
				}
				break;
			}
			if (c == '\n') {
				break;
			}
			if (c == '\r') {
				continue;
			}
			buffer.write(c);
		}
		return new String(buffer.toByteArray(), UTF8);
	}

	@Override
	public void writeMessage(String message) throws IOException {
		byte[] bytes = message.getBytes(UTF8);
		synchronized (out) {
			out.write(bytes);
			out.write('\n');
			out.flush();
		}
	}

	@Override
	public void close() throws IOException {
		try {
			out.flush();
		} catch (IOException e) {
			// the peer is gone; nothing useful to do
		}
		if (socket != null) {
			socket.close();
		}
	}

	@Override
	public String describe() {
		return description;
	}
}
