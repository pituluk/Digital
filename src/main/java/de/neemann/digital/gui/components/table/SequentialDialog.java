/*
 * Copyright (c) 2026 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.table;

import de.neemann.digital.analyse.ModelAnalyserInfo;
import de.neemann.digital.analyse.TransitionTable;
import de.neemann.digital.analyse.TruthTable;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.expression.modify.ExpressionModifier;
import de.neemann.digital.analyse.quinemc.BoolTableByteArray;
import de.neemann.digital.builder.circuit.CircuitBuilder;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.fsm.FSM;
import de.neemann.digital.fsm.gui.FSMFrame;
import de.neemann.gui.ErrorMessage;
import de.neemann.digital.gui.Main;
import de.neemann.digital.lang.Lang;
import de.neemann.gui.ToolTipAction;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;

/**
 * Generator dialog for synchronous and asynchronous sequential circuits and sequences.
 */
public class SequentialDialog extends JDialog {

    /**
     * Types of supported sequential patterns.
     */
    public enum PatternType {
        /** Binary counter up */
        BINARY_UP,
        /** Binary counter down */
        BINARY_DOWN,
        /** Binary counter up/down with direction variable D */
        BINARY_UP_DOWN,
        /** Gray code counter (single bit transitions, race-free) */
        GRAY_CODE,
        /** Gray code bidirectional counter with direction variable D */
        GRAY_CODE_BIDIR,
        /** Ring counter (one-hot) */
        RING,
        /** Johnson counter (twisted ring) */
        JOHNSON,
        /** Asynchronous SR latch with NOR gates */
        SR_LATCH_NOR,
        /** Asynchronous ~S~R latch with NAND gates */
        SR_LATCH_NAND,
        /** Custom sequence of numbers */
        CUSTOM
    }

    private final TableDialog parentTableDialog;
    private final ElementLibrary library;

    private final JComboBox<String> modeCombo;
    private final JComboBox<String> patternCombo;
    private final JSpinner bitsSpinner;
    private final JTextField prefixField;
    private final JTextField customSeqField;
    private final JPanel customSeqPanel;

    /**
     * Creates a new sequential generator dialog.
     *
     * @param parent  parent TableDialog
     * @param library element library
     */
    public SequentialDialog(TableDialog parent, ElementLibrary library) {
        super(parent, Lang.get("menu_table_sequential_generator"), ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        this.parentTableDialog = parent;
        this.library = library;

        JPanel mainPanel = new JPanel(new GridBagLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Mode
        gbc.gridx = 0;
        gbc.gridy = 0;
        mainPanel.add(new JLabel(Lang.get("menu_table_seq_mode") + ":"), gbc);
        gbc.gridx = 1;
        modeCombo = new JComboBox<>(new String[]{
                Lang.get("menu_table_seq_mode_sync"),
                Lang.get("menu_table_seq_mode_async_feedback"),
                Lang.get("menu_table_seq_mode_async_rs")
        });
        mainPanel.add(modeCombo, gbc);

        // Pattern
        gbc.gridx = 0;
        gbc.gridy = 1;
        mainPanel.add(new JLabel(Lang.get("menu_table_seq_pattern") + ":"), gbc);
        gbc.gridx = 1;
        patternCombo = new JComboBox<>(new String[]{
                Lang.get("menu_table_pattern_bin_up"),
                Lang.get("menu_table_pattern_bin_down"),
                Lang.get("menu_table_pattern_bin_bidir"),
                Lang.get("menu_table_pattern_gray"),
                Lang.get("menu_table_pattern_gray_bidir"),
                Lang.get("menu_table_pattern_ring"),
                Lang.get("menu_table_pattern_johnson"),
                Lang.get("menu_table_pattern_sr_nor"),
                Lang.get("menu_table_pattern_sr_nand"),
                Lang.get("menu_table_pattern_custom")
        });
        mainPanel.add(patternCombo, gbc);

        // Bits count
        gbc.gridx = 0;
        gbc.gridy = 2;
        mainPanel.add(new JLabel(Lang.get("menu_table_seq_bits") + ":"), gbc);
        gbc.gridx = 1;
        bitsSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 8, 1));
        mainPanel.add(bitsSpinner, gbc);

        // Variable prefix
        gbc.gridx = 0;
        gbc.gridy = 3;
        mainPanel.add(new JLabel(Lang.get("menu_table_seq_prefix") + ":"), gbc);
        gbc.gridx = 1;
        prefixField = new JTextField("Q", 5);
        mainPanel.add(prefixField, gbc);

        // Custom sequence
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        customSeqPanel = new JPanel(new BorderLayout(5, 5));
        customSeqPanel.add(new JLabel(Lang.get("menu_table_seq_custom_values") + ":"), BorderLayout.NORTH);
        customSeqField = new JTextField("0, 1, 3, 2, 6, 7, 5, 4", 20);
        customSeqPanel.add(customSeqField, BorderLayout.CENTER);
        customSeqPanel.setVisible(false);
        mainPanel.add(customSeqPanel, gbc);

        patternCombo.addActionListener(e -> {
            PatternType p = getSelectedPattern();
            boolean isCustom = (p == PatternType.CUSTOM);
            boolean isLatch = (p == PatternType.SR_LATCH_NOR || p == PatternType.SR_LATCH_NAND);
            customSeqPanel.setVisible(isCustom);
            if (isLatch) {
                bitsSpinner.setValue(1);
                bitsSpinner.setEnabled(false);
                if (modeCombo.getSelectedIndex() == 0) {
                    modeCombo.setSelectedIndex(1);
                }
            } else {
                bitsSpinner.setEnabled(true);
            }
            pack();
        });

        // Bottom buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton btnGenerate = new JButton(new ToolTipAction(Lang.get("menu_table_generate_tt")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                generateTruthTable();
            }
        });
        buttonPanel.add(btnGenerate);

        JButton btnTransTable = new JButton(new ToolTipAction(Lang.get("menu_table_transitionTable")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                openTransitionTable();
            }
        });
        buttonPanel.add(btnTransTable);

        JButton btnGraph = new JButton(new ToolTipAction(Lang.get("menu_table_stateGraph")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                openStateGraph();
            }
        });
        buttonPanel.add(btnGraph);

        JButton btnCircuit = new JButton(new ToolTipAction(Lang.get("menu_table_createCircuit")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                createCircuitDirectly();
            }
        });
        buttonPanel.add(btnCircuit);

        JButton btnCancel = new JButton(new ToolTipAction(Lang.get("cancel")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        buttonPanel.add(btnCancel);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(mainPanel, BorderLayout.CENTER);
        getContentPane().add(buttonPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(parent);
    }

    private PatternType getSelectedPattern() {
        int idx = patternCombo.getSelectedIndex();
        return PatternType.values()[idx];
    }

    private CircuitBuilder.AsyncMode getSelectedAsyncMode() {
        int idx = modeCombo.getSelectedIndex();
        if (idx == 1) {
            return CircuitBuilder.AsyncMode.FEEDBACK;
        } else if (idx == 2) {
            return CircuitBuilder.AsyncMode.RS_LATCH;
        }
        return CircuitBuilder.AsyncMode.NONE;
    }

    private static int gray(int n) {
        return n ^ (n >> 1);
    }

    private static int inverseGray(int gray) {
        int n = 0;
        for (; gray > 0; gray >>= 1) {
            n ^= gray;
        }
        return n;
    }

    /**
     * Builds the truth table configured in this dialog.
     *
     * @return generated TruthTable
     */
    public TruthTable buildTruthTable() {
        int n = (Integer) bitsSpinner.getValue();
        String prefix = prefixField.getText().trim();
        PatternType pattern = getSelectedPattern();
        return buildTruthTable(n, prefix, pattern, customSeqField.getText());
    }

    /**
     * Builds a sequential truth table for given parameters.
     *
     * @param n state bits count
     * @param prefix variable prefix
     * @param pattern pattern type
     * @param customSeq custom sequence string if pattern is CUSTOM
     * @return generated TruthTable
     */
    public static TruthTable buildTruthTable(int n, String prefix, PatternType pattern, String customSeq) {
        if (prefix == null || prefix.trim().isEmpty()) {
            prefix = "Q";
        } else {
            prefix = prefix.trim();
        }

        if (pattern == PatternType.SR_LATCH_NOR) {
            ArrayList<Variable> vars = new ArrayList<>();
            vars.add(new Variable("S"));
            vars.add(new Variable("R"));
            vars.add(new Variable(prefix + "_0^n"));

            TruthTable truthTable = new TruthTable(vars);
            BoolTableByteArray val = new BoolTableByteArray(8);
            val.set(0, 0); // S=0, R=0, Q=0 -> 0
            val.set(1, 1); // S=0, R=0, Q=1 -> 1
            val.set(2, 0); // S=0, R=1, Q=0 -> 0
            val.set(3, 0); // S=0, R=1, Q=1 -> 0
            val.set(4, 1); // S=1, R=0, Q=0 -> 1
            val.set(5, 1); // S=1, R=0, Q=1 -> 1
            val.set(6, 2); // S=1, R=1, Q=0 -> don't care
            val.set(7, 2); // S=1, R=1, Q=1 -> don't care

            truthTable.addResult(prefix + "_0^{n+1}", val);
            ModelAnalyserInfo mai = new ModelAnalyserInfo(null);
            mai.setSequentialInitValue(prefix + "_0^n", 0);
            ArrayList<String> bitNames = new ArrayList<>();
            bitNames.add(prefix + "_0^n");
            mai.setStateSignalBitNames(bitNames);
            mai.setStateSignalName(prefix);
            truthTable.setModelAnalyzerInfo(mai);
            return truthTable;
        }

        if (pattern == PatternType.SR_LATCH_NAND) {
            ArrayList<Variable> vars = new ArrayList<>();
            vars.add(new Variable("S_n"));
            vars.add(new Variable("R_n"));
            vars.add(new Variable(prefix + "_0^n"));

            TruthTable truthTable = new TruthTable(vars);
            BoolTableByteArray val = new BoolTableByteArray(8);
            val.set(0, 2); // S_n=0, R_n=0, Q=0 -> don't care
            val.set(1, 2); // S_n=0, R_n=0, Q=1 -> don't care
            val.set(2, 1); // S_n=0, R_n=1, Q=0 -> 1 (Set)
            val.set(3, 1); // S_n=0, R_n=1, Q=1 -> 1 (Set)
            val.set(4, 0); // S_n=1, R_n=0, Q=0 -> 0 (Reset)
            val.set(5, 0); // S_n=1, R_n=0, Q=1 -> 0 (Reset)
            val.set(6, 0); // S_n=1, R_n=1, Q=0 -> 0 (Hold)
            val.set(7, 1); // S_n=1, R_n=1, Q=1 -> 1 (Hold)

            truthTable.addResult(prefix + "_0^{n+1}", val);
            ModelAnalyserInfo mai = new ModelAnalyserInfo(null);
            mai.setSequentialInitValue(prefix + "_0^n", 0);
            ArrayList<String> bitNames = new ArrayList<>();
            bitNames.add(prefix + "_0^n");
            mai.setStateSignalBitNames(bitNames);
            mai.setStateSignalName(prefix);
            truthTable.setModelAnalyzerInfo(mai);
            return truthTable;
        }

        boolean isBidir = (pattern == PatternType.BINARY_UP_DOWN || pattern == PatternType.GRAY_CODE_BIDIR);

        ArrayList<Variable> vars = new ArrayList<>();
        if (isBidir) {
            vars.add(new Variable("D"));
        }
        for (int i = n - 1; i >= 0; i--) {
            vars.add(new Variable(prefix + "_" + i + "^n"));
        }

        TruthTable truthTable = new TruthTable(vars);
        int rows = 1 << vars.size();
        int stateCount = 1 << n;

        // Custom sequence parsing
        int[] customNext = null;
        if (pattern == PatternType.CUSTOM) {
            customNext = new int[stateCount];
            for (int i = 0; i < stateCount; i++) {
                customNext[i] = 0;
            }
            try {
                if (customSeq != null) {
                    String[] parts = customSeq.split("[,;\\s]+");
                    ArrayList<Integer> seq = new ArrayList<>();
                    for (String p : parts) {
                        if (!p.trim().isEmpty()) {
                            seq.add(Integer.parseInt(p.trim()) % stateCount);
                        }
                    }
                    if (!seq.isEmpty()) {
                        for (int i = 0; i < seq.size(); i++) {
                            int cur = seq.get(i);
                            int nxt = seq.get((i + 1) % seq.size());
                            customNext[cur] = nxt;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // Calculate next state for each row
        int[] nextStatePerRow = new int[rows];
        for (int row = 0; row < rows; row++) {
            int state = isBidir ? (row & (stateCount - 1)) : row;
            boolean dirDown = isBidir && (row >= stateCount);

            int nextState;
            switch (pattern) {
                case BINARY_UP:
                    nextState = (state + 1) & (stateCount - 1);
                    break;
                case BINARY_DOWN:
                    nextState = (state - 1 + stateCount) & (stateCount - 1);
                    break;
                case BINARY_UP_DOWN:
                    nextState = dirDown ? ((state - 1 + stateCount) & (stateCount - 1)) : ((state + 1) & (stateCount - 1));
                    break;
                case GRAY_CODE:
                    int kGray = inverseGray(state);
                    nextState = gray((kGray + 1) & (stateCount - 1));
                    break;
                case GRAY_CODE_BIDIR:
                    int kBidir = inverseGray(state);
                    int nextKBidir = dirDown ? ((kBidir - 1 + stateCount) & (stateCount - 1)) : ((kBidir + 1) & (stateCount - 1));
                    nextState = gray(nextKBidir);
                    break;
                case RING:
                    nextState = (state << 1) & (stateCount - 1);
                    if (nextState == 0) {
                        nextState = 1;
                    }
                    break;
                case JOHNSON:
                    int msb = (state >> (n - 1)) & 1;
                    nextState = ((state << 1) | (1 - msb)) & (stateCount - 1);
                    break;
                case CUSTOM:
                    nextState = customNext != null ? customNext[state] : 0;
                    break;
                default:
                    nextState = 0;
            }
            nextStatePerRow[row] = nextState;
        }

        // Add next state result columns
        for (int i = n - 1; i >= 0; i--) {
            BoolTableByteArray val = new BoolTableByteArray(rows);
            for (int row = 0; row < rows; row++) {
                int bit = (nextStatePerRow[row] >> i) & 1;
                val.set(row, bit);
            }
            truthTable.addResult(prefix + "_" + i + "^{n+1}", val);
        }

        ModelAnalyserInfo mai = new ModelAnalyserInfo(null);
        ArrayList<String> bitNames = new ArrayList<>();
        // Ring and Johnson counters must start at state 1 (one-hot bit 0 high),
        // since state 0 is invalid for these patterns.
        int initState = (pattern == PatternType.RING || pattern == PatternType.JOHNSON) ? 1 : 0;
        for (int i = 0; i < n; i++) {
            mai.setSequentialInitValue(prefix + "_" + i + "^n", (initState >> i) & 1);
            bitNames.add(prefix + "_" + i + "^n");
        }
        mai.setStateSignalBitNames(bitNames);
        mai.setStateSignalName(prefix);
        truthTable.setModelAnalyzerInfo(mai);

        return truthTable;
    }

    private void generateTruthTable() {
        try {
            TruthTable tt = buildTruthTable();
            if (parentTableDialog != null) {
                parentTableDialog.setTruthTable(tt);
                parentTableDialog.toFront();
            } else {
                new TableDialog(this, tt, library, null).setVisible(true);
            }
            dispose();
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_errorDuringCalculation")).addCause(e).show(this);
        }
    }

    private void openTransitionTable() {
        try {
            TruthTable tt = buildTruthTable();
            TransitionTable transTable = TransitionTable.fromTruthTable(tt);
            if (getSelectedAsyncMode() != CircuitBuilder.AsyncMode.NONE) {
                transTable.setAsync(true);
            }
            new TransitionTableDialog(this, transTable, library, parentTableDialog).setVisible(true);
            dispose();
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_errorDuringCalculation")).addCause(e).show(this);
        }
    }

    private void openStateGraph() {
        try {
            TruthTable tt = buildTruthTable();
            TransitionTable transTable = TransitionTable.fromTruthTable(tt);
            FSM fsm = transTable.toFSM();
            new FSMFrame(this, library).setFSM(fsm).setVisible(true);
            dispose();
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_fsmCantCreateTable")).addCause(e).show(this);
        }
    }

    private void createCircuitDirectly() {
        try {
            TruthTable actualTable = buildTruthTable();
            ExpressionListenerStore circuitExpressions = new ExpressionListenerStore(null);
            for (int i = 0; i < actualTable.getResultCount(); i++) {
                String rName = actualTable.getResultName(i);
                new de.neemann.digital.analyse.MinimizerQuineMcCluskey()
                        .minimize(actualTable.getVars(), actualTable.getResult(i), rName, circuitExpressions);
            }
            circuitExpressions.close();

            ShapeFactory shapeFactory = new ShapeFactory(library != null ? library : new de.neemann.digital.draw.library.ElementLibrary());
            CircuitBuilder.AsyncMode mode = getSelectedAsyncMode();

            CircuitBuilder circuitBuilder = new CircuitBuilder(shapeFactory, actualTable.getVars())
                    .setAsyncMode(mode)
                    .setResolveLocalVars(mode == CircuitBuilder.AsyncMode.FEEDBACK)
                    .setPreserveSharedOutputPins(mode == CircuitBuilder.AsyncMode.FEEDBACK)
                    .setModelAnalyzerInfo(actualTable.getModelAnalyzerInfo())
                    .setCircuitAnnotation("Sequential Circuit: " + patternCombo.getSelectedItem()
                            + "\nMode: " + modeCombo.getSelectedItem());

            ExpressionModifier modifier = ExpressionModifier.IDENTITY;
            PatternType p = getSelectedPattern();
            if (p == PatternType.SR_LATCH_NOR) {
                modifier = new de.neemann.digital.analyse.expression.modify.NOr();
            } else if (p == PatternType.SR_LATCH_NAND) {
                modifier = new de.neemann.digital.analyse.expression.modify.NAnd();
            }

            new BuilderExpressionCreator(circuitBuilder, modifier)
                    .create(circuitExpressions);

            Circuit circuit = circuitBuilder.createCircuit();
            new Main.MainBuilder()
                    .setParent(this)
                    .setLibrary(library)
                    .setCircuit(circuit)
                    .openLater();
            dispose();
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_errorDuringCalculation")).addCause(e).show(this);
        }
    }
}
