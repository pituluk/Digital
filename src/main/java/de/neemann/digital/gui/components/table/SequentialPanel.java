/*
 * Copyright (c) 2026 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.table;

import de.neemann.digital.analyse.AsynchronousLatchAnalyzer;
import de.neemann.digital.analyse.AsyncStateEncoder;
import de.neemann.digital.analyse.DetermineJKStateMachine;
import de.neemann.digital.analyse.MinimizerQuineMcCluskey;
import de.neemann.digital.analyse.TransitionTable;
import de.neemann.digital.analyse.TruthTable;
import de.neemann.digital.analyse.expression.Constant;
import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.Not;
import de.neemann.digital.analyse.expression.format.FormatToExpression;
import de.neemann.digital.analyse.expression.modify.ExpressionModifier;
import de.neemann.digital.analyse.expression.modify.NAnd;
import de.neemann.digital.analyse.expression.modify.NOr;
import de.neemann.digital.analyse.quinemc.QuineMcCluskey;
import de.neemann.digital.builder.circuit.CircuitBuilder;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.fsm.FSM;
import de.neemann.digital.fsm.gui.FSMFrame;
import de.neemann.digital.gui.Main;
import de.neemann.digital.gui.components.ShowStringDialog;
import de.neemann.digital.lang.Lang;
import de.neemann.gui.ErrorMessage;
import de.neemann.gui.ToolTipAction;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Dedicated tab panel for sequential circuits and asynchronous latches synthesis.
 */
public class SequentialPanel extends JPanel {

    private final TableDialog parentTableDialog;
    private final ElementLibrary library;
    private TransitionTable transitionTable;
    private final TransitionTableModel tableModel;
    private final JTable table;
    private final JComboBox<String> modeCombo;
    private final JComboBox<String> patternCombo;
    private JCheckBox asyncCheckBox;
    private JCheckBox hazardFreeCheckBox;
    private JSpinner bitsSpinner;
    private JLabel presetDescription;

    /**
     * Creates a new sequential circuit panel.
     *
     * @param parent  parent TableDialog
     * @param library element library
     */
    public SequentialPanel(TableDialog parent, ElementLibrary library) {
        super(new BorderLayout());
        this.parentTableDialog = parent;
        this.library = library;

        // Default to SR latch with NOR gates (Exercise 2 Task 1 preset)
        TruthTable defaultTT = SequentialDialog.buildTruthTable(1, "Q", SequentialDialog.PatternType.SR_LATCH_NOR, null);
        this.transitionTable = TransitionTable.fromTruthTable(defaultTT);
        this.transitionTable.setAsync(true);

        this.tableModel = new TransitionTableModel();
        this.table = new JTable(tableModel);
        this.table.setRowHeight(28);
        this.table.getTableHeader().setReorderingAllowed(false);
        this.table.setDefaultRenderer(Object.class, new TransitionCellRenderer());
        this.table.setDefaultEditor(Object.class, new TransitionCellEditor());

        // Top Control Panel
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));

        JPanel configRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        configRow.add(new JLabel(Lang.get("menu_table_seq_mode") + ":"));
        modeCombo = new JComboBox<>(new String[]{
                Lang.get("menu_table_seq_mode_async_feedback"),
                Lang.get("menu_table_seq_mode_async_rs"),
                Lang.get("menu_table_async_race_free"),
                Lang.get("menu_table_seq_mode_sync_d"),
                Lang.get("menu_table_seq_mode_sync_jk")
        });
        configRow.add(modeCombo);

        hazardFreeCheckBox = new JCheckBox(Lang.get("menu_table_hazard_free"), true);
        hazardFreeCheckBox.setToolTipText(Lang.get("menu_table_hazard_free_tt"));
        configRow.add(hazardFreeCheckBox);

        configRow.add(new JLabel(Lang.get("menu_table_seq_pattern") + ":"));
        patternCombo = new JComboBox<>(new String[]{
                Lang.get("menu_table_pattern_sr_nor"),
                Lang.get("menu_table_pattern_sr_nand"),
                Lang.get("menu_table_pattern_gray"),
                Lang.get("menu_table_pattern_gray_bidir"),
                Lang.get("menu_table_pattern_bin_up"),
                Lang.get("menu_table_pattern_bin_down"),
                Lang.get("menu_table_pattern_bin_bidir"),
                Lang.get("menu_table_pattern_ring"),
                Lang.get("menu_table_pattern_johnson"),
                Lang.get("menu_table_pattern_custom")
        });
        configRow.add(patternCombo);

        configRow.add(new JLabel(Lang.get("menu_table_seq_bits") + ":"));
        bitsSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 8, 1));
        bitsSpinner.setToolTipText(Lang.get("menu_table_seq_bits_tt"));
        configRow.add(bitsSpinner);

        JButton btnLoadPreset = new JButton(new ToolTipAction(Lang.get("btn_load_preset")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                loadSelectedPreset();
            }
        });
        configRow.add(btnLoadPreset);

        JButton btnHelp = new JButton(new ToolTipAction(Lang.get("btn_sequential_help")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                showHelpDialog();
            }
        });
        configRow.add(btnHelp);

        topPanel.add(configRow);

        // Preset description row
        presetDescription = new JLabel(" ");
        presetDescription.setFont(presetDescription.getFont().deriveFont(Font.ITALIC, 11f));
        presetDescription.setBorder(BorderFactory.createEmptyBorder(0, 8, 2, 8));
        topPanel.add(presetDescription);

        patternCombo.addActionListener(e -> {
            int pIdx = patternCombo.getSelectedIndex();
            bitsSpinner.setEnabled(pIdx >= 2);
            updatePresetDescription();
        });
        bitsSpinner.setEnabled(patternCombo.getSelectedIndex() >= 2);
        updatePresetDescription();

        topPanel.add(createEditToolBar());

        // Bottom Action Bar
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));

        JButton btnGraph = new JButton(new ToolTipAction(Lang.get("menu_table_stateGraph")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                openStateGraph();
            }
        });
        bottomPanel.add(btnGraph);

        JButton btnPreview = new JButton(new ToolTipAction(Lang.get("btn_seq_preview_equations")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                showEquationsPreview();
            }
        }.setToolTip(Lang.get("btn_seq_preview_equations_tt")));
        bottomPanel.add(btnPreview);

        JButton btnSyncTruthTable = new JButton(new ToolTipAction(Lang.get("btn_sync_truth_table")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                syncToTruthTable();
            }
        });
        bottomPanel.add(btnSyncTruthTable);

        JButton btnCreateCircuit = new JButton(new ToolTipAction(Lang.get("menu_table_createCircuit")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                createCircuit();
            }
        });
        btnCreateCircuit.setFont(btnCreateCircuit.getFont().deriveFont(Font.BOLD));
        bottomPanel.add(btnCreateCircuit);

        add(topPanel, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private JToolBar createEditToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);

        asyncCheckBox = new JCheckBox(Lang.get("menu_table_async_mode"), transitionTable.isAsync());
        asyncCheckBox.setToolTipText(Lang.get("menu_table_async_mode_tt"));
        asyncCheckBox.addActionListener(e -> {
            transitionTable.setAsync(asyncCheckBox.isSelected());
            tableModel.fireTableDataChanged();
        });
        toolBar.add(asyncCheckBox);
        toolBar.addSeparator();

        toolBar.add(new ToolTipAction(Lang.get("menu_table_add_state")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                String name = JOptionPane.showInputDialog(SequentialPanel.this,
                        Lang.get("msg_enter_state_name"), "S" + transitionTable.getStateCount());
                if (name != null && name.trim().length() > 0) {
                    transitionTable.addState(name.trim());
                    tableModel.fireTableStructureChanged();
                }
            }
        }.createJButton());

        toolBar.add(new ToolTipAction(Lang.get("menu_table_remove_state")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                int row = table.getSelectedRow();
                if (row >= 0 && row < transitionTable.getStateCount()) {
                    transitionTable.removeState(row);
                    tableModel.fireTableStructureChanged();
                }
            }
        }.createJButton());

        toolBar.addSeparator();
        addInputOutputActions(toolBar);

        return toolBar;
    }

    private void addInputOutputActions(JToolBar toolBar) {
        toolBar.add(new ToolTipAction(Lang.get("menu_table_add_input")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                String name = JOptionPane.showInputDialog(SequentialPanel.this,
                        Lang.get("msg_enter_input_name"), "X" + transitionTable.getInputVars().size());
                if (name != null && name.trim().length() > 0) {
                    transitionTable.addInputVar(name.trim());
                    tableModel.fireTableStructureChanged();
                }
            }
        }.createJButton());

        toolBar.add(new ToolTipAction(Lang.get("menu_table_remove_input")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!transitionTable.getInputVars().isEmpty()) {
                    transitionTable.removeInputVar(transitionTable.getInputVars().size() - 1);
                    tableModel.fireTableStructureChanged();
                }
            }
        }.createJButton());

        toolBar.addSeparator();

        toolBar.add(new ToolTipAction(Lang.get("menu_table_add_output")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                String name = JOptionPane.showInputDialog(SequentialPanel.this,
                        Lang.get("msg_enter_output_name"), "Z" + transitionTable.getOutputVars().size());
                if (name != null && name.trim().length() > 0) {
                    transitionTable.addOutputVar(name.trim());
                    tableModel.fireTableStructureChanged();
                }
            }
        }.createJButton());

        toolBar.add(new ToolTipAction(Lang.get("menu_table_remove_output")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!transitionTable.getOutputVars().isEmpty()) {
                    transitionTable.removeOutputVar(transitionTable.getOutputVars().size() - 1);
                    tableModel.fireTableStructureChanged();
                }
            }
        }.createJButton());
    }

    private void loadSelectedPreset() {
        int idx = patternCombo.getSelectedIndex();
        SequentialDialog.PatternType pattern;
        switch (idx) {
            case 0:
                pattern = SequentialDialog.PatternType.SR_LATCH_NOR;
                modeCombo.setSelectedIndex(1); // Async RS Latch
                break;
            case 1:
                pattern = SequentialDialog.PatternType.SR_LATCH_NAND;
                modeCombo.setSelectedIndex(1); // Async RS Latch
                break;
            case 2:
                pattern = SequentialDialog.PatternType.GRAY_CODE;
                if (modeCombo.getSelectedIndex() < 3) {
                    modeCombo.setSelectedIndex(3);
                }
                break;
            case 3:
                pattern = SequentialDialog.PatternType.GRAY_CODE_BIDIR;
                if (modeCombo.getSelectedIndex() < 3) {
                    modeCombo.setSelectedIndex(3);
                }
                break;
            case 4:
                pattern = SequentialDialog.PatternType.BINARY_UP;
                if (modeCombo.getSelectedIndex() < 3) {
                    modeCombo.setSelectedIndex(3);
                }
                break;
            case 5:
                pattern = SequentialDialog.PatternType.BINARY_DOWN;
                if (modeCombo.getSelectedIndex() < 3) {
                    modeCombo.setSelectedIndex(3);
                }
                break;
            case 6:
                pattern = SequentialDialog.PatternType.BINARY_UP_DOWN;
                if (modeCombo.getSelectedIndex() < 3) {
                    modeCombo.setSelectedIndex(3);
                }
                break;
            case 7:
                pattern = SequentialDialog.PatternType.RING;
                if (modeCombo.getSelectedIndex() < 3) {
                    modeCombo.setSelectedIndex(3);
                }
                break;
            case 8:
                pattern = SequentialDialog.PatternType.JOHNSON;
                if (modeCombo.getSelectedIndex() < 3) {
                    modeCombo.setSelectedIndex(3);
                }
                break;
            default:
                pattern = SequentialDialog.PatternType.CUSTOM;
                break;
        }

        int bits;
        if (pattern == SequentialDialog.PatternType.SR_LATCH_NOR
                || pattern == SequentialDialog.PatternType.SR_LATCH_NAND) {
            bits = 1;
        } else {
            bits = (Integer) bitsSpinner.getValue();
        }
        TruthTable tt = SequentialDialog.buildTruthTable(bits, "Q", pattern, null);
        updateFromTruthTable(tt);
    }

    private void updatePresetDescription() {
        int idx = patternCombo.getSelectedIndex();
        String desc;
        switch (idx) {
            case 0:
                desc = Lang.get("menu_table_pattern_sr_nor_desc");
                break;
            case 1:
                desc = Lang.get("menu_table_pattern_sr_nand_desc");
                break;
            case 2:
                desc = Lang.get("menu_table_pattern_gray_desc");
                break;
            case 3:
                desc = Lang.get("menu_table_pattern_gray_bidir_desc");
                break;
            case 4:
                desc = Lang.get("menu_table_pattern_bin_up_desc");
                break;
            case 5:
                desc = Lang.get("menu_table_pattern_bin_down_desc");
                break;
            case 6:
                desc = Lang.get("menu_table_pattern_bin_bidir_desc");
                break;
            case 7:
                desc = Lang.get("menu_table_pattern_ring_desc");
                break;
            case 8:
                desc = Lang.get("menu_table_pattern_johnson_desc");
                break;
            default:
                desc = Lang.get("menu_table_pattern_custom_desc");
                break;
        }
        presetDescription.setText("<html><b>" + Lang.get("menu_table_seq_pattern") + ":</b> " + desc + "</html>");
    }

    /**
     * Updates the transition table from a given TruthTable.
     *
     * @param tt TruthTable to read
     */
    public void updateFromTruthTable(TruthTable tt) {
        if (tt == null) {
            return;
        }
        this.transitionTable = TransitionTable.fromTruthTable(tt);
        boolean isAsync = modeCombo.getSelectedIndex() < 3;
        this.transitionTable.setAsync(isAsync);
        this.asyncCheckBox.setSelected(isAsync);
        this.tableModel.fireTableStructureChanged();
    }

    /**
     * Synchronizes this transition table into the parent TableDialog's truth table.
     */
    public void syncToTruthTable() {
        try {
            TruthTable tt = transitionTable.toTruthTable();
            if (parentTableDialog != null) {
                parentTableDialog.setTruthTable(tt);
                parentTableDialog.getTabs().setSelectedIndex(0);
            }
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_errorDuringCalculation")).addCause(e).show(this);
        }
    }

    private void openStateGraph() {
        try {
            FSM fsm = transitionTable.toFSM();
            FSMFrame frame = new FSMFrame(SwingUtilities.getWindowAncestor(this), library);
            frame.setFSM(fsm);
            frame.setParentTableDialog(parentTableDialog);
            frame.setVisible(true);
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_fsmCantCreateTable")).addCause(e).show(this);
        }
    }

    private void createCircuit() {
        int mIdx = modeCombo.getSelectedIndex();
        if (mIdx == 2) {
            createRaceFreeCircuit();
            return;
        }

        try {
            boolean isHazardFree = hazardFreeCheckBox != null && hazardFreeCheckBox.isSelected();
            boolean useJK = (mIdx == 4);
            TruthTable actualTable = transitionTable.toTruthTable();
            ExpressionListenerStore circuitExpressions = new ExpressionListenerStore(null);
            for (int i = 0; i < actualTable.getResultCount(); i++) {
                String rName = actualTable.getResultName(i);
                new MinimizerQuineMcCluskey()
                        .setHazardFree(isHazardFree)
                        .minimize(actualTable.getVars(), actualTable.getResult(i), rName, circuitExpressions);
            }
            circuitExpressions.close();

            ShapeFactory shapeFactory = new ShapeFactory(library != null ? library : new de.neemann.digital.draw.library.ElementLibrary());
            CircuitBuilder.AsyncMode mode;
            if (mIdx == 0) {
                mode = CircuitBuilder.AsyncMode.FEEDBACK;
            } else if (mIdx == 1) {
                mode = CircuitBuilder.AsyncMode.RS_LATCH;
            } else {
                mode = CircuitBuilder.AsyncMode.NONE;
            }

            CircuitBuilder circuitBuilder = new CircuitBuilder(shapeFactory, actualTable.getVars())
                    .setAsyncMode(mode)
                    .setUseJK(useJK)
                    .setForceJK(useJK)
                    .setHazardFree(isHazardFree)
                    .setResolveLocalVars(mode == CircuitBuilder.AsyncMode.FEEDBACK)
                    .setPreserveSharedOutputPins(mode == CircuitBuilder.AsyncMode.FEEDBACK)
                    .setModelAnalyzerInfo(actualTable.getModelAnalyzerInfo())
                    .setCircuitAnnotation(buildCircuitAnnotation(mIdx, isHazardFree));

            ExpressionModifier modifier = ExpressionModifier.IDENTITY;
            int pIdx = patternCombo.getSelectedIndex();
            if (pIdx == 0 && mode == CircuitBuilder.AsyncMode.FEEDBACK) {
                modifier = new NOr();
            } else if (pIdx == 1 && mode == CircuitBuilder.AsyncMode.FEEDBACK) {
                modifier = new NAnd();
            }

            new BuilderExpressionCreator(circuitBuilder, modifier)
                    .setUseJKOptimizer(useJK)
                    .create(circuitExpressions);

            Circuit circuit = circuitBuilder.createCircuit();
            new Main.MainBuilder()
                    .setParent(SwingUtilities.getWindowAncestor(this))
                    .setLibrary(library)
                    .setCircuit(circuit)
                    .openLater();
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_errorDuringCalculation")).addCause(e).show(this);
        }
    }

    private void createRaceFreeCircuit() {
        try {
            AsyncStateEncoder encoder = new AsyncStateEncoder(transitionTable);
            TruthTable srTable = encoder.generateSRExcitationTable();

            boolean isHazardFree = hazardFreeCheckBox == null || hazardFreeCheckBox.isSelected();
            ExpressionListenerStore circuitExpressions = new ExpressionListenerStore(null);
            for (int i = 0; i < srTable.getResultCount(); i++) {
                String rName = srTable.getResultName(i);
                new MinimizerQuineMcCluskey()
                        .setHazardFree(isHazardFree)
                        .minimize(srTable.getVars(), srTable.getResult(i), rName, circuitExpressions);
            }
            circuitExpressions.close();

            ShapeFactory shapeFactory = new ShapeFactory(library != null ? library : new de.neemann.digital.draw.library.ElementLibrary());
            CircuitBuilder circuitBuilder = new CircuitBuilder(shapeFactory, srTable.getVars())
                    .setAsyncMode(CircuitBuilder.AsyncMode.RS_LATCH)
                    .setHazardFree(isHazardFree)
                    .setModelAnalyzerInfo(srTable.getModelAnalyzerInfo())
                    .setCircuitAnnotation(buildCircuitAnnotation(2, isHazardFree));

            int stateBits = encoder.getStateBits();
            String prefix = transitionTable.getStateVarPrefix();
            if (prefix == null || prefix.isEmpty()) {
                prefix = "Q";
            }
            Map<String, Expression> exprMap = new HashMap<>();
            for (ExpressionListenerStore.Result r : circuitExpressions.getResults()) {
                exprMap.put(r.getName(), r.getExpression());
            }

            for (int k = stateBits - 1; k >= 0; k--) {
                String qName = prefix + "_" + k + "^n";
                String sName = "S_" + prefix + "_" + k;
                String rName = "R_" + prefix + "_" + k;
                Expression exprS = exprMap.getOrDefault(sName, Constant.ZERO);
                Expression exprR = exprMap.getOrDefault(rName, Constant.ZERO);
                circuitBuilder.addAsyncRSLatch(qName, exprS, exprR);
            }

            for (String ov : transitionTable.getOutputVars()) {
                Expression exprOut = exprMap.getOrDefault(ov, Constant.ZERO);
                circuitBuilder.addCombinatorial(ov, exprOut);
            }

            Circuit circuit = circuitBuilder.createCircuit();
            new Main.MainBuilder()
                    .setParent(SwingUtilities.getWindowAncestor(this))
                    .setLibrary(library)
                    .setCircuit(circuit)
                    .openLater();
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_errorDuringCalculation")).addCause(e).show(this);
        }
    }

    private String buildCircuitAnnotation(int mIdx, boolean isHazardFree) {
        StringBuilder sb = new StringBuilder();
        int stateBits = transitionTable.getStateBits();
        String prefix = transitionTable.getStateVarPrefix();
        if (prefix == null || prefix.isEmpty()) {
            prefix = "Q";
        }

        switch (mIdx) {
            case 0:
                sb.append("Sequential Circuit: Asynchronous Feedback\n");
                sb.append("Tunnels: Cross-coupled feedback loops\n");
                sb.append("Timing: Continuous-time (unclocked)\n");
                break;
            case 1:
                sb.append("Sequential Circuit: Asynchronous RS Latches\n");
                sb.append("Latches: Unclocked FlipflopRSAsync\n");
                sb.append("Synthesis: Shannon Decomposition\n");
                break;
            case 2:
                sb.append("Sequential Circuit: Critical Race-Free RS Latches\n");
                sb.append("Encoding: Adjacency-Preserving (Hamming dist <= 1)\n");
                sb.append("Latches: Unclocked FlipflopRSAsync\n");
                break;
            case 4:
                sb.append("Sequential Circuit: Synchronous (JK Flip-Flops)\n");
                sb.append("Flip-Flops: FlipflopJK with clock\n");
                sb.append("Clock: Connect Clock to C input\n");
                break;
            default:
                sb.append("Sequential Circuit: Synchronous (D Flip-Flops)\n");
                sb.append("Flip-Flops: FlipflopD with clock\n");
                sb.append("Clock: Connect Clock to C input\n");
                break;
        }

        sb.append("State Bits: ").append(stateBits).append(" (").append(prefix).append(")\n");
        if (mIdx == 2) {
            AsyncStateEncoder enc = new AsyncStateEncoder(transitionTable);
            for (int i = 0; i < transitionTable.getStateCount(); i++) {
                sb.append(transitionTable.getState(i).getName()).append("=").append(Integer.toBinaryString(enc.getStateCode(i))).append(" ");
            }
            sb.append("\n");
        }
        if (isHazardFree) {
            sb.append("Hazard-Free: Enabled (Complete Sum)\n");
        }
        return sb.toString().trim();
    }

    private void showEquationsPreview() {
        try {
            int mIdx = modeCombo.getSelectedIndex();
            boolean isHazardFree = hazardFreeCheckBox != null && hazardFreeCheckBox.isSelected();
            StringBuilder sb = new StringBuilder();
            String modeName;
            switch (mIdx) {
                case 0: modeName = Lang.get("menu_table_seq_mode_async_feedback"); break;
                case 1: modeName = Lang.get("menu_table_seq_mode_async_rs"); break;
                case 2: modeName = Lang.get("menu_table_async_race_free"); break;
                case 4: modeName = Lang.get("menu_table_seq_mode_sync_jk"); break;
                default: modeName = Lang.get("menu_table_seq_mode_sync_d"); break;
            }

            sb.append("==================================================\n");
            sb.append(Lang.get("msg_seq_preview_title")).append("\n");
            sb.append(Lang.get("menu_table_seq_mode")).append(": ").append(modeName).append("\n");
            sb.append(Lang.get("menu_table_hazard_free")).append(": ").append(isHazardFree ? "true" : "false").append("\n");
            sb.append("==================================================\n\n");

            if (mIdx == 2) {
                appendRaceFreePreview(sb, isHazardFree);
            } else {
                appendStandardPreview(sb, mIdx, isHazardFree);
            }

            new ShowStringDialog(
                    SwingUtilities.getWindowAncestor(this),
                    Lang.get("msg_seq_preview_title"),
                    sb.toString(),
                    false
            ).setVisible(true);
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_errorDuringCalculation")).addCause(e).show(this);
        }
    }

    private void appendRaceFreePreview(StringBuilder sb, boolean isHazardFree) throws Exception {
        AsyncStateEncoder encoder = new AsyncStateEncoder(transitionTable);
        sb.append("[").append(Lang.get("msg_seq_state_encoding")).append("]\n");
        for (int i = 0; i < transitionTable.getStateCount(); i++) {
            TransitionTable.StateItem s = transitionTable.getState(i);
            int code = encoder.getStateCode(i);
            String bin = String.format("%" + encoder.getStateBits() + "s", Integer.toBinaryString(code)).replace(' ', '0');
            sb.append(String.format("  %-10s = %s\n", s.getName(), bin));
        }
        sb.append("\n");

        TruthTable srTable = encoder.generateSRExcitationTable();
        ExpressionListenerStore store = new ExpressionListenerStore(null);
        for (int i = 0; i < srTable.getResultCount(); i++) {
            String rName = srTable.getResultName(i);
            new MinimizerQuineMcCluskey()
                    .setHazardFree(isHazardFree)
                    .minimize(srTable.getVars(), srTable.getResult(i), rName, store);
        }
        store.close();

        sb.append("[").append(Lang.get("msg_seq_excitation_equations")).append(" (RS Latches)]\n");
        for (ExpressionListenerStore.Result r : store.getResults()) {
            if (r.getName().startsWith("S_") || r.getName().startsWith("R_")) {
                sb.append(String.format("  %-12s = %s\n", r.getName(), FormatToExpression.defaultFormat(r.getExpression())));
            }
        }

        if (!transitionTable.getOutputVars().isEmpty()) {
            sb.append("\n[").append(Lang.get("msg_seq_output_equations")).append("]\n");
            for (ExpressionListenerStore.Result r : store.getResults()) {
                if (!r.getName().startsWith("S_") && !r.getName().startsWith("R_")) {
                    sb.append(String.format("  %-12s = %s\n", r.getName(), FormatToExpression.defaultFormat(r.getExpression())));
                }
            }
        }
    }

    private void appendStandardPreview(StringBuilder sb, int mIdx, boolean isHazardFree) throws Exception {
        TruthTable actualTable = transitionTable.toTruthTable();
        ExpressionListenerStore store = new ExpressionListenerStore(null);
        for (int i = 0; i < actualTable.getResultCount(); i++) {
            String rName = actualTable.getResultName(i);
            new MinimizerQuineMcCluskey()
                    .setHazardFree(isHazardFree)
                    .minimize(actualTable.getVars(), actualTable.getResult(i), rName, store);
        }
        store.close();

        sb.append("[").append(Lang.get("msg_seq_state_encoding")).append("]\n");
        int stateBits = transitionTable.getStateBits();
        for (int i = 0; i < transitionTable.getStateCount(); i++) {
            TransitionTable.StateItem s = transitionTable.getState(i);
            String bin = String.format("%" + stateBits + "s", Integer.toBinaryString(s.getCode())).replace(' ', '0');
            sb.append(String.format("  %-10s = %s\n", s.getName(), bin));
        }
        sb.append("\n");

        Map<String, Expression> exprMap = new HashMap<>();
        for (ExpressionListenerStore.Result r : store.getResults()) {
            exprMap.put(r.getName(), r.getExpression());
        }

        if (mIdx == 4) {
            sb.append("[").append(Lang.get("msg_seq_excitation_equations")).append(" (JK Flip-Flops)]\n");
            for (int k = stateBits - 1; k >= 0; k--) {
                String nextName = transitionTable.getStateVarPrefix() + "_" + k + "^{n+1}";
                String curName = transitionTable.getStateVarPrefix() + "_" + k + "^n";
                Expression expr = exprMap.get(nextName);
                if (expr != null) {
                    DetermineJKStateMachine jk = new DetermineJKStateMachine(curName, expr);
                    sb.append(String.format("  J_%-10s = %s\n", curName, FormatToExpression.defaultFormat(jk.getSimplifiedJ())));
                    sb.append(String.format("  K_%-10s = %s\n", curName, FormatToExpression.defaultFormat(jk.getSimplifiedK())));
                }
            }
        } else if (mIdx == 1) {
            sb.append("[").append(Lang.get("msg_seq_excitation_equations")).append(" (RS Latches)]\n");
            for (int k = stateBits - 1; k >= 0; k--) {
                String nextName = transitionTable.getStateVarPrefix() + "_" + k + "^{n+1}";
                String curName = transitionTable.getStateVarPrefix() + "_" + k + "^n";
                Expression expr = exprMap.get(nextName);
                if (expr != null) {
                    Expression exprS = AsynchronousLatchAnalyzer.substitute(expr, curName, Constant.ZERO);
                    Expression f1 = AsynchronousLatchAnalyzer.substitute(expr, curName, Constant.ONE);
                    Expression exprR = Not.not(f1);
                    if (isHazardFree) {
                        exprS = new QuineMcCluskey(exprS).setHazardFree(true).simplify().getExpression();
                        exprR = new QuineMcCluskey(exprR).setHazardFree(true).simplify().getExpression();
                    } else {
                        exprS = QuineMcCluskey.simplify(exprS);
                        exprR = QuineMcCluskey.simplify(exprR);
                    }
                    sb.append(String.format("  S_%-10s = %s\n", curName, FormatToExpression.defaultFormat(exprS)));
                    sb.append(String.format("  R_%-10s = %s\n", curName, FormatToExpression.defaultFormat(exprR)));
                }
            }
        } else if (mIdx == 0) {
            sb.append("[").append(Lang.get("msg_seq_excitation_equations")).append(" (Feedback Loops)]\n");
            for (int k = stateBits - 1; k >= 0; k--) {
                String nextName = transitionTable.getStateVarPrefix() + "_" + k + "^{n+1}";
                String curName = transitionTable.getStateVarPrefix() + "_" + k + "^n";
                Expression expr = exprMap.get(nextName);
                if (expr != null) {
                    sb.append(String.format("  %-12s = %s\n", curName, FormatToExpression.defaultFormat(expr)));
                }
            }
        } else {
            sb.append("[").append(Lang.get("msg_seq_excitation_equations")).append(" (D Flip-Flops)]\n");
            for (int k = stateBits - 1; k >= 0; k--) {
                String nextName = transitionTable.getStateVarPrefix() + "_" + k + "^{n+1}";
                String curName = transitionTable.getStateVarPrefix() + "_" + k + "^n";
                Expression expr = exprMap.get(nextName);
                if (expr != null) {
                    sb.append(String.format("  D_%-10s = %s\n", curName, FormatToExpression.defaultFormat(expr)));
                }
            }
        }

        if (!transitionTable.getOutputVars().isEmpty()) {
            sb.append("\n[").append(Lang.get("msg_seq_output_equations")).append("]\n");
            for (String ov : transitionTable.getOutputVars()) {
                Expression expr = exprMap.get(ov);
                if (expr != null) {
                    sb.append(String.format("  %-12s = %s\n", ov, FormatToExpression.defaultFormat(expr)));
                }
            }
        }
    }

    /**
     * Shows comprehensive sequential circuits and latches documentation.
     */
    public void showHelpDialog() {
        new ShowStringDialog(
                SwingUtilities.getWindowAncestor(this),
                Lang.get("msg_sequential_help_title"),
                Lang.get("msg_sequential_help"),
                true
        ).setVisible(true);
    }

    /**
     * @return the underlying transition table
     */
    public TransitionTable getTransitionTable() {
        return transitionTable;
    }

    private class TransitionTableModel extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return transitionTable.getStateCount();
        }

        @Override
        public int getColumnCount() {
            return 1 + transitionTable.getOutputVars().size() + transitionTable.getColumnCount();
        }

        @Override
        public String getColumnName(int columnIndex) {
            if (columnIndex == 0) {
                return Lang.get("msg_fsmState");
            }
            int outCount = transitionTable.getOutputVars().size();
            if (columnIndex <= outCount) {
                return transitionTable.getOutputVars().get(columnIndex - 1);
            }
            int inCol = columnIndex - 1 - outCount;
            return transitionTable.getColumnHeader(inCol);
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex >= transitionTable.getStateCount()) {
                return "";
            }
            TransitionTable.StateItem state = transitionTable.getState(rowIndex);
            if (columnIndex == 0) {
                return state.getName() + " (" + state.getCode() + ")";
            }
            int outCount = transitionTable.getOutputVars().size();
            if (columnIndex <= outCount) {
                String outName = transitionTable.getOutputVars().get(columnIndex - 1);
                Integer val = state.getOutput(outName);
                return val == null ? "-" : val.toString();
            }
            int inCol = columnIndex - 1 - outCount;
            int nextIdx = transitionTable.getNextState(rowIndex, inCol);
            if (nextIdx < 0 || nextIdx >= transitionTable.getStateCount()) {
                return "-";
            }
            String targetName = transitionTable.getState(nextIdx).getName();
            if (transitionTable.isAsync() && transitionTable.isStable(rowIndex, inCol)) {
                return "[" + targetName + "]";
            }
            return targetName;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return true;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (rowIndex >= transitionTable.getStateCount() || aValue == null) {
                return;
            }
            String valStr = aValue.toString().trim();
            TransitionTable.StateItem state = transitionTable.getState(rowIndex);

            if (columnIndex == 0) {
                int paren = valStr.indexOf('(');
                if (paren > 0) {
                    valStr = valStr.substring(0, paren).trim();
                }
                state.setName(valStr);
                fireTableCellUpdated(rowIndex, columnIndex);
                return;
            }

            int outCount = transitionTable.getOutputVars().size();
            if (columnIndex <= outCount) {
                String outName = transitionTable.getOutputVars().get(columnIndex - 1);
                if (valStr.equals("-") || valStr.equalsIgnoreCase("x") || valStr.isEmpty()) {
                    state.getOutputs().remove(outName);
                } else if (valStr.equalsIgnoreCase("true") || valStr.equalsIgnoreCase("t") || valStr.equalsIgnoreCase("h") || valStr.equals("1")) {
                    state.setOutput(outName, 1);
                } else if (valStr.equalsIgnoreCase("false") || valStr.equalsIgnoreCase("f") || valStr.equalsIgnoreCase("l") || valStr.equals("0")) {
                    state.setOutput(outName, 0);
                } else {
                    try {
                        int num = Integer.parseInt(valStr);
                        state.setOutput(outName, num);
                    } catch (NumberFormatException ignored) {
                    }
                }
                fireTableCellUpdated(rowIndex, columnIndex);
                return;
            }

            int inCol = columnIndex - 1 - outCount;
            if (valStr.startsWith("[") && valStr.endsWith("]")) {
                valStr = valStr.substring(1, valStr.length() - 1).trim();
            } else if (valStr.startsWith("(") && valStr.endsWith(")")) {
                valStr = valStr.substring(1, valStr.length() - 1).trim();
            }

            if (valStr.equals("-") || valStr.isEmpty()) {
                transitionTable.setNextState(rowIndex, inCol, -1);
            } else {
                int target = -1;
                for (int i = 0; i < transitionTable.getStateCount(); i++) {
                    if (transitionTable.getState(i).getName().equalsIgnoreCase(valStr)) {
                        target = i;
                        break;
                    }
                }
                if (target < 0) {
                    try {
                        int num = Integer.parseInt(valStr);
                        if (num >= 0 && num < transitionTable.getStateCount()) {
                            target = num;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
                if (target >= 0) {
                    transitionTable.setNextState(rowIndex, inCol, target);
                }
            }
            fireTableCellUpdated(rowIndex, columnIndex);
        }
    }

    private class TransitionCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            setHorizontalAlignment(SwingConstants.CENTER);

            int outCount = transitionTable.getOutputVars().size();
            if (column > outCount) {
                int inCol = column - 1 - outCount;
                if (transitionTable.isAsync() && transitionTable.isStable(row, inCol)) {
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                    if (!isSelected) {
                        c.setBackground(new Color(210, 245, 210));
                    }
                } else {
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                    if (!isSelected) {
                        c.setBackground(Color.WHITE);
                    }
                }
            } else {
                c.setFont(c.getFont().deriveFont(Font.PLAIN));
                if (!isSelected) {
                    c.setBackground(new Color(245, 245, 245));
                }
            }
            return c;
        }
    }

    private class TransitionCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final JComboBox<String> stateCombo = new JComboBox<>();
        private final JComboBox<String> outputCombo = new JComboBox<>();
        private final JTextField textField = new JTextField();
        private int editorType; // 0=textField (state name), 1=outputCombo, 2=stateCombo

        TransitionCellEditor() {
            outputCombo.setEditable(true);
            stateCombo.setEditable(true);
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            int outCount = transitionTable.getOutputVars().size();
            String valStr = value == null ? "" : value.toString();

            if (column == 0) {
                // State name: use text field
                editorType = 0;
                int paren = valStr.indexOf('(');
                if (paren > 0) {
                    valStr = valStr.substring(0, paren).trim();
                }
                textField.setText(valStr);
                return textField;
            } else if (column <= outCount) {
                // Output column: use editable combo with 0, 1, -
                editorType = 1;
                outputCombo.removeAllItems();
                outputCombo.addItem("0");
                outputCombo.addItem("1");
                outputCombo.addItem("-");
                outputCombo.setSelectedItem(valStr);
                if (outputCombo.getEditor() != null && outputCombo.getEditor().getEditorComponent() instanceof JTextField) {
                    JTextField tf = (JTextField) outputCombo.getEditor().getEditorComponent();
                    tf.setText(valStr);
                    tf.selectAll();
                }
                return outputCombo;
            } else {
                // Next state column: use combo with state names
                editorType = 2;
                stateCombo.removeAllItems();
                stateCombo.addItem("-");
                for (int i = 0; i < transitionTable.getStateCount(); i++) {
                    stateCombo.addItem(transitionTable.getState(i).getName());
                }
                if (valStr.startsWith("[") && valStr.endsWith("]")) {
                    valStr = valStr.substring(1, valStr.length() - 1).trim();
                } else if (valStr.startsWith("(") && valStr.endsWith(")")) {
                    valStr = valStr.substring(1, valStr.length() - 1).trim();
                }
                stateCombo.setSelectedItem(valStr);
                if (stateCombo.getEditor() != null && stateCombo.getEditor().getEditorComponent() instanceof JTextField) {
                    JTextField tf = (JTextField) stateCombo.getEditor().getEditorComponent();
                    tf.setText(valStr);
                    tf.selectAll();
                }
                return stateCombo;
            }
        }

        @Override
        public Object getCellEditorValue() {
            switch (editorType) {
                case 0:
                    return textField.getText();
                case 1:
                    Object outItem = outputCombo.isEditable() ? outputCombo.getEditor().getItem() : outputCombo.getSelectedItem();
                    return outItem == null ? "" : outItem.toString();
                default:
                    Object stItem = stateCombo.isEditable() ? stateCombo.getEditor().getItem() : stateCombo.getSelectedItem();
                    return stItem == null ? "" : stItem.toString();
            }
        }
    }
}
