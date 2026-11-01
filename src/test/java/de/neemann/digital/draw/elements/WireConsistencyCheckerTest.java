/*
 * Copyright (c) 2016 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.draw.elements;

import de.neemann.digital.draw.graphics.Vector;
import junit.framework.TestCase;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;

/**
 */
public class WireConsistencyCheckerTest extends TestCase {

    public void testCheck() throws Exception {
        ArrayList<Wire> wires = new ArrayList<>();
        wires.add(new Wire(new Vector(0, 0), new Vector(10, 0)));
        wires.add(new Wire(new Vector(10, 0), new Vector(10, 10)));
        wires.add(new Wire(new Vector(10, 0), new Vector(20, 0)));

        wires = new WireConsistencyChecker(wires).check();

        assertEquals(3, wires.size());
        checkContains(wires, new Wire(new Vector(0, 0), new Vector(10, 0)));
        checkContains(wires, new Wire(new Vector(10, 0), new Vector(20, 0)));
        checkContains(wires, new Wire(new Vector(10, 0), new Vector(10, 10)));
    }

    public void testCheck2() throws Exception {
        ArrayList<Wire> wires = new ArrayList<>();
        wires.add(new Wire(new Vector(0, 0), new Vector(10, 0)));
        wires.add(new Wire(new Vector(10, 0), new Vector(10, 10)));
        wires.add(new Wire(new Vector(0, 0), new Vector(20, 0)));

        wires = new WireConsistencyChecker(wires).check();

        assertEquals(3, wires.size());
        checkContains(wires, new Wire(new Vector(0, 0), new Vector(10, 0)));
        checkContains(wires, new Wire(new Vector(10, 0), new Vector(20, 0)));
        checkContains(wires, new Wire(new Vector(10, 0), new Vector(10, 10)));
    }

    public void testCheck3() throws Exception {
        ArrayList<Wire> wires = new ArrayList<>();
        wires.add(new Wire(new Vector(0, 0), new Vector(10, 0)));
        wires.add(new Wire(new Vector(10, 0), new Vector(20, 0)));
        wires.add(new Wire(new Vector(10, 0), new Vector(10, 10)));
        wires.add(new Wire(new Vector(10, 0), new Vector(10, -10)));

        wires = new WireConsistencyChecker(wires).check();

        assertEquals(4, wires.size());
        checkContains(wires, new Wire(new Vector(0, 0), new Vector(10, 0)));
        checkContains(wires, new Wire(new Vector(10, 0), new Vector(20, 0)));
        checkContains(wires, new Wire(new Vector(10, 0), new Vector(10, 10)));
        checkContains(wires, new Wire(new Vector(10, 0), new Vector(10, -10)));
    }

    public void testCheck4() throws Exception {
        ArrayList<Wire> wires = new ArrayList<>();
        wires.add(new Wire(new Vector(0, 10), new Vector(20, 10)));
        wires.add(new Wire(new Vector(10, 0), new Vector(10, 20)));

        wires = new WireConsistencyChecker(wires).check();

        assertEquals(2, wires.size());
        checkContains(wires, new Wire(new Vector(0, 10), new Vector(20, 10)));
        checkContains(wires, new Wire(new Vector(10, 0), new Vector(10, 20)));
    }

    public void testProtectedPinRemainsWireEndpoint() {
        ArrayList<Wire> wires = new ArrayList<>();
        wires.add(new Wire(new Vector(0, 0), new Vector(10, 0)));
        wires.add(new Wire(new Vector(10, 0), new Vector(20, 0)));

        wires = new WireConsistencyChecker(wires).check(Collections.singletonList(new Vector(10, 0)));

        assertEquals(2, wires.size());
        boolean leftSegment = false;
        boolean rightSegment = false;
        for (Wire wire : wires) {
            leftSegment |= wire.p1.equals(new Vector(0, 0)) && wire.p2.equals(new Vector(10, 0));
            rightSegment |= wire.p1.equals(new Vector(10, 0)) && wire.p2.equals(new Vector(20, 0));
        }
        assertTrue(leftSegment);
        assertTrue(rightSegment);
    }

    public void testProtectedPinOnWireInteriorDoesNotSplitUnconnectedWire() {
        ArrayList<Wire> wires = new ArrayList<>();
        wires.add(new Wire(new Vector(0, 0), new Vector(20, 0)));

        wires = new WireConsistencyChecker(wires).check(Collections.singletonList(new Vector(10, 0)));

        assertEquals(1, wires.size());
        Wire wire = wires.get(0);
        assertTrue(wire.p1.equals(new Vector(0, 0)) && wire.p2.equals(new Vector(20, 0))
                || wire.p1.equals(new Vector(20, 0)) && wire.p2.equals(new Vector(0, 0)));
    }

    public void testProtectedPinOnlySplitsItsOwnNet() {
        ArrayList<Wire> wires = new ArrayList<>();
        wires.add(new Wire(new Vector(10, 0), new Vector(20, 0)).setColor(Color.RED));
        wires.add(new Wire(new Vector(0, 0), new Vector(20, 0)).setColor(Color.BLUE));

        wires = new WireConsistencyChecker(wires).check(Collections.singletonList(new Vector(10, 0)));

        boolean blueWireUnsplit = false;
        for (Wire wire : wires) {
            if (Color.BLUE.equals(wire.getColor())) {
                blueWireUnsplit |= wire.p1.equals(new Vector(0, 0)) && wire.p2.equals(new Vector(20, 0));
                blueWireUnsplit |= wire.p1.equals(new Vector(20, 0)) && wire.p2.equals(new Vector(0, 0));
            }
        }
        assertTrue(blueWireUnsplit);
    }

    public static void checkContains(ArrayList<Wire> wires, Wire wire) {
        for (Wire w : wires)
            if (wire.equalsContent(wire))
                return;

        wire = new Wire(wire.p2, wire.p1);

        for (Wire w : wires)
            if (w.equalsContent(wire))
                return;

        assertTrue(false);
    }
}
