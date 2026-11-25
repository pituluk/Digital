/*
 * Copyright (c) 2026 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.analyse;

import de.neemann.digital.analyse.expression.Variable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Performs critical race-free state assignment for asynchronous state machines
 * and generates SR excitation tables for asynchronous RS latch synthesis.
 */
public class AsyncStateEncoder {

    /**
     * A directed transition between two distinct states under a specific input column.
     */
    public static class TransitionEdge {
        private final int fromState;
        private final int toState;
        private final int inputCol;

        /**
         * Creates a transition edge.
         *
         * @param fromState source state index
         * @param toState   target state index
         * @param inputCol  input column index
         */
        public TransitionEdge(int fromState, int toState, int inputCol) {
            this.fromState = fromState;
            this.toState = toState;
            this.inputCol = inputCol;
        }

        /**
         * @return source state index
         */
        public int getFromState() {
            return fromState;
        }

        /**
         * @return target state index
         */
        public int getToState() {
            return toState;
        }

        /**
         * @return input column index
         */
        public int getInputCol() {
            return inputCol;
        }
    }

    /**
     * Encoded state representation.
     */
    public static class EncodedState {
        private final String name;
        private final int originalIndex;
        private final int code;
        private final boolean transientState;

        /**
         * Creates an encoded state.
         *
         * @param name           state name
         * @param originalIndex  original state index, or -1 for transient
         * @param code           binary state code
         * @param transientState true if introduced as a transient routing state
         */
        public EncodedState(String name, int originalIndex, int code, boolean transientState) {
            this.name = name;
            this.originalIndex = originalIndex;
            this.code = code;
            this.transientState = transientState;
        }

        /**
         * @return state name
         */
        public String getName() {
            return name;
        }

        /**
         * @return original state index
         */
        public int getOriginalIndex() {
            return originalIndex;
        }

        /**
         * @return binary code
         */
        public int getCode() {
            return code;
        }

        /**
         * @return true if transient state
         */
        public boolean isTransientState() {
            return transientState;
        }
    }

    private final TransitionTable sourceTable;
    private int stateBits;
    private final int[] stateCodes;
    private final List<EncodedState> allStates;
    private final List<TransitionEdge> transitions;

    /**
     * Creates an encoder for the given transition table.
     *
     * @param table transition table
     */
    public AsyncStateEncoder(TransitionTable table) {
        this.sourceTable = table;
        this.transitions = new ArrayList<>();
        this.allStates = new ArrayList<>();

        int numStates = table.getStateCount();
        int cols = table.getColumnCount();

        for (int s = 0; s < numStates; s++) {
            for (int c = 0; c < cols; c++) {
                int next = table.getNextState(s, c);
                if (next >= 0 && next < numStates && next != s) {
                    transitions.add(new TransitionEdge(s, next, c));
                }
            }
        }

        int minBits = 1;
        while ((1 << minBits) < Math.max(2, numStates)) {
            minBits++;
        }

        int[] bestAssignment = findBestStateCodes(numStates, minBits);
        int maxDist = computeMaxHammingDistance(bestAssignment);

        if (maxDist > 1 && numStates <= (1 << minBits) - 2) {
            this.stateBits = minBits;
            this.stateCodes = bestAssignment;
        } else if (maxDist > 1) {
            // Use n+1 bits to provide ample transient codes for critical race-free routing
            this.stateBits = minBits + 1;
            this.stateCodes = findBestStateCodes(numStates, this.stateBits);
        } else {
            this.stateBits = minBits;
            this.stateCodes = bestAssignment;
        }

        for (int i = 0; i < numStates; i++) {
            TransitionTable.StateItem si = table.getState(i);
            allStates.add(new EncodedState(si.getName(), i, stateCodes[i], false));
        }
    }

    private static int hammingDistance(int c1, int c2) {
        return Integer.bitCount(c1 ^ c2);
    }

    private int computeMaxHammingDistance(int[] codes) {
        int max = 0;
        for (TransitionEdge t : transitions) {
            int dist = hammingDistance(codes[t.getFromState()], codes[t.getToState()]);
            if (dist > max) {
                max = dist;
            }
        }
        return max;
    }

    private int[] findBestStateCodes(int numStates, int bits) {
        int totalCodes = 1 << bits;
        int[] codes = new int[numStates];
        if (numStates <= 1) {
            codes[0] = 0;
            return codes;
        }

        // Greedy adjacency assignment
        boolean[] used = new boolean[totalCodes];
        codes[0] = 0;
        used[0] = true;

        for (int i = 1; i < numStates; i++) {
            int bestCode = -1;
            int bestCost = Integer.MAX_VALUE;

            for (int c = 0; c < totalCodes; c++) {
                if (used[c]) {
                    continue;
                }
                int cost = 0;
                for (TransitionEdge t : transitions) {
                    if (t.getFromState() == i && t.getToState() < i) {
                        cost += hammingDistance(c, codes[t.getToState()]);
                    } else if (t.getToState() == i && t.getFromState() < i) {
                        cost += hammingDistance(c, codes[t.getFromState()]);
                    }
                }
                if (cost < bestCost) {
                    bestCost = cost;
                    bestCode = c;
                }
            }

            if (bestCode < 0) {
                for (int c = 0; c < totalCodes; c++) {
                    if (!used[c]) {
                        bestCode = c;
                        break;
                    }
                }
            }
            codes[i] = bestCode;
            used[bestCode] = true;
        }
        return codes;
    }

    /**
     * @return number of state bits used
     */
    public int getStateBits() {
        return stateBits;
    }

    /**
     * @param stateIndex state index
     * @return assigned binary code
     */
    public int getStateCode(int stateIndex) {
        return stateCodes[stateIndex];
    }

    /**
     * Generates an SR Excitation TruthTable for the asynchronous state machine.
     * Maps each state bit Q_k to S_k and R_k columns:
     * 0 -> 0: S=0, R=X
     * 0 -> 1: S=1, R=0
     * 1 -> 0: S=0, R=1
     * 1 -> 1: S=X, R=0
     *
     * @return SR excitation truth table
     */
    public TruthTable generateSRExcitationTable() {
        int inputBits = sourceTable.getInputVars().size();
        String prefix = sourceTable.getStateVarPrefix();
        if (prefix == null || prefix.isEmpty()) {
            prefix = "Q";
        }

        // Variables: Q_{bits-1}^n .. Q_0^n, then primary inputs
        ArrayList<Variable> vars = new ArrayList<>();
        ArrayList<String> stateVarNames = new ArrayList<>();
        for (int i = 0; i < stateBits; i++) {
            stateVarNames.add(prefix + "_" + i + "^n");
        }
        for (int i = stateBits - 1; i >= 0; i--) {
            String vName = prefix + "_" + i + "^n";
            vars.add(new Variable(vName));
        }
        for (String iv : sourceTable.getInputVars()) {
            vars.add(new Variable(iv));
        }

        TruthTable tt = new TruthTable(vars);

        // Results: S_{Q_k}, R_{Q_k} for each state bit
        for (int k = stateBits - 1; k >= 0; k--) {
            tt.addResult("S_" + prefix + "_" + k);
            tt.addResult("R_" + prefix + "_" + k);
        }
        // Output variables
        for (String ov : sourceTable.getOutputVars()) {
            tt.addResult(ov);
        }

        // Initialize all rows to Don't Care (2)
        tt.modifyValues(v -> (byte) 2);

        int cols = sourceTable.getColumnCount();
        Map<Integer, Integer> codeToState = new HashMap<>();
        for (int s = 0; s < sourceTable.getStateCount(); s++) {
            codeToState.put(stateCodes[s], s);
        }

        // Handle direct and transient routed transitions
        Set<Integer> usedCodes = new HashSet<>();
        for (int c : stateCodes) {
            usedCodes.add(c);
        }

        // Map for transient routing: (fromCode, inputCol) -> nextCode
        Map<Long, Integer> routingMap = new HashMap<>();
        for (int s = 0; s < sourceTable.getStateCount(); s++) {
            for (int col = 0; col < cols; col++) {
                int next = sourceTable.getNextState(s, col);
                if (next < 0) {
                    continue;
                }
                int curCode = stateCodes[s];
                int nxtCode = stateCodes[next];
                int dist = hammingDistance(curCode, nxtCode);

                if (dist <= 1) {
                    long key = (((long) curCode) << 16) | col;
                    routingMap.put(key, nxtCode);
                } else {
                    int totalCodes = 1 << stateBits;
                    int current = curCode;
                    while (hammingDistance(current, nxtCode) > 1) {
                        int currentDist = hammingDistance(current, nxtCode);
                        int intermediate = -1;
                        for (int cand = 0; cand < totalCodes; cand++) {
                            if (!usedCodes.contains(cand) && hammingDistance(current, cand) == 1 && hammingDistance(cand, nxtCode) == currentDist - 1) {
                                intermediate = cand;
                                break;
                            }
                        }
                        if (intermediate >= 0) {
                            usedCodes.add(intermediate);
                            long kStep = (((long) current) << 16) | col;
                            routingMap.put(kStep, intermediate);
                            current = intermediate;
                        } else {
                            break;
                        }
                    }
                    long kFinal = (((long) current) << 16) | col;
                    routingMap.put(kFinal, nxtCode);
                }
            }
        }

        // Fill excitation table for each valid code and input combination
        for (int curCode : usedCodes) {
            Integer sIdx = codeToState.get(curCode);
            TransitionTable.StateItem stateItem = (sIdx != null) ? sourceTable.getState(sIdx) : null;

            for (int col = 0; col < cols; col++) {
                int row = (curCode << inputBits) | col;
                long rKey = (((long) curCode) << 16) | col;
                Integer targetCode = routingMap.get(rKey);

                if (targetCode != null) {
                    for (int k = 0; k < stateBits; k++) {
                        int qCur = (curCode >> k) & 1;
                        int qNxt = (targetCode >> k) & 1;

                        int sVal;
                        int rVal;
                        if (qCur == 0 && qNxt == 0) {
                            sVal = 0;
                            rVal = 2; // X
                        } else if (qCur == 0 && qNxt == 1) {
                            sVal = 1;
                            rVal = 0;
                        } else if (qCur == 1 && qNxt == 0) {
                            sVal = 0;
                            rVal = 1;
                        } else { // 1 -> 1
                            sVal = 2; // X
                            rVal = 0;
                        }

                        int sCol = vars.size() + (stateBits - 1 - k) * 2;
                        int rCol = sCol + 1;
                        tt.setValue(row, sCol, sVal);
                        tt.setValue(row, rCol, rVal);
                    }
                }

                // Output values
                if (stateItem != null) {
                    for (int oIdx = 0; oIdx < sourceTable.getOutputVars().size(); oIdx++) {
                        String oName = sourceTable.getOutputVars().get(oIdx);
                        Integer val = sourceTable.getTransitionOutput(sIdx, col, oName);
                        if (val == null) {
                            val = stateItem.getOutput(oName);
                        }
                        if (val != null) {
                            tt.setValue(row, vars.size() + stateBits * 2 + oIdx, val);
                        }
                    }
                }
            }
        }

        ModelAnalyserInfo mai = new ModelAnalyserInfo(null);
        if (sourceTable.getStateCount() > 0) {
            int initCode = stateCodes[0];
            for (int b = 0; b < stateBits; b++) {
                String varName = prefix + "_" + b + "^n";
                mai.setSequentialInitValue(varName, (initCode >> b) & 1);
            }
        }
        mai.setStateSignalBitNames(stateVarNames);
        mai.setStateSignalName(prefix);
        tt.setModelAnalyzerInfo(mai);

        return tt;
    }
}
