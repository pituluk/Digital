/*
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.expression;

import de.neemann.digital.analyse.AsynchronousLatchAnalyzer;
import de.neemann.digital.analyse.ModelAnalyserInfo;
import de.neemann.digital.analyse.TruthTable;
import de.neemann.digital.analyse.expression.ContextFiller;
import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.ExpressionException;
import de.neemann.digital.analyse.expression.NamedExpression;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.expression.VariableVisitor;
import de.neemann.digital.analyse.expression.format.FormatToExpression;
import de.neemann.digital.analyse.expression.modify.ExpressionModifier;
import de.neemann.digital.analyse.quinemc.QuineMcCluskey;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
        Map<String, Expression> namedExpressions = new HashMap<>();
        Set<String> latchVars = new HashSet<>();
        for (Expression expression : expressions) {
            if (expression instanceof NamedExpression) {
                NamedExpression named = (NamedExpression) expression;
                namedExpressions.put(named.getName(), named.getExpression().copy());
                if (AsynchronousLatchAnalyzer.isSelfReferential(named.getName(), named.getExpression())) {
                    latchVars.add(named.getName());
                }
            }
        }

        Map<String, Expression> resolvedNamedExpressions = new HashMap<>();
        for (String name : namedExpressions.keySet())
            resolveNamedExpression(name, namedExpressions, resolvedNamedExpressions, new HashSet<>(), latchVars);

        ArrayList<Expression> functions = new ArrayList<>();
        TreeSet<Variable> variableSet = new TreeSet<>();
        for (Expression expression : expressions) {
            Expression function = expression instanceof NamedExpression
                    ? ((NamedExpression) expression).getExpression()
                    : expression;
            function = resolveExpression(function.copy(), namedExpressions, resolvedNamedExpressions, new HashSet<>(), latchVars);
            if (simplify)
                function = QuineMcCluskey.simplify(function);
            functions.add(function);
            variableSet.addAll(function.traverse(new VariableVisitor()).getVariables());
        }

        for (int i = 0; i < functions.size(); i++) {
            Expression source = expressions.get(i);
            if (source instanceof NamedExpression) {
                String name = ((NamedExpression) source).getName();
                if (latchVars.contains(name)) {
                    new AsynchronousLatchAnalyzer(name, functions.get(i));
                }
            }
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

        if (!latchVars.isEmpty()) {
            ModelAnalyserInfo mai = new ModelAnalyserInfo(null);
            for (String lv : latchVars) {
                mai.setSequentialInitValue(lv, 0);
            }
            truthTable.setModelAnalyzerInfo(mai);
        }

        return truthTable;
    }

    private static Expression resolveNamedExpression(
            String name,
            Map<String, Expression> namedExpressions,
            Map<String, Expression> resolvedNamedExpressions,
            Set<String> resolving,
            Set<String> latchVars) throws ExpressionException {
        Expression resolved = resolvedNamedExpressions.get(name);
        if (resolved != null)
            return resolved.copy();
        if (!resolving.add(name)) {
            if (latchVars.contains(name)) {
                return new Variable(name);
            }
            throw new ExpressionException("Circular expression alias: " + name);
        }

        Expression expression = namedExpressions.get(name);
        if (expression == null)
            throw new ExpressionException("Expression alias not found: " + name);

        try {
            resolved = resolveExpression(expression.copy(), namedExpressions, resolvedNamedExpressions, resolving, latchVars);
            resolvedNamedExpressions.put(name, resolved.copy());
        } finally {
            resolving.remove(name);
        }
        return resolved.copy();
    }

    private static Expression resolveExpression(
            Expression expression,
            Map<String, Expression> namedExpressions,
            Map<String, Expression> resolvedNamedExpressions,
            Set<String> resolving,
            Set<String> latchVars) throws ExpressionException {
        try {
            return ExpressionModifier.modifyExpression(expression.copy(), variableExpression -> {
                if (variableExpression instanceof Variable) {
                    String name = ((Variable) variableExpression).getIdentifier();
                    if (namedExpressions.containsKey(name)) {
                        if (latchVars.contains(name)) {
                            return variableExpression;
                        }
                        try {
                            return resolveNamedExpression(name, namedExpressions, resolvedNamedExpressions, resolving, latchVars);
                        } catch (ExpressionException e) {
                            throw new AliasResolutionException(e);
                        }
                    }
                }
                return variableExpression;
            });
        } catch (AliasResolutionException e) {
            throw e.expressionException;
        }
    }

    private static final class AliasResolutionException extends RuntimeException {
        private final ExpressionException expressionException;

        private AliasResolutionException(ExpressionException expressionException) {
            this.expressionException = expressionException;
        }
    }
}
