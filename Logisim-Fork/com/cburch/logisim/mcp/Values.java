/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Value;
import com.google.gson.JsonObject;

/**
 * Conversion between Logisim's four-valued {@link Value} and the textual form
 * used on the MCP wire.
 *
 * <p>
 * A value is rendered most-significant-bit first, one character per bit, using
 * the very same characters Logisim itself uses: {@code 0}, {@code 1},
 * {@code x} (unknown / floating) and {@code e} (error / conflicting drivers). A
 * zero-width value (Logisim's "null" value) is rendered as {@code z}.
 * </p>
 */
public final class Values {
	private Values() {
	}

	public static char bitChar(Value bit) {
		if (bit == Value.TRUE) {
			return '1';
		}
		if (bit == Value.FALSE) {
			return '0';
		}
		if (bit == Value.ERROR) {
			return 'e';
		}
		return 'x';
	}

	/** Renders a value as a bit string, most significant bit first. */
	public static String format(Value value) {
		if (value == null || value.getWidth() == 0) {
			return "z";
		}
		int width = value.getWidth();
		StringBuilder buf = new StringBuilder(width);
		for (int i = width - 1; i >= 0; i--) {
			buf.append(bitChar(value.get(i)));
		}
		return buf.toString();
	}

	/** Full machine readable description of a value. */
	public static JsonObject describe(Value value) {
		JsonObject ret = new JsonObject();
		if (value == null || value.getWidth() == 0) {
			ret.addProperty("value", "z");
			ret.addProperty("width", 0);
			ret.addProperty("defined", false);
			ret.addProperty("error", false);
			return ret;
		}
		ret.addProperty("value", format(value));
		ret.addProperty("width", value.getWidth());
		ret.addProperty("defined", Boolean.valueOf(value.isFullyDefined()));
		ret.addProperty("error", Boolean.valueOf(value.isErrorValue()));
		if (value.isFullyDefined() && value.getWidth() <= 31) {
			ret.addProperty("int", value.toIntValue());
		}
		if (value.getWidth() == value.getAll().length) {
			boolean allOnes = true;
			for (Value bit : value.getAll()) {
				if (bit != Value.TRUE) {
					allOnes = false;
					break;
				}
			}
			ret.addProperty("all_ones", Boolean.valueOf(allOnes));
		}
		return ret;
	}

	/**
	 * Parses a value for a port of the given width. Accepts the per-bit characters
	 * used by Logisim ({@code 0 1 x e z}), the words {@code true}/{@code false},
	 * {@code high}/{@code low}, {@code unknown}, {@code error} and {@code tri}, and
	 * decimal or {@code 0x}-prefixed integers.
	 */
	public static Value parse(String text, BitWidth width) {
		if (text == null) {
			throw new McpException("a signal value is required");
		}
		String s = text.trim().toLowerCase();
		if (s.isEmpty()) {
			throw new McpException("signal value must not be empty");
		}
		if (s.equals("0") || s.equals("false") || s.equals("low") || s.equals("off")) {
			return Value.createKnown(width, 0);
		}
		if (s.equals("1") || s.equals("true") || s.equals("high") || s.equals("on")) {
			return Value.createKnown(width, 1);
		}
		if (s.equals("x") || s.equals("unknown") || s.equals("u") || s.equals("float") || s.equals("floating")) {
			return Value.createUnknown(width);
		}
		if (s.equals("e") || s.equals("error") || s.equals("err")) {
			return Value.createError(width);
		}
		if (s.equals("z") || s.equals("tri") || s.equals("hi-z") || s.equals("nil")) {
			return Value.NIL;
		}
		if (s.length() == width.getWidth() && isBitString(s)) {
			Value[] bits = new Value[s.length()];
			for (int i = 0; i < s.length(); i++) {
				bits[i] = bitValue(s.charAt(i));
			}
			return Value.create(bits);
		}
		try {
			int radix = 10;
			String digits = s;
			if (s.startsWith("0x")) {
				radix = 16;
				digits = s.substring(2);
			} else if (s.startsWith("0b")) {
				radix = 2;
				digits = s.substring(2);
			}
			long parsed = Long.parseLong(digits, radix);
			if (parsed < 0 || (width.getWidth() < 32 && parsed >= (1L << width.getWidth()))) {
				throw new McpException("value " + text + " does not fit in " + width.getWidth() + " bit(s)");
			}
			return Value.createKnown(width, (int) parsed);
		} catch (NumberFormatException e) {
			throw new McpException("cannot interpret '" + text + "' as a signal value; use 0, 1, x, e, z, "
					+ "or an integer (decimal, 0x hex, 0b binary)");
		}
	}

	private static boolean isBitString(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c != '0' && c != '1' && c != 'x' && c != 'e' && c != 'z') {
				return false;
			}
		}
		return true;
	}

	private static Value bitValue(char c) {
		switch (c) {
		case '0':
			return Value.FALSE;
		case '1':
			return Value.TRUE;
		case 'e':
			return Value.ERROR;
		case 'z':
			return Value.UNKNOWN;
		default:
			return Value.UNKNOWN;
		}
	}
}
