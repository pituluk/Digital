/*
 * Copyright (c) 2026
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.expression;

import de.neemann.digital.analyse.TruthTable;
import de.neemann.digital.analyse.expression.ContextFiller;
import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.parser.Parser;
import junit.framework.TestCase;

import java.util.ArrayList;

public class ExpressionToTruthTableTest extends TestCase {

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
