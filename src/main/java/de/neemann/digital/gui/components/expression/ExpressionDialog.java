/*
 * Copyright (c) 2016 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.expression;

import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.NamedExpression;
import de.neemann.digital.analyse.expression.format.FormatToExpression;
import de.neemann.digital.analyse.expression.modify.ExpressionModifier;
import de.neemann.digital.analyse.expression.modify.NAnd;
import de.neemann.digital.analyse.expression.modify.NInputs;
import de.neemann.digital.analyse.expression.modify.NOr;
import de.neemann.digital.analyse.parser.Parser;
import de.neemann.digital.builder.circuit.CircuitBuilder;
import de.neemann.digital.draw.elements.Circuit;
import de.neemann.digital.draw.library.ElementLibrary;
import de.neemann.digital.draw.shapes.ShapeFactory;
import de.neemann.digital.gui.Main;
import de.neemann.digital.gui.components.table.ShowStringDialog;
import de.neemann.digital.lang.Lang;
import de.neemann.gui.ErrorMessage;
import de.neemann.gui.ToolTipAction;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.io.File;
import java.util.ArrayList;

import static de.neemann.digital.gui.components.EditorFactory.addF1Traversal;

/**
 * Dialog to enter an expression.
 * Creates a new frame with a circuit generated from the entered expression.
 */
public class ExpressionDialog extends JDialog {

    private static class CircuitVariant {
        private final String name;
        private final boolean useLUTs;
        private final boolean use74xx;
        private final ExpressionModifier[] modifiers;

        CircuitVariant(String name, boolean useLUTs, boolean use74xx, ExpressionModifier... modifiers) {
            this.name = name;
            this.useLUTs = useLUTs;
            this.use74xx = use74xx;
            this.modifiers = modifiers;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Creates a new instance
     *
     * @param parent       the parent
     * @param library      the library to use
     * @param shapeFactory the shapeFactory used for new circuits
     * @param baseFilename filename used as base for file operations
     */
    public ExpressionDialog(Main parent, ElementLibrary library, ShapeFactory shapeFactory, File baseFilename) {
        super(parent, Lang.get("expression"), false);

        String exampleEquation = "(C ∨ B) ∧ (A ∨ C) ∧ (B ∨ !C) * (C + !A)";
        JTextArea text = addF1Traversal(new JTextArea(exampleEquation, 5, 40));
        getContentPane().add(new JScrollPane(text), BorderLayout.CENTER);
        getContentPane().add(new JLabel(Lang.get("msg_enterAnExpression")), BorderLayout.NORTH);

        JPanel bottom = new JPanel();
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        getContentPane().add(bottom, BorderLayout.SOUTH);

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JCheckBox useSwitchesAndLeds = new JCheckBox(Lang.get("msg_builder_useSwitchesAndLeds"));
        useSwitchesAndLeds.setToolTipText(Lang.get("msg_builder_useSwitchesAndLeds_tt"));
        options.add(useSwitchesAndLeds);

        JComboBox<CircuitVariant> variantPicker = createVariantPicker();
        options.add(new JLabel(Lang.get("menu_table_createCircuitMore") + ":"));
        options.add(variantPicker);
        bottom.add(options);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottom.add(buttons);

        buttons.add(new ToolTipAction(Lang.get("btn_help")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                new ShowStringDialog(
                        ExpressionDialog.this,
                        Lang.get("msg_expressionHelpTitle"),
                        Lang.get("msg_expressionHelp"), true)
                        .setVisible(true);
            }
        }.createJButton());

        buttons.add(new ToolTipAction(Lang.get("btn_create")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    ArrayList<Expression> expList = new Parser(text.getText()).parse();
                    CircuitVariant variant = (CircuitVariant) variantPicker.getSelectedItem();
                    if (variant == null) {
                        variant = variantPicker.getItemAt(0);
                    }

                    CircuitBuilder circuitBuilder = new CircuitBuilder(shapeFactory)
                            .setResolveLocalVars(true)
                            .setUseLUTs(variant.useLUTs)
                            .setUse74xx(variant.use74xx)
                            .setUseSwitchesAndLeds(useSwitchesAndLeds.isSelected());

                    if (expList.size() == 1) {
                        Expression item = expList.get(0);
                        String name = "Y";
                        Expression exp = item;
                        if (item instanceof NamedExpression) {
                            name = ((NamedExpression) item).getName();
                            exp = ((NamedExpression) item).getExpression();
                        }
                        Expression modified = ExpressionModifier.modifyExpression(exp, variant.modifiers);
                        circuitBuilder.addCombinatorial(name, modified);
                    } else {
                        for (Expression item : expList) {
                            String name = FormatToExpression.defaultFormat(item);
                            Expression exp = item;
                            if (item instanceof NamedExpression) {
                                name = ((NamedExpression) item).getName();
                                exp = ((NamedExpression) item).getExpression();
                            }
                            Expression modified = ExpressionModifier.modifyExpression(exp, variant.modifiers);
                            circuitBuilder.addCombinatorial(name, modified);
                        }
                    }

                    Circuit circuit = circuitBuilder.createCircuit();
                    new Main.MainBuilder()
                            .setParent(parent)
                            .setLibrary(library)
                            .setCircuit(circuit)
                            .setBaseFileName(baseFilename)
                            .openLater();
                } catch (Exception ex) {
                    new ErrorMessage().addCause(ex).show(ExpressionDialog.this);
                }
            }
        }.setToolTip(Lang.get("btn_create_tt")).createJButton());

        pack();
        setLocationRelativeTo(parent);
    }

    private static JComboBox<CircuitVariant> createVariantPicker() {
        JComboBox<CircuitVariant> variantPicker = new JComboBox<>();
        variantPicker.addItem(new CircuitVariant(Lang.get("menu_table_createCircuit"), false, false, ExpressionModifier.IDENTITY));
        variantPicker.addItem(new CircuitVariant(Lang.get("menu_table_createCircuit74xx"), false, true, new NInputs(2)));
        variantPicker.addItem(new CircuitVariant(Lang.get("menu_table_createCircuitLUT"), true, false, ExpressionModifier.IDENTITY));
        variantPicker.addItem(new CircuitVariant(Lang.get("menu_table_createNAnd"), false, false, new NAnd()));
        for (int i = 2; i < 7; i++) {
            variantPicker.addItem(new CircuitVariant(Lang.get("menu_table_maxInputs_N", i), false, false, new NInputs(i)));
        }
        if (Main.isExperimentalMode()) {
            variantPicker.addItem(new CircuitVariant(Lang.get("menu_table_createNOr"), false, false, new NOr()));
            variantPicker.addItem(new CircuitVariant(Lang.get("menu_table_createNAndTwo"), false, false, new NInputs(2), new NAnd()));
            variantPicker.addItem(new CircuitVariant(Lang.get("menu_table_createNOrTwo"), false, false, new NInputs(2), new NOr()));
        }
        return variantPicker;
    }
}
