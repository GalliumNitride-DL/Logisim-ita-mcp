/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.math.BigInteger;

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
		return describe(value, null);
	}

	/**
	 * Full machine readable description of a value, optionally with the reading of it
	 * an agent asked for.
	 *
	 * <p>
	 * The bit string is always reported, because it is the only form that can show an
	 * unknown or error bit. A value that is fully known also gets 'hex' - and, from four
	 * bits up, 'dec' and 'signed' - because a 32-bit result otherwise comes back as
	 * thirty-two 0s and 1s with no number in it at all to compare against. Logisim's own
	 * 'int' is only added up to 31 bits, where a Java int cannot overflow.
	 * </p>
	 *
	 * @param radix
	 *            "bin", "hex", "dec" or "signed": adds that reading as 'text'. Null
	 *            leaves it out, which is the default
	 */
	public static JsonObject describe(Value value, String radix) {
		JsonObject ret = new JsonObject();
		if (value == null || value.getWidth() == 0) {
			ret.addProperty("value", "z");
			ret.addProperty("width", 0);
			ret.addProperty("defined", false);
			ret.addProperty("error", false);
			addText(ret, "z", radix);
			return ret;
		}
		ret.addProperty("value", format(value));
		ret.addProperty("width", value.getWidth());
		ret.addProperty("defined", Boolean.valueOf(value.isFullyDefined()));
		ret.addProperty("error", Boolean.valueOf(value.isErrorValue()));
		boolean known = value.isFullyDefined();
		if (known && value.getWidth() <= 31) {
			ret.addProperty("int", value.toIntValue());
		}
		if (known) {
			String hex = hex(value);
			if (hex != null) {
				ret.addProperty("hex", "0x" + hex);
			}
			if (value.getWidth() >= 4) {
				BigInteger magnitude = magnitude(value);
				ret.addProperty("dec", magnitude.toString());
				if (value.get(value.getWidth() - 1) == Value.TRUE) {
					ret.addProperty("signed",
							magnitude.subtract(BigInteger.ONE.shiftLeft(value.getWidth())).toString());
				}
			}
			addText(ret, radix == null ? null : reading(value, radix), radix);
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
	 *
	 * <p>
	 * A {@code 0x} or {@code 0b} prefix is always a number, never a bit pattern. It used to
	 * be possible for a short one to be read as bits - {@code "0x0"} on a three-bit pin
	 * became the pattern {@code 0x0}, that is 0, unknown, 0, with no error reported, because
	 * {@code x} is one of the per-bit characters - and a caller could not tell that from the
	 * number 0.
	 * </p>
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
		boolean numbered = s.startsWith("0x") || s.startsWith("0b");
		if (!numbered && s.length() == width.getWidth() && isBitString(s)) {
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

	/** The readings 'radix' accepts, for the schema and for the error message. */
	public static final String[] RADIX_NAMES = { "bin", "hex", "dec", "signed" };

	/**
	 * Hex digits of a value, most significant first, padded to a whole number of
	 * nibbles - or null when some bit is unknown, since hex cannot show that.
	 */
	public static String hex(Value value) {
		if (value == null || value.getWidth() == 0 || !value.isFullyDefined()) {
			return null;
		}
		int width = value.getWidth();
		int digits = (width + 3) / 4;
		StringBuilder buf = new StringBuilder(digits);
		for (int digit = digits - 1; digit >= 0; digit--) {
			int nibble = 0;
			for (int bit = 0; bit < 4; bit++) {
				int index = digit * 4 + bit;
				if (index < width && bitChar(value.get(index)) == '1') {
					nibble |= 1 << bit;
				}
			}
			buf.append(Character.forDigit(nibble, 16));
		}
		return buf.toString();
	}

	/** The unsigned magnitude of a fully known value, of any width up to 64 bits. */
	public static BigInteger magnitude(Value value) {
		BigInteger ret = BigInteger.ZERO;
		for (int i = value.getWidth() - 1; i >= 0; i--) {
			ret = ret.shiftLeft(1);
			if (bitChar(value.get(i)) == '1') {
				ret = ret.add(BigInteger.ONE);
			}
		}
		return ret;
	}

	/** How a value reads in one of {@link #RADIX_NAMES}, or null when it has no such reading. */
	public static String reading(Value value, String radix) {
		if (value == null) {
			return null;
		}
		if ("bin".equals(radix)) {
			return format(value);
		}
		if (!value.isFullyDefined() || value.getWidth() == 0 || "z".equals(radix)) {
			return null;
		}
		if ("hex".equals(radix)) {
			String hex = hex(value);
			return hex == null ? null : "0x" + hex;
		}
		if ("dec".equals(radix)) {
			return magnitude(value).toString();
		}
		if ("signed".equals(radix)) {
			BigInteger magnitude = magnitude(value);
			return value.get(value.getWidth() - 1) == Value.TRUE
					? magnitude.subtract(BigInteger.ONE.shiftLeft(value.getWidth())).toString()
					: magnitude.toString();
		}
		throw new McpException("'" + radix + "' is not a way to read a value; use one of bin, hex, dec, signed");
	}

	/**
	 * Adds the reading the caller asked for as 'text', so that a table of values can be read
	 * without converting it by hand. A bit string needs no second copy of itself, so nothing
	 * is added when the reading is the bit string.
	 */
	private static void addText(JsonObject target, String text, String radix) {
		if (radix == null || text == null) {
			return;
		}
		target.addProperty("text", text);
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
