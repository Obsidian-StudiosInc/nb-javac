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

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type.ClassType;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCErroneous;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCLiteral;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCNewClass;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.tree.TreeTranslator;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 *
 * @author Dusan Balek
 */
public class Repair extends TreeTranslator {

    /** The context key for the Repair phase. */
    protected static final Context.Key<Repair> repairKey = new Context.Key<Repair>();
    private static final String ERR_MESSAGE = "Uncompilable source code";

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
    private TreeMaker make;
    
    private Env<AttrContext> attrEnv;
    private boolean hasError;
    List<JCTree> parents;
    private Set<JCTree> errTrees = new HashSet<JCTree>();
    
    private Repair(Context context) {
        context.put(repairKey, this);
        syms = Symtab.instance(context);
        rs = Resolve.instance(context);
    }

    @Override
    public <T extends JCTree> T translate(T tree) {
        boolean prevHasError = hasError;
        try {
            hasError = false;
            parents = parents.prepend(tree);
            tree = super.translate(tree);
            if (!hasError && errTrees.contains(tree))
                hasError = true;
            if (!(hasError && tree instanceof JCStatement))
                return tree;
            if (tree.getTag() == JCTree.CLASSDEF || tree.getTag() == JCTree.VARDEF) {
                JCTree parent = parents.tail.head;
                if (parent == null || parent.getTag() != JCTree.BLOCK)
                    return tree;
            }
            hasError = false;
            return (T)generateErrStat(tree.pos());
        } finally {
            parents = parents.tail;            
            hasError |= prevHasError;
        }
    }

    @Override
    public void visitVarDef(JCVariableDecl tree) {
        tree.mods = translate(tree.mods);
        tree.vartype = translate(tree.vartype);
        if (!hasError) {
            tree.init = translate(tree.init);
            if (hasError) {
                JCTree parent = parents != null ? parents.tail.head : null;
                if (parent != null && parent.getTag() == JCTree.CLASSDEF)
                    tree.init = generateErrExpr(tree.init.pos());
            }
        }
        result = tree;
    }

    @Override
    public void visitApply(JCMethodInvocation tree) {
        Symbol meth = TreeInfo.symbol(tree.meth);
        if (meth == null || meth.type.isErroneous())
            hasError = true;
        else
            super.visitApply(tree);
    }

    @Override
    public void visitErroneous(JCErroneous tree) {
        hasError = true;
        result = tree;
    }
    
    private JCStatement generateErrStat(DiagnosticPosition pos) {
        make.at(pos);
        ClassType ctype = (ClassType)syms.runtimeExceptionType;
        JCLiteral literal = make.Literal(ERR_MESSAGE);
        JCNewClass tree = make.NewClass(null, null, make.QualIdent(ctype.tsym), List.<JCExpression>of(literal), null);
        tree.constructor = rs.resolveConstructor(pos, attrEnv, ctype, List.of(literal.type), null, false, false);
        tree.type = ctype;
        return make.Throw(tree);
    }
    
    private JCExpression generateErrExpr(DiagnosticPosition pos) {
        JCExpression expr = make.Erroneous(List.<JCStatement>of(generateErrStat(pos)));
        expr.type = syms.errType;
        return expr;
    }
    
    public void markErrTree(JCTree tree) {
        errTrees.add(tree);
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
}
