/*
 * Copyright 1999-2006 Sun Microsystems, Inc.  All Rights Reserved.
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
package com.sun.tools.javac.comp;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds;
import com.sun.tools.javac.code.Scope;
import com.sun.tools.javac.code.Source;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.code.TypeTags;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAssignOp;
import com.sun.tools.javac.tree.JCTree.JCBinary;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCCase;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCErroneous;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCImport;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCUnary;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.MissingPlatformError;
import com.sun.tools.javac.util.Name;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 *
 * @author Dusan Balek
 */
public class Repair extends TreeTranslator {

    /** The context key for the Repair phase. */
    protected static final Context.Key<Repair> repairKey = new Context.Key<Repair>();
    private static final String ERR_MESSAGE = "Uncompilable source code";
    private static final Logger LOGGER = Logger.getLogger(Repair.class.getName());

    /** Get the instance for this context. */
    public static Repair instance(Context context) {
        Repair instance = context.get(repairKey);
        if (instance == null) {
            instance = new Repair(context);
        }
        return instance;
    }

    private Symtab syms;
    private Resolve rs;
    private Enter enter;
    private Types types;
    private Log log;
    private TreeMaker make;
    private JCDiagnostic.Factory diags;
    private boolean allowEnums;
    
    private Env<AttrContext> attrEnv;
    private boolean hasError;
    private JCDiagnostic err;
    private JCTree classLevelErrTree;
    private String classLevelErrMessage;
    private JCBlock staticInit;
    private List<JCTree> parents;
    private Set<ClassSymbol> repairedClasses = new HashSet<ClassSymbol>();
    private boolean isErrClass;
    
    private Repair(Context context) {
        context.put(repairKey, this);
        syms = Symtab.instance(context);
        rs = Resolve.instance(context);
        enter = Enter.instance(context);
        types = Types.instance(context);
        log = Log.instance(context);
        diags = JCDiagnostic.Factory.instance(context);
        Source source = Source.instance(context);
        allowEnums = source.allowEnums();
    }

    @Override
    public <T extends JCTree> T translate(T tree) {
        if (tree == null)
            return null;
        parents = parents.prepend(tree);
        try {
            if (hasError)
                return super.translate(tree);
            if ((err = log.getErrDiag(tree)) != null)
                hasError = true;
            tree = super.translate(tree);
        } finally {
            parents = parents.tail;            
        }
        if (tree.type != null && tree.type.isErroneous()) {
            JCTree parent = parents.head;
            if (parent == null || parent.getTag() != JCTree.CLASSDEF)
                hasError = true;
        }
        if (!(hasError && tree instanceof JCStatement))
            return tree;
        if (tree.getTag() == JCTree.CASE)
            return tree;
        if (tree.getTag() == JCTree.CLASSDEF || tree.getTag() == JCTree.VARDEF) {
            JCTree parent = parents.head;
            if (parent == null || (parent.getTag() != JCTree.BLOCK && parent.getTag() != JCTree.CASE))
                return tree;
        }
        String msg = err != null ? err.getMessage(null) : null;
        hasError = false;
        err = null;
        if (tree.getTag() == JCTree.BLOCK) {
            ((JCBlock)tree).stats = List.of(generateErrStat(tree.pos(), msg));
            return tree;
        }
        return (T)generateErrStat(tree.pos(), msg);
    }

    @Override
    public void visitImport(JCImport tree) {
        super.visitImport(tree);
        if (hasError && err != null) {
            classLevelErrTree = err.getTree();
            classLevelErrMessage = err.getMessage(null);
        }
    }

    @Override
    public void visitTypeParameter(JCTypeParameter tree) {
        super.visitTypeParameter(tree);
        if (tree.type != null && tree.type.tag == TypeTags.TYPEVAR) {
            Type.TypeVar tv = (Type.TypeVar)tree.type;
            if (tv.bound != null && tv.bound.isErroneous()) {
                tv.bound = syms.objectType;
                hasError = true;
            }
        }
    }

    @Override
    public void visitClassDef(JCClassDecl tree) {
        translateClass(tree.sym);
        result = tree;
    }

    @Override
    public void visitVarDef(JCVariableDecl tree) {
        super.visitVarDef(tree);
        if (hasError) {
            JCTree parent = parents != null ? parents.tail.head : null;
            if (parent != null && parent.getTag() == JCTree.CLASSDEF) {
                tree.init = err != null ? generateErrExpr(err.getTree(), err.getMessage(null)) : generateErrExpr(tree.init, null);
                hasError = false;
                err = null;
            }
        }
    }

    @Override
    public void visitMethodDef(JCMethodDecl tree) {
        boolean hadDuplicateSymError = hasError && err != null && "compiler.err.already.defined".equals(err.getCode()); //NOI18N
        tree.mods = translate(tree.mods);
        tree.restype = translate(tree.restype);
        tree.typarams = translateTypeParams(tree.typarams);
        tree.params = translateVarDefs(tree.params);
        tree.thrown = translate(tree.thrown);
        tree.defaultValue = translate(tree.defaultValue);
        tree.body = translate(tree.body);
        result = tree;
        if (isErrClass && tree.body != null) {
            JCMethodInvocation app = TreeInfo.firstConstructorCall(tree);
            Name meth = app != null ? TreeInfo.name(app.meth) : null;
            Symbol sym = app != null ? TreeInfo.symbol(app.meth) : null;
            if (meth != null && (meth == meth.table.names._this || meth == meth.table.names._super)
                    && sym != null && sym.owner.getQualifiedName() != meth.table.names.java_lang_Enum)
                tree.body.stats.tail = List.<JCStatement>nil();
            else
                tree.body.stats = List.<JCStatement>nil();
        }
        if (hasError && !hadDuplicateSymError) {
            if (tree.sym != null) {
                tree.sym.flags_field &= ~(Flags.ABSTRACT | Flags.NATIVE);
                tree.sym.defaultValue = null;
            }
            tree.defaultValue = null;
            if (tree.body == null) {
                tree.body = make.Block(0, List.<JCStatement>nil());
            }
            tree.body.stats = List.of(generateErrStat(tree.pos(), err != null ? err.getMessage(null) : null));
            hasError = false;
            err = null;
        }
    }

    @Override
    public void visitBlock(JCBlock tree) {
        if (tree.isStatic() && staticInit == null)
            staticInit = tree;
        List<JCStatement> last = null;
        for (List<JCStatement> l = tree.stats; l.nonEmpty(); l = l.tail) {
            l.head = translate(l.head);
            if (last == null && l.head.getTag() == JCTree.THROW)
                last = l;
        }
        if (last != null)
            last.tail = List.nil();
        result = tree;
    }

    @Override
    public void visitApply(JCMethodInvocation tree) {
        Symbol meth = TreeInfo.symbol(tree.meth);
        if (meth == null) {
            LOGGER.warning("Repair.visitApply tree [" + tree + "] has null symbol."); //NOI18N
            hasError = true;
        } else if (meth.type == null || meth.type.isErroneous()) {
            hasError = true;
        }
        super.visitApply(tree);
    }

    @Override
    public void visitNewClass(JCNewClass tree) {
        Symbol ctor = tree.constructor;
        if (ctor == null) {
            LOGGER.warning("Repair.visitNewClass tree [" + tree + "] has null constructor symbol."); //NOI18N
            hasError = true;
        } else if (tree.constructorType == null || tree.constructorType.isErroneous()) {
            hasError = true;
        }
        super.visitNewClass(tree);
    }

    @Override
    public void visitUnary(JCUnary tree) {
        Symbol operator = tree.operator;
        if (operator == null) {
            LOGGER.warning("Repair.visitUnary tree [" + tree + "] has null operator symbol."); //NOI18N
            hasError = true;
        }
        super.visitUnary(tree);
    }

    @Override
    public void visitBinary(JCBinary tree) {
        Symbol operator = tree.operator;
        if (operator == null) {
            LOGGER.warning("Repair.visitBinary tree [" + tree + "] has null operator symbol."); //NOI18N
            hasError = true;
        }
        super.visitBinary(tree);
    }

    @Override
    public void visitAssignop(JCAssignOp tree) {
        Symbol operator = tree.operator;
        if (operator == null) {
            LOGGER.warning("Repair.visitAssignop tree [" + tree + "] has null operator symbol."); //NOI18N
            hasError = true;
        }
        super.visitAssignop(tree);
    }

    @Override
    public void visitCase(JCCase tree) {
        tree.pat = translate(tree.pat);
        List<JCStatement> last = null;
        for (List<JCStatement> l = tree.stats; l.nonEmpty(); l = l.tail) {
            l.head = translate(l.head);
            if (last == null && l.head.getTag() == JCTree.THROW)
                last = l;
        }
        if (last != null)
            last.tail = List.nil();
        result = tree;
    }

    @Override
    public void visitErroneous(JCErroneous tree) {
        hasError = true;
        result = tree;
    }
    
    private JCStatement generateErrStat(DiagnosticPosition pos, String msg) {
        make.at(pos);
        ClassType ctype = (ClassType)syms.runtimeExceptionType;
        Symbol ctor = rs.resolveConstructor(pos, attrEnv, ctype, List.of(syms.stringType), null, false, false);
        if (ctor.kind == Kinds.MTH) {
            JCLiteral literal = make.Literal(msg != null ? ERR_MESSAGE + " - " + msg : ERR_MESSAGE); //NOI18N
            JCNewClass tree = make.NewClass(null, null, make.QualIdent(ctype.tsym), List.<JCExpression>of(literal), null);
            tree.type = ctype;
            tree.constructor = ctor;
            return make.Throw(tree);
        }
        ctor = rs.resolveConstructor(pos, attrEnv, ctype, List.<Type>nil(), null, false, false);
        if (ctor.kind == Kinds.MTH) {
            JCNewClass tree = make.NewClass(null, null, make.QualIdent(ctype.tsym), List.<JCExpression>nil(), null);
            tree.type = ctype;
            tree.constructor = ctor;
            return make.Throw(tree);
        }
        throw new MissingPlatformError (diags.fragment("fatal.err.cant.locate.ctor", ctype)); //NOI18N
    }
    
    private JCExpression generateErrExpr(DiagnosticPosition pos, String msg) {
        make.at(pos);
        JCExpression expr = make.Erroneous(List.<JCStatement>of(generateErrStat(pos, msg)));
        expr.type = syms.errType;
        return expr;
    }
    
    private JCBlock generateErrStaticInit(DiagnosticPosition pos, String msg) {
        make.at(pos);
        return make.Block(Flags.STATIC, List.<JCStatement>of(generateErrStat(pos, msg)));
    }

    private JCMethodDecl generateErrMethod(MethodSymbol sym) {
        make.at(null);
        return make.MethodDef(sym, make.Block(0, List.<JCStatement>of(generateErrStat(null, null))));
    }

    private void translateClass(ClassSymbol c) {
        if (c == null)
            return;
        Type st = types.supertype(c.type);
        if (st.tag == TypeTags.CLASS)
            translateClass((ClassSymbol)st.tsym);
        LOGGER.finest("Repair.translateClass: " + c); //NOI18N
        if (repairedClasses.contains(c)) {
            LOGGER.finest("Repair.translateClass: Should be already done"); //NOI18N
            return;
        }
        Env<AttrContext> myEnv = enter.typeEnvs.get(c);
        if (myEnv == null) {
            LOGGER.finest("Repair.translateClass: Context not found"); //NOI18N
            return;
        }
        LOGGER.finest("Repair.translateClass: Repairing " + c); //NOI18N
        repairedClasses.add(c);
        Env<AttrContext> oldEnv = attrEnv;
        try {
            attrEnv = myEnv;
            TreeMaker oldMake = make;
            make = make.forToplevel(attrEnv.toplevel);
            boolean oldHasError = hasError;
            boolean oldIsErrClass = isErrClass;
            JCDiagnostic oldErr = err;
            JCTree oldClassLevelErrTree = classLevelErrTree;
            String oldClassLevelErrMessage = classLevelErrMessage;
            JCBlock oldStaticinit = staticInit;
            try {
                for (JCImport imp : attrEnv.toplevel.getImports()) {
                    translate(imp);
                    if (classLevelErrTree != null)
                        break;
                }
                hasError = false;
                isErrClass = c.type.isErroneous();
                err = null;
                staticInit = null;
                JCClassDecl tree = (JCClassDecl)attrEnv.tree;
                tree.mods = translate(tree.mods);
                tree.typarams = translateTypeParams(tree.typarams);
                tree.extending = translate(tree.extending);
                tree.implementing = translate(tree.implementing);
                if (!hasError && (err = log.getErrDiag(tree)) != null) {
                    hasError = true;
                    isErrClass = true;
                }
                if (hasError && err != null) {
                    isErrClass = true;
                    classLevelErrTree = err.getTree();
                    classLevelErrMessage = err.getMessage(null);
                } else if (c.type.isErroneous() && oldHasError && oldErr != null) {
                    classLevelErrTree = oldErr.getTree();
                    classLevelErrMessage = oldErr.getMessage(null);
                }
                if (tree.defs != null) {
                    HashSet<MethodSymbol> nonAbstractMethods = new HashSet<MethodSymbol>();
                    for (Scope.Entry e = tree.sym.members_field.elems; e != null; e = e.sibling) {
                        if (e.sym.kind == Kinds.MTH && (e.sym.flags_field & Flags.ABSTRACT) == 0 && e.sym.name != e.sym.name.table.names.clinit)
                            nonAbstractMethods.add((MethodSymbol)e.sym);
                    }
                    List<JCTree> last = null;
                    for (List<JCTree> l = tree.defs; l != null && l.nonEmpty(); l = l.tail) {
                        if (l.head.getTag() == JCTree.METHODDEF)
                            nonAbstractMethods.remove(((JCMethodDecl)l.head).sym);
                        hasError = false;
                        err = null;
                        l.head = translate(l.head);
                        if (hasError) {
                            if (l.head.getTag() == JCTree.CLASSDEF && tree.sym.members_field.includes(((JCClassDecl)l.head).sym)) {
                                last = l;
                            } else {
                                if (last != null)
                                    last.tail = l.tail;
                                else
                                    tree.defs = l.tail;
                            }
                            if (classLevelErrTree == null) {
                                if (err != null) {
                                    classLevelErrTree = err.getTree();
                                    classLevelErrMessage = err.getMessage(null);
                                } else {
                                    classLevelErrTree = l.head;
                                }
                            }
                        } else {
                            last = l;
                        }
                    }
                    if (classLevelErrTree != null) {
                        if (staticInit != null)
                            staticInit.stats = List.of(generateErrStat(classLevelErrTree, classLevelErrMessage));
                        else
                            tree.defs = tree.defs.prepend(generateErrStaticInit(classLevelErrTree, classLevelErrMessage));
                    }
                    for (MethodSymbol symbol : nonAbstractMethods) {
                        if ((symbol.owner.flags() & Flags.ENUM) != 0) {
                            if ((symbol.name == symbol.name.table.names.values
                                    && symbol.type.asMethodType().argtypes.isEmpty())
                                    || (symbol.name == symbol.name.table.names.valueOf
                                    && symbol.type.asMethodType().argtypes.head == enter.syms.stringType
                                    && symbol.type.asMethodType().argtypes.tail.isEmpty())) {
                                continue;
                            }
                        }
                        tree.defs = tree.defs.prepend(generateErrMethod(symbol));
                    }
                }
            } finally {
                staticInit = oldStaticinit;
                classLevelErrTree = oldClassLevelErrTree;
                classLevelErrMessage = oldClassLevelErrMessage;
                err = oldErr;
                isErrClass = oldIsErrClass;
                hasError = oldHasError;
                make = oldMake;
            }
        } finally {
            attrEnv = oldEnv;
        }
    }

    public JCTree translateTopLevelClass(Env<AttrContext> env, JCTree tree, TreeMaker localMake) {
        try {
            attrEnv = env;
            make = localMake;
            hasError = false;
            parents = List.nil();
            return translate(tree);
        } finally {
            attrEnv = null;
            make = null;
        }
    }
    
    public void flush() {
        repairedClasses.clear();
    }
}
