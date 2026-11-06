/*
 * Copyright (c) 2026.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components;

import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.Wire;
import de.neemann.digital.draw.graphics.Vector;
import de.neemann.gui.Mouse;

import java.awt.event.MouseEvent;
import java.util.function.BiConsumer;

import static de.neemann.digital.draw.shapes.GenericShape.SIZE2;

/**
 * Selects a wire when a double-click targets it.
 */
final class WireDoubleClickHandler {
    private WireDoubleClickHandler() {
    }

    /**
     * Selects the wire nearest the given point when the click qualifies.
     *
     * @param event the click event
     * @param mouse the mouse event interpreter
     * @param circuit the circuit
     * @param pos the click position in circuit coordinates
     * @param scale the current workspace scale
     * @param onSelected called with the selected wire and position
     * @return true if a wire was selected
     */
    static boolean select(final MouseEvent event, final Mouse mouse, final Circuit circuit, final Vector pos,
                          final double scale, final BiConsumer<Wire, Vector> onSelected) {
        if (event.getClickCount() < 2 || !mouse.isPrimaryClick(event) || mouse.isClickModifier(event))
            return false;
        int radius = Math.max(SIZE2, (int) (SIZE2 / scale));
        Wire wire = circuit.getWireAt(pos, radius);
        if (wire == null)
            return false;
        onSelected.accept(wire, pos);
        return true;
    }
}
