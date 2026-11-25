/*
 * Copyright (c) 2016 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.builder.circuit;

import de.neemann.digital.TestExecuter;
import de.neemann.digital.analyse.ModelAnalyser;
import de.neemann.digital.analyse.TruthTable;
import de.neemann.digital.analyse.quinemc.BoolTableByteArray;
import de.neemann.digital.analyse.expression.Constant;
import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.NamedExpression;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.parser.ParseException;
import de.neemann.digital.analyse.parser.Parser;
import de.neemann.digital.analyse.expression.modify.NOr;
import de.neemann.digital.builder.BuilderException;
import de.neemann.digital.core.Model;
import de.neemann.digital.core.NodeException;
import de.neemann.digital.core.ObservableValue;
import de.neemann.digital.core.Signal;
import de.neemann.digital.core.element.Keys;
import de.neemann.digital.core.flipflops.FlipflopD;
import de.neemann.digital.core.flipflops.FlipflopRSAsync;
import de.neemann.digital.core.io.Const;
import de.neemann.digital.core.io.In;
import de.neemann.digital.core.io.Out;
import de.neemann.digital.core.basic.XOr;
import de.neemann.digital.core.memory.LookUpTable;
import de.neemann.digital.core.wiring.AsyncSeq;
import de.neemann.digital.core.wiring.Clock;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.elements.Pin;
import de.neemann.digital.draw.elements.PinException;
import de.neemann.digital.draw.elements.Tunnel;
import de.neemann.digital.draw.elements.VisualElement;
import de.neemann.digital.draw.elements.Wire;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.library.ElementNotFoundException;
import de.neemann.digital.draw.model.ModelCreator;
import de.neemann.digital.draw.model.ModelEntry;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.gui.components.table.BuilderExpressionCreator;
import de.neemann.digital.gui.components.table.ExpressionCreator;
import de.neemann.digital.gui.components.table.ExpressionListenerStore;
import de.neemann.digital.integration.ToBreakRunner;
import junit.framework.TestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static de.neemann.digital.analyse.expression.Not.not;
import static de.neemann.digital.analyse.expression.Operation.and;
import static de.neemann.digital.analyse.expression.Operation.or;
import static de.neemann.digital.analyse.expression.Operation.xor;
import static de.neemann.digital.draw.shapes.GenericShape.SIZE;

/**
 *
 */
public class CircuitBuilderTest extends TestCase {

    public void testBuilderCombinatorial() throws Exception {

        Variable a = new Variable("a");
        Variable b = new Variable("b");

        // xor
        Expression y = and(or(a, b), not(and(a, b)));

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .addCombinatorial("y", y)
                .createCircuit();

        ModelCreator m = new ModelCreator(circuit, library);
        TestExecuter te = new TestExecuter(m.createModel(false)).setUp(m);
        te.check(0, 0, 0);
        te.check(0, 1, 1);
        te.check(1, 0, 1);
        te.check(1, 1, 0);
    }

    public void testBuilderSequential() throws Exception {
        Variable y0 = new Variable("Y_0");
        Variable y1 = new Variable("Y_1");

        // counter
        Expression y0s = not(y0);
        Expression y1s = or(and(not(y0), y1), and(y0, not(y1)));

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .addSequential("Y_0", y0s)
                .addSequential("Y_1", y1s)
                .createCircuit();

        ModelCreator m = new ModelCreator(circuit, library);
        TestExecuter te = new TestExecuter(m.createModel(false)).setUp(m);
        te.check(0, 0);
        te.checkC(1, 0);
        te.checkC(0, 1);
        te.checkC(1, 1);
        te.checkC(0, 0);
    }

    public void testBuilderSequentialLUT() throws Exception {
        Variable y0 = new Variable("Y_0");
        Variable y1 = new Variable("Y_1");
        Variable y2 = new Variable("Y_2");

        // counter
        Expression y0s = not(y0);
        Expression y1s = or(and(not(y0), y1), and(y0, not(y1)));
        Expression y2s = or(and(y0, y1, not(y2)), and(not(y0), y2), and(not(y1), y2));

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setUseLUTs(true)
                .addSequential("Y_0", y0s)
                .addSequential("Y_1", y1s)
                .addSequential("Y_2", y2s)
                .createCircuit();

        final ArrayList<VisualElement> el = circuit.getElements();
        assertEquals(19, el.size());
        assertEquals(1, el.stream().filter(visualElement -> visualElement.equalsDescription(LookUpTable.DESCRIPTION)).count());

        ModelCreator m = new ModelCreator(circuit, library);
        TestExecuter te = new TestExecuter(m.createModel(false)).setUp(m);
        te.check(0, 0, 0);
        te.checkC(1, 0, 0);
        te.checkC(0, 1, 0);
        te.checkC(1, 1, 0);
        te.checkC(0, 0, 1);
        te.checkC(1, 0, 1);
        te.checkC(0, 1, 1);
        te.checkC(1, 1, 1);
        te.checkC(0, 0, 0);
    }

    public void testBuilderSequentialJK_JequalsK() throws Exception {
        Variable y0 = new Variable("Y_0");
        Variable y1 = new Variable("Y_1");

        // counter
        Expression y0s = not(y0);
        Expression y1s = or(and(not(y0), y1), and(y0, not(y1)));

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setUseJK(true)
                .addSequential("Y_0", y0s)
                .addSequential("Y_1", y1s)
                .createCircuit();

        ModelCreator m = new ModelCreator(circuit, library);
        TestExecuter te = new TestExecuter(m.createModel(false)).setUp(m);
        te.check(0, 0);
        te.checkC(1, 0);
        te.checkC(0, 1);
        te.checkC(1, 1);
        te.checkC(0, 0);
    }

    public void testBuilderSequentialJK() throws Exception {
        Variable y0 = new Variable("Y_0");
        Variable y1 = new Variable("Y_1");

        // counter
        Expression y0s = not(y0);
        Expression y1s = or(not(y0), not(y1));

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setUseJK(true)
                .addSequential("Y_0", y0s)
                .addSequential("Y_1", y1s)
                .createCircuit();

        ModelCreator m = new ModelCreator(circuit, library);
        TestExecuter te = new TestExecuter(m.createModel(false)).setUp(m);
        te.check(0, 0);
        te.checkC(1, 1);
        te.checkC(0, 0);
        te.checkC(1, 1);
        te.checkC(0, 0);
    }

    public void testBuilderSequentialConstant() throws Exception {
        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .addSequential("Y_0", Constant.ONE)
                .addSequential("Y_1", Constant.ZERO)
                .createCircuit();

        final ArrayList<VisualElement> el = circuit.getElements();
        assertEquals(10, el.size());
        assertEquals(4, el.stream().filter(visualElement -> visualElement.equalsDescription(Tunnel.DESCRIPTION)).count());
        assertEquals(2, el.stream().filter(visualElement -> visualElement.equalsDescription(Const.DESCRIPTION)).count());
        assertEquals(2, el.stream().filter(visualElement -> visualElement.equalsDescription(Out.DESCRIPTION)).count());
    }

    public void testBuilderSequentialConstantJK() throws Exception {
        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setUseJK(true)
                .addSequential("Y_0", Constant.ONE)
                .addSequential("Y_1", Constant.ZERO)
                .createCircuit();

        final ArrayList<VisualElement> el = circuit.getElements();
        assertEquals(10, el.size());
        assertEquals(4, el.stream().filter(visualElement -> visualElement.equalsDescription(Tunnel.DESCRIPTION)).count());
        assertEquals(2, el.stream().filter(visualElement -> visualElement.equalsDescription(Const.DESCRIPTION)).count());
        assertEquals(2, el.stream().filter(visualElement -> visualElement.equalsDescription(Out.DESCRIPTION)).count());
    }

    public void testBus() throws Exception {
        Circuit circuit = getCircuitFromCircuitAnalysis("dig/circuitBuilder/busTest.dig");

        // check
        List<VisualElement> in = circuit.getElements(v -> v.equalsDescription(In.DESCRIPTION));
        assertEquals(2, in.size());
        checkPin(in.get(0), "A", "1,2,3,4");
        checkPin(in.get(1), "B", "5,6,7,8");

        List<VisualElement> out = circuit.getElements(v -> v.equalsDescription(Out.DESCRIPTION));
        assertEquals(2, out.size());
        checkPin(out.get(0), "S", "9,10,11,12");
        checkPin(out.get(1), "U", "13,14,15,16");
    }

    private Circuit getCircuitFromCircuitAnalysis(String name) throws Exception {
        final ToBreakRunner runner = new ToBreakRunner(name, false);
        // create truth table incl. ModelAnalyzerInfo
        TruthTable tt = new ModelAnalyser(runner.getModel()).analyse();

        // create expressions based on truth table
        ExpressionListenerStore expr = new ExpressionListenerStore(null);
        new ExpressionCreator(tt).create(expr);

        // build a new circuit
        CircuitBuilder circuitBuilder = new CircuitBuilder(runner.getLibrary().getShapeFactory(), tt.getVars())
                .setModelAnalyzerInfo(tt.getModelAnalyzerInfo());
        new BuilderExpressionCreator(circuitBuilder).create(expr);
        return circuitBuilder.createCircuit();
    }

    public void testBusFix858() throws Exception {
        Circuit circuit = getCircuitFromCircuitAnalysis("dig/circuitBuilder/busTest2.dig");

        List<VisualElement> in = circuit.getElements(v -> v.equalsDescription(In.DESCRIPTION));
        assertEquals(24, in.size());
        for (int i = 0; i < 12; i++) {
            int n = 2 * i;
            assertEquals("A" + i, in.get(n).getElementAttributes().getLabel());
            assertEquals("B" + (11 - i), in.get(n + 1).getElementAttributes().getLabel());
        }
        List<VisualElement> out = circuit.getElements(v -> v.equalsDescription(Out.DESCRIPTION));
        assertEquals(12, out.size());
        for (int i = 0; i < 12; i++) {
            int n = (i + 2) % 12;
            assertEquals("S" + n, out.get(i).getElementAttributes().getLabel());
        }
    }


    private void checkPin(VisualElement e, String label, String pins) {
        assertEquals(label, e.getElementAttributes().getLabel());
        assertEquals(4, e.getElementAttributes().getBits());
        assertEquals(pins, e.getElementAttributes().get(Keys.PINNUMBER));
    }

    public void testIsXor() {
        assertFalse(CircuitBuilder.isXor(new long[]{0, 0, 0, 0}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 0, 0, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 0, 1, 0}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 0, 1, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 1, 0, 0}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 1, 0, 1}));
        assertTrue(CircuitBuilder.isXor(new long[]{0, 1, 1, 0}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 1, 1, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{1, 0, 0, 0}));
        assertFalse(CircuitBuilder.isXor(new long[]{1, 0, 0, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{1, 0, 1, 0}));
        assertFalse(CircuitBuilder.isXor(new long[]{1, 0, 1, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{1, 1, 0, 0}));
        assertFalse(CircuitBuilder.isXor(new long[]{1, 1, 0, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{1, 1, 1, 0}));
        assertFalse(CircuitBuilder.isXor(new long[]{1, 1, 1, 1}));

        assertTrue(CircuitBuilder.isXor(new long[]{0, 1, 1, 0, 1, 0, 0, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{1, 1, 1, 0, 1, 0, 0, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 0, 1, 0, 1, 0, 0, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 1, 0, 0, 1, 0, 0, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 1, 1, 1, 1, 0, 0, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 1, 1, 0, 0, 0, 0, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 1, 1, 0, 1, 1, 0, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 1, 1, 0, 1, 0, 1, 1}));
        assertFalse(CircuitBuilder.isXor(new long[]{0, 1, 1, 0, 1, 0, 0, 0}));
    }

    public void testIsXNor() {
        assertFalse(CircuitBuilder.isXNor(new long[]{0, 0, 0, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{0, 0, 0, 1}));
        assertFalse(CircuitBuilder.isXNor(new long[]{0, 0, 1, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{0, 0, 1, 1}));
        assertFalse(CircuitBuilder.isXNor(new long[]{0, 1, 0, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{0, 1, 0, 1}));
        assertFalse(CircuitBuilder.isXNor(new long[]{0, 1, 1, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{0, 1, 1, 1}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 0, 0, 0}));
        assertTrue(CircuitBuilder.isXNor(new long[]{1, 0, 0, 1}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 0, 1, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 0, 1, 1}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 1, 0, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 1, 0, 1}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 1, 1, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 1, 1, 1}));

        assertTrue(CircuitBuilder.isXNor(new long[]{1, 0, 0, 1, 0, 1, 1, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{0, 0, 0, 1, 0, 1, 1, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 1, 0, 1, 0, 1, 1, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 0, 1, 1, 0, 1, 1, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 0, 0, 0, 0, 1, 1, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 0, 0, 1, 1, 1, 1, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 0, 0, 1, 0, 0, 1, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 0, 0, 1, 0, 1, 0, 0}));
        assertFalse(CircuitBuilder.isXNor(new long[]{1, 0, 0, 1, 0, 1, 1, 1}));

    }

    public void testLocalVar() throws IOException, ParseException, BuilderException, ElementNotFoundException, PinException, NodeException {
        ArrayList<Expression> expList = new Parser("let a1=a+b;\nlet a2=c+d;\nlet y=a1^a2").parse();
        ElementLibrary library = new ElementLibrary();
        CircuitBuilder circuitBuilder = new CircuitBuilder(new ShapeFactory(library)).setResolveLocalVars(true);
        for (Expression exp : expList) {
            assertTrue(exp instanceof NamedExpression);
            NamedExpression ne = (NamedExpression) exp;
            circuitBuilder.addCombinatorial(ne.getName(), ne.getExpression());
        }
        Circuit circuit = circuitBuilder.createCircuit();
        List<VisualElement> outs = circuit.getElements(v -> v.equalsDescription(Out.DESCRIPTION));
        assertEquals(1, outs.size());
        assertEquals("y", outs.get(0).getElementAttributes().getLabel());
        List<VisualElement> tunnels = circuit.getElements(v -> v.equalsDescription(Tunnel.DESCRIPTION));
        assertEquals(4, tunnels.size());

        ModelCreator m = new ModelCreator(circuit, library);
        TestExecuter te = new TestExecuter(m.createModel(false)).setUp(m);
        te.check(0, 0, 0, 0, 0);
        te.check(0, 0, 0, 1, 1);
        te.check(0, 0, 1, 0, 1);
        te.check(0, 0, 1, 1, 1);
        te.check(0, 1, 0, 0, 1);
        te.check(0, 1, 0, 1, 0);
        te.check(0, 1, 1, 0, 0);
        te.check(0, 1, 1, 1, 0);
        te.check(1, 0, 0, 0, 1);
        te.check(1, 0, 0, 1, 0);
        te.check(1, 0, 1, 0, 0);
        te.check(1, 0, 1, 1, 0);
        te.check(1, 1, 0, 0, 1);
        te.check(1, 1, 0, 1, 0);
        te.check(1, 1, 1, 0, 0);
        te.check(1, 1, 1, 1, 0);
    }

    public void testDirectAssignmentLocalVars() throws IOException, ParseException, BuilderException, ElementNotFoundException, PinException, NodeException {
        ArrayList<Expression> expList = new Parser("a1=a+b;\na2=c+d;\ny=a1^a2", true).parse();
        ElementLibrary library = new ElementLibrary();
        CircuitBuilder circuitBuilder = new CircuitBuilder(new ShapeFactory(library)).setResolveLocalVars(true);
        for (Expression exp : expList) {
            assertTrue(exp instanceof NamedExpression);
            NamedExpression ne = (NamedExpression) exp;
            circuitBuilder.addCombinatorial(ne.getName(), ne.getExpression());
        }
        Circuit circuit = circuitBuilder.createCircuit();

        List<VisualElement> outs = circuit.getElements(v -> v.equalsDescription(Out.DESCRIPTION));
        assertEquals(1, outs.size());
        assertEquals("y", outs.get(0).getElementAttributes().getLabel());
        assertEquals(4, circuit.getElements(v -> v.equalsDescription(Tunnel.DESCRIPTION)).size());

        ModelCreator m = new ModelCreator(circuit, library);
        TestExecuter te = new TestExecuter(m.createModel(false)).setUp(m);
        te.check(0, 0, 0, 0, 0);
        te.check(0, 0, 1, 1, 1);
        te.check(1, 1, 0, 0, 1);
        te.check(1, 1, 1, 1, 0);
    }

    public void testReuseComputedSubExpressions() throws Exception {
        ArrayList<Expression> expressions = new Parser(
                "P2=A^C\nP1=P2^B\nM=P1", true).parse();
        ElementLibrary library = new ElementLibrary();
        CircuitBuilder builder = new CircuitBuilder(new ShapeFactory(library))
                .setResolveLocalVars(true)
                .setPreserveSharedOutputPins(true);
        for (Expression expression : expressions) {
            NamedExpression named = (NamedExpression) expression;
            builder.addCombinatorial(named.getName(), named.getExpression());
        }
        Circuit circuit = builder.createCircuit();
        assertEquals(2, circuit.getElements(v -> v.equalsDescription(XOr.DESCRIPTION)).size());
        assertEquals(0, circuit.getElements(v -> v.equalsDescription(In.DESCRIPTION)
                && "P1".equals(v.getElementAttributes().getLabel())).size());
        assertEquals(1, circuit.getElements(v -> v.equalsDescription(Out.DESCRIPTION)
                && "P1".equals(v.getElementAttributes().getLabel())).size());
        assertEquals(1, circuit.getElements(v -> v.equalsDescription(Out.DESCRIPTION)
                && "P2".equals(v.getElementAttributes().getLabel())).size());
        assertTrue(circuit.getElements(v -> v.equalsDescription(Tunnel.DESCRIPTION)
                && "P1".equals(v.getElementAttributes().get(Keys.NETNAME))).size() >= 1);
        assertTrue(circuit.getElements(v -> v.equalsDescription(Tunnel.DESCRIPTION)
                && "P2".equals(v.getElementAttributes().get(Keys.NETNAME))).size() >= 1);

        ModelCreator modelCreator = new ModelCreator(circuit, library);
        TestExecuter te = new TestExecuter(modelCreator.createModel(false)).setUp(modelCreator);
        for (int a = 0; a < 2; a++)
            for (int b = 0; b < 2; b++)
                for (int c = 0; c < 2; c++) {
                    int p2 = a ^ c;
                    int p1 = p2 ^ b;
                    java.util.HashMap<String, Integer> values = new java.util.HashMap<>();
                    values.put("P1", p1);
                    values.put("P2", p2);
                    values.put("M", p1);
                    values.put("E", c == 0 ? 1 : 0);
                    long[] outputValues = new long[modelCreator.getEntries("Out").size()];
                    int index = 0;
                    for (de.neemann.digital.draw.model.ModelEntry entry
                            : modelCreator.getEntries("Out")) {
                        outputValues[index++] = values.getOrDefault(
                                entry.getVisualElement().getElementAttributes().getLabel(), 0);
                    }
                    long[] checkValues = new long[3 + outputValues.length];
                    checkValues[0] = a;
                    checkValues[1] = b;
                    checkValues[2] = c;
                    System.arraycopy(outputValues, 0, checkValues, 3, outputValues.length);
                    te.check(checkValues);
                }
    }

    public void testBuilder74xx() throws Exception {
        Variable a = new Variable("a");
        Variable b = new Variable("b");
        Variable c = new Variable("c");
        Variable d = new Variable("d");

        // y1 = a & b, y2 = c & d, y = y1 | y2 | ~a
        // Needs 2 ANDs (packed into 1 7408), 2 ORs (packed into 1 7432), 1 NOT (1 7404)
        Expression y1 = and(a, b);
        Expression y2 = and(c, d);
        Expression y = or(y1, y2, not(a));

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setUse74xx(true)
                .addCombinatorial("y", y)
                .createCircuit();

        // Verify chips: exactly 1 7408 (packing both ANDs), 1 7432 (packing both ORs), 1 7404
        assertEquals(1, circuit.getElements(v -> v.getElementName().contains("7408")).size());
        assertEquals(1, circuit.getElements(v -> v.getElementName().contains("7432")).size());
        assertEquals(1, circuit.getElements(v -> v.getElementName().contains("7404")).size());

        ModelCreator m = new ModelCreator(circuit, library);
        TestExecuter te = new TestExecuter(m.createModel(false)).setUp(m);
        // a=0, b=0, c=0, d=0 -> ~a=1 => y=1
        te.check(0, 0, 0, 0, 1);
        // a=1, b=0, c=0, d=0 -> y=0
        te.check(1, 0, 0, 0, 0);
        // a=1, b=1, c=0, d=0 -> a&b=1 => y=1
        te.check(1, 1, 0, 0, 1);
        // a=1, b=0, c=1, d=1 -> c&d=1 => y=1
        te.check(1, 0, 1, 1, 1);
    }

    public void testBuilder74xxInputFanoutOnSameChip() throws Exception {
        Variable a = new Variable("a");
        Variable b = new Variable("b");
        Variable c = new Variable("c");

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setUse74xx(true)
                .addCombinatorial("y1", or(a, b))
                .addCombinatorial("y2", or(b, c))
                .addCombinatorial("y", or(a, b, c))
                .createCircuit();

        ModelCreator model = new ModelCreator(circuit, library);
        TestExecuter testExecuter = new TestExecuter(model.createModel(false)).setUp(model);
        testExecuter.check(0, 0, 0, 0, 0, 0);
        testExecuter.check(0, 1, 0, 1, 1, 1);
        testExecuter.check(1, 0, 0, 1, 0, 1);
        testExecuter.check(0, 0, 1, 0, 1, 1);
    }

    public void testBuilder74xxPacking() throws Exception {
        Variable a = new Variable("a");
        Variable b = new Variable("b");
        // 5 AND gates => requires 2 7408 chips (slot capacity is 4)
        Expression e1 = and(a, b);
        Expression e2 = and(a, b);
        Expression e3 = and(a, b);
        Expression e4 = and(a, b);
        Expression e5 = and(a, b);

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setUse74xx(true)
                .addCombinatorial("o1", e1)
                .addCombinatorial("o2", e2)
                .addCombinatorial("o3", e3)
                .addCombinatorial("o4", e4)
                .addCombinatorial("o5", e5)
                .createCircuit();

        assertEquals(2, circuit.getElements(v -> v.getElementName().contains("7408")).size());
    }

    public void testBuilder74xxGates() throws Exception {
        Variable a = new Variable("a");
        Variable b = new Variable("b");

        Expression nand = not(and(a, b));
        Expression nor = not(or(a, b));
        Expression xor = xor(a, b);
        Expression xnor = not(xor(a, b));

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setUse74xx(true)
                .addCombinatorial("nand", nand)
                .addCombinatorial("nor", nor)
                .addCombinatorial("xor", xor)
                .addCombinatorial("xnor", xnor)
                .createCircuit();

        assertEquals(1, circuit.getElements(v -> v.getElementName().contains("7400")).size());
        assertEquals(1, circuit.getElements(v -> v.getElementName().contains("7402")).size());
        assertEquals(1, circuit.getElements(v -> v.getElementName().contains("7486")).size());
        assertEquals(1, circuit.getElements(v -> v.getElementName().contains("747266")).size());

        ModelCreator m = new ModelCreator(circuit, library);
        TestExecuter te = new TestExecuter(m.createModel(false)).setUp(m);
        // a=0, b=0 => nand=1, nor=1, xor=0, xnor=1
        te.check(0, 0, 1, 1, 0, 1);
        // a=0, b=1 => nand=1, nor=0, xor=1, xnor=0
        te.check(0, 1, 1, 0, 1, 0);
        // a=1, b=0 => nand=1, nor=0, xor=1, xnor=0
        te.check(1, 0, 1, 0, 1, 0);
        // a=1, b=1 => nand=0, nor=0, xor=0, xnor=1
        te.check(1, 1, 0, 0, 0, 1);
    }

    public void testBuilder74xxRoutesUseGridAlignedOrthogonalSegments() throws Exception {
        Variable a = new Variable("a");
        Variable b = new Variable("b");
        Variable c = new Variable("c");
        Expression y = or(and(a, b), and(b, c), and(a, c));

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setUse74xx(true)
                .addCombinatorial("y", y)
                .createCircuit();

        assertFalse(circuit.getWires().isEmpty());
        for (Wire wire : circuit.getWires()) {
            assertTrue(wire.p1.x == wire.p2.x || wire.p1.y == wire.p2.y);
            assertEquals(0, wire.p1.x % SIZE);
            assertEquals(0, wire.p1.y % SIZE);
            assertEquals(0, wire.p2.x % SIZE);
            assertEquals(0, wire.p2.y % SIZE);
        }
    }

    public void testBuilder74xxSequentialCounter() throws Exception {
        Variable y0 = new Variable("Y_0");
        Variable y1 = new Variable("Y_1");
        Expression y1Next = or(and(not(y0), y1), and(y0, not(y1)));

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setUse74xx(true)
                .addSequential("Y_0", not(y0))
                .addSequential("Y_1", y1Next)
                .addCombinatorial("Y_0_out", y0)
                .addCombinatorial("Y_1_out", y1)
                .createCircuit();

        ModelCreator modelCreator = new ModelCreator(circuit, library);
        TestExecuter testExecuter = new TestExecuter(modelCreator.createModel(false)).setUp(modelCreator);
        testExecuter.check(0, 0);
        testExecuter.checkC(1, 0);
        testExecuter.checkC(0, 1);
        testExecuter.checkC(1, 1);
        testExecuter.checkC(0, 0);
    }

    public void testBuilder74xxFourBitSequentialCounterOutputs() throws Exception {
        Variable q0 = new Variable("Q_0");
        Variable q1 = new Variable("Q_1");
        Variable q2 = new Variable("Q_2");
        Variable q3 = new Variable("Q_3");

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setUse74xx(true)
                .addSequential("Q_0", not(q0))
                .addSequential("Q_1", or(and(not(q0), q1), and(q0, not(q1))))
                .addSequential("Q_2", or(and(not(and(q0, q1)), q2), and(and(q0, q1), not(q2))))
                .addSequential("Q_3", or(and(not(and(q0, q1, q2)), q3), and(and(q0, q1, q2), not(q3))))
                .createCircuit();

        assertEquals(4, circuit.getElements(element -> element.equalsDescription(Out.DESCRIPTION)).size());
        assertWiresDoNotCrossIoElements(circuit);
        ModelCreator modelCreator = new ModelCreator(circuit, library);
        TestExecuter testExecuter = new TestExecuter(modelCreator.createModel(false)).setUp(modelCreator);
        testExecuter.check(0, 0, 0, 0);
        for (int state = 1; state < 16; state++) {
            testExecuter.checkC(state & 1, (state >> 1) & 1, (state >> 2) & 1, (state >> 3) & 1);
        }
        testExecuter.checkC(0, 0, 0, 0);
    }

    public void testBuilder74xxEightVariableCombinatorialTemplate() throws Exception {
        ArrayList<Variable> vars = new ArrayList<>();
        for (int i = 0; i < 8; i++)
            vars.add(new Variable("A_" + i));

        ArrayList<Expression> minterms = new ArrayList<>();
        for (int term = 0; term < 16; term++) {
            int row = (term * 13) & 0xff;
            ArrayList<Expression> literals = new ArrayList<>();
            for (int bit = 0; bit < vars.size(); bit++) {
                Expression variable = vars.get(bit);
                literals.add(((row >> bit) & 1) == 1 ? variable : not(variable));
            }
            minterms.add(and(literals.toArray(new Expression[0])));
        }

        ElementLibrary library = new ElementLibrary();
        CircuitBuilder circuitBuilder = new CircuitBuilder(new ShapeFactory(library), vars)
                .setUse74xx(true);
        circuitBuilder.addCombinatorial("Y", or(minterms.toArray(new Expression[0])));
        Circuit circuit = circuitBuilder.createCircuit();

        assertTrue(circuit.getElements().size() > 30);
        assertFalse(circuit.getWires().isEmpty());
        assertWiresDoNotCrossIoElements(circuit);

        ModelCreator modelCreator = new ModelCreator(circuit, library);
        TestExecuter testExecuter = new TestExecuter(modelCreator.createModel(false)).setUp(modelCreator);
        for (int row = 0; row < 256; row++) {
            long[] values = new long[9];
            for (int bit = 0; bit < vars.size(); bit++)
                values[bit] = (row >> bit) & 1;
            values[8] = row <= 15 * 13 && row % 13 == 0 ? 1 : 0;
            testExecuter.check(values);
        }
    }

    public void testBuilder74xxSequentialEightVariableTemplate() throws Exception {
        ArrayList<Variable> vars = new ArrayList<>();
        for (int i = 7; i >= 0; i--)
            vars.add(new Variable("Q_" + i + "^n"));

        TruthTable truthTable = new TruthTable(vars);
        int rows = truthTable.getRows();
        int bit = 7;
        for (Variable variable : vars) {
            BoolTableByteArray values = new BoolTableByteArray(rows);
            for (int row = 0; row < rows; row++)
                values.set(row, ((row + 1) >> bit) & 1);
            truthTable.addResult(ModelAnalyser.addOne(variable.getIdentifier()), values);
            bit--;
        }

        ExpressionListenerStore expressions = new ExpressionListenerStore(null);
        new ExpressionCreator(truthTable).create(expressions);

        ElementLibrary library = new ElementLibrary();
        CircuitBuilder circuitBuilder = new CircuitBuilder(new ShapeFactory(library), vars)
                .setUse74xx(true);
        new BuilderExpressionCreator(circuitBuilder).create(expressions);
        Circuit circuit = circuitBuilder.createCircuit();

        assertFalse(circuit.getElements().isEmpty());
        assertWiresDoNotCrossIoElements(circuit);
    }

    private void assertWiresDoNotCrossIoElements(Circuit circuit) {
        List<VisualElement> ioElements = circuit.getElements(element ->
                element.equalsDescription(In.DESCRIPTION)
                        || element.equalsDescription(Out.DESCRIPTION)
                        || element.equalsDescription(FlipflopD.DESCRIPTION));
        for (VisualElement element : ioElements) {
            int minX = element.getMinMax(false).getMin().x;
            int minY = element.getMinMax(false).getMin().y;
            int maxX = element.getMinMax(false).getMax().x;
            int maxY = element.getMinMax(false).getMax().y;
            for (Pin pin : element.getPins()) {
                if (Math.abs(pin.getPos().x - minX) <= 2)
                    minX = pin.getPos().x;
                if (Math.abs(pin.getPos().x - maxX) <= 2)
                    maxX = pin.getPos().x;
                if (Math.abs(pin.getPos().y - minY) <= 2)
                    minY = pin.getPos().y;
                if (Math.abs(pin.getPos().y - maxY) <= 2)
                    maxY = pin.getPos().y;
            }
            for (Wire wire : circuit.getWires()) {
                if (wire.p1.y == wire.p2.y) {
                    int wireMinX = Math.min(wire.p1.x, wire.p2.x);
                    int wireMaxX = Math.max(wire.p1.x, wire.p2.x);
                    assertFalse("Wire (" + wire.p1.x + "," + wire.p1.y + ")-("
                                    + wire.p2.x + "," + wire.p2.y + ") crosses "
                                    + element.getElementName() + " bounds (" + minX + "," + minY
                                    + ")-(" + maxX + "," + maxY + ")",
                            wire.p1.y > minY && wire.p1.y < maxY
                                    && wireMaxX > minX && wireMinX < maxX);
                } else {
                    int wireMinY = Math.min(wire.p1.y, wire.p2.y);
                    int wireMaxY = Math.max(wire.p1.y, wire.p2.y);
                    assertFalse("Wire (" + wire.p1.x + "," + wire.p1.y + ")-("
                                    + wire.p2.x + "," + wire.p2.y + ") crosses "
                                    + element.getElementName() + " bounds (" + minX + "," + minY
                                    + ")-(" + maxX + "," + maxY + ")",
                            wire.p1.x > minX && wire.p1.x < maxX
                                    && wireMaxY > minY && wireMinY < maxY);
                }
            }
        }
    }

    public void testAsyncFeedback() throws Exception {
        Variable y0 = new Variable("y_0");
        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setAsyncMode(CircuitBuilder.AsyncMode.FEEDBACK)
                .addSequential("y_0", not(y0))
                .createCircuit();

        // Check that clock is NOT present
        assertEquals(0, circuit.getElements(v -> v.equalsDescription(Clock.DESCRIPTION)).size());
        // Check that AsyncSeq is present
        assertEquals(1, circuit.getElements(v -> v.equalsDescription(AsyncSeq.DESCRIPTION)).size());
    }

    public void testAsyncRSLatch() throws Exception {
        Variable y0 = new Variable("y_0");
        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setAsyncMode(CircuitBuilder.AsyncMode.RS_LATCH)
                .addSequential("y_0", not(y0))
                .createCircuit();

        // Check that clock is NOT present
        assertEquals(0, circuit.getElements(v -> v.equalsDescription(Clock.DESCRIPTION)).size());
        // Check that RS_FF_AS is present
        assertEquals(1, circuit.getElements(v -> v.equalsDescription(FlipflopRSAsync.DESCRIPTION)).size());
    }

    public void testCrossCoupledNorLatch() throws Exception {
        Variable r = new Variable("R");
        Variable s = new Variable("S");
        Variable q = new Variable("Q");
        Variable qn = new Variable("Q_n");

        ElementLibrary library = new ElementLibrary();
        Circuit circuit = new CircuitBuilder(new ShapeFactory(library))
                .setResolveLocalVars(true)
                .setPreserveSharedOutputPins(true)
                .addCombinatorial("Q", not(or(r, qn)))
                .addCombinatorial("Q_n", not(or(s, q)))
                .createCircuit();

        // Check that exactly 2 NOR gates are present
        assertEquals(2, circuit.getElements(v -> v.equalsDescription(de.neemann.digital.core.basic.NOr.DESCRIPTION)).size());
        // Check that 2 In pins and 2 Out pins are present
        assertEquals(2, circuit.getElements(v -> v.equalsDescription(In.DESCRIPTION)).size());
        assertEquals(2, circuit.getElements(v -> v.equalsDescription(Out.DESCRIPTION)).size());
        // Check that tunnels are used to cross-couple the outputs
        assertTrue(circuit.getElements(v -> v.equalsDescription(Tunnel.DESCRIPTION)).size() >= 2);

        // Simulate the cross-coupled NOR latch
        ModelCreator mc = new ModelCreator(circuit, library);
        Model model = mc.createModel(false);
        model.init(true);

        Signal.Setter setS = model.getSignalSetter("S");
        Signal.Setter setR = model.getSignalSetter("R");
        ObservableValue outQ = model.getOutput("Q");
        ObservableValue outQn = model.getOutput("Q_n");

        assertNotNull(setS);
        assertNotNull(setR);
        assertNotNull(outQ);
        assertNotNull(outQn);

        // Step 1: Set S=1, R=0 -> Q=1, Q_n=0
        setS.set(1, 0);
        setR.set(0, 0);
        model.doStep();
        assertEquals(1, outQ.getValue());
        assertEquals(0, outQn.getValue());

        // Step 2: Hold S=0, R=0 -> Q remains 1, Q_n remains 0 (latched state)
        setS.set(0, 0);
        setR.set(0, 0);
        model.doStep();
        assertEquals(1, outQ.getValue());
        assertEquals(0, outQn.getValue());

        // Step 3: Reset S=0, R=1 -> Q=0, Q_n=1
        setS.set(0, 0);
        setR.set(1, 0);
        model.doStep();
        assertEquals(0, outQ.getValue());
        assertEquals(1, outQn.getValue());

        // Step 4: Hold S=0, R=0 -> Q remains 0, Q_n remains 1 (latched state)
        setS.set(0, 0);
        setR.set(0, 0);
        model.doStep();
        assertEquals(0, outQ.getValue());
        assertEquals(1, outQn.getValue());
    }

    public void testNorLatchSequentialFeedback() throws Exception {
        Variable s = new Variable("S");
        Variable r = new Variable("R");
        Variable q = new Variable("Q_0^n");
        ArrayList<Variable> vars = new ArrayList<>();
        vars.add(s);
        vars.add(r);
        vars.add(q);
        TruthTable tt = new TruthTable(vars);
        BoolTableByteArray val = new BoolTableByteArray(8);
        for (int row = 0; row < 8; row++) {
            int sBit = (row >> 2) & 1;
            int rBit = (row >> 1) & 1;
            int qBit = row & 1;
            int nextQ = sBit | ((1 - rBit) & qBit);
            val.set(row, nextQ);
        }
        tt.addResult("Q_0^{n+1}", val);

        ElementLibrary library = new ElementLibrary();
        CircuitBuilder cb = new CircuitBuilder(new ShapeFactory(library), vars)
                .setAsyncMode(CircuitBuilder.AsyncMode.FEEDBACK);

        // Synthesize using NOR modifier
        ExpressionListenerStore els = new ExpressionListenerStore(null);
        els.resultFound("Q_0^{n+1}", or(s, and(not(r), q)));
        els.close();
        new BuilderExpressionCreator(cb, new NOr()).create(els);
        Circuit circuit = cb.createCircuit();

        // Check that clock is NOT present
        assertEquals(0, circuit.getElements(v -> v.equalsDescription(Clock.DESCRIPTION)).size());
        // Check that AsyncSeq is present
        assertEquals(1, circuit.getElements(v -> v.equalsDescription(AsyncSeq.DESCRIPTION)).size());
        // Check that NOR gate(s) are present
        assertTrue(circuit.getElements(v -> v.equalsDescription(de.neemann.digital.core.basic.NOr.DESCRIPTION)).size() > 0);
    }

    public void testNorLatchSequentialRSLatch() throws Exception {
        Variable s = new Variable("S");
        Variable r = new Variable("R");
        Variable q = new Variable("Q_0^n");
        ArrayList<Variable> vars = new ArrayList<>();
        vars.add(s);
        vars.add(r);
        vars.add(q);

        ElementLibrary library = new ElementLibrary();
        CircuitBuilder cb = new CircuitBuilder(new ShapeFactory(library), vars)
                .setAsyncMode(CircuitBuilder.AsyncMode.RS_LATCH);

        ExpressionListenerStore els = new ExpressionListenerStore(null);
        els.resultFound("Q_0^{n+1}", or(s, and(not(r), q)));
        els.close();
        new BuilderExpressionCreator(cb).create(els);
        Circuit circuit = cb.createCircuit();

        // Check that clock is NOT present
        assertEquals(0, circuit.getElements(v -> v.equalsDescription(Clock.DESCRIPTION)).size());
        // Check that FlipflopRSAsync (unclocked RS latch) is present
        assertEquals(1, circuit.getElements(v -> v.equalsDescription(FlipflopRSAsync.DESCRIPTION)).size());
    }
}
