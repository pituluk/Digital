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
import de.neemann.digital.analyse.expression.NamedExpression;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.expression.VariableVisitor;
import de.neemann.digital.analyse.expression.format.FormatToExpression;
import de.neemann.digital.analyse.quinemc.QuineMcCluskey;

import java.util.ArrayList;
import java.util.TreeSet;

/**
 * Converts expressions into truth tables, optionally simplifying them first.
 */
public final class ExpressionToTruthTable {

    private ExpressionToTruthTable() {
    }

    /**
     * Creates a truth table from the expressions without simplifying them.
     *
     * @param expressions the expressions
     * @return the truth table
     * @throws ExpressionException if an expression cannot be evaluated
     */
    public static TruthTable create(ArrayList<Expression> expressions) throws ExpressionException {
        return create(expressions, false);
    }

    /**
     * Creates a truth table for the expressions.
     *
     * @param expressions the expressions
     * @param simplify true if expressions should be simplified before evaluation
     * @return the truth table
     * @throws ExpressionException if an expression cannot be evaluated
     */
    public static TruthTable create(ArrayList<Expression> expressions, boolean simplify) throws ExpressionException {
        ArrayList<Expression> functions = new ArrayList<>();
        TreeSet<Variable> variableSet = new TreeSet<>();
        for (Expression expression : expressions) {
            Expression function = expression instanceof NamedExpression
                    ? ((NamedExpression) expression).getExpression()
                    : expression;
            if (simplify)
                function = QuineMcCluskey.simplify(function);
            functions.add(function);
            variableSet.addAll(function.traverse(new VariableVisitor()).getVariables());
        }

        ArrayList<Variable> variables = new ArrayList<>(variableSet);
        TruthTable truthTable = new TruthTable(variables);
        ContextFiller context = new ContextFiller(variables);
        for (int i = 0; i < functions.size(); i++) {
            Expression source = expressions.get(i);
            String name;
            if (source instanceof NamedExpression)
                name = ((NamedExpression) source).getName();
            else if (functions.size() == 1)
                name = "Y";
            else
                name = FormatToExpression.defaultFormat(source);

            truthTable.addResult(name);
            for (int row = 0; row < context.getRowCount(); row++) {
                context.setContextTo(row);
                truthTable.setByContext(i, context, functions.get(i).calculate(context) ? 1 : 0);
            }
        }
        return truthTable;
    }
}
