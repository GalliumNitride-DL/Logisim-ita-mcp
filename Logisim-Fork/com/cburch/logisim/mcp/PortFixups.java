/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ports whose declared direction is wrong in Logisim's own libraries.
 *
 * <p>
 * A few components declare a port as an input and then drive it. Logisim itself does not
 * mind - the simulator writes whatever the component sets, and every circuit built by hand
 * works - but anything that reads the declaration reports the opposite of the truth to an
 * agent. The visible damage is in the netlist: a net whose only source is such a port looks
 * undriven, so an agent that wires a carry chain is told its circuit has no driver.
 * </p>
 *
 * <p>
 * Only ports verified one by one belong here, and the verification is by simulation: place
 * the component, drive its inputs, propagate, and see which ports carry a value the
 * component produced. A candidate found by reading the source is not enough, which is why
 * the list is short.
 * </p>
 *
 * <pre>
 * Verified
 *   1. An Adder's Carry Out. Four facts from Logisim's own source, the same file:
 *        std/arith/Adder.java:90   ps[C_OUT] = new Port(-20, 20, Port.INPUT, 1);
 *        std/arith/Adder.java:95   ps[C_OUT].setToolTip(Strings.getter("adderCarryOutTip"));
 *        std/arith/Adder.java:109  painter.drawPort(C_OUT, "c out", Direction.SOUTH);
 *        std/arith/Adder.java:135  state.setPort(C_OUT, outs[1], delay);
 *      It is declared an input, named and drawn as an output, and written by propagate(),
 *      so it is an output that is declared an input. Port order for this component is IN0,
 *      IN1, OUT, C_IN, C_OUT (Adder.java:29-33), so its index is 4.
 *      Observed as well: before the correction, a net whose only source was this port was
 *      reported by logisim_circuit_nets as having no driver; after it, one.
 * </pre>
 *
 * <p>
 * Keyed by component type and port index, not by library: the type name is unique enough,
 * and the netlist path does not always know the library. Corrections are reported rather
 * than applied silently, so the difference between "Logisim says" and "we know" stays
 * visible in the reply.
 * </p>
 */
public final class PortFixups {
	/** Shown beside a corrected port, so the agent can see why it differs from the library. */
	public static final String NOTE = "the library declares this port as an input, but the component drives it "
			+ "(verified by simulation), so it is reported as an output";

	private static final Map<String, String> CORRECTIONS = new LinkedHashMap<String, String>();

	static {
		CORRECTIONS.put("Adder#4", "output");
	}

	private PortFixups() {
	}

	/** The true direction of a port, or null when the library's declaration is right. */
	public static String correction(String typeName, int index) {
		if (typeName == null || index < 0) {
			return null;
		}
		return CORRECTIONS.get(typeName + "#" + index);
	}

	/** True when this port's direction was corrected, so callers can add {@link #NOTE}. */
	public static boolean isCorrected(String typeName, int index) {
		return correction(typeName, index) != null;
	}

	/**
	 * Whether this port drives its net. Logisim's raw end type is what decides it - the
	 * stored type is inverted for pins, and the netlist counts drivers from the raw value -
	 * except where the library is simply wrong about a port.
	 */
	public static boolean isDriver(String typeName, int index, int endType) {
		if (isCorrected(typeName, index)) {
			return true;
		}
		return endType == com.cburch.logisim.comp.EndData.OUTPUT_ONLY;
	}

	/** How many corrections there are, so a test can tell if the table was left empty. */
	public static int size() {
		return CORRECTIONS.size();
	}
}
