/*
 * Copyright (c) 2026 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.analyse;

import de.neemann.digital.fsm.FSM;
import de.neemann.digital.fsm.State;
import junit.framework.TestCase;

import java.util.Arrays;

/**
 * Tests for TransitionTable
 */
public class TransitionTableTest extends TestCase {

    public void testBasicCreationAndEditing() {
        TransitionTable tt = new TransitionTable(4, Arrays.asList("X"), Arrays.asList("Z"));
        assertEquals(4, tt.getStateCount());
        assertEquals(1, tt.getInputVars().size());
        assertEquals(1, tt.getOutputVars().size());
        assertEquals(2, tt.getColumnCount()); // X=0, X=1

        // Define transitions:
        // S0: on X=0 -> S0 (stable), on X=1 -> S1
        tt.setNextState(0, 0, 0);
        tt.setNextState(0, 1, 1);
        // S1: on X=0 -> S0, on X=1 -> S1 (stable)
        tt.setNextState(1, 0, 0);
        tt.setNextState(1, 1, 1);

        assertTrue(tt.isStable(0, 0));
        assertFalse(tt.isStable(0, 1));
        assertFalse(tt.isStable(1, 0));
        assertTrue(tt.isStable(1, 1));

        assertEquals(0, tt.getNextState(0, 0));
        assertEquals(1, tt.getNextState(0, 1));
    }

    public void testToTruthTableAndBack() {
        TransitionTable tt = new TransitionTable(2, Arrays.asList("X"), Arrays.asList("Z"));
        // S0 (code 0): on X=0 -> S0, Z=0; on X=1 -> S1, Z=1
        tt.setNextState(0, 0, 0);
        tt.setTransitionOutput(0, 0, "Z", 0);
        tt.setNextState(0, 1, 1);
        tt.setTransitionOutput(0, 1, "Z", 1);
        // S1 (code 1): on X=0 -> S1, Z=1; on X=1 -> S0, Z=0
        tt.setNextState(1, 0, 1);
        tt.setTransitionOutput(1, 0, "Z", 1);
        tt.setNextState(1, 1, 0);
        tt.setTransitionOutput(1, 1, "Z", 0);

        TruthTable truthTable = tt.toTruthTable();
        assertNotNull(truthTable);
        // 1 state bit (Q_0^n) + 1 input (X) = 2 inputs -> 4 rows
        assertEquals(4, truthTable.getRows());
        assertEquals(2, truthTable.getVars().size());
        assertEquals("Q_0^n", truthTable.getVars().get(0).getIdentifier());
        assertEquals("X", truthTable.getVars().get(1).getIdentifier());
        // Results: Q_0^{n+1}, Z
        assertEquals(2, truthTable.getResultCount());
        assertEquals("Q_0^{n+1}", truthTable.getResultName(0));
        assertEquals("Z", truthTable.getResultName(1));

        // Inputs are col 0 and 1. Results are col 2 (Q_0^{n+1}) and 3 (Z).
        // Row 0: Q_0^n=0, X=0 -> next Q_0=0, Z=0
        assertEquals(0, truthTable.getValue(0, 2));
        assertEquals(0, truthTable.getValue(0, 3));
        // Row 1: Q_0^n=0, X=1 -> next Q_0=1, Z=1
        assertEquals(1, truthTable.getValue(1, 2));
        assertEquals(1, truthTable.getValue(1, 3));
        // Row 2: Q_0^n=1, X=0 -> next Q_0=1, Z=1
        assertEquals(1, truthTable.getValue(2, 2));
        assertEquals(1, truthTable.getValue(2, 3));
        // Row 3: Q_0^n=1, X=1 -> next Q_0=0, Z=0
        assertEquals(0, truthTable.getValue(3, 2));
        assertEquals(0, truthTable.getValue(3, 3));

        // Reconstruct from TruthTable
        TransitionTable reconstructed = TransitionTable.fromTruthTable(truthTable);
        assertEquals(2, reconstructed.getStateCount());
        assertEquals(1, reconstructed.getInputVars().size());
        assertEquals("X", reconstructed.getInputVars().get(0));
        assertEquals(0, reconstructed.getNextState(0, 0));
        assertEquals(1, reconstructed.getNextState(0, 1));
        assertEquals(1, reconstructed.getNextState(1, 0));
        assertEquals(0, reconstructed.getNextState(1, 1));
    }

    public void testFSMConversion() throws Exception {
        State a = new State("A");
        State b = new State("B");
        FSM fsm = new FSM(a, b)
                .transition(a, b, "Run=1")
                .transition(b, a, "Run=0");

        TransitionTable tt = TransitionTable.fromFSM(fsm);
        assertEquals(2, tt.getStateCount());
        assertEquals(1, tt.getInputVars().size());
        assertEquals("Run", tt.getInputVars().get(0));

        // Unspecified transitions should self-loop (stay in current state)
        // A with Run=0: no transition specified -> should stay in A (index 0)
        assertEquals(0, tt.getNextState(0, 0)); // A, Run=0 -> A
        // A with Run=1: transition to B
        assertEquals(1, tt.getNextState(0, 1)); // A, Run=1 -> B
        // B with Run=0: transition to A
        assertEquals(0, tt.getNextState(1, 0)); // B, Run=0 -> A
        // B with Run=1: no transition specified -> should stay in B (index 1)
        assertEquals(1, tt.getNextState(1, 1)); // B, Run=1 -> B

        // Convert back to FSM
        FSM fsmBack = tt.toFSM();
        assertNotNull(fsmBack);
        assertEquals(2, fsmBack.getStates().size());
        assertTrue(fsmBack.getTransitions().size() >= 2);
    }

    public void testFSMConversionDefaultOutputs() throws Exception {
        State a = new State("A");
        a.setValues("Z=1");
        State b = new State("B");
        // B has no output defined -> should default to Z=0
        FSM fsm = new FSM(a, b)
                .transition(a, b, "X=1")
                .transition(b, a, "X=0");

        TransitionTable tt = TransitionTable.fromFSM(fsm);
        assertEquals(2, tt.getStateCount());
        assertEquals(1, tt.getOutputVars().size());
        assertEquals("Z", tt.getOutputVars().get(0));

        // A has Z=1 explicitly
        assertEquals(Integer.valueOf(1), tt.getState(0).getOutput("Z"));
        // B has no Z defined -> should default to 0
        assertEquals(Integer.valueOf(0), tt.getState(1).getOutput("Z"));
    }

    public void testOutputCellEditingDontCare() {
        TransitionTable tt = new TransitionTable(2, java.util.Arrays.asList("X"), java.util.Arrays.asList("Z"));
        tt.getState(0).setOutput("Z", 1);
        assertEquals(Integer.valueOf(1), tt.getState(0).getOutput("Z"));

        // Setting output to null (don't care) by removing from map
        tt.getState(0).getOutputs().remove("Z");
        assertNull(tt.getState(0).getOutput("Z"));

        // Setting output back
        tt.getState(0).setOutput("Z", 0);
        assertEquals(Integer.valueOf(0), tt.getState(0).getOutput("Z"));
    }

    public void testTransitionTableCircuitCreationAsyncFeedback() throws Exception {
        TransitionTable tt = new TransitionTable(2, Arrays.asList("S", "R"), java.util.Collections.emptyList());
        tt.setAsync(true);
        tt.setNextState(0, 0, 0);
        tt.setNextState(0, 1, 0);
        tt.setNextState(0, 2, 1);
        tt.setNextState(0, 3, -1);
        tt.setNextState(1, 0, 1);
        tt.setNextState(1, 1, 0);
        tt.setNextState(1, 2, 1);
        tt.setNextState(1, 3, -1);

        TruthTable actualTable = tt.toTruthTable();
        de.neemann.digital.gui.components.table.ExpressionListenerStore circuitExpressions = new de.neemann.digital.gui.components.table.ExpressionListenerStore(
                null);
        for (int i = 0; i < actualTable.getResultCount(); i++) {
            String rName = actualTable.getResultName(i);
            new MinimizerQuineMcCluskey()
                    .minimize(actualTable.getVars(), actualTable.getResult(i), rName, circuitExpressions);
        }
        circuitExpressions.close();

        de.neemann.digital.draw.library.ElementLibrary library = new de.neemann.digital.draw.library.ElementLibrary();
        de.neemann.digital.draw.shapes.ShapeFactory shapeFactory = new de.neemann.digital.draw.shapes.ShapeFactory(
                library);
        de.neemann.digital.builder.circuit.CircuitBuilder circuitBuilder = new de.neemann.digital.builder.circuit.CircuitBuilder(
                shapeFactory, actualTable.getVars())
                .setAsyncMode(de.neemann.digital.builder.circuit.CircuitBuilder.AsyncMode.FEEDBACK)
                .setResolveLocalVars(true)
                .setPreserveSharedOutputPins(true)
                .setModelAnalyzerInfo(actualTable.getModelAnalyzerInfo());

        new de.neemann.digital.gui.components.table.BuilderExpressionCreator(circuitBuilder,
                de.neemann.digital.analyse.expression.modify.ExpressionModifier.IDENTITY)
                .create(circuitExpressions);

        de.neemann.digital.draw.elements.Circuit circuit = circuitBuilder.createCircuit();
        assertNotNull(circuit);
        assertTrue(circuit.getElements().size() > 0);
    }

    public void testTransitionTableCircuitCreationSync() throws Exception {
        TransitionTable tt = new TransitionTable(2, Arrays.asList("X"), Arrays.asList("Z"));
        tt.setNextState(0, 0, 0);
        tt.setTransitionOutput(0, 0, "Z", 0);
        tt.setNextState(0, 1, 1);
        tt.setTransitionOutput(0, 1, "Z", 1);
        tt.setNextState(1, 0, 1);
        tt.setTransitionOutput(1, 0, "Z", 1);
        tt.setNextState(1, 1, 0);
        tt.setTransitionOutput(1, 1, "Z", 0);

        TruthTable actualTable = tt.toTruthTable();
        de.neemann.digital.gui.components.table.ExpressionListenerStore circuitExpressions = new de.neemann.digital.gui.components.table.ExpressionListenerStore(
                null);
        for (int i = 0; i < actualTable.getResultCount(); i++) {
            String rName = actualTable.getResultName(i);
            new MinimizerQuineMcCluskey()
                    .minimize(actualTable.getVars(), actualTable.getResult(i), rName, circuitExpressions);
        }
        circuitExpressions.close();

        de.neemann.digital.draw.library.ElementLibrary library = new de.neemann.digital.draw.library.ElementLibrary();
        de.neemann.digital.draw.shapes.ShapeFactory shapeFactory = new de.neemann.digital.draw.shapes.ShapeFactory(
                library);
        de.neemann.digital.builder.circuit.CircuitBuilder circuitBuilder = new de.neemann.digital.builder.circuit.CircuitBuilder(
                shapeFactory, actualTable.getVars())
                .setAsyncMode(de.neemann.digital.builder.circuit.CircuitBuilder.AsyncMode.NONE)
                .setModelAnalyzerInfo(actualTable.getModelAnalyzerInfo());

        new de.neemann.digital.gui.components.table.BuilderExpressionCreator(circuitBuilder,
                de.neemann.digital.analyse.expression.modify.ExpressionModifier.IDENTITY)
                .create(circuitExpressions);

        de.neemann.digital.draw.elements.Circuit circuit = circuitBuilder.createCircuit();
        assertNotNull(circuit);
        assertTrue(circuit.getElements().size() > 0);
    }

    public void testTransitionTableCircuitCreationRaceFree() throws Exception {
        TransitionTable tt = new TransitionTable(2, Arrays.asList("S", "R"), java.util.Collections.emptyList());
        tt.setAsync(true);
        tt.setNextState(0, 0, 0);
        tt.setNextState(0, 1, 0);
        tt.setNextState(0, 2, 1);
        tt.setNextState(0, 3, -1);
        tt.setNextState(1, 0, 1);
        tt.setNextState(1, 1, 0);
        tt.setNextState(1, 2, 1);
        tt.setNextState(1, 3, -1);

        AsyncStateEncoder encoder = new AsyncStateEncoder(tt);
        TruthTable srTable = encoder.generateSRExcitationTable();

        de.neemann.digital.gui.components.table.ExpressionListenerStore circuitExpressions = new de.neemann.digital.gui.components.table.ExpressionListenerStore(
                null);
        for (int i = 0; i < srTable.getResultCount(); i++) {
            String rName = srTable.getResultName(i);
            new MinimizerQuineMcCluskey()
                    .setHazardFree(true)
                    .minimize(srTable.getVars(), srTable.getResult(i), rName, circuitExpressions);
        }

        de.neemann.digital.draw.library.ElementLibrary library = new de.neemann.digital.draw.library.ElementLibrary();
        de.neemann.digital.draw.shapes.ShapeFactory shapeFactory = new de.neemann.digital.draw.shapes.ShapeFactory(
                library);
        de.neemann.digital.builder.circuit.CircuitBuilder circuitBuilder = new de.neemann.digital.builder.circuit.CircuitBuilder(
                shapeFactory, srTable.getVars())
                .setAsyncMode(de.neemann.digital.builder.circuit.CircuitBuilder.AsyncMode.NONE)
                .setHazardFree(true)
                .setModelAnalyzerInfo(srTable.getModelAnalyzerInfo());

        java.util.Map<String, de.neemann.digital.analyse.expression.Expression> exprMap = new java.util.HashMap<>();
        for (de.neemann.digital.gui.components.table.ExpressionListenerStore.Result r : circuitExpressions
                .getResults()) {
            exprMap.put(r.getName(), r.getExpression());
        }

        int stateBits = encoder.getStateBits();
        String prefix = tt.getStateVarPrefix();
        if (prefix == null || prefix.isEmpty()) {
            prefix = "Q";
        }
        for (int k = stateBits - 1; k >= 0; k--) {
            String qName = prefix + "_" + k + "^n";
            String sName = "S_" + prefix + "_" + k;
            String rName = "R_" + prefix + "_" + k;
            de.neemann.digital.analyse.expression.Expression exprS = exprMap.getOrDefault(sName,
                    de.neemann.digital.analyse.expression.Constant.ZERO);
            de.neemann.digital.analyse.expression.Expression exprR = exprMap.getOrDefault(rName,
                    de.neemann.digital.analyse.expression.Constant.ZERO);
            circuitBuilder.addAsyncRSLatch(qName, exprS, exprR);
        }

        de.neemann.digital.draw.elements.Circuit circuit = circuitBuilder.createCircuit();
        assertNotNull(circuit);
        assertTrue(circuit.getElements().size() > 0);
    }
}
