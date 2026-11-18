/*
 * Copyright (c) 2016 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.analyse.quinemc;


import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.expression.format.FormatToExpression;
import de.neemann.digital.analyse.expression.format.FormatterException;
import de.neemann.digital.analyse.expression.modify.DeMorgan;
import de.neemann.digital.analyse.expression.modify.ExpressionModifier;
import de.neemann.digital.analyse.quinemc.BoolTableByteArray;
import junit.framework.TestCase;

import java.util.ArrayList;

import static de.neemann.digital.analyse.expression.Operation.and;
import static de.neemann.digital.analyse.expression.Operation.or;
import static de.neemann.digital.analyse.expression.Not.not;
import static de.neemann.digital.analyse.expression.Variable.v;

/**
 */
public class SimplifyTest extends TestCase {

    public void testSimplify() throws Exception, FormatterException {
        Variable a = v("a");
        Variable b = v("b");
        Expression e = or(and(a, b), a);
        Expression s = QuineMcCluskey.simplify(e);

        assertEquals("a", FormatToExpression.UNICODE.format(s));
    }

    public void testSimplify2() throws Exception, FormatterException {
        Variable a = v("a");
        Variable b = v("b");
        Expression e = and(or(a, b), a);
        Expression s = QuineMcCluskey.simplify(e);

        assertEquals("a", FormatToExpression.UNICODE.format(s));
    }

    public void testSimplify3() throws Exception {
        Variable a = v("a");
        Variable b = v("b");
        Variable c = v("c");
        Expression e = and(or(a, b), c);
        Expression s = QuineMcCluskey.simplify(e);

        assertTrue(s == e);
    }

    public void testSimplifyRecognizesXor() throws Exception {
        Variable a = v("a");
        Variable b = v("b");
        Expression e = or(and(a, not(b)), and(not(a), b));
        Expression s = QuineMcCluskey.recognizeParity(e);

        assertEquals("xor(a,b)", s.toString());
    }

    public void testApplyDeMorgan() throws Exception {
        Variable a = v("a");
        Variable b = v("b");
        Expression e = not(and(a, b));
        Expression s = ExpressionModifier.modifyExpression(e, DeMorgan.INSTANCE);

        assertEquals("or(not(a),not(b))", s.toString());
    }

    public void testRecognizeParityWithDontCare() throws Exception {
        ArrayList<Variable> vars = new ArrayList<>();
        vars.add(v("A"));
        vars.add(v("B"));
        vars.add(v("C"));
        BoolTableByteArray table = new BoolTableByteArray(new byte[]{
                2, 1, 2, 0, 2, 0, 2, 1
        });

        Expression p1 = QuineMcCluskey.recognizeParity(vars, table);
        assertNotNull(p1);
        assertEquals("xor(xor(A,B),C)", p1.toString());
    }
}
