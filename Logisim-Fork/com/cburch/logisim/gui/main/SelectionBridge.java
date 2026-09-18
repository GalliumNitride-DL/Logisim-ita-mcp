/*
 * MCP (Model Context Protocol) support for Logisim.
 *
 * This file is part of the Logisim source code, licensed under the GNU GPL v2
 * or later, exactly as the rest of Logisim.
 */

package com.cburch.logisim.gui.main;

/**
 * A very small bridge, added for the MCP layer, so that a selection can be cleared
 * without deleting anything.
 *
 * <p>
 * Deselecting is a package private operation ({@code SelectionBase.clear}) while the
 * looks-similar public API in {@code SelectionActions} ({@code clear}, {@code drop},
 * {@code dropAll}) really removes the components from the <em>circuit</em>. An agent
 * that wants to point at something - "look, these two nets are shorted" - must not
 * delete it, so the access is widened here instead of guessed at the call site.
 * </p>
 *
 * <p>
 * This adds no behaviour: it only exposes what the editor itself does when the user
 * clicks empty space. Nothing else about Logisim changes.
 * </p>
 */
public final class SelectionBridge {
	private SelectionBridge() {
	}

	/**
	 * True when components are in mid air (a paste or a drag that has not been placed
	 * yet). Clearing the selection then would throw those away, so callers should leave
	 * the selection alone until the user has finished.
	 */
	public static boolean hasFloatingComponents(Selection selection) {
		return selection != null && !selection.lifted.isEmpty();
	}

	/**
	 * Deselects everything without touching the circuit. Does nothing while components
	 * are floating; see {@link #hasFloatingComponents}.
	 *
	 * @return true when the selection was cleared
	 */
	public static boolean clearSelection(Selection selection) {
		if (selection == null || hasFloatingComponents(selection)) {
			return false;
		}
		if (selection.selected.isEmpty()) {
			return false;
		}
		selection.clear(null, false);
		return true;
	}
}
