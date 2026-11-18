/*
 * Copyright (c) 2026
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.expression;

import de.neemann.digital.analyse.TruthTable;
import de.neemann.digital.analyse.expression.ContextFiller;
import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.ExpressionException;
import de.neemann.digital.analyse.parser.Parser;
import junit.framework.TestCase;

import java.util.ArrayList;

public class ExpressionToTruthTableTest extends TestCase {

    public void testResolveNamedExpressionAliases() throws Exception {
        ArrayList<Expression> expressions = new Parser(
                "let GG = A+C\n"
                        + "D = GG\n"
                        + "E = D", true).parse();

        TruthTable truthTable = ExpressionToTruthTable.create(expressions);

        assertEquals(2, truthTable.getVars().size());
        assertEquals("A", truthTable.getVars().get(0).getIdentifier());
        assertEquals("C", truthTable.getVars().get(1).getIdentifier());
        assertEquals(3, truthTable.getResultCount());
        assertEquals("GG", truthTable.getResultName(0));
        assertEquals("D", truthTable.getResultName(1));
        assertEquals("E", truthTable.getResultName(2));

        ContextFiller context = new ContextFiller(truthTable.getVars());
        for (int row = 0; row < truthTable.getRows(); row++) {
            context.setContextTo(row);
            boolean a = context.get(truthTable.getVars().get(0));
            boolean c = context.get(truthTable.getVars().get(1));
            assertEquals(a || c ? 1 : 0, truthTable.getByContext(0, context));
            assertEquals(a || c ? 1 : 0, truthTable.getByContext(1, context));
            assertEquals(a || c ? 1 : 0, truthTable.getByContext(2, context));
        }
    }

    public void testRejectCircularNamedExpressionAliases() throws Exception {
        ArrayList<Expression> expressions = new Parser("A1=B1\nB1=A1", true).parse();
        try {
            ExpressionToTruthTable.create(expressions);
            fail("Circular aliases should be rejected");
        } catch (ExpressionException expected) {
            assertTrue(expected.getMessage().contains("Circular expression alias"));
        }
    }

    public void testCreateTruthTableFromMultipleDirectAssignments() throws Exception {
        ArrayList<Expression> expressions = new Parser(
                "P1 = (A * !B * !C) + (A * B * C)\n"
                        + "P2 = (A * !B * !C) + (A * B * !C)\n"
                        + "M = (A * !B * !C) + (A * B * C)\n"
                        + "E = (!A * !B * !C) + (A * !B * !C) + (A * B * !C)", true).parse();

        TruthTable truthTable = ExpressionToTruthTable.create(expressions);

        assertEquals(3, truthTable.getVars().size());
        assertEquals(4, truthTable.getResultCount());
        assertEquals("P1", truthTable.getResultName(0));
        assertEquals("P2", truthTable.getResultName(1));
        assertEquals("M", truthTable.getResultName(2));
        assertEquals("E", truthTable.getResultName(3));
    }

    public void testCreateTruthTableFromSimplifiedExpression() throws Exception {
        ArrayList<Expression> expressions = new Parser("let Z = A B + A C").parse();

        TruthTable truthTable = ExpressionToTruthTable.create(expressions, true);
        ContextFiller context = new ContextFiller(truthTable.getVars());

        assertEquals(3, truthTable.getVars().size());
        assertEquals("Z", truthTable.getResultName(0));
        for (int row = 0; row < truthTable.getRows(); row++) {
            context.setContextTo(row);
            boolean a = context.get(truthTable.getVars().get(0));
            boolean b = context.get(truthTable.getVars().get(1));
            boolean c = context.get(truthTable.getVars().get(2));
            assertEquals(a && (b || c) ? 1 : 0, truthTable.getByContext(0, context));
        }
    }

    public void testCreateTruthTableDropsVariablesRemovedBySimplification() throws Exception {
        ArrayList<Expression> expressions = new Parser("A + A B").parse();

        TruthTable truthTable = ExpressionToTruthTable.create(expressions, true);

        assertEquals(1, truthTable.getVars().size());
        assertEquals("A", truthTable.getVars().get(0).getIdentifier());
        assertEquals(0, truthTable.getByContext(0, new ContextFiller(truthTable.getVars()).setContextTo(0)));
        assertEquals(1, truthTable.getByContext(0, new ContextFiller(truthTable.getVars()).setContextTo(1)));
    }

    public void testCanGenerateTruthTableWithoutSimplifyingExpression() throws Exception {
        ArrayList<Expression> expressions = new Parser("A + A B").parse();

        TruthTable truthTable = ExpressionToTruthTable.create(expressions);

        assertEquals(2, truthTable.getVars().size());
        assertEquals("A", truthTable.getVars().get(0).getIdentifier());
        assertEquals("B", truthTable.getVars().get(1).getIdentifier());
        ContextFiller context = new ContextFiller(truthTable.getVars());
        for (int row = 0; row < truthTable.getRows(); row++) {
            context.setContextTo(row);
            boolean a = context.get(truthTable.getVars().get(0));
            boolean b = context.get(truthTable.getVars().get(1));
            assertEquals(a || (a && b) ? 1 : 0, truthTable.getByContext(0, context));
        }
    }
}
