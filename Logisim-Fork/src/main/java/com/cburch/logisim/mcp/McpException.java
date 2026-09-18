/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

/**
 * Signals a failure that should be reported to the agent. Anything thrown from a
 * tool is turned into an MCP tool result with {@code isError = true} and the
 * exception message, so the message should always be phrased as something an
 * agent can act upon (for example "no component with id c7 in circuit main").
 *
 * <p>
 * For failures of the protocol itself (unknown method, malformed request) the
 * JSON-RPC error code carried by this exception is used instead.
 * </p>
 */
public class McpException extends RuntimeException {
	private static final long serialVersionUID = 1L;

	private final int code;

	public McpException(String message) {
		this(JsonRpc.SERVER_ERROR, message, null);
	}

	public McpException(String message, Throwable cause) {
		this(JsonRpc.SERVER_ERROR, message, cause);
	}

	public McpException(int code, String message) {
		this(code, message, null);
	}

	public McpException(int code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public int getCode() {
		return code;
	}

	public static McpException invalidParams(String message) {
		return new McpException(JsonRpc.INVALID_PARAMS, message);
	}
}
