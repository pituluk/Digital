/*
 * Copyright (c) 2026 Helmut Neemann.
 * Copyright (c) 2026 Łukasz Pituła.
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.table;

import de.neemann.digital.analyse.AsyncStateEncoder;
import de.neemann.digital.analyse.MinimizerQuineMcCluskey;
import de.neemann.digital.analyse.TruthTable;
import de.neemann.digital.analyse.TransitionTable;
import de.neemann.digital.analyse.expression.Constant;
import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.modify.ExpressionModifier;
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
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellEditor;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * Dialog to view and edit transition tables (state/flow tables) for synchronous and asynchronous circuits.
 */
public class TransitionTableDialog extends JDialog {

    private final TransitionTable transitionTable;
    private final ElementLibrary library;
    private final TableDialog callerTableDialog;
    private final TransitionTableModel tableModel;
    private final JTable table;
    private final JCheckBox asyncCheckBox;

    /**
     * Creates a new transition table dialog.
     *
     * @param parent          parent window
     * @param transitionTable transition table model
     * @param library         element library
     * @param callerTable     caller TableDialog if any
     */
    public TransitionTableDialog(Window parent, TransitionTable transitionTable, ElementLibrary library, TableDialog callerTable) {
        super(parent, Lang.get("menu_table_transitionTable"), ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        this.transitionTable = transitionTable;
        this.library = library != null ? library : new de.neemann.digital.draw.library.ElementLibrary();
        this.callerTableDialog = callerTable;

        this.tableModel = new TransitionTableModel();
        this.table = new JTable(tableModel);
        this.table.setRowHeight(26);
        this.table.getTableHeader().setReorderingAllowed(false);
        this.table.setDefaultRenderer(Object.class, new TransitionCellRenderer());
        this.table.setDefaultEditor(Object.class, new TransitionCellEditor());

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
                String name = JOptionPane.showInputDialog(TransitionTableDialog.this,
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

        toolBar.add(new ToolTipAction(Lang.get("menu_table_add_input")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                String name = JOptionPane.showInputDialog(TransitionTableDialog.this,
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
                String name = JOptionPane.showInputDialog(TransitionTableDialog.this,
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

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton btnApply = new JButton(new ToolTipAction(Lang.get("menu_table_apply_synthesis")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                applyToSynthesis();
            }
        });
        bottomPanel.add(btnApply);

        JButton btnGraph = new JButton(new ToolTipAction(Lang.get("menu_table_stateGraph")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                openInGraph();
            }
        });
        bottomPanel.add(btnGraph);

        JButton btnCreateCircuit = new JButton(new ToolTipAction(Lang.get("menu_table_createCircuit")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                createCircuit();
            }
        });
        bottomPanel.add(btnCreateCircuit);

        JButton btnRaceFree = new JButton(new ToolTipAction(Lang.get("menu_table_async_race_free")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                createRaceFreeCircuit();
            }
        }.setToolTip(Lang.get("menu_table_async_race_free_tt")));
        bottomPanel.add(btnRaceFree);

        JButton btnClose = new JButton(new ToolTipAction(Lang.get("ok")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        });
        bottomPanel.add(btnClose);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(toolBar, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(bottomPanel, BorderLayout.SOUTH);

        pack();
        setLocationRelativeTo(parent);
    }

    private void applyToSynthesis() {
        try {
            TruthTable tt = transitionTable.toTruthTable();
            if (callerTableDialog != null) {
                callerTableDialog.setTruthTable(tt);
                callerTableDialog.toFront();
            } else {
                new TableDialog(TransitionTableDialog.this, tt, library, null).setVisible(true);
            }
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_errorDuringCalculation")).addCause(e).show(this);
        }
    }

    private void openInGraph() {
        try {
            FSM fsm = transitionTable.toFSM();
            new FSMFrame(this, library).setFSM(fsm).setVisible(true);
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_fsmCantCreateTable")).addCause(e).show(this);
        }
    }

    private void createCircuit() {
        try {
            TruthTable actualTable = transitionTable.toTruthTable();
            ExpressionListenerStore circuitExpressions = new ExpressionListenerStore(null);
            for (int i = 0; i < actualTable.getResultCount(); i++) {
                String rName = actualTable.getResultName(i);
                new de.neemann.digital.analyse.MinimizerQuineMcCluskey()
                        .minimize(actualTable.getVars(), actualTable.getResult(i), rName, circuitExpressions);
            }
            circuitExpressions.close();

            ShapeFactory shapeFactory = new ShapeFactory(library);
            CircuitBuilder.AsyncMode mode = transitionTable.isAsync()
                    ? CircuitBuilder.AsyncMode.FEEDBACK : CircuitBuilder.AsyncMode.NONE;

            CircuitBuilder circuitBuilder = new CircuitBuilder(shapeFactory, actualTable.getVars())
                    .setAsyncMode(mode)
                    .setResolveLocalVars(mode == CircuitBuilder.AsyncMode.FEEDBACK)
                    .setPreserveSharedOutputPins(mode == CircuitBuilder.AsyncMode.FEEDBACK)
                    .setModelAnalyzerInfo(actualTable.getModelAnalyzerInfo())
                    .setCircuitAnnotation(transitionTable.isAsync()
                            ? "Sequential Circuit: Asynchronous Feedback\nTunnels: Cross-coupled feedback loops\nTiming: Continuous-time (unclocked)"
                            : "Sequential Circuit: Synchronous (D Flip-Flops)\nFlip-Flops: FlipflopD with clock\nClock: Connect Clock to C input");

            new BuilderExpressionCreator(circuitBuilder, ExpressionModifier.IDENTITY)
                    .create(circuitExpressions);

            Circuit circuit = circuitBuilder.createCircuit();
            new Main.MainBuilder()
                    .setParent(TransitionTableDialog.this)
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

            ExpressionListenerStore circuitExpressions = new ExpressionListenerStore(null);
            for (int i = 0; i < srTable.getResultCount(); i++) {
                String rName = srTable.getResultName(i);
                new MinimizerQuineMcCluskey()
                        .setHazardFree(true)
                        .minimize(srTable.getVars(), srTable.getResult(i), rName, circuitExpressions);
            }
            circuitExpressions.close();

            ShapeFactory shapeFactory = new ShapeFactory(library);
            CircuitBuilder circuitBuilder = new CircuitBuilder(shapeFactory, srTable.getVars())
                    .setAsyncMode(CircuitBuilder.AsyncMode.RS_LATCH)
                    .setHazardFree(true)
                    .setModelAnalyzerInfo(srTable.getModelAnalyzerInfo())
                    .setCircuitAnnotation("Sequential Circuit: Critical Race-Free RS Latches\nEncoding: Adjacency-Preserving (Hamming dist <= 1)\nLatches: Unclocked FlipflopRSAsync\nHazard-Free: Enabled (Complete Sum)");

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
                    .setParent(TransitionTableDialog.this)
                    .setLibrary(library)
                    .setCircuit(circuit)
                    .openLater();
        } catch (Exception e) {
            new ErrorMessage(Lang.get("msg_errorDuringCalculation")).addCause(e).show(this);
        }
    }

    private class TransitionTableModel extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return transitionTable.getStateCount();
        }

        @Override
        public int getColumnCount() {
            // Col 0: State Name/Code
            // Col 1..outputCount: Moore outputs
            // Remaining: Input columns
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
                return "(" + targetName + ")";
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
                        c.setBackground(new Color(220, 245, 220));
                    }
                } else if (!isSelected) {
                    c.setBackground(Color.WHITE);
                }
            } else if (!isSelected) {
                c.setBackground(column == 0 ? new Color(240, 240, 240) : Color.WHITE);
            }
            return c;
        }
    }

    private class TransitionCellEditor extends AbstractCellEditor implements TableCellEditor {
        private final JComboBox<String> stateCombo = new JComboBox<>();
        private final JComboBox<String> outputCombo = new JComboBox<>();
        private final JTextField textField = new JTextField();
        private int editorType;

        TransitionCellEditor() {
            outputCombo.setEditable(true);
            stateCombo.setEditable(true);
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            int outCount = transitionTable.getOutputVars().size();
            String valStr = value == null ? "" : value.toString();

            if (column == 0) {
                editorType = 0;
                int paren = valStr.indexOf('(');
                if (paren > 0) {
                    valStr = valStr.substring(0, paren).trim();
                }
                textField.setText(valStr);
                return textField;
            } else if (column <= outCount) {
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
