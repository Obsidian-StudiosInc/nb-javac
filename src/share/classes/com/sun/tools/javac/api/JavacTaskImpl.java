/*
 * Copyright 2005-2008 Sun Microsystems, Inc.  All Rights Reserved.
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

package com.sun.tools.javac.api;

import com.sun.tools.javac.parser.JavacParser;
import java.io.File;
import java.io.IOException;
import java.nio.CharBuffer;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.processing.Processor;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.*;

import com.sun.source.tree.*;
import com.sun.source.util.*;
import com.sun.tools.javac.code.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.comp.*;
import com.sun.tools.javac.file.JavacFileManager;
import com.sun.tools.javac.main.*;
import com.sun.tools.javac.model.*;
import com.sun.tools.javac.parser.Parser;
import com.sun.tools.javac.parser.ParserFactory;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.main.JavaCompiler;

/**
 * Provides access to functionality specific to the Sun Java Compiler, javac.
 *
 * <p><b>This is NOT part of any API supported by Sun Microsystems.
 * If you write code that depends on this, you do so at your own
 * risk.  This code and its internal interfaces are subject to change
 * or deletion without notice.</b></p>
 *
 * @author Peter von der Ah&eacute;
 * @author Jonathan Gibbons
 */
public class JavacTaskImpl extends JavacTask {
    private JavacTool tool;
    private Main compilerMain;
    private JavaCompiler compiler;
    private Locale locale;
    private String[] args;
    private Context context;
    private List<JavaFileObject> fileObjects;
    private Map<JavaFileObject, JCCompilationUnit> notYetEntered;
    private ListBuffer<Env<AttrContext>> genList;
    private TaskListener taskListener;
    private AtomicBoolean used = new AtomicBoolean();
    private Iterable<? extends Processor> processors;
    
    private Integer result = null;

    JavacTaskImpl(JavacTool tool,
                Main compilerMain,
                String[] args,
                Context context,
                List<JavaFileObject> fileObjects) {
        this.tool = tool;
        this.compilerMain = compilerMain;
        this.args = args;
        this.context = context;
        this.fileObjects = fileObjects;
        setLocale(Locale.getDefault());
        // null checks
        compilerMain.getClass();
        args.getClass();
        context.getClass();
        fileObjects.getClass();

        // force the use of the scanner that captures Javadoc comments
        com.sun.tools.javac.parser.DocCommentScanner.Factory.preRegister(context);
    }

    JavacTaskImpl(JavacTool tool,
                Main compilerMain,
                Iterable<String> flags,
                Context context,
                Iterable<String> classes,
                Iterable<? extends JavaFileObject> fileObjects) {
        this(tool, compilerMain, toArray(flags, classes), context, toList(fileObjects));
    }

    JavacTaskImpl(Context context) {
        this.context = context;
        setLocale(Locale.getDefault());
    }

    static private String[] toArray(Iterable<String> flags, Iterable<String> classes) {
        ListBuffer<String> result = new ListBuffer<String>();
        if (flags != null)
            for (String flag : flags)
                result.append(flag);
        if (classes != null)
            for (String cls : classes)
                result.append(cls);
        return result.toArray(new String[result.length()]);
    }

    static private List<JavaFileObject> toList(Iterable<? extends JavaFileObject> fileObjects) {
        if (fileObjects == null)
            return List.nil();
        ListBuffer<JavaFileObject> result = new ListBuffer<JavaFileObject>();
        for (JavaFileObject fo : fileObjects)
            result.append(fo);
        return result.toList();
    }

    public Boolean call() {
        if (!used.getAndSet(true)) {
            beginContext();
            notYetEntered = new HashMap<JavaFileObject, JCCompilationUnit>();
            try {
                compilerMain.setFatalErrors(true);
                result = compilerMain.compile(args, context, fileObjects, processors);
            } finally {
                endContext();
            }
            compilerMain = null;
            args = null;
            context = null;
            fileObjects = null;
            notYetEntered = null;
            return result == 0;
        } else {
            throw new IllegalStateException("multiple calls to method 'call'");
        }
    }

    public void setProcessors(Iterable<? extends Processor> processors) {
        processors.getClass(); // null check
        // not mt-safe
        if (used.get())
            throw new IllegalStateException();
        this.processors = processors;
    }

    public Iterable<? extends Processor> getProcessors() {
        return processors;
    }

    public void setLocale(Locale locale) {
        if (used.get())
            throw new IllegalStateException();
        this.locale = locale;
    }

    private void prepareCompiler() throws IOException {
        if (!used.getAndSet(true)) {
            beginContext();
            compilerMain.setOptions(Options.instance(context));
            compilerMain.filenames = new ListBuffer<File>();
            List<File> filenames = compilerMain.processArgs(CommandLine.parse(args));
            if (!filenames.isEmpty())
                throw new IllegalArgumentException("Malformed arguments " + filenames.toString(" "));
            compiler = JavaCompiler.instance(context);
            // NOTE: this value will be updated after annotation processing
            compiler.initProcessAnnotations(processors);
            notYetEntered = new HashMap<JavaFileObject, JCCompilationUnit>();
            compiler.initNotYetEntered(notYetEntered);
            for (JavaFileObject file: fileObjects)
                notYetEntered.put(file, null);
            genList = new ListBuffer<Env<AttrContext>>();
            // endContext will be called when all classes have been generated
            // TODO: should handle the case after each phase if errors have occurred
            args = null;
        }
    }

    private void beginContext() {
        context.put(JavacTaskImpl.class, this);
        if (context.get(TaskListener.class) != null)
            context.put(TaskListener.class, (TaskListener)null);
        if (taskListener != null)
            context.put(TaskListener.class, wrap(taskListener));
        tool.beginContext(context);
        //initialize compiler's default locale
        JavacMessages.instance(context).setCurrentLocale(locale);
    }
    // where
    private TaskListener wrap(final TaskListener tl) {
        tl.getClass(); // null check
        return new TaskListener() {
            public void started(TaskEvent e) {
                try {
                    tl.started(e);
                } catch (Throwable t) {
                    throw new ClientCodeException(t);
                }
            }

            public void finished(TaskEvent e) {
                try {
                    tl.finished(e);
                } catch (Throwable t) {
                    throw new ClientCodeException(t);
                }
            }

        };
    }

    private void endContext() {
        tool.endContext();
    }

    /**
     * Construct a JavaFileObject from the given file.
     *
     * <p><b>TODO: this method is useless here</b></p>
     *
     * @param file a file
     * @return a JavaFileObject from the standard file manager.
     */
    public JavaFileObject asJavaFileObject(File file) {
        JavacFileManager fm = (JavacFileManager)context.get(JavaFileManager.class);
        return fm.getRegularFile(file);
    }

    public void setTaskListener(TaskListener taskListener) {
        this.taskListener = taskListener;
    }

    public Iterable<? extends CompilationUnitTree> parse (JavaFileObject... files) throws IOException {
        prepareCompiler();
        java.util.List<CompilationUnitTree> trees = new java.util.LinkedList<CompilationUnitTree> ();
        this.fileObjects = List.nil();

        for (JavaFileObject file : files) {
            CompilationUnitTree tree = getTreeForFile (file);
            if (tree != null) {
                trees.add(tree);
            }
            else {
                this.fileObjects = this.fileObjects.append(file);
                if (notYetEntered != null) {
                    assert !notYetEntered.containsKey(file);
                    notYetEntered.put(file, null);
                }
            }
        }
        if (!this.fileObjects.isEmpty()) {
            Iterable<? extends CompilationUnitTree> newTrees = this.parse();
            for (CompilationUnitTree newTree : newTrees) {
                trees.add (newTree);
            }
        }
        return trees;
    }

    private CompilationUnitTree getTreeForFile (final JavaFileObject file) {
        assert file != null;
        Enter enter = Enter.instance(context);
        CompilationUnitTree tree = enter.getCompilationUnit(file);
        if (tree == null && notYetEntered != null) {
            tree = (CompilationUnitTree) notYetEntered.get(file);
        }
        return tree;
    }

    /**
     * Parse the specified files returning a list of abstract syntax trees.
     *
     * @throws java.io.IOException TODO
     * @return a list of abstract syntax trees
     */
    public Iterable<? extends CompilationUnitTree> parse() throws IOException {
        try {
            prepareCompiler();
            List<JCCompilationUnit> units = compiler.parseFiles(fileObjects);
            for (JCCompilationUnit unit: units) {
                JavaFileObject file = unit.getSourceFile();
                if (notYetEntered.containsKey(file))
                    notYetEntered.put(file, unit);
            }
            return units;
        }
        finally {
            parsed = true;
            if (compiler != null && compiler.log != null)
                compiler.log.flush();
            for (JavaFileObject file : fileObjects) {
                if (notYetEntered.get(file) == null)
                    notYetEntered.remove(file);
            }
        }
    }

    private boolean parsed = false;

    /**
     * Translate all the abstract syntax trees to elements.
     *
     * @throws IOException TODO
     * @return a list of elements corresponding to the top level
     * classes in the abstract syntax trees
     */
    public Iterable<? extends TypeElement> enter() throws IOException {
        return enter(null);
    }

    /**
     * Translate the given abstract syntax trees to elements.
     *
     * @param trees a list of abstract syntax trees.
     * @throws java.io.IOException TODO
     * @return a list of elements corresponding to the top level
     * classes in the abstract syntax trees
     */
    public Iterable<? extends TypeElement> enter(Iterable<? extends CompilationUnitTree> trees)
        throws IOException
    {
        prepareCompiler();

        ListBuffer<JCCompilationUnit> roots = null;

        if (trees == null) {
            // If there are still files which were specified to be compiled
            // (i.e. in fileObjects) but which have not yet been entered,
            // then we make sure they have been parsed and add them to the
            // list to be entered.
            if (notYetEntered.size() > 0) {
                if (!parsed)
                    parse(); // TODO would be nice to specify files needed to be parsed
                for (JavaFileObject file: fileObjects) {
                    JCCompilationUnit unit = notYetEntered.remove(file);
                    if (unit != null) {
                        if (roots == null)
                            roots = new ListBuffer<JCCompilationUnit>();
                        roots.append(unit);
                    }
                }
                notYetEntered.clear();
            }
        }
        else {
            for (CompilationUnitTree cu : trees) {
                if (cu instanceof JCCompilationUnit) {
                    if (roots == null)
                        roots = new ListBuffer<JCCompilationUnit>();
                    roots.append((JCCompilationUnit)cu);
                    notYetEntered.remove(cu.getSourceFile());
                }
                else
                    throw new IllegalArgumentException(cu.toString());
            }
        }

        if (roots == null)
            return List.nil();

        try {
            List<JCCompilationUnit> units = compiler.enterTrees(roots.toList());

            if (!compiler.skipAnnotationProcessing)
                compiler = compiler.processAnnotations(units);

            compiler.flushTempDiags();

            ListBuffer<TypeElement> elements = new ListBuffer<TypeElement>();
            for (JCCompilationUnit unit : units) {
                boolean isPkgInfo = unit.sourcefile.isNameCompatible("package-info",
                                                                     JavaFileObject.Kind.SOURCE);
                if (isPkgInfo) {
                    if (unit.packge.package_info != null)
                        elements.append(unit.packge.package_info);
                } else {
                    for (JCTree node : unit.defs)
                        if (node.getTag() == JCTree.CLASSDEF && ((JCTree.JCClassDecl) node).sym != null)
                            elements.append(((JCTree.JCClassDecl) node).sym);
                }
            }
            return elements.toList();
        }
        finally {
            compiler.log.flush();
        }
    }


    public Iterable<? extends TypeElement> enterTrees (final Iterable<? extends CompilationUnitTree> trees) throws IOException {
        final java.util.List<CompilationUnitTree> toEnter = new java.util.ArrayList ();
        final java.util.List<TypeElement> res = new java.util.ArrayList ();
        for (CompilationUnitTree tree : trees) {
            final java.util.Collection<TypeElement> te = this.getEnteredElements(tree);
            if (te.isEmpty()) {
                toEnter.add (tree);
            }
            else {
                res.addAll(te);
            }
        }
        if (!toEnter.isEmpty()) {
            final Iterable<? extends TypeElement> classes = this.enter(toEnter);
            for (TypeElement te : classes) {
                res.add (te);
            }
        }
        return res;
    }


    private java.util.Collection<TypeElement> getEnteredElements (final CompilationUnitTree tree) {
        assert tree instanceof JCCompilationUnit;
        final java.util.List<TypeElement> res = new java.util.ArrayList<TypeElement>();
        if (((JCCompilationUnit)tree).packge != null) {
            for (JCTree t : ((JCCompilationUnit)tree).defs) {
                if (t.getTag() == JCTree.CLASSDEF) {
                    ClassSymbol sym = ((JCClassDecl)t).sym;
                    if (sym != null)
                        res.add(sym);
                }
            }
        }
        return res;
    }

    /**
     * Complete all analysis.
     * @throws IOException TODO
     */
    @Override
    public Iterable<? extends Element> analyze() throws IOException {
        return analyze(null);
    }

    /**
     * Complete all analysis on the given classes.
     * This can be used to ensure that all compile time errors are reported.
     * The classes must have previously been returned from {@link #enter}.
     * If null is specified, all outstanding classes will be analyzed.
     *
     * @param classes a list of class elements
     */
    // This implementation requires that we open up privileges on JavaCompiler.
    // An alternative implementation would be to move this code to JavaCompiler and
    // wrap it here
    public Iterable<? extends Element> analyze(Iterable<? extends TypeElement> classes) throws IOException {
        if (classes == null)
            enter(null);  // ensure all classes have been entered

        final ListBuffer<Element> results = new ListBuffer<Element>();
        try {
            if (classes == null) {
                handleFlowResults(compiler.flow(compiler.attribute(compiler.todo)), results);
            } else {
                Filter f = new Filter() {
                    public void process(Env<AttrContext> env) {
                        handleFlowResults(compiler.flow(compiler.attribute(env)), results);
                    }
                };
                f.run(compiler.todo, classes);
            }
        } finally {
            compiler.log.flush();
        }
        return results;
    }
    // where
        private void handleFlowResults(Queue<Env<AttrContext>> queue, ListBuffer<Element> elems) {
            for (Env<AttrContext> env: queue) {
                switch (env.tree.getTag()) {
                    case JCTree.CLASSDEF:
                        JCClassDecl cdef = (JCClassDecl) env.tree;
                        if (cdef.sym != null)
                            elems.append(cdef.sym);
                        break;
                    case JCTree.TOPLEVEL:
                        JCCompilationUnit unit = (JCCompilationUnit) env.tree;
                        if (unit.packge != null)
                            elems.append(unit.packge);
                        break;
                }
            }
            genList.addAll(queue);
        }


    /**
     * Generate code.
     * @throws IOException TODO
     */
    @Override
    public Iterable<? extends JavaFileObject> generate() throws IOException {
        return generate(null);
    }

    /**
     * Generate code corresponding to the given classes.
     * The classes must have previously been returned from {@link #enter}.
     * If there are classes outstanding to be analyzed, that will be done before
     * any classes are generated.
     * If null is specified, code will be generated for all outstanding classes.
     *
     * @param classes a list of class elements
     */
    public Iterable<? extends JavaFileObject> generate(Iterable<? extends TypeElement> classes) throws IOException {
        final ListBuffer<JavaFileObject> results = new ListBuffer<JavaFileObject>();
        try {
            analyze(classes);  // ensure all classes have been parsed, entered, and analyzed

            if (classes == null) {
                compiler.generate(compiler.desugar(genList), results);
                genList.clear();
            }
            else {
                Filter f = new Filter() {
                        public void process(Env<AttrContext> env) {
                            compiler.generate(compiler.desugar(ListBuffer.of(env)), results);
                        }
                    };
                f.run(genList, classes);
            }
            if (genList.isEmpty()) {
                compiler.reportDeferredDiagnostics();
                compiler.log.flush();
                compiler.repair.flush();
                endContext();
            }
        }
        finally {
            compiler.log.flush();
        }
        return results;
    }

    public void generateTypeElements (Iterable<? extends TypeElement> classes) throws IOException {
        assert classes != null;
        try {
            analyze (classes);
            Filter f = new Filter() {
                public void process(Env<AttrContext> env) {
                    compiler.generate(compiler.desugar(ListBuffer.of(env)));
                }
            };
            f.run(genList, classes);
        } finally {
            compiler.log.flush();
        }
    }

    public void finish () {
        if (notYetEntered != null && !notYetEntered.isEmpty()) {
            this.notYetEntered.clear();
        }
        if (this.compiler != null && this.compiler.todo != null && !this.compiler.todo.isEmpty()) {
            this.compiler.todo.clear();
        }
        if (this.genList != null && !this.genList.isEmpty()) {
            this.genList.clear();
        }
        endContext();
    }

    public TypeMirror getTypeMirror(Iterable<? extends Tree> path) {
        // TODO: Should complete attribution if necessary
        Tree last = null;
        for (Tree node : path)
            last = node;
        return ((JCTree)last).type;
    }

    public JavacElements getElements() {
        if (context == null)
            throw new IllegalStateException();
        return JavacElements.instance(context);
    }

    public JavacTypes getTypes() {
        if (context == null)
            throw new IllegalStateException();
        return JavacTypes.instance(context);
    }

    public Iterable<? extends Tree> pathFor(CompilationUnitTree unit, Tree node) {
        return TreeInfo.pathFor((JCTree) node, (JCTree.JCCompilationUnit) unit).reverse();
    }

    abstract class Filter {
        void run(Queue<Env<AttrContext>> list, Iterable<? extends TypeElement> classes) {
            Set<TypeElement> set = new HashSet<TypeElement>();
            for (TypeElement item: classes)
                set.add(item);

            ListBuffer<Env<AttrContext>> defer = ListBuffer.<Env<AttrContext>>lb();
            while (list.peek() != null) {
                Env<AttrContext> env = list.remove();
                ClassSymbol csym;
                boolean isPkgInfo = env.toplevel.sourcefile.isNameCompatible("package-info",
                                                                             JavaFileObject.Kind.SOURCE);
                if (isPkgInfo) {
                    csym = env.toplevel.packge.package_info;
                } else {
                    csym = env.enclClass.sym;
                }
                if (csym != null && set.contains(csym.outermostClass()))
                    process(env);
                else
                    defer = defer.append(env);
            }

            list.addAll(defer);
        }

        abstract void process(Env<AttrContext> env);
    }

    /**
     * For internal use by Sun Microsystems only.  This method will be
     * removed without warning.
     */
    public Context getContext() {
        return context;
    }

    /**
     * For internal use by Sun Microsystems only.  This method will be
     * removed without warning.
     */
    public void updateContext(Context newContext) {
        context = newContext;
    }

    /**
     * For internal use by Sun Microsystems only.  This method will be
     * removed without warning.
     */
    public Type parseType(String expr, TypeElement scope) {
        if (expr == null || expr.equals(""))
            throw new IllegalArgumentException();
        compiler = JavaCompiler.instance(context);
        JavaFileObject prev = compiler.log.useSource(null);
        boolean old = compiler.log.suppressErrorsAndWarnings;
        compiler.log.suppressErrorsAndWarnings = true;
        ParserFactory parserFactory = ParserFactory.instance(context);
        Attr attr = Attr.instance(context);
        try {
            CharBuffer buf = CharBuffer.wrap((expr+"\u0000").toCharArray(), 0, expr.length());
            Parser parser = parserFactory.newParser(buf, false, false, false, true);
            JCTree tree = parser.parseType();
            return attr.attribType(tree, (Symbol.TypeSymbol)scope);
        } finally {
            compiler.log.suppressErrorsAndWarnings = old;
            compiler.log.useSource(prev);
        }
    }

    public JCStatement parseStatement(CharSequence stmt, SourcePositions[] pos) {
        if (stmt == null || (pos != null && pos.length != 1))
            throw new IllegalArgumentException();
        compiler = JavaCompiler.instance(context);
        JavaFileObject prev = compiler.log.useSource(null);
        boolean old = compiler.log.suppressErrorsAndWarnings;
        compiler.log.suppressErrorsAndWarnings = true;
        ParserFactory parserFactory = ParserFactory.instance(context);
        try {
            CharBuffer buf = CharBuffer.wrap((stmt+"\u0000").toCharArray(), 0, stmt.length());
            Parser parser = parserFactory.newParser(buf, false, true, false, true);
            if (parser instanceof JavacParser) {
                if (pos != null)
                    pos[0] = new ParserSourcePositions((JavacParser)parser);
                return parser.parseStatement();
            }
            return null;
        } finally {
            compiler.log.suppressErrorsAndWarnings = old;
            compiler.log.useSource(prev);
        }
    }

    public JCExpression parseExpression(CharSequence expr, SourcePositions[] pos) {
        if (expr == null || (pos != null && pos.length != 1))
            throw new IllegalArgumentException();
            compiler = JavaCompiler.instance(context);
        JavaFileObject prev = compiler.log.useSource(null);
        boolean old = compiler.log.suppressErrorsAndWarnings;
        compiler.log.suppressErrorsAndWarnings = true;
        ParserFactory parserFactory = ParserFactory.instance(context);
        try {
            CharBuffer buf = CharBuffer.wrap((expr+"\u0000").toCharArray(), 0, expr.length());
            Parser parser = parserFactory.newParser(buf, false, true, false, true);
            if (parser instanceof JavacParser) {
                if (pos != null)
                    pos[0] = new ParserSourcePositions((JavacParser)parser);
                return parser.parseExpression();
            }
            return null;
        } finally {
            compiler.log.suppressErrorsAndWarnings = old;
            compiler.log.useSource(prev);
        }
    }

    public JCExpression parseVariableInitializer(CharSequence init, SourcePositions[] pos) {
        if (init == null || (pos != null && pos.length != 1))
            throw new IllegalArgumentException();
            compiler = JavaCompiler.instance(context);
        JavaFileObject prev = compiler.log.useSource(null);
        boolean old = compiler.log.suppressErrorsAndWarnings;
        compiler.log.suppressErrorsAndWarnings = true;
        ParserFactory parserFactory = ParserFactory.instance(context);
        try {
            CharBuffer buf = CharBuffer.wrap((init+"\u0000").toCharArray(), 0, init.length());
            Parser parser = parserFactory.newParser(buf, false, true, false, true);
            if (parser instanceof JavacParser) {
                if (pos != null)
                    pos[0] = new ParserSourcePositions((JavacParser)parser);
                return ((JavacParser)parser).variableInitializer();
            }
            return null;
        } finally {
            compiler.log.suppressErrorsAndWarnings = old;
            compiler.log.useSource(prev);
        }
    }

    public JCBlock parseStaticBlock(CharSequence block, SourcePositions[] pos) {
        if (block == null || (pos != null && pos.length != 1))
            throw new IllegalArgumentException();
            compiler = JavaCompiler.instance(context);
        JavaFileObject prev = compiler.log.useSource(null);
        boolean old = compiler.log.suppressErrorsAndWarnings;
        compiler.log.suppressErrorsAndWarnings = true;
        ParserFactory parserFactory = ParserFactory.instance(context);
        try {
            CharBuffer buf = CharBuffer.wrap((block+"\u0000").toCharArray(), 0, block.length());
            Parser parser = parserFactory.newParser(buf, false, true, false, true);
            if (parser instanceof JavacParser) {
                if (pos != null)
                    pos[0] = new ParserSourcePositions((JavacParser)parser);
                List<JCTree> trees = ((JavacParser)parser).classOrInterfaceBodyDeclaration(null, false);
                return trees.head != null && trees.head.getTag() == JCTree.BLOCK ? (JCBlock) trees.head : null;
            }
            return null;
        } finally {
            compiler.log.suppressErrorsAndWarnings = old;
            compiler.log.useSource(prev);
        }
    }

    public Type attributeTree(JCTree tree, Env<AttrContext>env) {
        Log log = Log.instance(context);
        Attr attr = Attr.instance(context);
        JavaFileObject prev = log.useSource(null);
        boolean old = log.suppressErrorsAndWarnings;
        log.suppressErrorsAndWarnings = true;
        try {
            if (tree instanceof JCExpression)
                return attr.attribExpr(tree, env, Type.noType);
            return attr.attribStat(tree, env);
        } finally {
            log.suppressErrorsAndWarnings = old;
            log.useSource(prev);
        }
    }

    public JavacScope attributeTreeTo(JCTree tree, Env<AttrContext>env, JCTree to) {
        Log log = Log.instance(context);
        Attr attr = Attr.instance(context);
        JavaFileObject prev = log.useSource(null);
        boolean old = log.suppressErrorsAndWarnings;
        log.suppressErrorsAndWarnings = true;
        try {
            Env<AttrContext> ret = tree instanceof JCExpression ? attr.attribExprToTree(tree, env, to) : attr.attribStatToTree(tree, env, to);
            return new JavacScope(ret);
        } finally {
            log.suppressErrorsAndWarnings = old;
            log.useSource(prev);
        }
    }

    private class ParserSourcePositions implements SourcePositions {

        private JavacParser parser;

        private ParserSourcePositions(JavacParser parser) {
            this.parser = parser;
        }

        public long getStartPosition(CompilationUnitTree file, Tree tree) {
            return parser.getStartPos((JCTree)tree);
        }

        public long getEndPosition(CompilationUnitTree file, Tree tree) {
            return parser.getEndPos((JCTree)tree);
        }
    }

    public JCBlock reparseMethodBody(CompilationUnitTree topLevel, MethodTree methodToReparse, String newBodyText, int annonIndex,
            final Map<? super JCTree,? super String> docComments) {
        ParserFactory parserFactory = ParserFactory.instance(context);
        CharBuffer buf = CharBuffer.wrap((newBodyText+"\u0000").toCharArray(), 0, newBodyText.length());
        JavacParser parser = (JavacParser) parserFactory.newParser(buf, ((JCBlock)methodToReparse.getBody()).pos, ((JCCompilationUnit)topLevel).endPositions);
        final JCStatement statement = parser.parseStatement();
        JavacParser.assignAnonymousClassIndices(Names.instance(context), statement, Names.instance(context).empty, annonIndex);
        if (statement.getKind() == Tree.Kind.BLOCK) {
            if (docComments != null) {
                docComments.putAll(parser.getDocComments());
            }
            return (JCBlock) statement;            
        }
        return null;
    }

    public BlockTree reattrMethodBody(MethodTree methodToReparse, BlockTree block) {
        Attr attr = Attr.instance(context);
        assert ((JCMethodDecl)methodToReparse).localEnv != null;
        JCMethodDecl tree = (JCMethodDecl) methodToReparse;
        final Names names = Names.instance(context);
        final Symtab syms = Symtab.instance(context);
        final MemberEnter memberEnter = MemberEnter.instance(context);
        final Log log = Log.instance(context);
        final TreeMaker make = TreeMaker.instance(context);
        final Env<AttrContext> env = attr.dupLocalEnv(((JCMethodDecl) methodToReparse).localEnv);
        final ClassSymbol owner = env.enclClass.sym;
        if (tree.name == names.init && !owner.type.isErroneous() && owner.type != syms.objectType) {
            JCBlock body = tree.body;
            if (body.stats.isEmpty() || !TreeInfo.isSelfCall(body.stats.head)) {
                body.stats = body.stats.
                prepend(memberEnter.SuperCall(make.at(body.pos),
                    List.<Type>nil(),
                    List.<JCVariableDecl>nil(),
                    false));
            } else if ((env.enclClass.sym.flags() & Flags.ENUM) != 0 &&
                (tree.mods.flags & Flags.GENERATEDCONSTR) == 0 &&
                TreeInfo.isSuperCall(body.stats.head)) {
                // enum constructors are not allowed to call super
                // directly, so make sure there aren't any super calls
                // in enum constructors, except in the compiler
                // generated one.
                log.error(tree.body.stats.head.pos(),
                          "call.to.super.not.allowed.in.enum.ctor",
                          env.enclClass.sym);
                    }
                }
        attr.attribStat((JCBlock)block, env);
        return block;
    }

    public BlockTree reflowMethodBody(CompilationUnitTree topLevel, ClassTree ownerClass, MethodTree methodToReparse) {
        Flow flow = Flow.instance(context);
        TreeMaker make = TreeMaker.instance(context);
        flow.reanalyzeMethod(make.forToplevel((JCCompilationUnit)topLevel),
                (JCClassDecl)ownerClass);
        return methodToReparse.getBody();
    }

    //Debug methods
    public String dumpTodo () {
        StringBuilder res = new StringBuilder ();
        if (compiler != null && compiler.todo != null) {
            for (Env<AttrContext> env : compiler.todo) {
                res.append(((JCClassDecl)env.tree).sym.toString()  + " from: " +env.toplevel.sourcefile.toUri());
            }
        }
        return res.toString();
    }

    public java.util.List<Env<AttrContext>> getTodo () {
        if (compiler != null && compiler.todo != null) {
            return new java.util.ArrayList<Env<AttrContext>> (compiler.todo);
        }
        return java.util.Collections.<Env<AttrContext>>emptyList();
    }

}
