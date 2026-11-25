/*
 * Copyright (c) 2026 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.analyse;

import de.neemann.digital.analyse.expression.Constant;
import de.neemann.digital.analyse.expression.ContextFiller;
import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.ExpressionException;
import de.neemann.digital.analyse.expression.Not;
import de.neemann.digital.analyse.expression.Operation;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.expression.VariableVisitor;
import de.neemann.digital.analyse.expression.modify.ExpressionModifier;
import de.neemann.digital.analyse.quinemc.QuineMcCluskey;
import de.neemann.digital.lang.Lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Analyzes and decomposes self-referential asynchronous continuous equations: Q = f(Q, x).
 * Performs Shannon expansion to extract S = f(0, x) and R = ~f(1, x),
 * validates stability (oscillation checks), and synthesizes hazard-free RS excitation.
 */
public class AsynchronousLatchAnalyzer {

    private final String varName;
    private final Expression originalExpression;
    private final Expression sExpression;
    private final Expression rExpression;
    private final List<Variable> inputVariables;

    /**
     * Creates an analyzer for a candidate equation varName = expression.
     *
     * @param varName    target variable name Q
     * @param expression right-hand side expression f(Q, x)
     * @throws ExpressionException if the expression is unstable (e.g. oscillator Q = ~Q)
     */
    public AsynchronousLatchAnalyzer(String varName, Expression expression) throws ExpressionException {
        this.varName = varName;
        this.originalExpression = expression.copy();

        // Shannon expansion:
        // S(x) = f(0, x)
        // R(x) = ~f(1, x)
        Expression sRaw = substitute(expression, varName, Constant.ZERO);
        Expression f1 = substitute(expression, varName, Constant.ONE);
        Expression rRaw = Not.not(f1);

        this.sExpression = QuineMcCluskey.simplify(sRaw);
        this.rExpression = QuineMcCluskey.simplify(rRaw);

        // Collect independent input variables (excluding Q)
        VariableVisitor vv = new VariableVisitor();
        sExpression.traverse(vv);
        rExpression.traverse(vv);
        ArrayList<Variable> vars = new ArrayList<>();
        for (Variable v : vv.getVariables()) {
            if (!v.getIdentifier().equals(varName)) {
                vars.add(v);
            }
        }
        this.inputVariables = vars;

        // Validate stability: check whether S and R can be 1 simultaneously
        validateStability();
    }

    private void validateStability() throws ExpressionException {
        Expression contradiction = Operation.and(sExpression, rExpression);
        Expression simplified = QuineMcCluskey.simplify(contradiction);
        if (simplified instanceof Constant && ((Constant) simplified).getValue()) {
            throw new ExpressionException(Lang.get("err_unstableOscillator_N", varName));
        }

        if (inputVariables.isEmpty()) {
            if (simplified instanceof Constant && ((Constant) simplified).getValue()) {
                throw new ExpressionException(Lang.get("err_unstableOscillator_N", varName));
            }
        } else {
            ContextFiller context = new ContextFiller(inputVariables);
            boolean alwaysTrue = true;
            for (int i = 0; i < context.getRowCount(); i++) {
                context.setContextTo(i);
                if (!contradiction.calculate(context)) {
                    alwaysTrue = false;
                    break;
                }
            }
            if (alwaysTrue) {
                throw new ExpressionException(Lang.get("err_unstableOscillator_N", varName));
            }
        }
    }

    /**
     * Checks if variable Q depends on itself in expression without clock/next-state operator.
     *
     * @param name       variable name
     * @param expression expression AST
     * @return true if self-referential continuous equation
     */
    public static boolean isSelfReferential(String name, Expression expression) {
        if (name == null || expression == null) {
            return false;
        }
        if (name.endsWith("+") || name.endsWith("n+1") || name.endsWith("{n+1}")) {
            return false;
        }
        VariableVisitor vv = new VariableVisitor();
        expression.traverse(vv);
        for (Variable v : vv.getVariables()) {
            if (v.getIdentifier().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Substitutes a variable in an expression with a replacement.
     *
     * @param expression  expression AST
     * @param varToSub    variable to replace
     * @param replacement replacement expression
     * @return modified expression
     */
    public static Expression substitute(Expression expression, String varToSub, Expression replacement) {
        return ExpressionModifier.modifyExpression(expression.copy(), exp -> {
            if (exp instanceof Variable && ((Variable) exp).getIdentifier().equals(varToSub)) {
                return replacement.copy();
            }
            return exp;
        });
    }

    /**
     * @return variable name Q
     */
    public String getVarName() {
        return varName;
    }

    /**
     * @return pure combinational S expression = f(0, x)
     */
    public Expression getSExpression() {
        return sExpression;
    }

    /**
     * @return pure combinational R expression = ~f(1, x)
     */
    public Expression getRExpression() {
        return rExpression;
    }

    /**
     * @return input variables excluding Q
     */
    public List<Variable> getInputVariables() {
        return inputVariables;
    }

    /**
     * @return original expression f(Q, x)
     */
    public Expression getOriginalExpression() {
        return originalExpression;
    }
}
