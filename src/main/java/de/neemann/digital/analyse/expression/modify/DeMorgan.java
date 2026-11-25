/*
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.analyse.expression.modify;

import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.Not;
import de.neemann.digital.analyse.expression.Operation;

import java.util.ArrayList;

/**
 * Applies De Morgan rewrites and removes double inversions.
 */
public class DeMorgan implements ExpressionModifier {

    /**
     * Shared De Morgan modifier instance.
     */
    public static final DeMorgan INSTANCE = new DeMorgan();

    @Override
    public Expression modify(Expression expression) {
        if (!(expression instanceof Not))
            return expression;

        Not not = (Not) expression;
        Expression inner = not.getExpression();
        if (inner instanceof Not)
            return ((Not) inner).getExpression();
        if (inner instanceof Operation.And) {
            ArrayList<Expression> expressions = new ArrayList<>();
            for (Expression item : ((Operation.And) inner).getExpressions())
                expressions.add(Not.not(item));
            return Operation.or(expressions);
        }
        if (inner instanceof Operation.Or) {
            ArrayList<Expression> expressions = new ArrayList<>();
            for (Expression item : ((Operation.Or) inner).getExpressions())
                expressions.add(Not.not(item));
            return Operation.and(expressions);
        }
        return expression;
    }
}
