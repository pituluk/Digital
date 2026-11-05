/*
 * Copyright (c) 2026
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.table;

import de.neemann.digital.analyse.TruthTable;
import de.neemann.digital.analyse.expression.ContextFiller;
import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.quinemc.BoolTableByteArray;
import junit.framework.TestCase;

import java.util.ArrayList;

public class ExpressionCreatorTest extends TestCase {

    public void testCreateUnoptimizedExpressionMatchesTruthTable() throws Exception {
        ArrayList<Variable> vars = Variable.vars("A", "B", "C");
        TruthTable table = new TruthTable(vars).addResult("Y");
        BoolTableByteArray values = (BoolTableByteArray) table.getResult(0);
        for (int row = 0; row < table.getRows(); row++)
            values.set(row, row % 3 == 1 ? 1 : 0);

        Expression expression = ExpressionCreator.createUnoptimizedExpression(vars, table.getResult(0));
        ContextFiller context = new ContextFiller(vars);
        for (int row = 0; row < table.getRows(); row++) {
            context.setContextTo(row);
            assertEquals(table.getValue(row, vars.size()) != 0, expression.calculate(context));
        }
    }

    public void testCreateUnoptimizedExpressionForConstantTables() throws Exception {
        ArrayList<Variable> vars = Variable.vars("A");
        BoolTableByteArray values = new BoolTableByteArray(2);
        values.set(0, 0);
        values.set(1, 0);
        assertFalse(ExpressionCreator.createUnoptimizedExpression(vars, values)
                .calculate(new ContextFiller(vars).setContextTo(0)));

        values.set(0, 1);
        values.set(1, 1);
        assertTrue(ExpressionCreator.createUnoptimizedExpression(vars, values)
                .calculate(new ContextFiller(vars).setContextTo(0)));
        assertTrue(ExpressionCreator.createUnoptimizedExpression(new ArrayList<>(), valuesOf(1))
                .calculate(new ContextFiller(new ArrayList<>())));
    }

    private BoolTableByteArray valuesOf(int value) {
        BoolTableByteArray values = new BoolTableByteArray(1);
        values.set(0, value);
        return values;
    }
}
