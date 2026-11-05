/*
 * Copyright (c) 2017 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.karnaugh;

import de.neemann.digital.analyse.TruthTable;
import de.neemann.digital.analyse.AnalyseException;
import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.ExpressionException;
import de.neemann.digital.analyse.expression.NamedExpression;
import de.neemann.digital.analyse.expression.Not;
import de.neemann.digital.analyse.expression.Variable;
import de.neemann.digital.analyse.expression.format.FormatToExpression;
import de.neemann.digital.analyse.expression.format.FormatterException;
import de.neemann.digital.analyse.quinemc.BoolTable;
import de.neemann.digital.analyse.quinemc.ThreeStateValue;
import de.neemann.digital.gui.components.table.ExpressionComponent;
import de.neemann.digital.gui.components.table.ExpressionCreator;
import de.neemann.digital.gui.components.table.ExpressionListenerStore;
import de.neemann.digital.lang.Lang;
import de.neemann.gui.ErrorMessage;

import javax.swing.*;
import javax.swing.event.ListDataListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dialog to show a KV map
 */
public class KarnaughMapDialog extends JDialog {

    private final KarnaughMapComponent kvComponent;
    private final JComboBox<ExpressionListenerStore.Result> combo;
    private final JDialog parent;
    private final JRadioButton groupOnes;
    private final JRadioButton groupZeros;
    private TruthTable table;
    private List<ExpressionListenerStore.Result> results;
    private Map<String, Expression> zeroExpressions = new HashMap<>();
    private TruthTable zeroExpressionTable;

    /**
     * Creates a new instance
     *
     * @param parent   the parent dialog
     * @param modifier the modifier which is used by the k-map to modify the table.
     */
    public KarnaughMapDialog(JDialog parent, KarnaughMapComponent.Modifier modifier) {
        super(parent, Lang.get("win_karnaughMap"), false);
        this.parent = parent;
        setDefaultCloseOperation(HIDE_ON_CLOSE);
        kvComponent = new KarnaughMapComponent(modifier);
        getContentPane().add(kvComponent);

        combo = new JComboBox<>();
        combo.setRenderer(new MyDefaultListCellRenderer());
        groupOnes = new JRadioButton(Lang.get("msg_karnaughGroupOnes"), true);
        groupZeros = new JRadioButton(Lang.get("msg_karnaughGroupZeros"));
        ButtonGroup group = new ButtonGroup();
        group.add(groupOnes);
        group.add(groupZeros);
        JPanel controls = new JPanel(new BorderLayout());
        controls.add(combo, BorderLayout.NORTH);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT));
        options.add(groupOnes);
        options.add(groupZeros);
        controls.add(options, BorderLayout.SOUTH);
        getContentPane().add(controls, BorderLayout.NORTH);
        combo.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                updateSelectedResult();
            }
        });
        groupOnes.addActionListener(event -> updateSelectedResult());
        groupZeros.addActionListener(event -> updateSelectedResult());

        pack();
        setLocationRelativeTo(null);
    }

    private BoolTable getResultTable(String name) {
        for (int i = 0; i < table.getResultCount(); i++)
            if (normalizeName(table.getResultName(i)).equals(normalizeName(name)))
                return table.getResult(i);
        return null;
    }

    private Expression getDisplayedExpression(ExpressionListenerStore.Result result, BoolTable boolTable) {
        if (boolTable == null)
            return result.getExpression();
        if (!groupZeros.isSelected())
            return result.getExpression();
        return zeroExpressions.getOrDefault(getResultName(result.getName()), result.getExpression());
    }

    private String getDisplayedName(ExpressionListenerStore.Result result) {
        if (!groupZeros.isSelected() || getResultTable(result.getName()) == null)
            return result.getName();
        return FormatToExpression.defaultFormat(Not.not(new Variable(result.getName())));
    }

    private String getResultName(String name) {
        int resultIndex = getResultIndex(name);
        return resultIndex < 0 ? name : table.getResultName(resultIndex);
    }

    private int getResultIndex(String name) {
        for (int i = 0; i < table.getResultCount(); i++)
            if (normalizeName(table.getResultName(i)).equals(normalizeName(name)))
                return i;
        return -1;
    }

    private String normalizeName(String name) {
        return name.replace("^{n+1}", "^n+1");
    }

    private void updateZeroExpressions() throws AnalyseException, FormatterException, ExpressionException {
        if (zeroExpressionTable == table)
            return;

        TruthTable invertedTable = new TruthTable(new ArrayList<>(table.getVars()));
        for (int result = 0; result < table.getResultCount(); result++)
            invertedTable.addResult(table.getResultName(result));
        for (int row = 0; row < table.getRows(); row++) {
            for (int result = 0; result < table.getResultCount(); result++) {
                ThreeStateValue inverted = table.getResult(result).get(row).invert();
                invertedTable.setValue(row, table.getVars().size() + result, inverted.asInt());
            }
        }

        ExpressionListenerStore storage = new ExpressionListenerStore(null);
        new ExpressionCreator(invertedTable).create(storage);
        Map<String, Expression> expressions = new HashMap<>();
        for (ExpressionListenerStore.Result result : storage.getResults())
            expressions.putIfAbsent(result.getName(), result.getExpression());
        zeroExpressions = expressions;
        zeroExpressionTable = table;
    }

    private void updateSelectedResult() {
        int i = combo.getSelectedIndex();
        if (i < 0 || results == null || i >= results.size())
            return;

        ExpressionListenerStore.Result result = results.get(i);
        BoolTable boolTable = getResultTable(result.getName());
        try {
            if (groupZeros.isSelected())
                updateZeroExpressions();
            if (boolTable != null)
                kvComponent.setResult(table.getVars(), boolTable, getDisplayedExpression(result, boolTable));
            else
                kvComponent.showNothing();
            combo.repaint();
        } catch (AnalyseException | FormatterException | ExpressionException e) {
            kvComponent.showNothing();
            new ErrorMessage(Lang.get("msg_errorDuringCalculation")).addCause(e).show(this);
        }
    }

    /**
     * Sets the available results
     *
     * @param table      the table
     * @param newResults the result list
     */
    public void setResult(TruthTable table, List<ExpressionListenerStore.Result> newResults) {
        this.table = table;
        zeroExpressionTable = null;

        int i = combo.getSelectedIndex();
        String name = null;
        if (i >= 0 && results != null && i < results.size()) name = results.get(i).getName();

        this.results = newResults;
        combo.setModel(new MyComboBoxModel(results));

        if (results.size() > 0) {
            i = 0;
            for (int j = 0; j < results.size(); j++)
                if (results.get(j).getName().equals(name)) {
                    i = j;
                    break;

                }
            combo.setSelectedIndex(i);
        }
    }

    /**
     * @return the actual results
     */
    public List<ExpressionListenerStore.Result> getResults() {
        return results;
    }

    @Override
    public void setVisible(boolean visible) {
        if (!isVisible())
            setLocation(parent.getX() + parent.getWidth(), parent.getY());
        super.setVisible(visible);
    }

    private static final class MyComboBoxModel implements ComboBoxModel<ExpressionListenerStore.Result> {
        private List<ExpressionListenerStore.Result> results;
        private ExpressionListenerStore.Result selected;

        private MyComboBoxModel(List<ExpressionListenerStore.Result> results) {
            this.results = results;
        }

        public void setSelected(ExpressionListenerStore.Result selected) {
            this.selected = selected;
        }

        @Override
        public void setSelectedItem(Object o) {
            selected = (ExpressionListenerStore.Result) o;
        }

        @Override
        public Object getSelectedItem() {
            return selected;
        }

        @Override
        public int getSize() {
            return results == null ? 0 : results.size();
        }

        @Override
        public ExpressionListenerStore.Result getElementAt(int i) {
            return results.get(i);
        }

        @Override
        public void addListDataListener(ListDataListener listDataListener) {
        }

        @Override
        public void removeListDataListener(ListDataListener listDataListener) {
        }
    }

    private final class MyDefaultListCellRenderer extends ExpressionComponent implements ListCellRenderer<ExpressionListenerStore.Result> {

        @Override
        public Component getListCellRendererComponent(JList<? extends ExpressionListenerStore.Result> jList, ExpressionListenerStore.Result result, int index, boolean isSelected, boolean focus) {
            if (result != null)
                setExpression(new NamedExpression(getDisplayedName(result),
                        getDisplayedExpression(result, getResultTable(result.getName()))));
            else
                setExpression(null);
            updateComponentSize(combo.getGraphics());

            if (isSelected) {
                this.setBackground(jList.getSelectionBackground());
                this.setForeground(jList.getSelectionForeground());
            } else {
                this.setBackground(jList.getBackground());
                this.setForeground(jList.getForeground());
            }
            return this;
        }
    }
}
