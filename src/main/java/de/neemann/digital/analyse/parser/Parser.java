/*
 * Copyright (c) 2016 Helmut Neemann
 * Use of this source code is governed by the GPL v3 license
 * that can be found in the LICENSE file.
 */
package de.neemann.digital.analyse.parser;

import de.neemann.digital.analyse.expression.*;
import de.neemann.digital.lang.Lang;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;

import static de.neemann.digital.analyse.parser.Tokenizer.Token.*;

/**
 * Class to parse a string to an expression
 */
public class Parser {

    private final Tokenizer tokenizer;
    private final boolean directAssignments;

    /**
     * Creates a new instance
     *
     * @param expression the string to parse
     */
    public Parser(String expression) {
        this(new StringReader(expression));
    }

    /**
     * Creates a parser which may interpret direct assignments as named expressions.
     *
     * @param expression expression text
     * @param directAssignments true to recognize direct assignments such as U=A+B
     */
    public Parser(String expression, boolean directAssignments) {
        this(new StringReader(expression), directAssignments);
    }

    /**
     * Creates a new instance
     *
     * @param reader the reader to read the expression
     */
    public Parser(Reader reader) {
        this(reader, false);
    }

    /**
     * Creates a parser for the given reader.
     *
     * @param reader reader
     * @param directAssignments true to recognize direct assignments
     */
    public Parser(Reader reader, boolean directAssignments) {
        tokenizer = new Tokenizer(reader);
        this.directAssignments = directAssignments;
    }

    /**
     * Parses the the string expression and returns a expression instance
     *
     * @return the expression instance
     * @throws IOException    IOException
     * @throws ParseException ParseException
     */
    public ArrayList<Expression> parse() throws IOException, ParseException {
        ArrayList<Expression> list = new ArrayList<>();
        while (true) {
            while (tokenizer.peek() == COMMA)
                tokenizer.consume();
            if (tokenizer.peek() == EOF)
                return list;
            list.add(parseLet());
            if (tokenizer.peek() == EOF)
                return list;
            if (tokenizer.peek() != COMMA)
                throw new ParseException(Lang.get("err_parserUnexpectedToken_N", tokenizer.toString()));
        }
    }

    private Expression parseLet() throws IOException, ParseException {
        if (tokenizer.peek() == IDENT && tokenizer.getIdent().equals("let")) {
            tokenizer.consume();
            consume(IDENT);
            String name = tokenizer.getIdent();
            consume(EQUAL);
            return new NamedExpression(name, parseOr());
        }
        if (tokenizer.peek() == IDENT) {
            tokenizer.consume();
            String name = tokenizer.getIdent();
            if (tokenizer.peek() == EQUAL) {
                if (!directAssignments || isSimpleVariableName(name))
                    return parseOrStarting(new Variable(name));
                tokenizer.consume();
                Expression right = parseOr();
                return new NamedExpression(name, right);
            }
            return parseOrStarting(new Variable(name));
        }
        return parseOr();
    }

    private boolean isSimpleVariableName(String name) {
        return name.length() == 1 && "ABCabc".indexOf(name.charAt(0)) >= 0;
    }

    private Expression parseOrStarting(Expression ex) throws IOException, ParseException {
        ex = parseAndStarting(ex);
        while (tokenizer.peek() == OR || tokenizer.peek() == XOR) {
            if (tokenizer.next() == OR)
                ex = Operation.or(ex, parseAnd());
            else
                ex = Operation.xor(ex, parseAnd());
        }
        return ex;
    }

    private Expression parseAndStarting(Expression ex) throws IOException, ParseException {
        ex = parseEqualStarting(ex);
        while (true) {
            if (tokenizer.peek() == AND) {
                tokenizer.consume();
                ex = Operation.and(ex, parseEqual());
            } else if (isSimpleEx(tokenizer.peek())) {
                ex = Operation.and(ex, parseEqual());
            } else
                return ex;
        }
    }

    private Expression parseEqualStarting(Expression ex) throws IOException, ParseException {
        while (tokenizer.peek() == EQUAL || tokenizer.peek() == NOTEQUAL) {
            Tokenizer.Token tok = tokenizer.next();
            ex = Operation.xor(ex, parseSimpleExp());
            if (tok == EQUAL)
                ex = Not.not(ex);
        }
        while (tokenizer.peek() == POSTNOT) {
            tokenizer.consume();
            ex = Not.not(ex);
        }
        return ex;
    }

    private Expression parseOr() throws IOException, ParseException {
        Expression ex = parseAnd();
        while (tokenizer.peek() == OR || tokenizer.peek() == XOR) {
            if (tokenizer.next() == OR)
                ex = Operation.or(ex, parseAnd());
            else
                ex = Operation.xor(ex, parseAnd());
        }
        return ex;
    }

    private Expression parseAnd() throws IOException, ParseException {
        Expression ex = parseEqual();
        while (true) {
            if (tokenizer.peek() == AND) {
                tokenizer.consume();
                ex = Operation.and(ex, parseEqual());
            } else if (isSimpleEx(tokenizer.peek())) {
                ex = Operation.and(ex, parseEqual());
            } else
                return ex;
        }
    }

    private Expression parseEqual() throws IOException, ParseException {
        Expression ex = parseSimpleExp();
        while (tokenizer.peek() == EQUAL || tokenizer.peek() == NOTEQUAL) {
            Tokenizer.Token tok = tokenizer.next();
            ex = Operation.xor(ex, parseSimpleExp());
            if (tok == EQUAL)
                ex = Not.not(ex);
        }
        return ex;
    }

    private boolean isSimpleEx(Tokenizer.Token tok) {
        return tok == NOT || tok == OPEN || tok == IDENT || tok == ONE || tok == ZERO;
    }

    private Expression parseSimpleExp() throws IOException, ParseException {
        Expression res;
        switch (tokenizer.next()) {
            case NOT:
                res = Not.not(parseSimpleExp());
                break;
            case OPEN:
                res = parseOr();
                if (!(tokenizer.next() == CLOSE))
                    throw new ParseException(Lang.get("err_parserMissingClosedParenthesis"));
                break;
            case IDENT:
                res = new Variable(tokenizer.getIdent());
                break;
            case ONE:
                res = Constant.ONE;
                break;
            case ZERO:
                res = Constant.ZERO;
                break;
            default:
                throw new ParseException(Lang.get("err_parserUnexpectedToken_N", tokenizer.toString()));
        }
        while (tokenizer.peek() == POSTNOT) {
            tokenizer.consume();
            res = Not.not(res);
        }
        return res;
    }

    private void consume(Tokenizer.Token token) throws IOException, ParseException {
        if (!tokenizer.next().equals(token))
            throw new ParseException(Lang.get("err_parserUnexpectedToken_N", tokenizer.toString()));
    }

}
