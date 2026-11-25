/*
 * Copyright (c) 2026 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.analyse;

import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.ExpressionException;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.parser.Parser;
import de.neemann.digital.gui.components.expression.ExpressionToTruthTable;
import junit.framework.TestCase;

import java.util.ArrayList;

import static de.neemann.digital.analyse.expression.Not.not;
import static de.neemann.digital.analyse.expression.Operation.and;
import static de.neemann.digital.analyse.expression.Operation.or;

public class AsynchronousLatchAnalyzerTest extends TestCase {

    public void testShannonExpansionSRLatch() throws Exception {
        Variable q = new Variable("Q");
        Variable s = new Variable("S");
        Variable r = new Variable("R");

        // Q = S | (!R & Q)
        Expression expr = or(s, and(not(r), q));

        assertTrue(AsynchronousLatchAnalyzer.isSelfReferential("Q", expr));
        assertFalse(AsynchronousLatchAnalyzer.isSelfReferential("D", expr));
        assertFalse(AsynchronousLatchAnalyzer.isSelfReferential("Q+", expr));

        AsynchronousLatchAnalyzer analyzer = new AsynchronousLatchAnalyzer("Q", expr);
        assertNotNull(analyzer.getSExpression());
        assertNotNull(analyzer.getRExpression());

        // S = f(0, S, R) = S
        assertEquals("S", analyzer.getSExpression().toString());
        // R = ~f(1, S, R) = ~(S | !R) = !S & R
        assertTrue(analyzer.getRExpression().toString().contains("R"));
    }

    public void testOscillatorDetection() {
        Variable q = new Variable("Q");
        // Q = ~Q
        Expression expr = not(q);
        assertTrue(AsynchronousLatchAnalyzer.isSelfReferential("Q", expr));

        try {
            new AsynchronousLatchAnalyzer("Q", expr);
            fail("Expected oscillator exception!");
        } catch (ExpressionException e) {
            assertTrue(e.getMessage().contains("Q"));
        }
    }

    public void testExpressionToTruthTableWithSelfReferentialLatch() throws Exception {
        ArrayList<Expression> expressions = new Parser("Q = S | (!R & Q)", true).parse();
        TruthTable tt = ExpressionToTruthTable.create(expressions, false);
        assertNotNull(tt);
        assertEquals(8, tt.getRows()); // 3 variables: Q, R, S
        assertEquals(1, tt.getResultCount());
        assertEquals("Q", tt.getResultName(0));
        assertNotNull(tt.getModelAnalyzerInfo());
    }

    public void testExpressionToTruthTableOscillatorRejection() throws Exception {
        ArrayList<Expression> expressions = new Parser("Q = ~Q", true).parse();
        try {
            ExpressionToTruthTable.create(expressions, false);
            fail("Expected oscillator exception!");
        } catch (ExpressionException e) {
            assertTrue(e.getMessage().contains("Q"));
        }
    }
}
