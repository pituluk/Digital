/*
 * Copyright (c) 2026 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.analyse;

import de.neemann.digital.analyse.expression.ContextMap;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.expression.VariableVisitor;
import de.neemann.digital.fsm.FSM;
import de.neemann.digital.fsm.FiniteStateMachineException;
import de.neemann.digital.fsm.State;
import de.neemann.digital.fsm.Transition;
import de.neemann.digital.fsm.ValueParser;
import de.neemann.digital.gui.components.table.ExpressionListenerJK;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Representation of a transition table (state/flow table) for synchronous and asynchronous sequential circuits.
 */
public class TransitionTable {

    /**
     * A state in the transition table.
     */
    public static class StateItem {
        private String name;
        private int code;
        private final LinkedHashMap<String, Integer> outputs;

        /**
         * Creates a new state item.
         *
         * @param name the state name
         * @param code the binary state code
         */
        public StateItem(String name, int code) {
            this.name = name;
            this.code = code;
            this.outputs = new LinkedHashMap<>();
        }

        /**
         * @return the name
         */
        public String getName() {
            return name;
        }

        /**
         * Sets the state name.
         *
         * @param name name
         */
        public void setName(String name) {
            this.name = name;
        }

        /**
         * @return the state code
         */
        public int getCode() {
            return code;
        }

        /**
         * Sets the state code.
         *
         * @param code code
         */
        public void setCode(int code) {
            this.code = code;
        }

        /**
         * @return the state outputs
         */
        public Map<String, Integer> getOutputs() {
            return outputs;
        }

        /**
         * Sets an output value for this state.
         *
         * @param outputName name of output
         * @param value      value (0 or 1)
         */
        public void setOutput(String outputName, int value) {
            outputs.put(outputName, value);
        }

        /**
         * @param outputName name of output
         * @return output value or null if not set
         */
        public Integer getOutput(String outputName) {
            return outputs.get(outputName);
        }
    }

    private final ArrayList<StateItem> states;
    private final ArrayList<String> inputVars;
    private final ArrayList<String> outputVars;
    private int[][] nextStates;
    private ArrayList<LinkedHashMap<String, Integer>> transitionOutputs;
    private boolean isAsync;
    private String stateVarPrefix;

    /**
     * Creates an empty transition table.
     */
    public TransitionTable() {
        this.states = new ArrayList<>();
        this.inputVars = new ArrayList<>();
        this.outputVars = new ArrayList<>();
        this.isAsync = false;
        this.stateVarPrefix = "Q";
        rebuildMatrix();
    }

    /**
     * Creates a transition table with a given number of states and variables.
     *
     * @param stateCount number of initial states
     * @param inputVars  list of input variable names
     * @param outputVars list of output variable names
     */
    public TransitionTable(int stateCount, List<String> inputVars, List<String> outputVars) {
        this.states = new ArrayList<>();
        this.inputVars = new ArrayList<>();
        if (inputVars != null) {
            this.inputVars.addAll(inputVars);
        }
        this.outputVars = new ArrayList<>();
        if (outputVars != null) {
            this.outputVars.addAll(outputVars);
        }
        this.isAsync = false;
        this.stateVarPrefix = "Q";

        for (int i = 0; i < stateCount; i++) {
            states.add(new StateItem("S" + i, i));
        }
        rebuildMatrix();
    }

    /**
     * @return the number of states
     */
    public int getStateCount() {
        return states.size();
    }

    /**
     * @param index state index
     * @return the state item
     */
    public StateItem getState(int index) {
        return states.get(index);
    }

    /**
     * @return list of states
     */
    public List<StateItem> getStates() {
        return Collections.unmodifiableList(states);
    }

    /**
     * Adds a state.
     *
     * @param name state name
     * @return index of newly added state
     */
    public int addState(String name) {
        int code = states.size();
        for (StateItem s : states) {
            if (s.getCode() >= code) {
                code = s.getCode() + 1;
            }
        }
        return addState(name, code);
    }

    /**
     * Adds a state with specific code.
     *
     * @param name state name
     * @param code state code
     * @return index of newly added state
     */
    public int addState(String name, int code) {
        if (code < 0) {
            return addState(name);
        }
        states.add(new StateItem(name, code));
        rebuildMatrix();
        return states.size() - 1;
    }

    /**
     * Removes a state.
     *
     * @param index state index
     */
    public void removeState(int index) {
        if (index >= 0 && index < states.size()) {
            states.remove(index);
            rebuildMatrix();
        }
    }

    /**
     * @return list of input variable names
     */
    public List<String> getInputVars() {
        return Collections.unmodifiableList(inputVars);
    }

    /**
     * Adds an input variable.
     *
     * @param name variable name
     */
    public void addInputVar(String name) {
        if (!inputVars.contains(name)) {
            inputVars.add(name);
            rebuildMatrix();
        }
    }

    /**
     * Removes an input variable.
     *
     * @param index variable index
     */
    public void removeInputVar(int index) {
        if (index >= 0 && index < inputVars.size()) {
            inputVars.remove(index);
            rebuildMatrix();
        }
    }

    /**
     * @return list of output variable names
     */
    public List<String> getOutputVars() {
        return Collections.unmodifiableList(outputVars);
    }

    /**
     * Adds an output variable.
     *
     * @param name variable name
     */
    public void addOutputVar(String name) {
        if (!outputVars.contains(name)) {
            outputVars.add(name);
        }
    }

    /**
     * Removes an output variable.
     *
     * @param index variable index
     */
    public void removeOutputVar(int index) {
        if (index >= 0 && index < outputVars.size()) {
            String name = outputVars.remove(index);
            for (StateItem s : states) {
                s.getOutputs().remove(name);
            }
            if (transitionOutputs != null) {
                for (LinkedHashMap<String, Integer> map : transitionOutputs) {
                    if (map != null) {
                        map.remove(name);
                    }
                }
            }
        }
    }

    /**
     * @return number of input columns
     */
    public int getColumnCount() {
        return inputVars.isEmpty() ? 1 : (1 << inputVars.size());
    }

    /**
     * Formats a column header.
     *
     * @param col column index
     * @return formatted header
     */
    public String getColumnHeader(int col) {
        if (inputVars.isEmpty()) {
            return "-";
        }
        int numVars = inputVars.size();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numVars; i++) {
            if (i > 0) {
                sb.append(" ");
            }
            int bit = (col >> (numVars - 1 - i)) & 1;
            sb.append(inputVars.get(i)).append("=").append(bit);
        }
        return sb.toString();
    }

    private int getCellIndex(int stateIndex, int colIndex) {
        return stateIndex * getColumnCount() + colIndex;
    }

    private void rebuildMatrix() {
        int rows = states.size();
        int cols = getColumnCount();
        int[][] oldNext = nextStates;
        ArrayList<LinkedHashMap<String, Integer>> oldOutputs = transitionOutputs;

        nextStates = new int[rows][cols];
        transitionOutputs = new ArrayList<>(rows * cols);
        for (int i = 0; i < rows * cols; i++) {
            transitionOutputs.add(new LinkedHashMap<>());
        }

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (oldNext != null && r < oldNext.length && c < oldNext[r].length) {
                    nextStates[r][c] = oldNext[r][c];
                    int oldIdx = r * (oldNext[0].length) + c;
                    if (oldOutputs != null && oldIdx < oldOutputs.size()) {
                        transitionOutputs.get(getCellIndex(r, c)).putAll(oldOutputs.get(oldIdx));
                    }
                } else {
                    nextStates[r][c] = -1; // unassigned
                }
            }
        }
    }

    /**
     * Sets next state for a cell.
     *
     * @param stateIndex      state index (row)
     * @param colIndex        input column index
     * @param targetStateIndex target state index, or -1 for don't care
     */
    public void setNextState(int stateIndex, int colIndex, int targetStateIndex) {
        if (stateIndex >= 0 && stateIndex < states.size() && colIndex >= 0 && colIndex < getColumnCount()) {
            nextStates[stateIndex][colIndex] = targetStateIndex;
        }
    }

    /**
     * Gets next state for a cell.
     *
     * @param stateIndex state index (row)
     * @param colIndex   input column index
     * @return target state index, or -1 for don't care
     */
    public int getNextState(int stateIndex, int colIndex) {
        if (stateIndex >= 0 && stateIndex < states.size() && colIndex >= 0 && colIndex < getColumnCount()) {
            return nextStates[stateIndex][colIndex];
        }
        return -1;
    }

    /**
     * Sets transition output (Mealy).
     *
     * @param stateIndex state index
     * @param colIndex   column index
     * @param outputName output variable name
     * @param val        value (0 or 1)
     */
    public void setTransitionOutput(int stateIndex, int colIndex, String outputName, int val) {
        if (stateIndex >= 0 && stateIndex < states.size() && colIndex >= 0 && colIndex < getColumnCount()) {
            transitionOutputs.get(getCellIndex(stateIndex, colIndex)).put(outputName, val);
        }
    }

    /**
     * Gets transition output (Mealy).
     *
     * @param stateIndex state index
     * @param colIndex   column index
     * @param outputName output variable name
     * @return value or null if not set
     */
    public Integer getTransitionOutput(int stateIndex, int colIndex, String outputName) {
        if (stateIndex >= 0 && stateIndex < states.size() && colIndex >= 0 && colIndex < getColumnCount()) {
            return transitionOutputs.get(getCellIndex(stateIndex, colIndex)).get(outputName);
        }
        return null;
    }

    /**
     * Checks if state is stable under the given input combination.
     * In asynchronous sequential circuits, a state is stable if next state == current state.
     *
     * @param stateIndex state index
     * @param colIndex   column index
     * @return true if stable
     */
    public boolean isStable(int stateIndex, int colIndex) {
        int next = getNextState(stateIndex, colIndex);
        return next == stateIndex;
    }

    /**
     * @return true if asynchronous mode is active
     */
    public boolean isAsync() {
        return isAsync;
    }

    /**
     * Sets asynchronous mode.
     *
     * @param isAsync true for asynchronous mode
     */
    public void setAsync(boolean isAsync) {
        this.isAsync = isAsync;
    }

    /**
     * @return prefix used for state variables (e.g. "Q", "y", "Z")
     */
    public String getStateVarPrefix() {
        return stateVarPrefix;
    }

    /**
     * Sets state variable prefix.
     *
     * @param stateVarPrefix prefix
     */
    public void setStateVarPrefix(String stateVarPrefix) {
        this.stateVarPrefix = stateVarPrefix;
    }

    /**
     * Determines number of state bits required.
     *
     * @return number of state bits
     */
    public int getStateBits() {
        int maxCode = 0;
        for (StateItem s : states) {
            if (s.getCode() > maxCode) {
                maxCode = s.getCode();
            }
        }
        int bits = 1;
        while ((1 << bits) <= maxCode) {
            bits++;
        }
        return bits;
    }

    /**
     * Converts this transition table to a {@link TruthTable}.
     *
     * @return the generated truth table
     */
    public TruthTable toTruthTable() {
        int stateBits = getStateBits();
        int inputBits = inputVars.size();

        // Input variables in TruthTable: state vars (Q_{k-1}^n .. Q_0^n), then primary inputs
        ArrayList<Variable> inVars = new ArrayList<>();
        ArrayList<String> maiNames = new ArrayList<>();
        for (int i = stateBits - 1; i >= 0; i--) {
            Variable v = new Variable(stateVarPrefix + "_" + i + "^n");
            inVars.add(v);
            maiNames.add(v.getIdentifier());
        }
        for (String iv : inputVars) {
            inVars.add(new Variable(iv));
        }

        TruthTable truthTable = new TruthTable(inVars);

        // Next state results: Q_{k-1}^{n+1} .. Q_0^{n+1}
        for (int i = stateBits - 1; i >= 0; i--) {
            truthTable.addResult(stateVarPrefix + "_" + i + "^{n+1}");
        }
        // Output results
        for (String ov : outputVars) {
            truthTable.addResult(ov);
        }

        // Initialize all values to Don't Care (2)
        truthTable.modifyValues(v -> (byte) 2);

        int cols = getColumnCount();
        for (int sIdx = 0; sIdx < states.size(); sIdx++) {
            StateItem s = states.get(sIdx);
            int sCode = s.getCode();

            for (int c = 0; c < cols; c++) {
                int row = (sCode << inputBits) | c;
                int nextIdx = getNextState(sIdx, c);

                if (nextIdx >= 0 && nextIdx < states.size()) {
                    int nextCode = states.get(nextIdx).getCode();
                    for (int b = 0; b < stateBits; b++) {
                        int bitVal = (nextCode >> b) & 1;
                        int col = inVars.size() + (stateBits - 1 - b);
                        truthTable.setValue(row, col, bitVal);
                    }
                }

                // Outputs
                for (int oIdx = 0; oIdx < outputVars.size(); oIdx++) {
                    String oName = outputVars.get(oIdx);
                    Integer val = getTransitionOutput(sIdx, c, oName);
                    if (val == null) {
                        val = s.getOutput(oName);
                    }
                    if (val != null) {
                        truthTable.setValue(row, inVars.size() + stateBits + oIdx, val);
                    }
                }
            }
        }

        ModelAnalyserInfo mai = new ModelAnalyserInfo(null);
        if (!states.isEmpty()) {
            int initCode = states.get(0).getCode();
            for (int b = 0; b < stateBits; b++) {
                String varName = stateVarPrefix + "_" + b + "^n";
                mai.setSequentialInitValue(varName, (initCode >> b) & 1);
            }
        }
        ArrayList<String> stateSignalBits = new ArrayList<>();
        for (int i = 0; i < stateBits; i++) {
            stateSignalBits.add(stateVarPrefix + "_" + i + "^n");
        }
        mai.setStateSignalBitNames(stateSignalBits);
        mai.setStateSignalName(stateVarPrefix);
        truthTable.setModelAnalyzerInfo(mai);

        return truthTable;
    }

    /**
     * Creates a {@link TransitionTable} from an existing {@link TruthTable}.
     *
     * @param tt the truth table
     * @return the transition table
     */
    public static TransitionTable fromTruthTable(TruthTable tt) {
        if (tt == null) {
            return new TransitionTable();
        }

        ArrayList<Variable> vars = tt.getVars();
        ArrayList<String> resultNames = tt.getResultNames();

        ArrayList<String> seqResultNames = new ArrayList<>();
        ArrayList<String> outVars = new ArrayList<>();

        for (String rName : resultNames) {
            if (ExpressionListenerJK.isSequentialVar(rName) != null) {
                seqResultNames.add(rName);
            } else {
                outVars.add(rName);
            }
        }

        int stateBits = seqResultNames.size();
        if (stateBits == 0) {
            // Not a sequential table, create default 2-state table
            return new TransitionTable(2, null, null);
        }

        // Determine state variable prefix
        String prefix = "Q";
        String firstSeq = seqResultNames.get(0);
        int underscoreIdx = firstSeq.indexOf('_');
        if (underscoreIdx > 0) {
            prefix = firstSeq.substring(0, underscoreIdx);
        }

        ArrayList<Variable> stateVars = new ArrayList<>();
        ArrayList<String> inVars = new ArrayList<>();
        for (Variable v : vars) {
            String vName = v.getIdentifier();
            if (vName.endsWith("^n") || vName.endsWith("{n}")) {
                stateVars.add(v);
            } else {
                inVars.add(vName);
            }
        }

        if (stateVars.isEmpty()) {
            for (int i = 0; i < stateBits && i < vars.size(); i++) {
                stateVars.add(vars.get(i));
            }
            inVars.clear();
            for (int i = stateBits; i < vars.size(); i++) {
                inVars.add(vars.get(i).getIdentifier());
            }
        }

        int numStates = 1 << stateBits;
        TransitionTable transTable = new TransitionTable(numStates, inVars, outVars);
        transTable.setStateVarPrefix(prefix);

        for (int row = 0; row < tt.getRows(); row++) {
            int sCode = 0;
            for (Variable sv : stateVars) {
                int colIdx = vars.indexOf(sv);
                int bit = tt.getValue(row, colIdx);
                sCode = (sCode << 1) | (bit & 1);
            }

            int c = 0;
            for (String ivName : inVars) {
                int colIdx = -1;
                for (int k = 0; k < vars.size(); k++) {
                    if (vars.get(k).getIdentifier().equals(ivName)) {
                        colIdx = k;
                        break;
                    }
                }
                int bit = (colIdx >= 0) ? tt.getValue(row, colIdx) : 0;
                c = (c << 1) | (bit & 1);
            }

            if (sCode < numStates && c < transTable.getColumnCount()) {
                boolean hasDC = false;
                int nextCode = 0;
                for (String srName : seqResultNames) {
                    int resIdx = resultNames.indexOf(srName);
                    int val = tt.getValue(row, vars.size() + resIdx);
                    if (val == 2) {
                        hasDC = true;
                        break;
                    }
                    nextCode = (nextCode << 1) | (val & 1);
                }
                if (!hasDC && nextCode < numStates) {
                    transTable.setNextState(sCode, c, nextCode);
                }

                for (String ovName : outVars) {
                    int resIdx = resultNames.indexOf(ovName);
                    int val = tt.getValue(row, vars.size() + resIdx);
                    if (val != 2) {
                        transTable.setTransitionOutput(sCode, c, ovName, val);
                        if (transTable.getState(sCode).getOutput(ovName) == null) {
                            transTable.getState(sCode).setOutput(ovName, val);
                        }
                    }
                }
            }
        }

        return transTable;
    }

    /**
     * Converts this transition table to an {@link FSM}.
     *
     * @return the generated FSM
     */
    public FSM toFSM() {
        FSM fsm = new FSM();
        ArrayList<State> fsmStates = new ArrayList<>();

        for (int i = 0; i < states.size(); i++) {
            StateItem si = states.get(i);
            State s = new State(si.getName());
            s.setNumber(si.getCode());
            if (i == 0) {
                s.setInitial(true);
            }
            if (!si.getOutputs().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, Integer> e : si.getOutputs().entrySet()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(e.getKey()).append("=").append(e.getValue());
                }
                s.setValues(sb.toString());
            }
            fsm.add(s);
            fsmStates.add(s);
        }

        int cols = getColumnCount();
        int numInputs = inputVars.size();

        for (int sIdx = 0; sIdx < states.size(); sIdx++) {
            State fromState = fsmStates.get(sIdx);
            for (int c = 0; c < cols; c++) {
                int targetIdx = getNextState(sIdx, c);
                if (targetIdx >= 0 && targetIdx < fsmStates.size()) {
                    State toState = fsmStates.get(targetIdx);

                    String cond = null;
                    if (numInputs > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < numInputs; i++) {
                            if (sb.length() > 0) {
                                sb.append(" & ");
                            }
                            int bit = (c >> (numInputs - 1 - i)) & 1;
                            sb.append(inputVars.get(i)).append("=").append(bit);
                        }
                        cond = sb.toString();
                    }

                    Transition t = new Transition(fromState, toState, cond);
                    Map<String, Integer> outMap = transitionOutputs.get(getCellIndex(sIdx, c));
                    if (outMap != null && !outMap.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (Map.Entry<String, Integer> e : outMap.entrySet()) {
                            if (sb.length() > 0) {
                                sb.append(", ");
                            }
                            sb.append(e.getKey()).append("=").append(e.getValue());
                        }
                        t.setValues(sb.toString());
                    }
                    fsm.add(t);
                }
            }
        }

        fsm.circle();
        return fsm;
    }

    /**
     * Creates a {@link TransitionTable} from an {@link FSM}.
     *
     * @param fsm the FSM
     * @return the transition table
     * @throws FiniteStateMachineException if condition parsing fails
     */
    public static TransitionTable fromFSM(FSM fsm) throws FiniteStateMachineException {
        if (fsm == null || fsm.getStates().isEmpty()) {
            return new TransitionTable();
        }

        List<State> fsmStates = fsm.getStates();
        List<Transition> fsmTransitions = fsm.getTransitions();

        VariableVisitor vv = new VariableVisitor();
        for (Transition t : fsmTransitions) {
            if (t.hasCondition()) {
                t.getConditionExpression().traverse(vv);
            }
        }
        ArrayList<String> inVars = new ArrayList<>();
        for (Variable v : vv.getVariables()) {
            inVars.add(v.getIdentifier());
        }

        ArrayList<String> outVars = new ArrayList<>();
        for (State s : fsmStates) {
            try {
                TreeMap<String, Integer> vals = new ValueParser(s.getValues()).parse();
                for (String k : vals.keySet()) {
                    if (!outVars.contains(k)) {
                        outVars.add(k);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        for (Transition t : fsmTransitions) {
            try {
                TreeMap<String, Integer> vals = new ValueParser(t.getValues()).parse();
                for (String k : vals.keySet()) {
                    if (!outVars.contains(k)) {
                        outVars.add(k);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        TransitionTable tt = new TransitionTable(0, inVars, outVars);
        int nextCode = 0;
        java.util.HashSet<Integer> usedCodes = new java.util.HashSet<>();
        for (State s : fsmStates) {
            if (s.getNumber() >= 0) {
                usedCodes.add(s.getNumber());
            }
        }
        for (int i = 0; i < fsmStates.size(); i++) {
            State s = fsmStates.get(i);
            int code = s.getNumber();
            if (code < 0) {
                while (usedCodes.contains(nextCode)) {
                    nextCode++;
                }
                code = nextCode;
                usedCodes.add(code);
            }
            int idx = tt.addState(s.getName(), code);
            // Set state (Moore) outputs: if a value is explicitly defined use it,
            // otherwise default to 0 (unless the state is marked as Don't Care)
            for (String ov : outVars) {
                Integer val = null;
                try {
                    TreeMap<String, Integer> vals = new ValueParser(s.getValues()).parse();
                    val = vals.get(ov);
                } catch (Exception ignored) {
                }
                if (val != null) {
                    tt.getState(idx).setOutput(ov, val);
                } else if (!s.isDefaultDC()) {
                    tt.getState(idx).setOutput(ov, 0);
                }
            }
        }

        int cols = tt.getColumnCount();
        ContextMap ctx = new ContextMap();

        // Initialize default next-state: unspecified transitions stay in current
        // state (self-loop) unless the state is marked as Don't Care.
        // This matches TransitionTableCreator semantics.
        for (int sIdx = 0; sIdx < tt.getStateCount(); sIdx++) {
            State s = fsmStates.get(sIdx);
            int defNext = s.isDefaultDC() ? -1 : sIdx;
            for (int c = 0; c < cols; c++) {
                tt.setNextState(sIdx, c, defNext);
            }
        }

        for (Transition t : fsmTransitions) {
            int fromIdx = fsmStates.indexOf(t.getStartState());
            int toIdx = fsmStates.indexOf(t.getTargetState());
            if (fromIdx < 0 || toIdx < 0) {
                continue;
            }

            for (int c = 0; c < cols; c++) {
                boolean matches = true;
                if (t.hasCondition()) {
                    for (int i = 0; i < inVars.size(); i++) {
                        int bit = (c >> (inVars.size() - 1 - i)) & 1;
                        ctx.set(new Variable(inVars.get(i)), bit != 0);
                    }
                    try {
                        matches = t.getConditionExpression().calculate(ctx);
                    } catch (Exception e) {
                        matches = false;
                    }
                }

                if (matches) {
                    tt.setNextState(fromIdx, c, toIdx);
                    try {
                        TreeMap<String, Integer> vals = new ValueParser(t.getValues()).parse();
                        for (Map.Entry<String, Integer> e : vals.entrySet()) {
                            tt.setTransitionOutput(fromIdx, c, e.getKey(), e.getValue());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return tt;
    }
}
