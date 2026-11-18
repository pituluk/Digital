/*
 * Copyright (c) 2017 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.gui.components.table;

import de.neemann.digital.analyse.expression.Expression;
import de.neemann.digital.analyse.expression.ExpressionException;
import de.neemann.digital.analyse.expression.NamedExpression;
import de.neemann.digital.analyse.expression.VariableVisitor;
import de.neemann.digital.analyse.expression.format.FormatToExpression;
import de.neemann.digital.analyse.expression.format.FormatterException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ExpressionListenerStore stores the generated expressions for later use.
 * <p>
 */
public class ExpressionListenerStore implements ExpressionListener {

    private final ExpressionListener parent;
    private final ArrayList<Result> results;
    private boolean closed;

    /**
     * Creates a new instance
     *
     * @param parent the {@link ExpressionListener} which is to fill
     */
    public ExpressionListenerStore(ExpressionListener parent) {
        this.parent = parent;
        this.results = new ArrayList<>();
        closed = false;
    }

    @Override
    public void resultFound(String name, Expression expression) throws FormatterException, ExpressionException {
        results.add(new Result(name, expression));
        if (parent != null)
            parent.resultFound(name, expression);
    }

    @Override
    public void close() throws FormatterException, ExpressionException {
        closed = true;
        if (parent != null)
            parent.close();
    }

    /**
     * Puts the results to the given listener
     *
     * @param listener the listener to fill
     * @throws FormatterException  FormatterException
     * @throws ExpressionException ExpressionException
     */
    public void replayTo(ExpressionListener listener) throws FormatterException, ExpressionException {
        if (!closed)
            throw new ExpressionException("ExpressionListenerStore not closed");
        for (Result r : getDependencyOrderedResults())
            listener.resultFound(r.name, r.expression.copy());
    }

    private List<Result> getDependencyOrderedResults() {
        Map<String, Result> byName = new HashMap<>();
        for (Result result : results)
            byName.put(result.name, result);

        ArrayList<Result> ordered = new ArrayList<>();
        Set<String> emitted = new HashSet<>();
        for (Result result : results)
            visitDependencies(result, byName, emitted, ordered);
        return ordered;
    }

    private void visitDependencies(Result result, Map<String, Result> byName,
                                   Set<String> emitted, List<Result> ordered) {
        if (!emitted.add(result.name))
            return;
        Set<String> dependencies = new HashSet<>();
        result.expression.traverse(new VariableVisitor()).getVariables()
                .forEach(variable -> {
                    if (byName.containsKey(variable.getIdentifier()))
                        dependencies.add(variable.getIdentifier());
                });
        for (String dependency : dependencies) {
            Result dependencyResult = byName.get(dependency);
            if (dependencyResult != null)
                visitDependencies(dependencyResult, byName, emitted, ordered);
        }
        ordered.add(result);
    }

    /**
     * @return the first found expression
     */
    public Expression getFirst() {
        return results.get(0).expression.copy();
    }

    /**
     * @return the list of al results
     */
    public List<Result> getResults() {
        return Collections.unmodifiableList(results);
    }

    /**
     * Container for the stored expressions
     */
    public static final class Result {
        private final String name;
        private final Expression expression;

        private Result(String name, Expression expression) {
            this.name = name;
            this.expression = expression;
        }

        /**
         * @return the expressions name
         */
        public String getName() {
            return name;
        }

        /**
         * @return the expression
         */
        public Expression getExpression() {
            return expression;
        }

        @Override
        public String toString() {
            return FormatToExpression.defaultFormat(new NamedExpression(name, expression));
        }
    }
}
