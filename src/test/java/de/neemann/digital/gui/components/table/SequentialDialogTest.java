/*
 * Copyright (c) 2026 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.table;

import de.neemann.digital.analyse.TransitionTable;
import de.neemann.digital.analyse.TruthTable;
import de.neemann.digital.fsm.FSM;
import junit.framework.TestCase;

/**
 * Tests for SequentialDialog patterns and synthesis truth table generation.
 */
public class SequentialDialogTest extends TestCase {

    private int getNextState(TruthTable tt, int row, int n) {
        int next = 0;
        int varCount = tt.getVars().size();
        for (int i = 0; i < n; i++) {
            // result index: varCount + i corresponds to bit (n - 1 - i)
            int bitVal = tt.getValue(row, varCount + i);
            next = (next << 1) | bitVal;
        }
        return next;
    }

    public void testBinaryUp() {
        TruthTable tt = SequentialDialog.buildTruthTable(3, "Q", SequentialDialog.PatternType.BINARY_UP, null);
        assertEquals(3, tt.getVars().size());
        assertEquals(3, tt.getResultCount());
        assertEquals(8, tt.getRows());

        for (int r = 0; r < 8; r++) {
            int expected = (r + 1) & 7;
            assertEquals(expected, getNextState(tt, r, 3));
        }
    }

    public void testBinaryDown() {
        TruthTable tt = SequentialDialog.buildTruthTable(3, "Q", SequentialDialog.PatternType.BINARY_DOWN, null);
        for (int r = 0; r < 8; r++) {
            int expected = (r - 1 + 8) & 7;
            assertEquals(expected, getNextState(tt, r, 3));
        }
    }

    public void testGrayCodeCounter() {
        TruthTable tt = SequentialDialog.buildTruthTable(3, "Q", SequentialDialog.PatternType.GRAY_CODE, null);
        for (int r = 0; r < 8; r++) {
            int next = getNextState(tt, r, 3);
            // Verify race-free property: Hamming distance between current state r and next state is exactly 1
            int diff = r ^ next;
            assertEquals("Hamming distance must be 1 for race-free async transitions: state=" + r + " next=" + next,
                    1, Integer.bitCount(diff));
        }
    }

    public void testGrayCodeBidir() {
        TruthTable tt = SequentialDialog.buildTruthTable(3, "Q", SequentialDialog.PatternType.GRAY_CODE_BIDIR, null);
        assertEquals(4, tt.getVars().size()); // D + 3 state bits
        assertEquals(16, tt.getRows());

        // For D = 0 (rows 0..7), next state is count up in Gray
        for (int r = 0; r < 8; r++) {
            int next = getNextState(tt, r, 3);
            assertEquals(1, Integer.bitCount(r ^ next));
        }
        // For D = 1 (rows 8..15), next state is count down in Gray
        for (int r = 8; r < 16; r++) {
            int state = r & 7;
            int next = getNextState(tt, r, 3);
            assertEquals(1, Integer.bitCount(state ^ next));
        }
    }

    public void testRingCounter() {
        TruthTable tt = SequentialDialog.buildTruthTable(3, "Q", SequentialDialog.PatternType.RING, null);
        // State 001 (1) -> 010 (2) -> 100 (4) -> 001 (1)
        assertEquals(2, getNextState(tt, 1, 3));
        assertEquals(4, getNextState(tt, 2, 3));
        assertEquals(1, getNextState(tt, 4, 3));
    }

    public void testJohnsonCounter() {
        TruthTable tt = SequentialDialog.buildTruthTable(3, "Q", SequentialDialog.PatternType.JOHNSON, null);
        // Johnson 3 bits: 000(0) -> 001(1) -> 011(3) -> 111(7) -> 110(6) -> 100(4) -> 000(0)
        assertEquals(1, getNextState(tt, 0, 3));
        assertEquals(3, getNextState(tt, 1, 3));
        assertEquals(7, getNextState(tt, 3, 3));
        assertEquals(6, getNextState(tt, 7, 3));
        assertEquals(4, getNextState(tt, 6, 3));
        assertEquals(0, getNextState(tt, 4, 3));
    }

    public void testCustomSequence() {
        TruthTable tt = SequentialDialog.buildTruthTable(3, "Q", SequentialDialog.PatternType.CUSTOM, "0, 3, 5, 6");
        assertEquals(3, getNextState(tt, 0, 3));
        assertEquals(5, getNextState(tt, 3, 3));
        assertEquals(6, getNextState(tt, 5, 3));
        assertEquals(0, getNextState(tt, 6, 3));
    }

    public void testSequentialToTransitionTableAndFSM() throws Exception {
        TruthTable tt = SequentialDialog.buildTruthTable(2, "Q", SequentialDialog.PatternType.GRAY_CODE, null);
        TransitionTable transTable = TransitionTable.fromTruthTable(tt);
        assertEquals(4, transTable.getStateCount());
        assertEquals(1, transTable.getColumnCount()); // 0 inputs -> 1 column

        FSM fsm = transTable.toFSM();
        assertEquals(4, fsm.getStates().size());
        assertEquals(4, fsm.getTransitions().size());
    }

    public void testSrLatchNor() throws Exception {
        TruthTable tt = SequentialDialog.buildTruthTable(1, "Q", SequentialDialog.PatternType.SR_LATCH_NOR, null);
        assertEquals(3, tt.getVars().size());
        assertEquals("S", tt.getVars().get(0).getIdentifier());
        assertEquals("R", tt.getVars().get(1).getIdentifier());
        assertEquals("Q_0^n", tt.getVars().get(2).getIdentifier());
        assertEquals(1, tt.getResultCount());
        assertEquals("Q_0^{n+1}", tt.getResultName(0));
        assertEquals(8, tt.getRows());

        // Row values: (S, R, Q) -> Q+
        // (0,0,0)->0, (0,0,1)->1
        assertEquals(0, tt.getValue(0, 3));
        assertEquals(1, tt.getValue(1, 3));
        // (0,1,0)->0, (0,1,1)->0 (Reset)
        assertEquals(0, tt.getValue(2, 3));
        assertEquals(0, tt.getValue(3, 3));
        // (1,0,0)->1, (1,0,1)->1 (Set)
        assertEquals(1, tt.getValue(4, 3));
        assertEquals(1, tt.getValue(5, 3));
        // (1,1,0)->2, (1,1,1)->2 (Forbidden / Don't care)
        assertEquals(2, tt.getValue(6, 3));
        assertEquals(2, tt.getValue(7, 3));

        // Convert to Transition Table
        TransitionTable transTable = TransitionTable.fromTruthTable(tt);
        assertEquals(2, transTable.getStateCount());
        assertEquals(2, transTable.getInputVars().size());
        assertEquals(4, transTable.getColumnCount()); // 00, 01, 10, 11

        // State 0 transitions:
        assertEquals(0, transTable.getNextState(0, 0)); // SR=00 -> 0 (stable)
        assertEquals(0, transTable.getNextState(0, 1)); // SR=01 -> 0 (stable)
        assertEquals(1, transTable.getNextState(0, 2)); // SR=10 -> 1
        assertEquals(-1, transTable.getNextState(0, 3)); // SR=11 -> don't care

        // State 1 transitions:
        assertEquals(1, transTable.getNextState(1, 0)); // SR=00 -> 1 (stable)
        assertEquals(0, transTable.getNextState(1, 1)); // SR=01 -> 0
        assertEquals(1, transTable.getNextState(1, 2)); // SR=10 -> 1 (stable)
        assertEquals(-1, transTable.getNextState(1, 3)); // SR=11 -> don't care

        // Check stable states
        assertTrue(transTable.isStable(0, 0));
        assertTrue(transTable.isStable(0, 1));
        assertFalse(transTable.isStable(0, 2));
        assertTrue(transTable.isStable(1, 0));
        assertFalse(transTable.isStable(1, 1));
        assertTrue(transTable.isStable(1, 2));

        // Convert to FSM
        FSM fsm = transTable.toFSM();
        assertEquals(2, fsm.getStates().size());
        assertTrue(fsm.getTransitions().size() >= 4);
    }

    public void testSrLatchNand() throws Exception {
        TruthTable tt = SequentialDialog.buildTruthTable(1, "Q", SequentialDialog.PatternType.SR_LATCH_NAND, null);
        assertEquals(3, tt.getVars().size());
        assertEquals("S_n", tt.getVars().get(0).getIdentifier());
        assertEquals("R_n", tt.getVars().get(1).getIdentifier());
        assertEquals("Q_0^n", tt.getVars().get(2).getIdentifier());
        assertEquals(1, tt.getResultCount());
        assertEquals("Q_0^{n+1}", tt.getResultName(0));
        assertEquals(8, tt.getRows());

        // Convert to Transition Table
        TransitionTable transTable = TransitionTable.fromTruthTable(tt);
        assertEquals(2, transTable.getStateCount());
        assertEquals(2, transTable.getInputVars().size());
        assertEquals(4, transTable.getColumnCount());

        // Stable states for NAND active-low:
        // Col 1 (S_n=0, R_n=1: Set): state 1 is stable
        assertTrue(transTable.isStable(1, 1));
        // Col 2 (S_n=1, R_n=0: Reset): state 0 is stable
        assertTrue(transTable.isStable(0, 2));
        // Col 3 (S_n=1, R_n=1: Hold): state 0 and 1 are stable
        assertTrue(transTable.isStable(0, 3));
        assertTrue(transTable.isStable(1, 3));
    }
}
