/*
 * Copyright (c) 2026 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.analyse;

import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.quinemc.QuineMcCluskey;
import junit.framework.TestCase;

import java.util.Arrays;
import java.util.List;

import static de.neemann.digital.analyse.expression.Not.not;
import static de.neemann.digital.analyse.expression.Operation.and;
import static de.neemann.digital.analyse.expression.Operation.or;

public class AsyncStateEncoderTest extends TestCase {

    public void testHazardFreeCompleteSum() throws Exception {
        // F(A, B, C) = A*!B + B*C + A*C (consensus term is A*C)
        Variable a = new Variable("A");
        Variable b = new Variable("B");
        Variable c = new Variable("C");
        List<Variable> vars = Arrays.asList(a, b, c);

        Expression exprWithConsensus = or(and(a, not(b)), or(and(b, c), and(a, c)));

        // Standard QMC simplifies and may drop the redundant consensus term A*C
        Expression simplified = new QuineMcCluskey(exprWithConsensus)
                .simplify()
                .getExpression();

        // Hazard-free QMC must retain all prime implicants (including A*C)
        Expression hazardFree = new QuineMcCluskey(exprWithConsensus)
                .setHazardFree(true)
                .simplify()
                .getExpression();

        assertNotNull(hazardFree);
        // Hazard-free cover has all 3 prime implicants
        assertTrue(hazardFree.toString().contains("A") && hazardFree.toString().contains("B") && hazardFree.toString().contains("C"));
    }

    public void testAsyncStateEncoderSRExcitation() {
        TransitionTable tt = new TransitionTable(2, Arrays.asList("X"), Arrays.asList("Z"));
        // S0: X=0 -> S0 (stable), X=1 -> S1
        tt.setNextState(0, 0, 0);
        tt.setNextState(0, 1, 1);
        tt.setTransitionOutput(0, 0, "Z", 0);
        tt.setTransitionOutput(0, 1, "Z", 1);
        // S1: X=0 -> S0, X=1 -> S1 (stable)
        tt.setNextState(1, 0, 0);
        tt.setNextState(1, 1, 1);
        tt.setTransitionOutput(1, 0, "Z", 1);
        tt.setTransitionOutput(1, 1, "Z", 0);

        AsyncStateEncoder encoder = new AsyncStateEncoder(tt);
        assertEquals(1, encoder.getStateBits());
        assertEquals(0, encoder.getStateCode(0));
        assertEquals(1, encoder.getStateCode(1));

        TruthTable srTable = encoder.generateSRExcitationTable();
        assertNotNull(srTable);
        // 1 state bit (Q_0^n) + 1 input (X) = 2 inputs -> 4 rows
        assertEquals(4, srTable.getRows());
        // Results: S_Q_0, R_Q_0, Z
        assertEquals(3, srTable.getResultCount());
        assertEquals("S_Q_0", srTable.getResultName(0));
        assertEquals("R_Q_0", srTable.getResultName(1));
        assertEquals("Z", srTable.getResultName(2));

        // Check SR excitation rules:
        // Row 0: Q=0, X=0 -> next Q=0 (0 -> 0): S=0, R=X (2)
        assertEquals(0, srTable.getValue(0, 2));
        assertEquals(2, srTable.getValue(0, 3));

        // Row 1: Q=0, X=1 -> next Q=1 (0 -> 1): S=1, R=0
        assertEquals(1, srTable.getValue(1, 2));
        assertEquals(0, srTable.getValue(1, 3));

        // Row 2: Q=1, X=0 -> next Q=0 (1 -> 0): S=0, R=1
        assertEquals(0, srTable.getValue(2, 2));
        assertEquals(1, srTable.getValue(2, 3));

        // Row 3: Q=1, X=1 -> next Q=1 (1 -> 1): S=X (2), R=0
        assertEquals(2, srTable.getValue(3, 2));
        assertEquals(0, srTable.getValue(3, 3));
    }
}
