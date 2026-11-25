/*
 * Copyright (c) 2026 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.table;

import de.neemann.digital.analyse.AsyncStateEncoder;
import de.neemann.digital.analyse.MinimizerQuineMcCluskey;
import de.neemann.digital.analyse.TransitionTable;
import de.neemann.digital.analyse.TruthTable;
import de.neemann.digital.analyse.expression.Constant;
import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.modify.ExpressionModifier;
import de.neemann.digital.analyse.expression.modify.NAnd;
import de.neemann.digital.analyse.expression.modify.NOr;
import de.neemann.digital.builder.circuit.CircuitBuilder;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.fsm.FSM;
import de.neemann.digital.fsm.gui.FSMFrame;
import de.neemann.digital.gui.Main;
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
                Lang.get("menu_table_seq_mode_sync")
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

        patternCombo.addActionListener(e -> updatePresetDescription());
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
                modeCombo.setSelectedIndex(0); // Async feedback
                break;
            case 1:
                pattern = SequentialDialog.PatternType.SR_LATCH_NAND;
                modeCombo.setSelectedIndex(0); // Async feedback
                break;
            case 2:
                pattern = SequentialDialog.PatternType.GRAY_CODE;
                break;
            case 3:
                pattern = SequentialDialog.PatternType.GRAY_CODE_BIDIR;
                break;
            case 4:
                pattern = SequentialDialog.PatternType.BINARY_UP;
                break;
            case 5:
                pattern = SequentialDialog.PatternType.BINARY_DOWN;
                break;
            case 6:
                pattern = SequentialDialog.PatternType.BINARY_UP_DOWN;
                break;
            case 7:
                pattern = SequentialDialog.PatternType.RING;
                break;
            case 8:
                pattern = SequentialDialog.PatternType.JOHNSON;
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
        presetDescription.setText(desc);
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
                    .setHazardFree(isHazardFree)
                    .setResolveLocalVars(mode == CircuitBuilder.AsyncMode.FEEDBACK)
                    .setPreserveSharedOutputPins(mode == CircuitBuilder.AsyncMode.FEEDBACK)
                    .setModelAnalyzerInfo(actualTable.getModelAnalyzerInfo());

            ExpressionModifier modifier = ExpressionModifier.IDENTITY;
            int pIdx = patternCombo.getSelectedIndex();
            if (pIdx == 0 && mode == CircuitBuilder.AsyncMode.FEEDBACK) {
                modifier = new NOr();
            } else if (pIdx == 1 && mode == CircuitBuilder.AsyncMode.FEEDBACK) {
                modifier = new NAnd();
            }

            new BuilderExpressionCreator(circuitBuilder, modifier)
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
                    .setAsyncMode(CircuitBuilder.AsyncMode.NONE)
                    .setHazardFree(isHazardFree)
                    .setModelAnalyzerInfo(srTable.getModelAnalyzerInfo());

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
                // Output column: use combo with 0, 1, -
                editorType = 1;
                outputCombo.removeAllItems();
                outputCombo.addItem("0");
                outputCombo.addItem("1");
                outputCombo.addItem("-");
                outputCombo.setSelectedItem(valStr);
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
                return stateCombo;
            }
        }

        @Override
        public Object getCellEditorValue() {
            switch (editorType) {
                case 0: return textField.getText();
                case 1: return outputCombo.getSelectedItem();
                default: return stateCombo.getSelectedItem();
            }
        }
    }
}
