/*
 * Copyright 1999-2009 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

package com.sun.tools.javac.parser;

import java.util.*;

import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;
import static com.sun.tools.javac.util.ListBuffer.lb;

import com.sun.tools.javac.tree.JCTree.*;

import static com.sun.tools.javac.parser.Token.*;

/** The parser maps a token sequence into an abstract syntax
 *  tree. It operates by recursive descent, with code derived
 *  systematically from an LL(1) grammar. For efficiency reasons, an
 *  operator precedence scheme is used for parsing binary operation
 *  expressions.
 *
 *  <p><b>This is NOT part of any API supported by Sun Microsystems.  If
 *  you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class JavacParser implements Parser {

    /** The number of precedence levels of infix operators.
     */
    private static final int infixPrecedenceLevels = 10;

    /** The scanner used for lexical analysis.
     */
    protected Lexer S;

    /** The factory to be used for abstract syntax tree construction.
     */
    protected TreeMaker F;

    /** The log to be used for error diagnostics.
     */
    private Log log;

    /** The keyword table. */
    private Keywords keywords;

    /** The Source language setting. */
    private Source source;

    /** The name table. */
    private Names names;

    // Because of javac's limited lookahead, some contexts are ambiguous in
    // the presence of type annotations even though they are not ambiguous
    // in the absence of type annotations.  Consider this code:
    //   void m(String [] m) { }
    //   void m(String ... m) { }
    // After parsing "String", javac calls bracketsOpt which immediately
    // returns if the next character is not '['.  Similarly, javac can see
    // if the next token is ... and in that case parse an ellipsis.  But in
    // the presence of type annotations:
    //   void m(String @A [] m) { }
    //   void m(String @A ... m) { }
    // no finite lookahead is enough to determine whether to read array
    // levels or an ellipsis.  Furthermore, if you call bracketsOpt, then
    // bracketsOpt first reads all the leading annotations and only then
    // discovers that it needs to fail.  bracketsOpt needs a way to push
    // back the extra annotations that it read.  (But, bracketsOpt should
    // not *always* be allowed to push back extra annotations that it finds
    // -- in most contexts, any such extra annotation is an error.
    // Another similar case occurs with arrays and receiver annotations:
    //   String b() @Array [] @Receiver { }
    //   String b() @Receiver { }
    //
    // The following two variables permit type annotations that have
    // already been read to be stored for later use.  Alternate
    // implementations are possible but would cause much larger changes to
    // the parser.
    /** Type annotations that have already been read but have not yet been used. **/
    private List<JCTypeAnnotation> typeAnnotationsPushedBack = null;
    /**
     * If the parser notices extra annotations, then it either immediately
     * issues an error (if this variable is false) or places the extra
     * annotations in variable typeAnnotationsPushedBack (if this variable
     * is true).
     */
    private boolean permitTypeAnnotationsPushBack = false;

    private final CancelService cancelService;

    /** Construct a parser from a given scanner, tree factory and log.
     */
    protected JavacParser(ParserFactory fac,
                     Lexer S,
                     boolean keepDocComments,
                     boolean keepLineMap,
                     CancelService cancelService) {
        this.S = S;
        S.nextToken(); // prime the pump
        this.F = fac.F;
        this.log = fac.log;
        this.names = fac.names;
        this.keywords = fac.keywords;
        this.source = fac.source;
        this.allowGenerics = source.allowGenerics();
        this.allowVarargs = source.allowVarargs();
        this.allowAsserts = source.allowAsserts();
        this.allowEnums = source.allowEnums();
        this.allowForeach = source.allowForeach();
        this.allowStaticImport = source.allowStaticImport();
        this.allowAnnotations = source.allowAnnotations();
        this.allowDiamond = source.allowDiamond();
        this.allowTypeAnnotations = source.allowTypeAnnotations();
        this.allowStringFolding = fac.options.get("disableStringFolding") == null; //NOI18N
        this.keepDocComments = keepDocComments;
        docComments = keepDocComments ? new HashMap<JCTree,String>() : null;
        this.keepLineMap = keepLineMap;
        this.errorTree = F.Erroneous();
        this.debugJSR308 = fac.options.get("TA:parser") != null;
        this.cancelService = cancelService;
    }

    /** Switch: debug output for type-annotations operations
     */
    boolean debugJSR308;

    /** Switch: Should generics be recognized?
     */
    boolean allowGenerics;

    /** Switch: Should diamond operator be recognized?
     */
    boolean allowDiamond;

    /** Switch: Should varargs be recognized?
     */
    boolean allowVarargs;

    /** Switch: should we recognize assert statements, or just give a warning?
     */
    boolean allowAsserts;

    /** Switch: should we recognize enums, or just give a warning?
     */
    boolean allowEnums;

    /** Switch: should we recognize foreach?
     */
    boolean allowForeach;

    /** Switch: should we recognize foreach?
     */
    boolean allowStaticImport;

    /** Switch: should we recognize annotations?
     */
    boolean allowAnnotations;

    /** Switch: should we recognize type annotations?
     */
    boolean allowTypeAnnotations;

    /** Switch: should we fold strings?
     */
    boolean allowStringFolding;

    /** Switch: should we keep docComments?
     */
    boolean keepDocComments;

    /** Switch: should we keep line table?
     */
    boolean keepLineMap;

    /** When terms are parsed, the mode determines which is expected:
     *     mode = EXPR        : an expression
     *     mode = TYPE        : a type
     *     mode = NOPARAMS    : no parameters allowed for type
     *     mode = TYPEARG     : type argument
     */
    static final int EXPR = 1;
    static final int TYPE = 2;
    static final int NOPARAMS = 4;
    static final int TYPEARG = 8;
    static final int DIAMOND = 16;

    /** The current mode.
     */
    private int mode = 0;

    /** The mode of the term that was parsed last.
     */
    private int lastmode = 0;

/* ---------- error recovery -------------- */

    private JCErroneous errorTree;

    /** Skip forward until a suitable stop token is found.
     */
    private void skip(boolean stopAtImport, boolean stopAtMemberDecl, boolean stopAtIdentifier, boolean stopAtStatement) {
         while (true) {
             switch (S.token()) {
                case SEMI:
                    S.nextToken();
                    return;
                case PUBLIC:
                case FINAL:
                case ABSTRACT:
                case MONKEYS_AT:
                case EOF:
                case CLASS:
                case INTERFACE:
                case ENUM:
                    return;
                case IMPORT:
                case PACKAGE:
                    if (stopAtImport)
                        return;
                    break;
                case LBRACE:
                case RBRACE:
                case PRIVATE:
                case PROTECTED:
                case STATIC:
                case TRANSIENT:
                case NATIVE:
                case VOLATILE:
                case SYNCHRONIZED:
                case STRICTFP:
                case BYTE:
                case SHORT:
                case CHAR:
                case INT:
                case LONG:
                case FLOAT:
                case DOUBLE:
                case BOOLEAN:
                case VOID:
                    if (stopAtMemberDecl)
                        return;
                    break;
                case IDENTIFIER:
                   if (stopAtIdentifier)
                        return;
                    break;
                case CASE:
                case DEFAULT:
                case IF:
                case FOR:
                case WHILE:
                case DO:
                case TRY:
                case SWITCH:
                case RETURN:
                case THROW:
                case BREAK:
                case CONTINUE:
                case ELSE:
                case FINALLY:
                case CATCH:
                case THIS:
                case SUPER:
                    if (stopAtStatement)
                        return;
                    break;
            }
            S.nextToken();
        }
    }

    private JCErroneous syntaxError(int pos, String key, Token... args) {
        return syntaxError(pos, List.<JCTree>nil(), key, args);
    }

    private JCErroneous syntaxError(int pos, List<JCTree> errs, String key, Token... args) {
        setErrorEndPos(pos);
        JCErroneous err = F.at(pos).Erroneous(errs);
        reportSyntaxError(err, pos, key, (Object[])args);
        if (errs != null) {
            JCTree last = errs.last();
            if (last != null)
                storeEnd(last, pos);
        }
        return toP(err);
    }

    /**
     * Report a syntax error at given position using the given
     * argument unless one was already reported at the same position.
     */
    private void reportSyntaxError(JCDiagnostic.DiagnosticPosition diag, int pos, String key, Object... args) {
        if (diag != null) {
            pos = diag.getPreferredPosition();
            if (pos > S.errPos() || pos == Position.NOPOS) {
                if (S.token() == EOF)
                    log.error(diag, "premature.eof");
                else
                    log.error(diag, key, args);
            }
        } else {
            if (pos > S.errPos() || pos == Position.NOPOS) {
                if (S.token() == EOF)
                    log.error(pos, "premature.eof");
                else
                    log.error(pos, key, args);
            }
        }
        S.errPos(pos);
    }

    /** Generate a syntax error at current position unless one was already
     *  reported at the same position.
     */
    private JCErroneous syntaxError(String key) {
        return syntaxError(S.pos(), key);
    }

    /** Generate a syntax error at current position unless one was
     *  already reported at the same position.
     */
    private JCErroneous syntaxError(String key, Token arg) {
        return syntaxError(S.pos(), key, arg);
    }

    /** If next input token matches given token, skip it, otherwise report
     *  an error.
     */
    public void accept(Token token) {
        if (S.token() == token) {
            S.nextToken();
        } else {
            setErrorEndPos(S.pos());
            reportSyntaxError(null, S.prevEndPos(), "expected", token);
        }
    }

    /** Report an illegal start of expression/type error at given position.
     */
    JCExpression illegal(int pos) {
        setErrorEndPos(pos);
        if ((mode & EXPR) != 0)
            return syntaxError(pos, "illegal.start.of.expr");
        else
            return syntaxError(pos, "illegal.start.of.type");

    }

    /** Report an illegal start of expression/type error at current position.
     */
    JCExpression illegal() {
        return illegal(S.pos());
    }

    /** Diagnose a modifier flag from the set, if any. */
    void checkNoMods(long mods) {
        if (mods != 0) {
            long lowestMod = mods & -mods;
            log.error(S.pos(), "mod.not.allowed.here",
                      Flags.asFlagSet(lowestMod));
        }
    }

/* ---------- doc comments --------- */

    /** A hashtable to store all documentation comments
     *  indexed by the tree nodes they refer to.
     *  defined only if option flag keepDocComment is set.
     */
    private final Map<JCTree, String> docComments;

    /** Make an entry into docComments hashtable,
     *  provided flag keepDocComments is set and given doc comment is non-null.
     *  @param tree   The tree to be used as index in the hashtable
     *  @param dc     The doc comment to associate with the tree, or null.
     */
    void attach(JCTree tree, String dc) {
        if (keepDocComments && dc != null) {
//          System.out.println("doc comment = ");System.out.println(dc);//DEBUG
            docComments.put(tree, dc);
        }
    }

    public Map<JCTree, String> getDocComments() {
        return docComments == null ? Collections.<JCTree,String>emptyMap() : Collections.unmodifiableMap(docComments);
    }

/* -------- source positions ------- */

    private int errorEndPos = -1;

    private void setErrorEndPos(int errPos) {
        if (errPos > errorEndPos)
            errorEndPos = errPos;
    }

    protected int getErrorEndPos() {
        return errorEndPos;
    }

    /**
     * Store ending position for a tree.
     * @param tree   The tree.
     * @param endpos The ending position to associate with the tree.
     */
    protected void storeEnd(JCTree tree, int endpos) {}

    /**
     * Store ending position for a tree.  The ending position should
     * be the ending position of the current token.
     * @param t The tree.
     */
    protected <T extends JCTree> T to(T t) { return t; }

    /**
     * Store ending position for a tree.  The ending position should
     * be greater of the ending position of the previous token and errorEndPos.
     * @param t The tree.
     */
    protected <T extends JCTree> T toP(T t) { return t; }

    /** Get the start position for a tree node.  The start position is
     * defined to be the position of the first character of the first
     * token of the node's source text.
     * @param tree  The tree node
     */
    public int getStartPos(JCTree tree) {
        return TreeInfo.getStartPos(tree);
    }

    /**
     * Get the end position for a tree node.  The end position is
     * defined to be the position of the last character of the last
     * token of the node's source text.  Returns Position.NOPOS if end
     * positions are not generated or the position is otherwise not
     * found.
     * @param tree  The tree node
     */
    public int getEndPos(JCTree tree) {
        return Position.NOPOS;
    }



/* ---------- parsing -------------- */

    /**
     * Ident = IDENTIFIER
     */
    Name ident() {
        if (S.token() == IDENTIFIER) {
            Name name = S.name();
            S.nextToken();
            return name;
        } else if (S.token() == ASSERT) {
            if (allowAsserts) {
                log.error(S.pos(), "assert.as.identifier");
                S.nextToken();
                return names.error;
            } else {
                log.warning(S.pos(), "assert.as.identifier");
                Name name = S.name();
                S.nextToken();
                return name;
            }
        } else if (S.token() == ENUM) {
            if (allowEnums) {
                log.error(S.pos(), "enum.as.identifier");
                S.nextToken();
                return names.error;
            } else {
                log.warning(S.pos(), "enum.as.identifier");
                Name name = S.name();
                S.nextToken();
                return name;
            }
        } else {
            accept(IDENTIFIER);
            return names.error;
        }
}

    /**
     * Qualident = Ident { DOT Ident }
     */
    public JCExpression qualident() {
        JCExpression t = toP(F.at(S.pos()).Ident(ident()));
        while (S.token() == DOT) {
            int pos = S.pos();
            S.nextToken();
            t = toP(F.at(pos).Select(t, ident()));
        }
        return t;
    }

    /**
     * Literal =
     *     INTLITERAL
     *   | LONGLITERAL
     *   | FLOATLITERAL
     *   | DOUBLELITERAL
     *   | CHARLITERAL
     *   | STRINGLITERAL
     *   | TRUE
     *   | FALSE
     *   | NULL
     */
    JCExpression literal(Name prefix, int pos) {
        JCExpression t = errorTree;
        switch (S.token()) {
        case INTLITERAL:
            try {
                t = F.at(pos).Literal(
                    TypeTags.INT,
                    Convert.string2int(strval(prefix), S.radix()));
            } catch (NumberFormatException ex) {
                log.error(S.pos(), "int.number.too.large", strval(prefix));
            }
            break;
        case LONGLITERAL:
            try {
                t = F.at(pos).Literal(
                    TypeTags.LONG,
                    new Long(Convert.string2long(strval(prefix), S.radix())));
            } catch (NumberFormatException ex) {
                log.error(S.pos(), "int.number.too.large", strval(prefix));
            }
            break;
        case FLOATLITERAL: {
            String proper = (S.radix() == 16 ? ("0x"+ S.stringVal()) : S.stringVal());
            Float n;
            try {
                n = Float.valueOf(proper);
            } catch (NumberFormatException ex) {
                // error already repoted in scanner
                n = Float.NaN;
            }
            if (n.floatValue() == 0.0f && !isZero(proper))
                log.error(S.pos(), "fp.number.too.small");
            else if (n.floatValue() == Float.POSITIVE_INFINITY)
                log.error(S.pos(), "fp.number.too.large");
            else
                t = F.at(pos).Literal(TypeTags.FLOAT, n);
            break;
        }
        case DOUBLELITERAL: {
            String proper = (S.radix() == 16 ? ("0x"+ S.stringVal()) : S.stringVal());
            Double n;
            try {
                n = Double.valueOf(proper);
            } catch (NumberFormatException ex) {
                // error already reported in scanner
                n = Double.NaN;
            }
            if (n.doubleValue() == 0.0d && !isZero(proper))
                log.error(S.pos(), "fp.number.too.small");
            else if (n.doubleValue() == Double.POSITIVE_INFINITY)
                log.error(S.pos(), "fp.number.too.large");
            else
                t = F.at(pos).Literal(TypeTags.DOUBLE, n);
            break;
        }
        case CHARLITERAL:
            t = F.at(pos).Literal(
                TypeTags.CHAR,
                S.stringVal().charAt(0) + 0);
            break;
        case STRINGLITERAL:
            t = F.at(pos).Literal(
                TypeTags.CLASS,
                S.stringVal());
            break;
        case TRUE: case FALSE:
            t = F.at(pos).Literal(
                TypeTags.BOOLEAN,
                (S.token() == TRUE ? 1 : 0));
            break;
        case NULL:
            t = F.at(pos).Literal(
                TypeTags.BOT,
                null);
            break;
        default:
            assert false;
        }
        if (t == errorTree)
            t = F.at(pos).Erroneous();
        storeEnd(t, S.endPos());
        S.nextToken();
        return t;
    }
//where
        boolean isZero(String s) {
            if (s.length() == 1)
                return  '0' == s.charAt(0);
            char[] cs = s.toCharArray();
            int base = ((cs.length > 1 && Character.toLowerCase(cs[1]) == 'x') ? 16 : 10);
            int i = ((base==16) ? 2 : 0);
            while (i < cs.length && (cs[i] == '0' || cs[i] == '.')) i++;
            return !(i < cs.length && (Character.digit(cs[i], base) > 0));
        }

        String strval(Name prefix) {
            String s = S.stringVal();
            return prefix.isEmpty() ? s : prefix + s;
        }

    /** terms can be either expressions or types.
     */
    public JCExpression parseExpression() {
        return term(EXPR);
    }

    /**
     * parses (optional) type annotations followed by a type. If the
     * annotations are present before the type and are not consumed during array
     * parsing, this method returns a {@link JCAnnotatedType} consisting of
     * these annotations and the underlying type. Otherwise, it returns the
     * underlying type.
     *
     * <p>
     *
     * Note that this method sets {@code mode} to {@code TYPE} first, before
     * parsing annotations.
     */
    public JCExpression parseType() {
        List<JCTypeAnnotation> annotations = typeAnnotationsOpt();
        return parseType(annotations);
    }

    public JCExpression parseType(List<JCTypeAnnotation> annotations) {
        JCExpression result = unannotatedType();

        if (!annotations.isEmpty())
            result = F.AnnotatedType(annotations, result);

        return result;
    }

    public JCExpression unannotatedType() {
        return term(TYPE);
    }

    JCExpression term(int newmode) {
        int prevmode = mode;
        mode = newmode;
        JCExpression t = term();
        lastmode = mode;
        mode = prevmode;
        return t;
    }

    /**
     *  Expression = Expression1 [ExpressionRest]
     *  ExpressionRest = [AssignmentOperator Expression1]
     *  AssignmentOperator = "=" | "+=" | "-=" | "*=" | "/=" |
     *                       "&=" | "|=" | "^=" |
     *                       "%=" | "<<=" | ">>=" | ">>>="
     *  Type = Type1
     *  TypeNoParams = TypeNoParams1
     *  StatementExpression = Expression
     *  ConstantExpression = Expression
     */
    JCExpression term() {
        JCExpression t = term1();
        if ((mode & EXPR) != 0 &&
            S.token() == EQ || PLUSEQ.compareTo(S.token()) <= 0 && S.token().compareTo(GTGTGTEQ) <= 0)
            return termRest(t);
        else
            return t;
    }

    JCExpression termRest(JCExpression t) {
        switch (S.token()) {
        case EQ: {
            int pos = S.pos();
            S.nextToken();
            mode = EXPR;
            JCExpression t1 = term();
            return toP(F.at(pos).Assign(t, t1));
        }
        case PLUSEQ:
        case SUBEQ:
        case STAREQ:
        case SLASHEQ:
        case PERCENTEQ:
        case AMPEQ:
        case BAREQ:
        case CARETEQ:
        case LTLTEQ:
        case GTGTEQ:
        case GTGTGTEQ:
            int pos = S.pos();
            Token token = S.token();
            S.nextToken();
            mode = EXPR;
            JCExpression t1 = term();
            return F.at(pos).Assignop(optag(token), t, t1);
        default:
            return t;
        }
    }

    /** Expression1   = Expression2 [Expression1Rest]
     *  Type1         = Type2
     *  TypeNoParams1 = TypeNoParams2
     */
    JCExpression term1() {
        JCExpression t = term2();
        if ((mode & EXPR) != 0 && S.token() == QUES) {
            mode = EXPR;
            return term1Rest(t);
        } else {
            return t;
        }
    }

    /** Expression1Rest = ["?" Expression ":" Expression1]
     */
    JCExpression term1Rest(JCExpression t) {
        if (S.token() == QUES) {
            int pos = S.pos();
            S.nextToken();
            JCExpression t1 = term();
            accept(COLON);
            JCExpression t2 = term1();
            return F.at(pos).Conditional(t, t1, t2);
        } else {
            return t;
        }
    }

    /** Expression2   = Expression3 [Expression2Rest]
     *  Type2         = Type3
     *  TypeNoParams2 = TypeNoParams3
     */
    JCExpression term2() {
        JCExpression t = term3();
        if ((mode & EXPR) != 0 && prec(S.token()) >= TreeInfo.orPrec) {
            mode = EXPR;
            return term2Rest(t, TreeInfo.orPrec);
        } else {
            return t;
        }
    }

    /*  Expression2Rest = {infixop Expression3}
     *                  | Expression3 instanceof Type
     *  infixop         = "||"
     *                  | "&&"
     *                  | "|"
     *                  | "^"
     *                  | "&"
     *                  | "==" | "!="
     *                  | "<" | ">" | "<=" | ">="
     *                  | "<<" | ">>" | ">>>"
     *                  | "+" | "-"
     *                  | "*" | "/" | "%"
     */
    JCExpression term2Rest(JCExpression t, int minprec) {
        List<JCExpression[]> savedOd = odStackSupply.elems;
        JCExpression[] odStack = newOdStack();
        List<int[]> savedPos = posStackSupply.elems;
        int[] posStack = newPosStack();
        List<Token[]> savedOp = opStackSupply.elems;
        Token[] opStack = newOpStack();
        // optimization, was odStack = new Tree[...]; opStack = new Tree[...];
        int top = 0;
        odStack[0] = t;
        int startPos = S.pos();
        Token topOp = ERROR;
        while (prec(S.token()) >= minprec) {
            opStack[top] = topOp;
            top++;
            topOp = S.token();
            int pos = S.pos();
            S.nextToken();
            odStack[top] = topOp == INSTANCEOF ? parseType() : term3();
            posStack[top - 1] = pos;
            while (top > 0 && prec(topOp) >= prec(S.token())) {
                odStack[top-1] = makeOp(posStack[top - 1], topOp, odStack[top-1],
                                        odStack[top]);
                top--;
                topOp = opStack[top];
            }
        }
        assert top == 0;
        t = odStack[0];

        if (t.getTag() == JCTree.PLUS) {
            StringBuffer buf = foldStrings(t);
            if (buf != null) {
                t = toP(F.at(startPos).Literal(TypeTags.CLASS, buf.toString()));
            }
        }

        odStackSupply.elems = savedOd; // optimization
        posStackSupply.elems = savedPos; // optimization
        opStackSupply.elems = savedOp; // optimization
        return t;
    }
//where
        /** Construct a binary or type test node.
         */
        private JCExpression makeOp(int pos,
                                    Token topOp,
                                    JCExpression od1,
                                    JCExpression od2)
        {
            if (topOp == INSTANCEOF) {
                return F.at(pos).TypeTest(od1, od2);
            } else {
                return F.at(pos).Binary(optag(topOp), od1, od2);
            }
        }
        /** If tree is a concatenation of string literals, replace it
         *  by a single literal representing the concatenated string.
         */
        protected StringBuffer foldStrings(JCTree tree) {
            if (!allowStringFolding)
                return null;
            List<String> buf = List.nil();
            while (true) {
                if (tree.getTag() == JCTree.LITERAL) {
                    JCLiteral lit = (JCLiteral) tree;
                    if (lit.typetag == TypeTags.CLASS) {
                        StringBuffer sbuf =
                            new StringBuffer((String)lit.value);
                        while (buf.nonEmpty()) {
                            sbuf.append(buf.head);
                            buf = buf.tail;
                        }
                        return sbuf;
                    }
                } else if (tree.getTag() == JCTree.PLUS) {
                    JCBinary op = (JCBinary)tree;
                    if (op.rhs.getTag() == JCTree.LITERAL) {
                        JCLiteral lit = (JCLiteral) op.rhs;
                        if (lit.typetag == TypeTags.CLASS) {
                            buf = buf.prepend((String) lit.value);
                            tree = op.lhs;
                            continue;
                        }
                    }
                }
                return null;
            }
        }

        /** optimization: To save allocating a new operand/operator stack
         *  for every binary operation, we use supplys.
         */
        ListBuffer<JCExpression[]> odStackSupply = new ListBuffer<JCExpression[]>();
        ListBuffer<int[]> posStackSupply = new ListBuffer<int[]>();
        ListBuffer<Token[]> opStackSupply = new ListBuffer<Token[]>();

        private JCExpression[] newOdStack() {
            if (odStackSupply.elems == odStackSupply.last)
                odStackSupply.append(new JCExpression[infixPrecedenceLevels + 1]);
            JCExpression[] odStack = odStackSupply.elems.head;
            odStackSupply.elems = odStackSupply.elems.tail;
            return odStack;
        }

        private int[] newPosStack() {
            if (posStackSupply.elems == posStackSupply.last)
                posStackSupply.append(new int[infixPrecedenceLevels + 1]);
            int[] posStack = posStackSupply.elems.head;
            posStackSupply.elems = posStackSupply.elems.tail;
            return posStack;
        }

        private Token[] newOpStack() {
            if (opStackSupply.elems == opStackSupply.last)
                opStackSupply.append(new Token[infixPrecedenceLevels + 1]);
            Token[] opStack = opStackSupply.elems.head;
            opStackSupply.elems = opStackSupply.elems.tail;
            return opStack;
        }

    /** Expression3    = PrefixOp Expression3
     *                 | "(" Expr | TypeNoParams ")" Expression3
     *                 | Primary {Selector} {PostfixOp}
     *  Primary        = "(" Expression ")"
     *                 | Literal
     *                 | [TypeArguments] THIS [Arguments]
     *                 | [TypeArguments] SUPER SuperSuffix
     *                 | NEW [TypeArguments] Creator
     *                 | [Annotations] Ident { "." Ident }
     *                   [ [Annotations] "[" ( "]" BracketsOpt "." CLASS | Expression "]" )
     *                   | Arguments
     *                   | "." ( CLASS | THIS | [TypeArguments] SUPER Arguments | NEW [TypeArguments] InnerCreator )
     *                   ]
     *                 | BasicType BracketsOpt "." CLASS
     *  PrefixOp       = "++" | "--" | "!" | "~" | "+" | "-"
     *  PostfixOp      = "++" | "--"
     *  Type3          = Ident { "." Ident } [TypeArguments] {TypeSelector} BracketsOpt
     *                 | BasicType
     *  TypeNoParams3  = Ident { "." Ident } BracketsOpt
     *  Selector       = "." [TypeArguments] Ident [Arguments]
     *                 | "." THIS
     *                 | "." [TypeArguments] SUPER SuperSuffix
     *                 | "." NEW [TypeArguments] InnerCreator
     *                 | "[" Expression "]"
     *  TypeSelector   = "." Ident [TypeArguments]
     *  SuperSuffix    = Arguments | "." Ident [Arguments]
     */
    protected JCExpression term3() {
        int pos = S.pos();
        JCExpression t;
        int prevmode = mode;
        List<JCExpression> typeArgs = typeArgumentsOpt(EXPR);
        if (typeArgs != null && S.pos() <= errorEndPos) {
            // error recovery
            mode = prevmode;
            return F.at(pos).Erroneous(typeArgs);
        }
        switch (S.token()) {
        case QUES:
            if ((mode & TYPE) != 0 && (mode & (TYPEARG|NOPARAMS)) == TYPEARG) {
                mode = TYPE;
                return typeArgument();
            } else
                return illegal();
        case PLUSPLUS: case SUBSUB: case BANG: case TILDE: case PLUS: case SUB:
            if (typeArgs == null && (mode & EXPR) != 0) {
                Token token = S.token();
                S.nextToken();
                mode = EXPR;
                if (token == SUB &&
                    (S.token() == INTLITERAL || S.token() == LONGLITERAL) &&
                    S.radix() == 10) {
                    mode = EXPR;
                    t = literal(names.hyphen, pos);
                } else {
                    t = term3();
                    return F.at(pos).Unary(unoptag(token), t);
                }
            } else return illegal();
            break;
        case LPAREN:
            if (typeArgs == null && (mode & EXPR) != 0) {
                S.nextToken();
                mode = EXPR | TYPE | NOPARAMS;
                t = term3();
                if ((mode & TYPE) != 0 && S.token() == LT) {
                    // Could be a cast to a parameterized type
                    int op = JCTree.LT;
                    int pos1 = S.pos();
                    S.nextToken();
                    mode &= (EXPR | TYPE);
                    mode |= TYPEARG;
                    JCExpression t1 = term3();
                    if ((mode & TYPE) != 0 &&
                        (S.token() == COMMA || S.token() == GT)) {
                        mode = TYPE;
                        ListBuffer<JCExpression> args = new ListBuffer<JCExpression>();
                        args.append(t1);
                        while (S.token() == COMMA) {
                            S.nextToken();
                            args.append(typeArgument());
                        }
                        accept(GT);
                        t = F.at(pos1).TypeApply(t, args.toList());
                        checkGenerics();
                        while (S.token() == DOT) {
                            S.nextToken();
                            mode = TYPE;
                            t = toP(F.at(S.pos()).Select(t, ident()));
                            t = typeArgumentsOpt(t);
                        }
                        t = bracketsOpt(toP(t));
                    } else if ((mode & EXPR) != 0) {
                        mode = EXPR;
                        t = F.at(pos1).Binary(op, t, term2Rest(t1, TreeInfo.shiftPrec));
                        t = termRest(term1Rest(term2Rest(t, TreeInfo.orPrec)));
                    } else {
                        accept(GT);
                    }
                }
                else {
                    t = termRest(term1Rest(term2Rest(t, TreeInfo.orPrec)));
                }
                accept(RPAREN);
                lastmode = mode;
                mode = EXPR;
                if ((lastmode & EXPR) == 0) {
                    JCExpression t1 = term3();
                    return F.at(pos).TypeCast(t, t1);
                } else if ((lastmode & TYPE) != 0) {
                    switch (S.token()) {
                    /*case PLUSPLUS: case SUBSUB: */
                    case BANG: case TILDE:
                    case LPAREN: case THIS: case SUPER:
                    case INTLITERAL: case LONGLITERAL: case FLOATLITERAL:
                    case DOUBLELITERAL: case CHARLITERAL: case STRINGLITERAL:
                    case TRUE: case FALSE: case NULL:
                    case NEW: case IDENTIFIER: case ASSERT: case ENUM:
                    case BYTE: case SHORT: case CHAR: case INT:
                    case LONG: case FLOAT: case DOUBLE: case BOOLEAN: case VOID:
                        JCExpression t1 = term3();
                        return F.at(pos).TypeCast(t, t1);
                    }
                }
            } else return illegal();
            t = toP(F.at(pos).Parens(t));
            break;
        case THIS:
            if ((mode & EXPR) != 0) {
                mode = EXPR;
                t = to(F.at(pos).Ident(names._this));
                S.nextToken();
                if (typeArgs == null)
                    t = argumentsOpt(null, t);
                else
                    t = arguments(typeArgs, t);
                typeArgs = null;
            } else return illegal();
            break;
        case SUPER:
            if ((mode & EXPR) != 0) {
                mode = EXPR;
                t = superSuffix(typeArgs, to(F.at(pos).Ident(names._super)));
                typeArgs = null;
            } else return illegal();
            break;
        case INTLITERAL: case LONGLITERAL: case FLOATLITERAL: case DOUBLELITERAL:
        case CHARLITERAL: case STRINGLITERAL:
        case TRUE: case FALSE: case NULL:
            if (typeArgs == null && (mode & EXPR) != 0) {
                mode = EXPR;
                t = literal(names.empty, S.pos());
            } else return illegal();
            break;
        case NEW:
            if (typeArgs != null) return illegal();
            if ((mode & EXPR) != 0) {
                mode = EXPR;
                S.nextToken();
                if (S.token() == LT) typeArgs = typeArguments();
                t = creator(pos, typeArgs);
                typeArgs = null;
            } else return illegal();
            break;
        case MONKEYS_AT:

            // only annotated targetting class literals or cast types are valid
            List<JCTypeAnnotation> typeAnnos = typeAnnotationsOpt();
            if (typeAnnos.isEmpty()) {
                // else there would be no '@'
                throw new AssertionError("type annos is empty");
            }

            JCExpression expr = term3();

            // Type annotations: If term3 just parsed a non-type, expect a
            // class literal (and issue a syntax error if there is no class
            // literal). Otherwise, create a JCAnnotatedType.
            if ((mode & TYPE) == 0) {
                if (expr.getTag() != JCTree.SELECT)
                    return illegal(typeAnnos.head.pos);
                JCFieldAccess sel = (JCFieldAccess)expr;
                if (sel.name != names._class)
                    return illegal();
                else {
                    sel.selected = F.AnnotatedType(typeAnnos, sel.selected);
                    t = expr;
                }
            } else {
                // type annotation targeting a cast
                t = toP(F.at(S.pos()).AnnotatedType(typeAnnos, expr));
            }
            break;
        case IDENTIFIER: case ASSERT: case ENUM:
            if (typeArgs != null) return illegal();
            t = toP(F.at(S.pos()).Ident(ident()));
            loop: while (true) {
                pos = S.pos();
                final List<JCTypeAnnotation> annos = typeAnnotationsOpt();

                // need to report an error later if LBRACKET is for array
                // index access rather than array creation level
                if (!annos.isEmpty() && S.token() != LBRACKET && S.token() != ELLIPSIS)
                    return illegal(annos.head.pos);
                switch (S.token()) {
                case LBRACKET:
                    S.nextToken();

                    if (S.token() == RBRACKET) {

                        S.nextToken();

                        t = bracketsOpt(t, annos);
                        t = toP(F.at(pos).TypeArray(t));
                        t = bracketsSuffix(t);
                    } else {
                        if ((mode & EXPR) != 0) {
                            mode = EXPR;
                            JCExpression t1 = term();
                            if (!annos.isEmpty()) t = illegal(annos.head.pos);
                            t = to(F.at(pos).Indexed(t, t1));
                        }
                        accept(RBRACKET);
                    }
                    break loop;
                case LPAREN:
                    if ((mode & EXPR) != 0) {
                        mode = EXPR;
                        t = arguments(typeArgs, t);
                        typeArgs = null;
                    }
                    break loop;
                case DOT:
                    S.nextToken();
                    int oldmode = mode;
                    mode &= ~NOPARAMS;
                    typeArgs = typeArgumentsOpt(EXPR);
                    mode = oldmode;
                    if ((mode & EXPR) != 0) {
                        switch (S.token()) {
                        case CLASS:
                            if (typeArgs != null) return illegal();
                            mode = EXPR;
                            t = to(F.at(pos).Select(t, names._class));
                            S.nextToken();
                            break loop;
                        case THIS:
                            if (typeArgs != null) return illegal();
                            mode = EXPR;
                            t = to(F.at(pos).Select(t, names._this));
                            S.nextToken();
                            break loop;
                        case SUPER:
                            mode = EXPR;
                            t = to(F.at(pos).Select(t, names._super));
                            t = superSuffix(typeArgs, t);
                            typeArgs = null;
                            break loop;
                        case NEW:
                            if (typeArgs != null) return illegal();
                            mode = EXPR;
                            int pos1 = S.pos();
                            S.nextToken();
                            if (S.token() == LT) typeArgs = typeArguments();
                            t = innerCreator(pos1, typeArgs, t);
                            typeArgs = null;
                            break loop;
                        }
                    }
                    // typeArgs saved for next loop iteration.
                    t = toP(F.at(pos).Select(t, ident()));
                    break;
                case ELLIPSIS:
                    if (this.permitTypeAnnotationsPushBack)
                        typeAnnotationsPushedBack = annos;
                    break loop;
                default:
                    break loop;
                }
            }
            if (typeArgs != null) illegal();
            t = typeArgumentsOpt(t);
            break;
        case BYTE: case SHORT: case CHAR: case INT: case LONG: case FLOAT:
        case DOUBLE: case BOOLEAN:
            if (typeArgs != null) illegal();
            t = bracketsSuffix(bracketsOpt(basicType()));
            break;
        case VOID:
            if (typeArgs != null) illegal();
            if ((mode & EXPR) != 0) {
                S.nextToken();
                if (S.token() == DOT) {
                    JCPrimitiveTypeTree ti = toP(F.at(pos).TypeIdent(TypeTags.VOID));
                    t = bracketsSuffix(ti);
                } else {
                    return illegal(pos);
                }
            } else {
                // Support the corner case of myMethodHandle.<void>invoke() by passing
                // a void type (like other primitive types) to the next phase.
                // The error will be reported in Attr.attribTypes or Attr.visitApply.
                JCPrimitiveTypeTree ti = to(F.at(pos).TypeIdent(TypeTags.VOID));
                S.nextToken();
                return ti;
                //return illegal();
            }
            break;
        default:
            return illegal();
        }
        if (typeArgs != null) illegal();
        while (true) {
            int pos1 = S.pos();

            final List<JCTypeAnnotation> annos = typeAnnotationsOpt();

            if (S.token() == LBRACKET) {
                S.nextToken();

                if ((mode & TYPE) != 0) {
                    int oldmode = mode;
                    mode = TYPE;
                    if (S.token() == RBRACKET) {
                        S.nextToken();
                        t = bracketsOpt(t, annos);
                        t = toP(F.at(pos1).TypeArray(t));
                        return t;
                    }
                    mode = oldmode;
                }
                if ((mode & EXPR) != 0) {
                    mode = EXPR;
                    JCExpression t1 = term();
                    t = to(F.at(pos1).Indexed(t, t1));
                }
                accept(RBRACKET);
            } else if (S.token() == DOT) {
                S.nextToken();
                typeArgs = typeArgumentsOpt(EXPR);
                if (S.token() == SUPER && (mode & EXPR) != 0) {
                    mode = EXPR;
                    t = to(F.at(pos1).Select(t, names._super));
                    S.nextToken();
                    t = arguments(typeArgs, t);
                    typeArgs = null;
                } else if (S.token() == NEW && (mode & EXPR) != 0) {
                    if (typeArgs != null) return illegal();
                    mode = EXPR;
                    int pos2 = S.pos();
                    S.nextToken();
                    if (S.token() == LT) typeArgs = typeArguments();
                    t = innerCreator(pos2, typeArgs, t);
                    typeArgs = null;
                } else {
                    t = toP(F.at(pos1).Select(t, ident()));
                    t = argumentsOpt(typeArgs, typeArgumentsOpt(t));
                    typeArgs = null;
                }
            } else {
                if (!annos.isEmpty()) {
                    if (permitTypeAnnotationsPushBack)
                        typeAnnotationsPushedBack = annos;
                    else
                        return illegal(annos.head.pos);
                }
                break;
            }
        }
        while ((S.token() == PLUSPLUS || S.token() == SUBSUB) && (mode & EXPR) != 0) {
            mode = EXPR;
            t = to(F.at(S.pos()).Unary(
                  S.token() == PLUSPLUS ? JCTree.POSTINC : JCTree.POSTDEC, t));
            S.nextToken();
        }

        return toP(t);
    }

    /** SuperSuffix = Arguments | "." [TypeArguments] Ident [Arguments]
     */
    JCExpression superSuffix(List<JCExpression> typeArgs, JCExpression t) {
        S.nextToken();
        if (S.token() == LPAREN || typeArgs != null) {
            t = arguments(typeArgs, t);
        } else {
            int pos = S.pos();
            accept(DOT);
            typeArgs = (S.token() == LT) ? typeArguments() : null;
            t = toP(F.at(pos).Select(t, ident()));
            t = argumentsOpt(typeArgs, t);
        }
        return t;
    }

    /** BasicType = BYTE | SHORT | CHAR | INT | LONG | FLOAT | DOUBLE | BOOLEAN
     */
    JCPrimitiveTypeTree basicType() {
        JCPrimitiveTypeTree t = to(F.at(S.pos()).TypeIdent(typetag(S.token())));
        S.nextToken();
        return t;
    }

    /** ArgumentsOpt = [ Arguments ]
     */
    JCExpression argumentsOpt(List<JCExpression> typeArgs, JCExpression t) {
        if ((mode & EXPR) != 0 && S.token() == LPAREN || typeArgs != null) {
            mode = EXPR;
            return arguments(typeArgs, t);
        } else {
            return t;
        }
    }

    /** Arguments = "(" [Expression { COMMA Expression }] ")"
     */
    List<JCExpression> arguments() {
        ListBuffer<JCExpression> args = lb();
        if (S.token() == LPAREN) {
            S.nextToken();
            if (S.token() != RPAREN) {
                args.append(parseExpression());
                while (S.token() == COMMA) {
                    S.nextToken();
                    args.append(parseExpression());
                }
            }
            accept(RPAREN);
        } else {
            syntaxError(S.pos(), "expected", LPAREN);
        }
        return args.toList();
    }

    JCMethodInvocation arguments(List<JCExpression> typeArgs, JCExpression t) {
        int pos = S.pos();
        List<JCExpression> args = arguments();
        return toP(F.at(pos).Apply(typeArgs, t, args));
    }

    /**  TypeArgumentsOpt = [ TypeArguments ]
     */
    JCExpression typeArgumentsOpt(JCExpression t) {
        if (S.token() == LT &&
            (mode & TYPE) != 0 &&
            (mode & NOPARAMS) == 0) {
            mode = TYPE;
            checkGenerics();
            return typeArguments(t);
        } else {
            return t;
        }
    }
    List<JCExpression> typeArgumentsOpt() {
        return typeArgumentsOpt(TYPE);
    }

    List<JCExpression> typeArgumentsOpt(int useMode) {
        if (S.token() == LT) {
            checkGenerics();
            if ((mode & useMode) == 0 ||
                (mode & NOPARAMS) != 0) {
                illegal();
            }
            mode = useMode;
            return typeArguments();
        }
        return null;
    }

    /**  TypeArguments  = "<" TypeArgument {"," TypeArgument} ">"
     */
    List<JCExpression> typeArguments() {
        ListBuffer<JCExpression> args = lb();
        if (S.token() == LT) {
            S.nextToken();
            if (S.token() == GT && (mode & DIAMOND) != 0) {
                checkDiamond();
                S.nextToken();
                return List.nil();
            }
            args.append(((mode & EXPR) == 0) ? typeArgument() : parseType());
            while (S.token() == COMMA) {
                S.nextToken();
                args.append(((mode & EXPR) == 0) ? typeArgument() : parseType());
            }
            switch (S.token()) {
            case GTGTGTEQ:
                S.token(GTGTEQ);
                break;
            case GTGTEQ:
                S.token(GTEQ);
                break;
            case GTEQ:
                S.token(EQ);
                break;
            case GTGTGT:
                S.token(GTGT);
                break;
            case GTGT:
                S.token(GT);
                break;
            default:
                accept(GT);
                break;
            }
        } else {
            syntaxError(S.pos(), "expected", LT);
        }
        return args.toList();
    }

    /** TypeArgument = Type
     *               | [Annotations] "?"
     *               | [Annotations] "?" EXTENDS Type {"&" Type}
     *               | [Annotations] "?" SUPER Type
     */
    JCExpression typeArgument() {
        List<JCTypeAnnotation> annotations = typeAnnotationsOpt();
        if (S.token() != QUES) return parseType(annotations);
        int pos = S.pos();
        S.nextToken();
        JCExpression result;
        if (S.token() == EXTENDS) {
            TypeBoundKind t = to(F.at(S.pos()).TypeBoundKind(BoundKind.EXTENDS));
            S.nextToken();
            JCExpression type = parseType();
            result = F.at(pos).Wildcard(t, type);
        } else if (S.token() == SUPER) {
            TypeBoundKind t = to(F.at(S.pos()).TypeBoundKind(BoundKind.SUPER));
            S.nextToken();
            JCExpression type = parseType();
            result = F.at(pos).Wildcard(t, type);
        } else if (S.token() == IDENTIFIER) {
            //error recovery
            TypeBoundKind t = F.at(Position.NOPOS).TypeBoundKind(BoundKind.UNBOUND);
            JCExpression wc = toP(F.at(pos).Wildcard(t, null));
            JCIdent id = toP(F.at(S.pos()).Ident(ident()));
            JCErroneous err = F.at(pos).Erroneous(List.<JCTree>of(wc, id));
            reportSyntaxError(err, S.prevEndPos(), "expected3",
                    GT, EXTENDS, SUPER);
            result = err;
        } else {
            TypeBoundKind t = F.at(Position.NOPOS).TypeBoundKind(BoundKind.UNBOUND);
            result = toP(F.at(pos).Wildcard(t, null));
        }
        if (!annotations.isEmpty())
            result = toP(F.at(annotations.head.pos).AnnotatedType(annotations,result));
        return result;
    }

    JCTypeApply typeArguments(JCExpression t) {
        int pos = S.pos();
        List<JCExpression> args = typeArguments();
        return toP(F.at(pos).TypeApply(t, args));
    }

    /**
     * BracketsOpt = { [Annotations] "[" "]" }
     *
     * <p>
     *
     * <code>annotations</code> is the list of annotations targeting
     * the expression <code>t</code>.
     */
    private JCExpression bracketsOpt(JCExpression t,
            List<JCTypeAnnotation> annotations) {
        List<JCTypeAnnotation> nextLevelAnnotations = typeAnnotationsOpt();

        if (S.token() == LBRACKET) {
            int pos = S.pos();
            S.nextToken();

            JCExpression orig = t;
            t = bracketsOptCont(t, pos, nextLevelAnnotations);
        } else if (!nextLevelAnnotations.isEmpty()) {
            if (permitTypeAnnotationsPushBack) {
                this.typeAnnotationsPushedBack = nextLevelAnnotations;
            } else
                return illegal(nextLevelAnnotations.head.pos);
        }

        int apos = S.pos();
        if (!annotations.isEmpty())
            t = F.at(apos).AnnotatedType(annotations, t);
        return t;
    }

    /** BracketsOpt = {"[" TypeAnnotations "]"}
     */
    private JCExpression bracketsOpt(JCExpression t) {
        return bracketsOpt(t, List.<JCTypeAnnotation>nil());
    }

    private JCArrayTypeTree bracketsOptCont(JCExpression t, int pos,
            List<JCTypeAnnotation> annotations) {
        accept(RBRACKET);
        t = bracketsOpt(t, annotations);
        return toP(F.at(pos).TypeArray(t));
    }

    /** BracketsSuffixExpr = "." CLASS
     *  BracketsSuffixType =
     */
    JCExpression bracketsSuffix(JCExpression t) {
        if ((mode & EXPR) != 0 && S.token() == DOT) {
            mode = EXPR;
            int pos = S.pos();
            S.nextToken();
            accept(CLASS);
            if (S.pos() == errorEndPos) {
                // error recovery
                Name name = null;
                if (S.token() == IDENTIFIER) {
                    name = S.name();
                    S.nextToken();
                } else {
                    name = names.error;
                }
                t = F.at(pos).Erroneous(List.<JCTree>of(toP(F.at(pos).Select(t, name))));
            } else {
                t = toP(F.at(pos).Select(t, names._class));
            }
        } else if ((mode & TYPE) != 0) {
            mode = TYPE;
        } else {
            syntaxError(S.pos(), "dot.class.expected");
        }
        return t;
    }

    /** Creator = [Annotations] Qualident [TypeArguments] ( ArrayCreatorRest | ClassCreatorRest )
     */
    JCExpression creator(int newpos, List<JCExpression> typeArgs) {

        List<JCTypeAnnotation> newAnnotations = typeAnnotationsOpt();

        switch (S.token()) {
        case BYTE: case SHORT: case CHAR: case INT: case LONG: case FLOAT:
        case DOUBLE: case BOOLEAN:
            if (typeArgs == null) {
                if (newAnnotations.isEmpty())
                    return arrayCreatorRest(newpos, basicType());
                else
                    return arrayCreatorRest(newpos, F.AnnotatedType(newAnnotations, basicType()));
            }
            break;
        default:
        }
        JCExpression t = qualident();
        // handle type annotations for non primitive arrays
        if (!newAnnotations.isEmpty())
            t = F.AnnotatedType(newAnnotations, t);

        int oldmode = mode;
        mode = TYPE | DIAMOND;
        if (S.token() == LT) {
            checkGenerics();
            t = typeArguments(t);
        }
        while (S.token() == DOT) {
            int pos = S.pos();
            S.nextToken();
            t = toP(F.at(pos).Select(t, ident()));
            if (S.token() == LT) {
                checkGenerics();
                t = typeArguments(t);
            }
        }
        mode = oldmode;
        if (S.token() == LBRACKET || S.token() == MONKEYS_AT) {
            JCExpression e = arrayCreatorRest(newpos, t);
            if (typeArgs != null) {
                int pos = newpos;
                if (!typeArgs.isEmpty() && typeArgs.head.pos != Position.NOPOS) {
                    // note: this should always happen but we should
                    // not rely on this as the parser is continuously
                    // modified to improve error recovery.
                    pos = typeArgs.head.pos;
                }
                setErrorEndPos(S.prevEndPos());
                JCErroneous err = F.at(pos).Erroneous(typeArgs.prepend(e));
                reportSyntaxError(err, pos, "cannot.create.array.with.type.arguments");
                return toP(err);
            }
            return e;
        } else if (S.token() == LPAREN) {
            JCNewClass newClass = classCreatorRest(newpos, null, typeArgs, t);
            if (newClass.def != null) {
                assert newClass.def.mods.annotations.isEmpty();
                newClass.def.mods.annotations = List.convert(JCAnnotation.class, newAnnotations);
            }
            return newClass;
        } else {
            errorEndPos = S.pos();
            t = toP(F.at(newpos).NewClass(null, typeArgs, t, List.<JCExpression>nil(), null));
            JCErroneous err = F.at(newpos).Erroneous(List.<JCTree>of(t));
            reportSyntaxError(err, S.pos(), "expected2",
                               LPAREN, LBRACKET);
            return toP(err);
        }
    }

    /** InnerCreator = Ident [TypeArguments] ClassCreatorRest
     */
    JCExpression innerCreator(int newpos, List<JCExpression> typeArgs, JCExpression encl) {
        JCExpression t = toP(F.at(S.pos()).Ident(ident()));
        if (S.token() == LT) {
            int oldmode = mode;
            mode |= DIAMOND;
            checkGenerics();
            t = typeArguments(t);
            mode = oldmode;
        }
        return classCreatorRest(newpos, encl, typeArgs, t);
    }

    /** ArrayCreatorRest = [Annotations] "[" ( "]" BracketsOpt ArrayInitializer
     *                         | Expression "]" {[Annotations]  "[" Expression "]"} BracketsOpt )
     */
    JCExpression arrayCreatorRest(int newpos, JCExpression elemtype) {

        List<JCTypeAnnotation> topAnnos = List.nil();
        if (elemtype.getTag() == JCTree.ANNOTATED_TYPE) {
            JCAnnotatedType atype = (JCAnnotatedType) elemtype;
            topAnnos = atype.annotations;
            elemtype = atype.underlyingType;
        }

        List<JCTypeAnnotation> annos = typeAnnotationsOpt();

        accept(LBRACKET);

        if (S.token() == RBRACKET) {
            accept(RBRACKET);

            elemtype = bracketsOpt(elemtype, annos);

            if (S.token() == LBRACE) {
                JCNewArray na = (JCNewArray)arrayInitializer(newpos, elemtype);

                na.annotations = topAnnos;

                return na;
            } else {
                return syntaxError(S.pos(), List.<JCTree>of(toP(F.at(newpos).NewArray(elemtype, List.<JCExpression>nil(), null))), "array.dimension.missing");
            }
        } else {
            ListBuffer<JCExpression> dims = new ListBuffer<JCExpression>();

            // maintain array dimension type annotations
            ListBuffer<List<JCTypeAnnotation>> dimAnnotations = ListBuffer.lb();
            dimAnnotations.append(annos);

            dims.append(parseExpression());
            accept(RBRACKET);
            while (S.token() == LBRACKET
                    || (S.token() == MONKEYS_AT)) {
                List<JCTypeAnnotation> maybeDimAnnos = typeAnnotationsOpt();
                int pos = S.pos();
                S.nextToken();
                if (S.token() == RBRACKET) {
                    elemtype = bracketsOptCont(elemtype, pos, maybeDimAnnos);
                } else {
                    if (S.token() == RBRACKET) { // no dimension
                        elemtype = bracketsOptCont(elemtype, pos, maybeDimAnnos);
                    } else {
                        dimAnnotations.append(maybeDimAnnos);
                        dims.append(parseExpression());
                        accept(RBRACKET);
                    }
                }
            }

            JCNewArray na = toP(F.at(newpos).NewArray(elemtype, dims.toList(), null));
            na.annotations = topAnnos;
            na.dimAnnotations = dimAnnotations.toList();
            return na;
        }
    }

    /** ClassCreatorRest = Arguments [ClassBody]
     */
    JCNewClass classCreatorRest(int newpos,
                                  JCExpression encl,
                                  List<JCExpression> typeArgs,
                                  JCExpression t)
    {
        List<JCExpression> args = arguments();
        JCClassDecl body = null;
        if (S.token() == LBRACE) {
            int pos = 0;
            List<JCTree> defs = null;
            JCModifiers mods = null;
            pos = S.pos();
            defs = classOrInterfaceBody(names.empty, false);
            mods = F.at(Position.NOPOS).Modifiers(0);
            body = toP(F.at(pos).AnonymousClassDef(mods, defs));
        }
        return toP(F.at(newpos).NewClass(encl, typeArgs, t, args, body));
    }

    /** ArrayInitializer = "{" [VariableInitializer {"," VariableInitializer}] [","] "}"
     */
    JCExpression arrayInitializer(int newpos, JCExpression t) {
        accept(LBRACE);
        ListBuffer<JCExpression> elems = new ListBuffer<JCExpression>();
        if (S.token() == COMMA) {
            S.nextToken();
        } else if (S.token() != RBRACE) {
            elems.append(variableInitializer());
            while (S.token() == COMMA) {
                S.nextToken();
                if (S.token() == RBRACE) break;
                elems.append(variableInitializer());
            }
            if (S.pos() <= errorEndPos)
                skip(false, true, true, true);
        }
        accept(RBRACE);
        return toP(F.at(newpos).NewArray(t, List.<JCExpression>nil(), elems.toList()));
    }

    /** VariableInitializer = ArrayInitializer | Expression
     */
    public JCExpression variableInitializer() {
        return S.token() == LBRACE ? arrayInitializer(S.pos(), null) : parseExpression();
    }

    /** ParExpression = "(" Expression ")"
     */
    JCExpression parExpression() {
        accept(LPAREN);
        JCExpression t = parseExpression();
        accept(RPAREN);
        return t;
    }

    /** Block = "{" BlockStatements "}"
     */
    JCBlock block(int pos, long flags) {
        accept(LBRACE);
        List<JCStatement> stats = blockStatements();
        JCBlock t = F.at(pos).Block(flags, stats);
        while (S.token() == CASE || S.token() == DEFAULT) {
            syntaxError("orphaned", S.token());
            switchBlockStatementGroups();
        }
        // the Block node has a field "endpos" for first char of last token, which is
        // usually but not necessarily the last char of the last token.
        t.endpos = S.pos();
        accept(RBRACE);
        return toP(t);
    }

    public JCBlock block() {
        return block(S.pos(), 0);
    }

    /** BlockStatements = { BlockStatement }
     *  BlockStatement  = LocalVariableDeclarationStatement
     *                  | ClassOrInterfaceOrEnumDeclaration
     *                  | [Ident ":"] Statement
     *  LocalVariableDeclarationStatement
     *                  = { FINAL | '@' Annotation } Type VariableDeclarators ";"
     */
    @SuppressWarnings("fallthrough")
    List<JCStatement> blockStatements() {
//todo: skip to anchor on error(?)
        int lastErrPos = -1;
        ListBuffer<JCStatement> stats = new ListBuffer<JCStatement>();
        while (true) {
            int pos = S.pos();
            switch (S.token()) {
            case RBRACE: case CASE: case DEFAULT: case EOF:
                return stats.toList();
            case LBRACE: case IF: case FOR: case WHILE: case DO: case TRY:
            case SWITCH: case SYNCHRONIZED: case RETURN: case THROW: case BREAK:
            case CONTINUE: case SEMI: case ELSE: case FINALLY: case CATCH:
                stats.append(parseStatement());
                break;
            case MONKEYS_AT:
            case FINAL: {
                String dc = S.docComment();
                JCModifiers mods = modifiersOpt();
                if (S.token() == INTERFACE ||
                    S.token() == CLASS ||
                    allowEnums && S.token() == ENUM) {
                    stats.append(classOrInterfaceOrEnumDeclaration(mods, dc));
                } else {
                    JCExpression t = parseType();
                    stats.appendList(variableDeclarators(mods, t,
                                                         new ListBuffer<JCStatement>()));
                    // A "LocalVariableDeclarationStatement" subsumes the terminating semicolon
                    storeEnd(stats.elems.last(), S.endPos());
                    accept(SEMI);
                }
                break;
            }
            case ABSTRACT: case STRICTFP: {
                String dc = S.docComment();
                JCModifiers mods = modifiersOpt();
                if (S.token() == INTERFACE ||
                    S.token() == CLASS ||
                    allowEnums && S.token() == ENUM) {
                    stats.append(classOrInterfaceOrEnumDeclaration(mods, dc));
                } else {
                    setErrorEndPos(S.pos());
                }
                break;
            }
            case INTERFACE:
            case CLASS:
                stats.append(classOrInterfaceOrEnumDeclaration(modifiersOpt(),
                                                               S.docComment()));
                break;
            case ENUM:
            case ASSERT:
                if (allowEnums && S.token() == ENUM) {
                    log.error(S.pos(), "local.enum");
                    stats.
                        append(classOrInterfaceOrEnumDeclaration(modifiersOpt(),
                                                                 S.docComment()));
                    break;
                } else if (allowAsserts && S.token() == ASSERT) {
                    stats.append(parseStatement());
                    break;
                }
                /* fall through to default */
            default:
                Name name = S.name();
                JCExpression t = term(EXPR | TYPE);
                if (S.token() == COLON && t.getTag() == JCTree.IDENT) {
                    S.nextToken();
                    JCStatement stat = parseStatement();
                    stats.append(F.at(pos).Labelled(name, stat));
                } else if ((lastmode & TYPE) != 0 &&
                           (S.token() == IDENTIFIER ||
                            S.token() == ASSERT ||
                            S.token() == ENUM)) {
                    pos = S.pos();
                    JCModifiers mods = F.at(Position.NOPOS).Modifiers(0);
                    F.at(pos);
                    stats.appendList(variableDeclarators(mods, t,
                                                         new ListBuffer<JCStatement>()));
                    // A "LocalVariableDeclarationStatement" subsumes the terminating semicolon
                    accept(SEMI);
                    storeEnd(stats.elems.last(), S.prevEndPos());
                } else {
                    // This Exec is an "ExpressionStatement"; it subsumes the terminating semicolon
                    accept(SEMI);
                    stats.append(toP(F.at(pos).Exec(checkExprStat(t))));
                }
            }

            // error recovery
            if (S.pos() == lastErrPos)
                return stats.toList();
            if (S.pos() <= errorEndPos) {
                skip(false, true, true, true);
                lastErrPos = S.pos();
            }

            // ensure no dangling /** @deprecated */ active
            S.resetDeprecatedFlag();
        }
    }

    /** Statement =
     *       Block
     *     | IF ParExpression Statement [ELSE Statement]
     *     | FOR "(" ForInitOpt ";" [Expression] ";" ForUpdateOpt ")" Statement
     *     | FOR "(" FormalParameter : Expression ")" Statement
     *     | WHILE ParExpression Statement
     *     | DO Statement WHILE ParExpression ";"
     *     | TRY Block ( Catches | [Catches] FinallyPart )
     *     | SWITCH ParExpression "{" SwitchBlockStatementGroups "}"
     *     | SYNCHRONIZED ParExpression Block
     *     | RETURN [Expression] ";"
     *     | THROW Expression ";"
     *     | BREAK [Ident] ";"
     *     | CONTINUE [Ident] ";"
     *     | ASSERT Expression [ ":" Expression ] ";"
     *     | ";"
     *     | ExpressionStatement
     *     | Ident ":" Statement
     */
    @SuppressWarnings("fallthrough")
    public JCStatement parseStatement() {
        int pos = S.pos();
        switch (S.token()) {
        case LBRACE:
            return block();
        case IF: {
            S.nextToken();
            JCExpression cond = parExpression();
            JCStatement thenpart = parseStatement();
            JCStatement elsepart = null;
            if (S.token() == ELSE) {
                S.nextToken();
                elsepart = parseStatement();
            }
            return F.at(pos).If(cond, thenpart, elsepart);
        }
        case FOR: {
            S.nextToken();
            accept(LPAREN);
            List<JCStatement> inits = S.token() == SEMI ? List.<JCStatement>nil() : forInit();
            if (inits.length() == 1 &&
                inits.head.getTag() == JCTree.VARDEF &&
                ((JCVariableDecl) inits.head).init == null &&
                S.token() == COLON) {
                checkForeach();
                JCVariableDecl var = (JCVariableDecl)inits.head;
                accept(COLON);
                JCExpression expr = parseExpression();
                accept(RPAREN);
                if (errorEndPos >= S.pos()) //error recovery
                    storeEnd(expr, errorEndPos);
                JCStatement body = parseStatement();
                return F.at(pos).ForeachLoop(var, expr, body);
            } else {
                accept(SEMI);
                if (errorEndPos >= S.pos() && inits.length() > 0) //error recovery
                    storeEnd(inits.last(), errorEndPos);
                JCExpression cond = S.token() == SEMI ? null : parseExpression();
                accept(SEMI);
                if (errorEndPos >= S.pos()) //error recovery
                    storeEnd(cond, errorEndPos);
                List<JCExpressionStatement> steps = S.token() == RPAREN ? List.<JCExpressionStatement>nil() : forUpdate();
                accept(RPAREN);
                if (errorEndPos >= S.pos() && steps.length() > 0) //error recovery
                    storeEnd(steps.last(), errorEndPos);
                JCStatement body = parseStatement();
                return F.at(pos).ForLoop(inits, cond, steps, body);
            }
        }
        case WHILE: {
            S.nextToken();
            JCExpression cond = parExpression();
            JCStatement body = parseStatement();
            return F.at(pos).WhileLoop(cond, body);
        }
        case DO: {
            S.nextToken();
            JCStatement body = parseStatement();
            accept(WHILE);
            JCExpression cond = parExpression();
            accept(SEMI);
            JCDoWhileLoop t = toP(F.at(pos).DoLoop(body, cond));
            return t;
        }
        case TRY: {
            S.nextToken();
            JCBlock body = block();
            ListBuffer<JCCatch> catchers = new ListBuffer<JCCatch>();
            JCBlock finalizer = null;
            String err = null;
            if (S.token() == CATCH || S.token() == FINALLY) {
                while (S.token() == CATCH) catchers.append(catchClause());
                if (S.token() == FINALLY) {
                    S.nextToken();
                    finalizer = block();
                }
            } else {
                err = "try.without.catch.or.finally";
            }
            JCTry t = F.at(pos).Try(body, catchers.toList(), finalizer);
            if (err != null)
                log.error(t, err);
            return t;
        }
        case SWITCH: {
            S.nextToken();
            JCExpression selector = parExpression();
            accept(LBRACE);
            List<JCCase> cases = switchBlockStatementGroups();
            JCSwitch t = to(F.at(pos).Switch(selector, cases));
            accept(RBRACE);
            return t;
        }
        case SYNCHRONIZED: {
            S.nextToken();
            JCExpression lock = parExpression();
            JCBlock body = block();
            return F.at(pos).Synchronized(lock, body);
        }
        case RETURN: {
            S.nextToken();
            JCExpression result = S.token() == SEMI ? null : parseExpression();
            accept(SEMI);
            JCReturn t = toP(F.at(pos).Return(result));
            return t;
        }
        case THROW: {
            S.nextToken();
            JCExpression exc = parseExpression();
            accept(SEMI);
            JCThrow t = toP(F.at(pos).Throw(exc));
            return t;
        }
        case BREAK: {
            S.nextToken();
            Name label = (S.token() == IDENTIFIER || S.token() == ASSERT || S.token() == ENUM) ? ident() : null;
            accept(SEMI);
            JCBreak t = toP(F.at(pos).Break(label));
            return t;
        }
        case CONTINUE: {
            S.nextToken();
            Name label = (S.token() == IDENTIFIER || S.token() == ASSERT || S.token() == ENUM) ? ident() : null;
            accept(SEMI);
            JCContinue t =  toP(F.at(pos).Continue(label));
            return t;
        }
        case SEMI:
            S.nextToken();
            return toP(F.at(pos).Skip());
        case ELSE:
            return toP(F.Exec(syntaxError("else.without.if")));
        case FINALLY:
            return toP(F.Exec(syntaxError("finally.without.try")));
        case CATCH:
            return toP(F.Exec(syntaxError("catch.without.try")));
        case ASSERT: {
            if (allowAsserts && S.token() == ASSERT) {
                S.nextToken();
                JCExpression assertion = parseExpression();
                JCExpression message = null;
                if (S.token() == COLON) {
                    S.nextToken();
                    message = parseExpression();
                }
                accept(SEMI);
                JCAssert t = toP(F.at(pos).Assert(assertion, message));
                return t;
            }
            /* else fall through to default case */
        }
        case ENUM:
        default:
            Name name = S.name();
            JCExpression expr = parseExpression();
            if (S.token() == COLON && expr.getTag() == JCTree.IDENT) {
                S.nextToken();
                JCStatement stat = parseStatement();
                return F.at(pos).Labelled(name, stat);
            } else {
                // This Exec is an "ExpressionStatement"; it subsumes the terminating semicolon
                JCExpressionStatement stat = to(F.at(pos).Exec(checkExprStat(expr)));
                if (S.token() != SEMI) // Error recovery
                    storeEnd(stat, S.pos());
                accept(SEMI);
                return stat;
            }
        }
    }

    /** CatchClause     = CATCH "(" FormalParameter ")" Block
     */
    JCCatch catchClause() {
        int pos = S.pos();
        accept(CATCH);
        accept(LPAREN);
        JCVariableDecl formal =
            variableDeclaratorId(optFinal(Flags.PARAMETER),
                                 qualident());
        accept(RPAREN);
        JCBlock body = block();
        return F.at(pos).Catch(formal, body);
    }

    /** SwitchBlockStatementGroups = { SwitchBlockStatementGroup }
     *  SwitchBlockStatementGroup = SwitchLabel BlockStatements
     *  SwitchLabel = CASE ConstantExpression ":" | DEFAULT ":"
     */
    List<JCCase> switchBlockStatementGroups() {
        ListBuffer<JCCase> cases = new ListBuffer<JCCase>();
        while (true) {
            int pos = S.pos();
            switch (S.token()) {
            case CASE: {
                S.nextToken();
                JCExpression pat = parseExpression();
                accept(COLON);
                List<JCStatement> stats = blockStatements();
                JCCase c = F.at(pos).Case(pat, stats);
                if (stats.isEmpty())
                    storeEnd(c, S.prevEndPos());
                cases.append(c);
                break;
            }
            case DEFAULT: {
                S.nextToken();
                accept(COLON);
                List<JCStatement> stats = blockStatements();
                JCCase c = F.at(pos).Case(null, stats);
                if (stats.isEmpty())
                    storeEnd(c, S.prevEndPos());
                cases.append(c);
                break;
            }
            case RBRACE: case EOF:
                return cases.toList();
            default:
                S.nextToken(); // to ensure progress
                syntaxError(pos, "expected3",
                    CASE, DEFAULT, RBRACE);
            }
        }
    }

    /** MoreStatementExpressions = { COMMA StatementExpression }
     */
    <T extends ListBuffer<? super JCExpressionStatement>> T moreStatementExpressions(int pos,
                                                                    JCExpression first,
                                                                    T stats) {
        // This Exec is a "StatementExpression"; it subsumes no terminating token
        stats.append(toP(F.at(pos).Exec(checkExprStat(first))));
        while (S.token() == COMMA) {
            S.nextToken();
            pos = S.pos();
            JCExpression t = parseExpression();
            // This Exec is a "StatementExpression"; it subsumes no terminating token
            stats.append(toP(F.at(pos).Exec(checkExprStat(t))));
        }
        return stats;
    }

    /** ForInit = StatementExpression MoreStatementExpressions
     *           |  { FINAL | '@' Annotation } Type VariableDeclarators
     */
    List<JCStatement> forInit() {
        ListBuffer<JCStatement> stats = lb();
        int pos = S.pos();
        if (S.token() == FINAL || S.token() == MONKEYS_AT) {
            return variableDeclarators(optFinal(0), parseType(), stats).toList();
        } else {
            JCExpression t = term(EXPR | TYPE);
            if ((lastmode & TYPE) != 0 &&
                (S.token() == IDENTIFIER || S.token() == ASSERT || S.token() == ENUM || S.token() == COLON))
                return variableDeclarators(modifiersOpt(), t, stats).toList();
            else
                return moreStatementExpressions(pos, t, stats).toList();
        }
    }

    /** ForUpdate = StatementExpression MoreStatementExpressions
     */
    List<JCExpressionStatement> forUpdate() {
        return moreStatementExpressions(S.pos(),
                                        parseExpression(),
                                        new ListBuffer<JCExpressionStatement>()).toList();
    }

    enum AnnotationKind { DEFAULT_ANNO, TYPE_ANNO };

    /** AnnotationsOpt = { '@' Annotation }
     */
    List<JCAnnotation> annotationsOpt(AnnotationKind kind) {
        if (S.token() != MONKEYS_AT) return List.nil(); // optimization
        ListBuffer<JCAnnotation> buf = new ListBuffer<JCAnnotation>();
        int prevmode = mode;
        while (S.token() == MONKEYS_AT) {
            int pos = S.pos();
            S.nextToken();
            buf.append(annotation(pos, kind));
        }
        lastmode = mode;
        mode = prevmode;
        List<JCAnnotation> annotations = buf.toList();

        if (debugJSR308 && kind == AnnotationKind.TYPE_ANNO)
            System.out.println("TA: parsing " + annotations
                    + " in " + log.currentSourceFile());
        return annotations;
    }

    List<JCTypeAnnotation> typeAnnotationsOpt() {
        List<JCAnnotation> annotations = annotationsOpt(AnnotationKind.TYPE_ANNO);
        return List.convert(JCTypeAnnotation.class, annotations);
    }

    /** ModifiersOpt = { Modifier }
     *  Modifier = PUBLIC | PROTECTED | PRIVATE | STATIC | ABSTRACT | FINAL
     *           | NATIVE | SYNCHRONIZED | TRANSIENT | VOLATILE | "@"
     *           | "@" Annotation
     */
    JCModifiers modifiersOpt() {
        return modifiersOpt(null);
    }
    protected JCModifiers modifiersOpt(JCModifiers partial) {
        long flags = (partial == null) ? 0 : partial.flags;
        if (S.deprecatedFlag()) {
            flags |= Flags.DEPRECATED;
            S.resetDeprecatedFlag();
        }
        ListBuffer<JCAnnotation> annotations = new ListBuffer<JCAnnotation>();
        if (partial != null) annotations.appendList(partial.annotations);
        int pos = S.pos();
        int lastPos = Position.NOPOS;
    loop:
        while (true) {
            long flag;
            switch (S.token()) {
            case PRIVATE     : flag = Flags.PRIVATE; break;
            case PROTECTED   : flag = Flags.PROTECTED; break;
            case PUBLIC      : flag = Flags.PUBLIC; break;
            case STATIC      : flag = Flags.STATIC; break;
            case TRANSIENT   : flag = Flags.TRANSIENT; break;
            case FINAL       : flag = Flags.FINAL; break;
            case ABSTRACT    : flag = Flags.ABSTRACT; break;
            case NATIVE      : flag = Flags.NATIVE; break;
            case VOLATILE    : flag = Flags.VOLATILE; break;
            case SYNCHRONIZED: flag = Flags.SYNCHRONIZED; break;
            case STRICTFP    : flag = Flags.STRICTFP; break;
            case MONKEYS_AT  : flag = Flags.ANNOTATION; break;
            default: break loop;
            }
            if ((flags & flag) != 0) log.error(S.pos(), "repeated.modifier");
            lastPos = S.pos();
            S.nextToken();
            if (flag == Flags.ANNOTATION) {
                checkAnnotations();
                if (S.token() != INTERFACE) {
                JCAnnotation ann = annotation(lastPos, AnnotationKind.DEFAULT_ANNO);
                // if first modifier is an annotation, set pos to annotation's.
                if (flags == 0 && annotations.isEmpty())
                    pos = ann.pos;
                annotations.append(ann);
                lastPos = ann.pos;
                    flag = 0;
                }
            }
            flags |= flag;
        }

        /* A modifiers tree with no modifier tokens or annotations
         * has no text position. */
        if (flags == 0 && annotations.isEmpty())
            pos = Position.NOPOS;

        switch (S.token()) {
        case ENUM:
            if (this.allowEnums) {
                flags |= Flags.ENUM;
            }
            break;
        case INTERFACE: flags |= Flags.INTERFACE; break;
        default: break;
        }

        JCModifiers mods = F.at(pos).Modifiers(flags, annotations.toList());
        if (pos != Position.NOPOS)
            storeEnd(mods, S.prevEndPos());
        return mods;
    }

    /** Annotation              = "@" Qualident [ "(" AnnotationFieldValues ")" ]
     * @param pos position of "@" token
     */
    JCAnnotation annotation(int pos, AnnotationKind kind) {
        // accept(AT); // AT consumed by caller
        checkAnnotations();
        if (kind == AnnotationKind.TYPE_ANNO)
            checkTypeAnnotations();
        JCTree ident = qualident();
        int identEndPos = S.prevEndPos();
        boolean hasParens = S.token() == LPAREN;
        List<JCExpression> fieldValues = annotationFieldValuesOpt();
        JCAnnotation ann;
        if (kind == AnnotationKind.DEFAULT_ANNO)
            ann = F.at(pos).Annotation(ident, fieldValues);
        else
            ann = F.at(pos).TypeAnnotation(ident, fieldValues);
        storeEnd(ann, hasParens ? S.prevEndPos() : identEndPos);
        return ann;
    }

    List<JCExpression> annotationFieldValuesOpt() {
        return (S.token() == LPAREN) ? annotationFieldValues() : List.<JCExpression>nil();
    }

    /** AnnotationFieldValues   = "(" [ AnnotationFieldValue { "," AnnotationFieldValue } ] ")" */
    List<JCExpression> annotationFieldValues() {
        accept(LPAREN);
        ListBuffer<JCExpression> buf = new ListBuffer<JCExpression>();
        if (S.token() != RPAREN) {
            buf.append(annotationFieldValue());
            while (S.token() == COMMA) {
                S.nextToken();
                buf.append(annotationFieldValue());
            }
        }
        accept(RPAREN);
        return buf.toList();
    }

    /** AnnotationFieldValue    = AnnotationValue
     *                          | Identifier "=" AnnotationValue
     */
    JCExpression annotationFieldValue() {
        if (S.token() == IDENTIFIER) {
            mode = EXPR;
            JCExpression t1 = term1();
            if (t1.getTag() == JCTree.IDENT && S.token() == EQ) {
                int pos = S.pos();
                accept(EQ);
                return toP(F.at(pos).Assign(t1, annotationValue()));
            } else {
                return t1;
            }
        }
        return annotationValue();
    }

    /* AnnotationValue          = ConditionalExpression
     *                          | Annotation
     *                          | "{" [ AnnotationValue { "," AnnotationValue } ] [","] "}"
     */
    JCExpression annotationValue() {
        int pos;
        switch (S.token()) {
        case MONKEYS_AT:
            pos = S.pos();
            S.nextToken();
            return annotation(pos, AnnotationKind.DEFAULT_ANNO);
        case LBRACE:
            pos = S.pos();
            accept(LBRACE);
            ListBuffer<JCExpression> buf = new ListBuffer<JCExpression>();
            if (S.token() != RBRACE) {
                buf.append(annotationValue());
                while (S.token() == COMMA) {
                    S.nextToken();
                    if (S.token() == RBRACE) break;
                    buf.append(annotationValue());
                }
            }
            accept(RBRACE);
            return toP(F.at(pos).NewArray(null, List.<JCExpression>nil(), buf.toList()));
        default:
            mode = EXPR;
            return term1();
        }
    }

    /** VariableDeclarators = VariableDeclarator { "," VariableDeclarator }
     */
    public <T extends ListBuffer<? super JCVariableDecl>> T variableDeclarators(JCModifiers mods,
                                                                         JCExpression type,
                                                                         T vdefs)
    {
        return variableDeclaratorsRest(S.pos(), mods, type, ident(), false, null, vdefs);
    }

    /** VariableDeclaratorsRest = VariableDeclaratorRest { "," VariableDeclarator }
     *  ConstantDeclaratorsRest = ConstantDeclaratorRest { "," ConstantDeclarator }
     *
     *  @param reqInit  Is an initializer always required?
     *  @param dc       The documentation comment for the variable declarations, or null.
     */
    <T extends ListBuffer<? super JCVariableDecl>> T variableDeclaratorsRest(int pos,
                                                                     JCModifiers mods,
                                                                     JCExpression type,
                                                                     Name name,
                                                                     boolean reqInit,
                                                                     String dc,
                                                                     T vdefs)
    {
        vdefs.append(variableDeclaratorRest(pos, mods, type, name, reqInit, dc));
        while (S.token() == COMMA) {
            // All but last of multiple declarators subsume a comma
            storeEnd((JCTree)vdefs.elems.last(), S.endPos());
            S.nextToken();
            vdefs.append(variableDeclarator(mods, type, reqInit, dc));
        }
        return vdefs;
    }

    /** VariableDeclarator = Ident VariableDeclaratorRest
     *  ConstantDeclarator = Ident ConstantDeclaratorRest
     */
    JCVariableDecl variableDeclarator(JCModifiers mods, JCExpression type, boolean reqInit, String dc) {
        return variableDeclaratorRest(S.pos(), mods, type, ident(), reqInit, dc);
    }

    /** VariableDeclaratorRest = BracketsOpt ["=" VariableInitializer]
     *  ConstantDeclaratorRest = BracketsOpt "=" VariableInitializer
     *
     *  @param reqInit  Is an initializer always required?
     *  @param dc       The documentation comment for the variable declarations, or null.
     */
    JCVariableDecl variableDeclaratorRest(int pos, JCModifiers mods, JCExpression type, Name name,
                                  boolean reqInit, String dc) {
        type = bracketsOpt(type);
        JCExpression init = null;
        if (S.token() == EQ) {
            S.nextToken();
            init = variableInitializer();
            if (init.getTag() == JCTree.ERRONEOUS && ((JCErroneous)init).errs.isEmpty() && S.prevEndPos() < init.pos)
                init.pos = S.prevEndPos();
        }
        else if (reqInit) syntaxError(S.pos(), "expected", EQ);
        JCVariableDecl result =
            toP(F.at(pos).VarDef(mods, name, type, init));
        attach(result, dc);
        return result;
    }

    /** VariableDeclaratorId = Ident BracketsOpt
     */
    JCVariableDecl variableDeclaratorId(JCModifiers mods, JCExpression type) {
        int pos = S.pos();
        Name name = ident();
        if ((mods.flags & Flags.VARARGS) == 0)
            type = bracketsOpt(type);
        return toP(F.at(pos).VarDef(mods, name, type, null));
    }

    /** CompilationUnit = [ { "@" Annotation } PACKAGE Qualident ";"] {ImportDeclaration} {TypeDeclaration}
     */
    public JCTree.JCCompilationUnit parseCompilationUnit() {
        int pos = S.pos();
        JCExpression pid = null;
        String toplevel_dc = S.docComment();
        String dc = S.docComment();
        JCModifiers mods = null;
        List<JCAnnotation> packageAnnotations = List.nil();
        ListBuffer<JCTree> defs = new ListBuffer<JCTree>();
        boolean checkForPackage = true;
        boolean checkForImports = true;
        while (S.token() != EOF) {
            if (S.pos() <= errorEndPos) {
                // error recovery
                skip(checkForImports, false, false, false);
                if (S.token() == EOF)
                    break;
            }
            if (checkForPackage && S.token() == MONKEYS_AT) {
                mods = modifiersOpt();
            } else if (S.token() == PACKAGE) {
                if (checkForPackage) {
                    if (mods != null) {
                        checkNoMods(mods.flags);
                        packageAnnotations = mods.annotations;
                        mods = null;
                    }
                    S.nextToken();
                    pid = qualident();
                    accept(SEMI);
                    checkForPackage = false;
                } else {
                    S.nextToken();
                }
                dc = null;
            } else if (checkForImports && mods == null && S.token() == IMPORT) {
                defs.append(importDeclaration());
                checkForPackage = false;
                dc = null;
            } else {
                JCTree def = typeDeclaration(mods, dc);
                defs.append(def);
                if (def instanceof JCClassDecl) {
                    checkForPackage = false;
                    checkForImports = false;
                }
                mods = null;
                dc = null;
            }
        }
        JCTree.JCCompilationUnit toplevel = F.at(pos).TopLevel(packageAnnotations, pid, defs.toList());
        attach(toplevel, toplevel_dc);
        if (defs.elems.isEmpty())
            storeEnd(toplevel, S.prevEndPos());
        if (keepDocComments)
            toplevel.docComments = docComments;
        if (keepLineMap)
            toplevel.lineMap = S.getLineMap();

        assignAnonymousClassIndices(names, toplevel, null, -1);
        return toplevel;
    }

    /** ImportDeclaration = IMPORT [ STATIC ] Ident { "." Ident } [ "." "*" ] ";"
     */
    JCTree importDeclaration() {
        int pos = S.pos();
        S.nextToken();
        boolean importStatic = false;
        if (S.token() == STATIC) {
            checkStaticImports();
            importStatic = true;
            S.nextToken();
        }
        JCExpression pid = toP(F.at(S.pos()).Ident(ident()));
        do {
            int pos1 = S.pos();
            accept(DOT);
            if (S.token() == STAR) {
                pid = to(F.at(pos1).Select(pid, names.asterisk));
                S.nextToken();
                break;
            } else {
                pid = toP(F.at(pos1).Select(pid, ident()));
            }
        } while (S.token() == DOT);
        accept(SEMI);
        return toP(F.at(pos).Import(pid, importStatic));
    }

    /** TypeDeclaration = ClassOrInterfaceOrEnumDeclaration
     *                  | ";"
     */
    JCTree typeDeclaration(JCModifiers mods, String comment) {
        int pos = S.pos();
        if (mods == null && S.token() == SEMI) {
            S.nextToken();
            return toP(F.at(pos).Skip());
        } else {
            String dc = S.docComment();
            return classOrInterfaceOrEnumDeclaration(modifiersOpt(mods), dc != null ? dc : comment);
        }
    }

    /** ClassOrInterfaceOrEnumDeclaration = ModifiersOpt
     *           (ClassDeclaration | InterfaceDeclaration | EnumDeclaration)
     *  @param mods     Any modifiers starting the class or interface declaration
     *  @param dc       The documentation comment for the class, or null.
     */
    JCStatement classOrInterfaceOrEnumDeclaration(JCModifiers mods, String dc) {
        if (S.token() == CLASS) {
            return classDeclaration(mods, dc);
        } else if (S.token() == INTERFACE) {
            return interfaceDeclaration(mods, dc);
        } else if (allowEnums) {
            if (S.token() == ENUM) {
                return enumDeclaration(mods, dc);
            } else {
                int pos = S.pos();
                List<JCTree> errs;
                if (S.token() == IDENTIFIER) {
                    errs = List.<JCTree>of(mods, toP(F.at(pos).Ident(ident())));
                    setErrorEndPos(S.pos());
                } else {
                    errs = List.<JCTree>of(mods);
                }
                return toP(F.Exec(syntaxError(pos, errs, "expected3",
                                              CLASS, INTERFACE, ENUM)));
            }
        } else {
            if (S.token() == ENUM) {
                log.error(S.pos(), "enums.not.supported.in.source", source.name);
                allowEnums = true;
                mods.flags|=Flags.ENUM;
                return enumDeclaration(mods, dc);
            }
            int pos = S.pos();
            List<JCTree> errs;
            if (S.token() == IDENTIFIER) {
                errs = List.<JCTree>of(mods, toP(F.at(pos).Ident(ident())));
                setErrorEndPos(S.pos());
            } else {
                errs = List.<JCTree>of(mods);
            }
            return toP(F.Exec(syntaxError(pos, errs, "expected2",
                                          CLASS, INTERFACE)));
        }
    }

    /** ClassDeclaration = CLASS Ident TypeParametersOpt [EXTENDS Type]
     *                     [IMPLEMENTS TypeList] ClassBody
     *  @param mods    The modifiers starting the class declaration
     *  @param dc       The documentation comment for the class, or null.
     */
    JCClassDecl classDeclaration(JCModifiers mods, String dc) {
        if (cancelService != null) {
            cancelService.abortIfCanceled();
        }
        int pos = S.pos();
        accept(CLASS);
        Name name = ident();

        List<JCTypeParameter> typarams = typeParametersOpt();

        JCTree extending = null;
        if (S.token() == EXTENDS) {
            S.nextToken();
            extending = parseType();
        }
        List<JCExpression> implementing = List.nil();
        if (S.token() == IMPLEMENTS) {
            S.nextToken();
            implementing = typeList();
        }
        List<JCTree> defs = classOrInterfaceBody(name, false);
        JCClassDecl result = toP(F.at(pos).ClassDef(
                mods, name, typarams, extending, implementing, defs));
        attach(result, dc);
        return result;
    }

    /** InterfaceDeclaration = INTERFACE Ident TypeParametersOpt
     *                         [EXTENDS TypeList] InterfaceBody
     *  @param mods    The modifiers starting the interface declaration
     *  @param dc       The documentation comment for the interface, or null.
     */
    JCClassDecl interfaceDeclaration(JCModifiers mods, String dc) {
        if (cancelService != null) {
            cancelService.abortIfCanceled();
        }
        int pos = S.pos();
        accept(INTERFACE);
        Name name = ident();

        List<JCTypeParameter> typarams = typeParametersOpt();

        List<JCExpression> extending = List.nil();
        if (S.token() == EXTENDS) {
            S.nextToken();
            extending = typeList();
        }
        List<JCTree> defs = classOrInterfaceBody(name, true);
        JCClassDecl result = toP(F.at(pos).ClassDef(
            mods, name, typarams, null, extending, defs));
        attach(result, dc);
        return result;
    }

    /** EnumDeclaration = ENUM Ident [IMPLEMENTS TypeList] EnumBody
     *  @param mods    The modifiers starting the enum declaration
     *  @param dc       The documentation comment for the enum, or null.
     */
    JCClassDecl enumDeclaration(JCModifiers mods, String dc) {
        if (cancelService != null) {
            cancelService.abortIfCanceled();
        }
        int pos = S.pos();
        accept(ENUM);
        JCModifiers newMods =
            F.at(mods.pos).Modifiers(mods.flags|Flags.ENUM, mods.annotations);
        storeEnd(newMods, getEndPos(mods));
        Name name = ident();
        return enumDeclaration(newMods, dc, pos, name);
    }

    JCClassDecl enumDeclaration(JCModifiers mods, String dc, int pos, Name name) {
        List<JCExpression> implementing = List.nil();
        if (S.token() == IMPLEMENTS) {
            S.nextToken();
            implementing = typeList();
        }

        List<JCTree> defs = enumBody(name);

        JCClassDecl result = toP(F.at(pos).
            ClassDef(mods, name, List.<JCTypeParameter>nil(),
                null, implementing, defs));

        attach(result, dc);
        return result;
    }

    /** EnumBody = "{" { EnumeratorDeclarationList } [","]
     *                  [ ";" {ClassBodyDeclaration} ] "}"
     */
    List<JCTree> enumBody(Name enumName) {
        accept(LBRACE);
        if (S.pos() <= errorEndPos) {
            // error recovery
            skip(false, true, false, false);
            if (S.token() == LBRACE)
                S.nextToken();
        }
        ListBuffer<JCTree> defs = new ListBuffer<JCTree>();
        if (S.token() == COMMA) {
            S.nextToken();
        } else if (S.token() != RBRACE && S.token() != SEMI) {
            boolean hasError = false;
            List<JCTree> decl = enumeratorDeclaration(enumName);
            defs.appendList(decl);
            if (decl.head.getTag() != JCTree.VARDEF || (((JCVariableDecl)decl.head).getModifiers().flags & Flags.ENUM) == 0)
                hasError = true;
            while (S.token() != RBRACE && S.token() != SEMI && !hasError) {
                if (S.token() == COMMA)
                    S.nextToken();
                else
                    syntaxError(S.pos(), "expected3", COMMA, RBRACE, SEMI);
                if (S.token() == RBRACE || S.token() == SEMI) break;
                decl = enumeratorDeclaration(enumName);
                defs.appendList(decl);
                if (decl.head.getTag() != JCTree.VARDEF || (((JCVariableDecl)decl.head).getModifiers().flags & Flags.ENUM) == 0)
                    hasError = true;
            }
        }
        if (S.token() == SEMI)
            S.nextToken();
        while (S.token() != RBRACE && S.token() != EOF) {
            defs.appendList(classOrInterfaceBodyDeclaration(enumName,
                                                            false));
            if (S.pos() <= errorEndPos) {
                // error recovery
               skip(false, true, true, false);
            }
        }
        accept(RBRACE);
        return defs.toList();
    }

    /** EnumeratorDeclaration = AnnotationsOpt [TypeArguments] IDENTIFIER [ Arguments ] [ "{" ClassBody "}" ]
     */
    List<JCTree> enumeratorDeclaration(Name enumName) {
        String dc = S.docComment();
        int flags = Flags.PUBLIC|Flags.STATIC|Flags.FINAL|Flags.ENUM;
        if (S.deprecatedFlag()) {
            flags |= Flags.DEPRECATED;
            S.resetDeprecatedFlag();
        }
        int pos = S.pos();
        List<JCAnnotation> annotations = annotationsOpt(AnnotationKind.DEFAULT_ANNO);
        JCModifiers mods = F.at(annotations.isEmpty() ? Position.NOPOS : pos).Modifiers(flags, annotations);
        List<JCExpression> typeArgs = typeArgumentsOpt();
        int identPos = S.pos();
        Name name = ident();
        if (name != names.error) {
            int createPos = S.pos();
            List<JCExpression> args = (S.token() == LPAREN)
                ? arguments() : List.<JCExpression>nil();
            JCClassDecl body = null;
            if (S.token() == LBRACE) {
                JCModifiers mods1 = F.at(Position.NOPOS).Modifiers(Flags.ENUM | Flags.STATIC);
                List<JCTree> defs = classOrInterfaceBody(names.empty, false);
                body = toP(F.at(identPos).AnonymousClassDef(mods1, defs));
            }
            JCIdent ident = F.at(Position.NOPOS).Ident(enumName);
            JCNewClass create = F.at(createPos).NewClass(null, typeArgs, ident, args, body);
            if (createPos != Position.NOPOS)
                storeEnd(create, S.prevEndPos());
            ident = F.at(Position.NOPOS).Ident(enumName);
            JCTree result = toP(F.at(pos).VarDef(mods, name, ident, create));
            attach(result, dc);
            return List.<JCTree>of(result);
        }
        // error recovery: not an EnumeratorDeclaration; let's try ClassBodyDeclaration
        mods = annotations.isEmpty() ? modifiersOpt() : modifiersOpt(F.at(pos).Modifiers(0, annotations));
        if (S.token() == CLASS ||
            S.token() == INTERFACE ||
            allowEnums && S.token() == ENUM) {
            return List.<JCTree>of(classOrInterfaceOrEnumDeclaration(mods, dc));
        } else if (S.token() == LBRACE &&
                   (mods.flags & Flags.StandardFlags & ~Flags.STATIC) == 0 &&
                   mods.annotations.isEmpty()) {
            return List.<JCTree>of(block(pos, mods.flags));
        } else {
            pos = S.pos();
            List<JCTypeParameter> typarams = typeParametersOpt();
            // Hack alert:  if there are type arguments but no Modifiers, the start
            // position will be lost unless we set the Modifiers position.  There
            // should be an AST node for type parameters (BugId 5005090).
            if (typarams.length() > 0 && mods.pos == Position.NOPOS) {
                mods.pos = pos;
            }
            Token token = S.token();
            name = S.name();
            pos = S.pos();
            JCExpression type;
            boolean isVoid = token == VOID;
            if (isVoid) {
                type = to(F.at(pos).TypeIdent(TypeTags.VOID));
                S.nextToken();
            } else {
                type = parseType();
            }
            if (S.token() == LPAREN && type.getTag() == JCTree.IDENT) {
                if (name != enumName) {
                    log.error(pos, "invalid.meth.decl.ret.type.req");
                    return List.of(methodDeclaratorRest(
                        pos, mods, null, name, typarams,
                        false, true, dc));
                }
                return List.of(methodDeclaratorRest(
                    pos, mods, null, names.init, typarams,
                    false, true, dc));
            } else {
                pos = S.pos();
                name = ident();
                if (S.token() == LPAREN) {
                    return List.of(methodDeclaratorRest(
                        pos, mods, type, name, typarams,
                        false, isVoid, dc));
                } else if (token == ENUM &&  typarams.isEmpty() && (S.token() == LBRACE || S.token() == IMPLEMENTS)) {
                    log.error(pos, "enums.not.supported.in.source", source.name);
                    allowEnums = true;
                    JCModifiers newMods =
                        F.at(mods.pos).Modifiers(mods.flags|Flags.ENUM, mods.annotations);
                    storeEnd(newMods, getEndPos(mods));
                    return List.<JCTree>of(enumDeclaration(newMods, dc, pos, name));
                } else if (!isVoid && typarams.isEmpty()) {
                    List<JCTree> defs =
                        variableDeclaratorsRest(pos, mods, type, name, false, dc,
                                                new ListBuffer<JCTree>()).toList();
                    accept(SEMI);
                    storeEnd(defs.last(), S.prevEndPos());
                    return defs;
                } else {
                    pos = S.pos();
                    List<JCTree> err = isVoid
                        ? List.<JCTree>of(toP(F.at(pos).MethodDef(mods, name, type, typarams,
                            List.<JCVariableDecl>nil(), List.<JCExpression>nil(), null, null)))
                        : List.<JCTree>nil();
                    return List.<JCTree>of(syntaxError(S.pos(), err, "expected", LPAREN));
                }
            }
        }
    }

    /** TypeList = Type {"," Type}
     */
    List<JCExpression> typeList() {
        ListBuffer<JCExpression> ts = new ListBuffer<JCExpression>();
        ts.append(parseType());
        while (S.token() == COMMA) {
            S.nextToken();
            ts.append(parseType());
        }
        return ts.toList();
    }

    /** ClassBody     = "{" {ClassBodyDeclaration} "}"
     *  InterfaceBody = "{" {InterfaceBodyDeclaration} "}"
     */
    List<JCTree> classOrInterfaceBody(Name className, boolean isInterface) {
        accept(LBRACE);
        if (S.pos() <= errorEndPos) {
            // error recovery
            skip(false, true, false, false);
            if (S.token() == LBRACE)
                S.nextToken();
        }
        ListBuffer<JCTree> defs = new ListBuffer<JCTree>();
        while (S.token() != RBRACE && S.token() != EOF) {
            defs.appendList(classOrInterfaceBodyDeclaration(className, isInterface));
            if (S.pos() <= errorEndPos) {
               // error recovery
               if (S.token() == LBRACE && isInterface)
                   S.nextToken();
               skip(false, true, true, false);
           }
        }
        accept(RBRACE);
        return defs.toList();
    }

    /** ClassBodyDeclaration =
     *      ";"
     *    | [STATIC] Block
     *    | ModifiersOpt
     *      ( Type Ident
     *        ( VariableDeclaratorsRest ";" | MethodDeclaratorRest )
     *      | VOID Ident MethodDeclaratorRest
     *      | TypeParameters (Type | VOID) Ident MethodDeclaratorRest
     *      | Ident ConstructorDeclaratorRest
     *      | TypeParameters Ident ConstructorDeclaratorRest
     *      | ClassOrInterfaceOrEnumDeclaration
     *      )
     *  InterfaceBodyDeclaration =
     *      ";"
     *    | ModifiersOpt Type Ident
     *      ( ConstantDeclaratorsRest | InterfaceMethodDeclaratorRest ";" )
     */
    public List<JCTree> classOrInterfaceBodyDeclaration(Name className, boolean isInterface) {
        if (S.token() == SEMI) {
            JCTree block = F.at(S.pos()).Block(0, List.<JCStatement>nil());
            storeEnd(block, S.endPos());
            S.nextToken();
            return List.<JCTree>of(block);
        } else {
            String dc = S.docComment();
            int pos = S.pos();
            JCModifiers mods = modifiersOpt();
            if (S.token() == CLASS ||
                S.token() == INTERFACE ||
                allowEnums && S.token() == ENUM) {
                return List.<JCTree>of(classOrInterfaceOrEnumDeclaration(mods, dc));
            } else if (S.token() == LBRACE && !isInterface &&
                       (mods.flags & Flags.StandardFlags & ~Flags.STATIC) == 0 &&
                       mods.annotations.isEmpty()) {
                return List.<JCTree>of(block(pos, mods.flags));
            } else {
                pos = S.pos();
                List<JCTypeParameter> typarams = typeParametersOpt();
                // Hack alert:  if there are type arguments but no Modifiers, the start
                // position will be lost unless we set the Modifiers position.  There
                // should be an AST node for type parameters (BugId 5005090).
                if (typarams.length() > 0 && mods.pos == Position.NOPOS) {
                    mods.pos = pos;
                }

                List<JCAnnotation> annosAfterParams = annotationsOpt(AnnotationKind.DEFAULT_ANNO);

                Token token = S.token();
                Name name = S.name();
                pos = S.pos();
                JCExpression type;
                boolean isVoid = token == VOID;
                if (isVoid) {
                    if (annosAfterParams.nonEmpty())
                        illegal(annosAfterParams.head.pos);
                    type = to(F.at(pos).TypeIdent(TypeTags.VOID));
                    S.nextToken();
                } else {
                    mods.annotations = mods.annotations.appendList(annosAfterParams);
                    // method returns types are un-annotated types
                    type = unannotatedType();
                }
                if (S.token() == LPAREN && !isInterface && type.getTag() == JCTree.IDENT) {
                    if (isInterface || name != className) {
                        log.error(pos, "invalid.meth.decl.ret.type.req");
                        return List.of(methodDeclaratorRest(
                            pos, mods, null, name, typarams,
                            isInterface, true, dc));
                    }
                    return List.of(methodDeclaratorRest(
                        pos, mods, null, names.init, typarams,
                        isInterface, true, dc));
                } else {
                    pos = S.pos();
                    name = ident();
                    if (S.token() == LPAREN) {
                        return List.of(methodDeclaratorRest(
                            pos, mods, type, name, typarams,
                            isInterface, isVoid, dc));
                    } else if (token == ENUM &&  typarams.isEmpty() && (S.token() == LBRACE || S.token() == IMPLEMENTS)) {
                        log.error(pos, "enums.not.supported.in.source", source.name);
                        allowEnums = true;
                        JCModifiers newMods =
                            F.at(mods.pos).Modifiers(mods.flags|Flags.ENUM, mods.annotations);
                        storeEnd(newMods, getEndPos(mods));
                        return List.<JCTree>of(enumDeclaration(newMods, dc, pos, name));
                    } else if (!isVoid && typarams.isEmpty()) {
                        List<JCTree> defs =
                            variableDeclaratorsRest(pos, mods, type, name, isInterface, dc,
                                                    new ListBuffer<JCTree>()).toList();
                        accept(SEMI);
                        storeEnd(defs.last(), S.prevEndPos());
                        return defs;
                    } else {
                        pos = S.pos();
                        List<JCTree> err;
                        if (isVoid) {
                            JCMethodDecl meth = toP(F.at(pos).MethodDef(mods, name, type, typarams,
                                List.<JCVariableDecl>nil(), List.<JCExpression>nil(), null, null));
                            attach(meth, dc);
                            err = List.<JCTree>of(meth);
                        } else {
                            err = List.<JCTree>nil();
                        }
                        return List.<JCTree>of(syntaxError(S.pos(), err, "expected", LPAREN));
                    }
                }
            }
        }
    }

    /** MethodDeclaratorRest =
     *      FormalParameters BracketsOpt [Annotations] [Throws TypeList] ( MethodBody | [DEFAULT AnnotationValue] ";")
     *  VoidMethodDeclaratorRest =
     *      FormalParameters [Annotations] [Throws TypeList] ( MethodBody | ";")
     *  InterfaceMethodDeclaratorRest =
     *      FormalParameters BracketsOpt [Annotations] [THROWS TypeList] ";"
     *  VoidInterfaceMethodDeclaratorRest =
     *      FormalParameters [Annotations] [THROWS TypeList] ";"
     *  ConstructorDeclaratorRest =
     *      "(" FormalParameterListOpt ")" [Annotations] [THROWS TypeList] MethodBody
     */
    JCTree methodDeclaratorRest(int pos,
                              JCModifiers mods,
                              JCExpression type,
                              Name name,
                              List<JCTypeParameter> typarams,
                              boolean isInterface, boolean isVoid,
                              String dc) {
        if (cancelService != null) {
            cancelService.abortIfCanceled();
        }
        List<JCVariableDecl> params = formalParameters();

        List<JCTypeAnnotation> receiverAnnotations;
        if (!isVoid) {
            // need to distinguish between receiver anno and array anno
            // look at typeAnnotationsPushedBack comment
            this.permitTypeAnnotationsPushBack = true;
            type = methodReturnArrayRest(type);
            this.permitTypeAnnotationsPushBack = false;
            if (typeAnnotationsPushedBack == null)
                receiverAnnotations = List.nil();
            else
                receiverAnnotations = typeAnnotationsPushedBack;
            typeAnnotationsPushedBack = null;
        } else
            receiverAnnotations = typeAnnotationsOpt();

        List<JCExpression> thrown = List.nil();
        if (S.token() == THROWS) {
            S.nextToken();
            thrown = qualidentList();
        }
        JCBlock body = null;
        JCExpression defaultValue;
        if (S.token() == LBRACE) {
            body = block();
            defaultValue = null;
        } else {
            if (S.token() == DEFAULT) {
                accept(DEFAULT);
                defaultValue = annotationValue();
            } else {
                defaultValue = null;
            }
            accept(SEMI);
            if (S.pos() <= errorEndPos) {
                // error recovery
                skip(false, true, false, false);
                if (S.token() == LBRACE) {
                    body = block();
                }
            }
        }
        JCMethodDecl result =
            toP(F.at(pos).MethodDef(mods, name, type, typarams,
                                    params, receiverAnnotations, thrown,
                                    body, defaultValue));
        attach(result, dc);
        return result;
    }

    /** Parses the array levels after the format parameters list, and append
     * them to the return type, while preseving the order of type annotations
     */
    private JCExpression methodReturnArrayRest(JCExpression type) {
        if (type.getTag() != JCTree.TYPEARRAY)
            return bracketsOpt(type);

        JCArrayTypeTree baseArray = (JCArrayTypeTree)type;
        while (TreeInfo.typeIn(baseArray.elemtype) instanceof JCArrayTypeTree)
            baseArray = (JCArrayTypeTree)TreeInfo.typeIn(baseArray.elemtype);

        if (baseArray.elemtype.getTag() == JCTree.ANNOTATED_TYPE) {
            JCAnnotatedType at = (JCAnnotatedType)baseArray.elemtype;
            at.underlyingType = bracketsOpt(at.underlyingType);
        } else {
            baseArray.elemtype = bracketsOpt(baseArray.elemtype);
        }

        return type;
    }

    /** QualidentList = [Annotations] Qualident {"," [Annotations] Qualident}
     */
    List<JCExpression> qualidentList() {
        ListBuffer<JCExpression> ts = new ListBuffer<JCExpression>();

        List<JCTypeAnnotation> typeAnnos = typeAnnotationsOpt();
        if (!typeAnnos.isEmpty())
            ts.append(F.AnnotatedType(typeAnnos, qualident()));
        else
            ts.append(qualident());
        while (S.token() == COMMA) {
            S.nextToken();

            typeAnnos = typeAnnotationsOpt();
            if (!typeAnnos.isEmpty())
                ts.append(F.AnnotatedType(typeAnnos, qualident()));
            else
                ts.append(qualident());
        }
        return ts.toList();
    }

    /** TypeParametersOpt = ["<" TypeParameter {"," TypeParameter} ">"]
     */
    List<JCTypeParameter> typeParametersOpt() {
        if (S.token() == LT) {
            checkGenerics();
            ListBuffer<JCTypeParameter> typarams = new ListBuffer<JCTypeParameter>();
            S.nextToken();
            typarams.append(typeParameter());
            while (S.token() == COMMA) {
                S.nextToken();
                typarams.append(typeParameter());
            }
            accept(GT);
            return typarams.toList();
        } else {
            return List.nil();
        }
    }

    /** TypeParameter = [Annotations] TypeVariable [TypeParameterBound]
     *  TypeParameterBound = EXTENDS Type {"&" Type}
     *  TypeVariable = Ident
     */
    JCTypeParameter typeParameter() {
        int pos = S.pos();
        List<JCTypeAnnotation> annos = typeAnnotationsOpt();
        Name name = ident();
        ListBuffer<JCExpression> bounds = new ListBuffer<JCExpression>();
        if (S.token() == EXTENDS) {
            S.nextToken();
            bounds.append(parseType());
            while (S.token() == AMP) {
                S.nextToken();
                bounds.append(parseType());
            }
        }
        return toP(F.at(pos).TypeParameter(name, bounds.toList(), annos));
    }

    /** FormalParameters = "(" [ FormalParameterList ] ")"
     *  FormalParameterList = [ FormalParameterListNovarargs , ] LastFormalParameter
     *  FormalParameterListNovarargs = [ FormalParameterListNovarargs , ] FormalParameter
     */
    List<JCVariableDecl> formalParameters() {
        ListBuffer<JCVariableDecl> params = new ListBuffer<JCVariableDecl>();
        JCVariableDecl lastParam = null;
        accept(LPAREN);
        if (S.token() != RPAREN) {
            params.append(lastParam = formalParameter());
            while ((lastParam.mods.flags & Flags.VARARGS) == 0 && S.token() == COMMA) {
                S.nextToken();
                params.append(lastParam = formalParameter());
            }
        }
        accept(RPAREN);
        return params.toList();
    }

    JCModifiers optFinal(long flags) {
        JCModifiers mods = modifiersOpt();
        checkNoMods(mods.flags & ~(Flags.FINAL | Flags.DEPRECATED));
        mods.flags |= flags;
        return mods;
    }

    /** FormalParameter = { FINAL | '@' Annotation } Type VariableDeclaratorId
     *  LastFormalParameter = { FINAL | '@' Annotation } Type '...' Ident | FormalParameter
     */
    JCVariableDecl formalParameter() {
        JCModifiers mods = optFinal(Flags.PARAMETER);
        // need to distinguish between vararg annos and array annos
        // look at typeAnnotaitonsPushedBack comment
        this.permitTypeAnnotationsPushBack = true;
        JCExpression type = parseType();
        this.permitTypeAnnotationsPushBack = false;

        if (S.token() == ELLIPSIS) {
            List<JCTypeAnnotation> varargsAnnos = typeAnnotationsPushedBack;
            typeAnnotationsPushedBack = null;
            checkVarargs();
            mods.flags |= Flags.VARARGS;
            // insert var arg type annotations
            if (varargsAnnos != null && varargsAnnos.nonEmpty())
                type = F.at(S.pos()).AnnotatedType(varargsAnnos, type);
            type = to(F.at(S.pos()).TypeArray(type));

            S.nextToken();
        } else {
            // if not a var arg, then typeAnnotationsPushedBack should be null
            if (typeAnnotationsPushedBack != null
                    && !typeAnnotationsPushedBack.isEmpty()) {
                reportSyntaxError(null, typeAnnotationsPushedBack.head.pos,
                        "illegal.start.of.type");
            }
            typeAnnotationsPushedBack = null;
        }
        return variableDeclaratorId(mods, type);
    }

/* ---------- auxiliary methods -------------- */

    /** Check that given tree is a legal expression statement.
     */
    protected JCExpression checkExprStat(JCExpression t) {
        switch(t.getTag()) {
        case JCTree.PREINC: case JCTree.PREDEC:
        case JCTree.POSTINC: case JCTree.POSTDEC:
        case JCTree.ASSIGN:
        case JCTree.BITOR_ASG: case JCTree.BITXOR_ASG: case JCTree.BITAND_ASG:
        case JCTree.SL_ASG: case JCTree.SR_ASG: case JCTree.USR_ASG:
        case JCTree.PLUS_ASG: case JCTree.MINUS_ASG:
        case JCTree.MUL_ASG: case JCTree.DIV_ASG: case JCTree.MOD_ASG:
        case JCTree.APPLY: case JCTree.NEWCLASS:
        case JCTree.ERRONEOUS:
            return t;
        default:
            JCExpression ret = F.at(t.pos).Erroneous(List.<JCTree>of(t));
            log.error(ret, "not.stmt");
            return ret;
        }
    }

    /** Return precedence of operator represented by token,
     *  -1 if token is not a binary operator. @see TreeInfo.opPrec
     */
    static int prec(Token token) {
        int oc = optag(token);
        return (oc >= 0) ? TreeInfo.opPrec(oc) : -1;
    }

    /** Return operation tag of binary operator represented by token,
     *  -1 if token is not a binary operator.
     */
    static int optag(Token token) {
        switch (token) {
        case BARBAR:
            return JCTree.OR;
        case AMPAMP:
            return JCTree.AND;
        case BAR:
            return JCTree.BITOR;
        case BAREQ:
            return JCTree.BITOR_ASG;
        case CARET:
            return JCTree.BITXOR;
        case CARETEQ:
            return JCTree.BITXOR_ASG;
        case AMP:
            return JCTree.BITAND;
        case AMPEQ:
            return JCTree.BITAND_ASG;
        case EQEQ:
            return JCTree.EQ;
        case BANGEQ:
            return JCTree.NE;
        case LT:
            return JCTree.LT;
        case GT:
            return JCTree.GT;
        case LTEQ:
            return JCTree.LE;
        case GTEQ:
            return JCTree.GE;
        case LTLT:
            return JCTree.SL;
        case LTLTEQ:
            return JCTree.SL_ASG;
        case GTGT:
            return JCTree.SR;
        case GTGTEQ:
            return JCTree.SR_ASG;
        case GTGTGT:
            return JCTree.USR;
        case GTGTGTEQ:
            return JCTree.USR_ASG;
        case PLUS:
            return JCTree.PLUS;
        case PLUSEQ:
            return JCTree.PLUS_ASG;
        case SUB:
            return JCTree.MINUS;
        case SUBEQ:
            return JCTree.MINUS_ASG;
        case STAR:
            return JCTree.MUL;
        case STAREQ:
            return JCTree.MUL_ASG;
        case SLASH:
            return JCTree.DIV;
        case SLASHEQ:
            return JCTree.DIV_ASG;
        case PERCENT:
            return JCTree.MOD;
        case PERCENTEQ:
            return JCTree.MOD_ASG;
        case INSTANCEOF:
            return JCTree.TYPETEST;
        default:
            return -1;
        }
    }

    /** Return operation tag of unary operator represented by token,
     *  -1 if token is not a binary operator.
     */
    static int unoptag(Token token) {
        switch (token) {
        case PLUS:
            return JCTree.POS;
        case SUB:
            return JCTree.NEG;
        case BANG:
            return JCTree.NOT;
        case TILDE:
            return JCTree.COMPL;
        case PLUSPLUS:
            return JCTree.PREINC;
        case SUBSUB:
            return JCTree.PREDEC;
        default:
            return -1;
        }
    }

    /** Return type tag of basic type represented by token,
     *  -1 if token is not a basic type identifier.
     */
    static int typetag(Token token) {
        switch (token) {
        case BYTE:
            return TypeTags.BYTE;
        case CHAR:
            return TypeTags.CHAR;
        case SHORT:
            return TypeTags.SHORT;
        case INT:
            return TypeTags.INT;
        case LONG:
            return TypeTags.LONG;
        case FLOAT:
            return TypeTags.FLOAT;
        case DOUBLE:
            return TypeTags.DOUBLE;
        case BOOLEAN:
            return TypeTags.BOOLEAN;
        default:
            return -1;
        }
    }

    void checkDiamond() {
        if (!allowDiamond) {
            log.error(S.pos(), "diamond.not.supported.in.source", source.name);
            allowDiamond = true;
        }
    }
    void checkGenerics() {
        if (!allowGenerics) {
            log.error(S.pos(), "generics.not.supported.in.source", source.name);
            allowGenerics = true;
        }
    }
    void checkVarargs() {
        if (!allowVarargs) {
            log.error(S.pos(), "varargs.not.supported.in.source", source.name);
            allowVarargs = true;
        }
    }
    void checkForeach() {
        if (!allowForeach) {
            log.error(S.pos(), "foreach.not.supported.in.source", source.name);
            allowForeach = true;
        }
    }
    void checkStaticImports() {
        if (!allowStaticImport) {
            log.error(S.pos(), "static.import.not.supported.in.source", source.name);
            allowStaticImport = true;
        }
    }
    void checkAnnotations() {
        if (!allowAnnotations) {
            log.error(S.pos(), "annotations.not.supported.in.source", source.name);
            allowAnnotations = true;
        }
    }
    void checkTypeAnnotations() {
        if (!allowTypeAnnotations) {
            log.error(S.pos(), "type.annotations.not.supported.in.source", source.name);
            allowTypeAnnotations = true;
        }
    }

    public static void assignAnonymousClassIndices(Names names, JCTree tree, Name name, int startNumber) {
        AssignAnonymousIndices aai = new AssignAnonymousIndices(names);

        if (name != null) {
            aai.newAnonScope(name, startNumber);
        }

        aai.scan(tree);
    }
    
    private static final class AssignAnonymousIndices extends TreeScanner {
        private final Names names;

        public AssignAnonymousIndices(Names names) {
            this.names = names;
        }
        
        /**
         *Represents a scope for anon class number assignment
         */
        private static class AnonScope {
            public boolean localClass;
            private final Name parentDecl;
            private int currentNumber;
            private Map<Name,Integer> localClasses;

            private AnonScope (final Name name, final int startNumber) {
                assert name != null;
                this.parentDecl = name;
                this.currentNumber = startNumber;
            }

            public int assignNumber () {
                int ret = this.currentNumber;
                if (this.currentNumber != -1) {
                    this.currentNumber++;
                }
                return ret;
            }

            public int assignLocalNumber (final Name name) {
                if (localClasses == null) {
                    localClasses = new HashMap<Name,Integer> ();
                }
                Integer num = localClasses.get(name);
                if (num == null) {
                    num = 1;
                }
                else {
                    num += 1;
                }
                localClasses.put(name, num);
                return num.intValue();
            }

            @Override
            public String toString () {
                return String.format("%s : %d",this.parentDecl.toString(), this.currentNumber);
            }
        }

        private final Map<Name, AnonScope> anonScopeMap = new HashMap<Name, AnonScope>();
        private final Stack<AnonScope> anonScopes = new Stack<AnonScope> ();

        void newAnonScope(final Name name) {
            newAnonScope(name, 1);
        }

        public void newAnonScope(final Name name, final int startNumber) {
            AnonScope parent = anonScopes.isEmpty() ? null : anonScopes.peek();
            Name fqn = parent != null && parent.parentDecl != names.empty ? parent.parentDecl.append('.', name) : name;
            AnonScope scope = anonScopeMap.get(fqn);
            if (scope == null) {
                scope = new AnonScope(name, startNumber);
                anonScopeMap.put(fqn, scope);
            }
            anonScopes.push(scope);
        }

        @Override
        public void visitClassDef(JCClassDecl tree) {
            if (tree.name == names.empty) {
                tree.index = this.anonScopes.peek().assignNumber();
            }
            newAnonScope(names.empty);
            try {
                super.visitClassDef(tree);
            } finally {
                this.anonScopes.pop();
            }
            if (!this.anonScopes.isEmpty() && this.anonScopes.peek().localClass) {
                tree.index = this.anonScopes.peek().assignLocalNumber(tree.name);
            }
        }
        @Override
        public void visitMethodDef(JCMethodDecl tree) {
            final AnonScope as = this.anonScopes.peek();
            as.localClass=true;
            try {
                super.visitMethodDef(tree);
            } finally {
                as.localClass=false;
            }
        }
        @Override
        public void visitApply(JCMethodInvocation tree) {
            scan(tree.args);
            scan(tree.meth);
        }
    }
}
