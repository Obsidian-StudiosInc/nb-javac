/*
 * Copyright (c) 2003, 2012, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.tools.javac.comp;

import java.util.*;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.tools.JavaFileObject;

import com.sun.tools.javac.code.*;
import com.sun.tools.javac.jvm.*;
import com.sun.tools.javac.tree.*;
import com.sun.tools.javac.util.*;
import com.sun.tools.javac.util.List;

import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.tree.JCTree.*;

import static com.sun.tools.javac.code.Flags.*;
import static com.sun.tools.javac.code.Flags.ANNOTATION;
import static com.sun.tools.javac.code.Kinds.*;
import static com.sun.tools.javac.code.TypeTag.CLASS;
import static com.sun.tools.javac.code.TypeTag.ERROR;
import static com.sun.tools.javac.code.TypeTag.FORALL;
import static com.sun.tools.javac.code.TypeTag.TYPEVAR;
import static com.sun.tools.javac.tree.JCTree.Tag.*;
import com.sun.tools.javac.model.LazyTreeLoader;
import com.sun.tools.javac.parser.Tokens.Comment;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticFlag;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;

/** This is the second phase of Enter, in which classes are completed
 *  by entering their members into the class scope using
 *  MemberEnter.complete().  See Enter for an overview.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class MemberEnter extends JCTree.Visitor implements Completer {
    protected static final Context.Key<MemberEnter> memberEnterKey =
        new Context.Key<MemberEnter>();

    /** A switch to determine whether we check for package/class conflicts
     */
    final static boolean checkClash = true;

    private final Names names;
    private final Enter enter;
    private final Log log;
    private final Check chk;
    private final Attr attr;
    private final Symtab syms;
    private final TreeMaker make;
    private final ClassReader reader;
    private final Todo todo;
    private final Annotate annotate;
    private final Types types;
    private final JCDiagnostic.Factory diags;
    private final Source source;
    private final Target target;
    private final DeferredLintHandler deferredLintHandler;
    private final JavacMessages messages;
    private final LazyTreeLoader treeLoader;
    private final boolean ignoreNoLang;

    public static MemberEnter instance(Context context) {
        MemberEnter instance = context.get(memberEnterKey);
        if (instance == null)
            instance = new MemberEnter(context);
        return instance;
    }

    protected MemberEnter(Context context) {
        context.put(memberEnterKey, this);
        names = Names.instance(context);
        enter = Enter.instance(context);
        log = Log.instance(context);
        chk = Check.instance(context);
        attr = Attr.instance(context);
        syms = Symtab.instance(context);
        make = TreeMaker.instance(context);
        reader = ClassReader.instance(context);
        todo = Todo.instance(context);
        annotate = Annotate.instance(context);
        types = Types.instance(context);
        diags = JCDiagnostic.Factory.instance(context);
        source = Source.instance(context);
        target = Target.instance(context);
        deferredLintHandler = DeferredLintHandler.instance(context);
        Options options = Options.instance(context);
        boolean ideMode = options.get("ide") != null;
        boolean backgroundCompilation = options.get("backgroundCompilation") != null;
        ignoreNoLang = ideMode && !backgroundCompilation;
        messages = JavacMessages.instance(context);
        treeLoader = LazyTreeLoader.instance(context);
    }

    /** A queue for classes whose members still need to be entered into the
     *  symbol table.
     */
    public ListBuffer<Env<AttrContext>> halfcompleted = new ListBuffer<Env<AttrContext>>();

    /** Set to true only when the first of a set of classes is
     *  processed from the halfcompleted queue.
     */
    boolean isFirst = true;

    /** A flag to disable completion from time to time during member
     *  enter, as we only need to look up types.  This avoids
     *  unnecessarily deep recursion.
     */
    boolean completionEnabled = true;

    /* ---------- Processing import clauses ----------------
     */

    /** Import all classes of a class or package on demand.
     *  @param pos           Position to be used for error reporting.
     *  @param tsym          The class or package the members of which are imported.
     *  @param env           The env in which the imported classes will be entered.
     */
    private void importAll(int pos,
                           final TypeSymbol tsym,
                           Env<AttrContext> env) {
        // Check that packages imported from exist (JLS ???).
        if (tsym.kind == PCK && tsym.members().elems == null && !tsym.exists()) {
            // If we can't find java.lang, exit immediately.
            if (((PackageSymbol)tsym).fullname.equals(names.java_lang)) {
                JCDiagnostic msg = diags.fragment("fatal.err.no.java.lang");
                if (ignoreNoLang) {                    
                    throw new CompletionFailure(tsym, msg.toString());
                }
                else {
                    throw new MissingPlatformError (msg);
                }
            } else {
                log.error(DiagnosticFlag.RESOLVE_ERROR, pos, "doesnt.exist", tsym);
            }
        }
        env.toplevel.starImportScope.importAll(tsym.members());
    }

    /** Import all static members of a class or package on demand.
     *  @param pos           Position to be used for error reporting.
     *  @param tsym          The class or package the members of which are imported.
     *  @param env           The env in which the imported classes will be entered.
     */
    private void importStaticAll(int pos,
                                 final TypeSymbol tsym,
                                 Env<AttrContext> env) {
        final JavaFileObject sourcefile = env.toplevel.sourcefile;
        final Scope toScope = env.toplevel.starImportScope;
        final PackageSymbol packge = env.toplevel.packge;
        final TypeSymbol origin = tsym;

        // enter imported types immediately
        new Object() {
            Set<Symbol> processed = new HashSet<Symbol>();
            void importFrom(TypeSymbol tsym) {
                if (tsym == null || !processed.add(tsym))
                    return;

                // also import inherited names
                Type sup = types.supertype(tsym.type);
                if (sup != null)
                    importFrom(sup.tsym);
                for (Type t : types.interfaces(tsym.type))
                    importFrom(t.tsym);

                final Scope fromScope = tsym.members();
                for (Scope.Entry e = fromScope.elems; e != null; e = e.sibling) {
                    Symbol sym = e.sym;
                    if (sym.kind == TYP &&
                        (sym.flags() & STATIC) != 0 &&
                        staticImportAccessible(sym, packge) &&
                        sym.isMemberOf(origin, types) &&
                        !toScope.includes(sym))
                        toScope.enter(sym, fromScope, origin.members());
                }
            }
        }.importFrom(tsym);

        // enter non-types before annotations that might use them
        annotate.earlier(new Annotate.Annotator() {
            Set<Symbol> processed = new HashSet<Symbol>();

            public String toString() {
                return "import static " + tsym + ".*" + " in " + sourcefile;
            }
            void importFrom(TypeSymbol tsym) {
                if (tsym == null || !processed.add(tsym))
                    return;

                // also import inherited names
                Type sup = types.supertype(tsym.type);
                if (sup != null)
                    importFrom(sup.tsym);
                for (Type t : types.interfaces(tsym.type))
                    importFrom(t.tsym);

                final Scope fromScope = tsym.members();
                for (Scope.Entry e = fromScope.elems; e != null; e = e.sibling) {
                    Symbol sym = e.sym;
                    if (sym.isStatic() && sym.kind != TYP &&
                        staticImportAccessible(sym, packge) &&
                        !toScope.includes(sym) &&
                        sym.isMemberOf(origin, types)) {
                        toScope.enter(sym, fromScope, origin.members());
                    }
                }
            }
            public void enterAnnotation() {
                importFrom(tsym);
            }
        });
    }

    // is the sym accessible everywhere in packge?
    boolean staticImportAccessible(Symbol sym, PackageSymbol packge) {
        int flags = (int)(sym.flags() & AccessFlags);
        switch (flags) {
        default:
        case PUBLIC:
            return true;
        case PRIVATE:
            return false;
        case 0:
        case PROTECTED:
            return sym.packge() == packge;
        }
    }

    /** Import statics types of a given name.  Non-types are handled in Attr.
     *  @param pos           Position to be used for error reporting.
     *  @param tsym          The class from which the name is imported.
     *  @param name          The (simple) name being imported.
     *  @param env           The environment containing the named import
     *                  scope to add to.
     */
    private void importNamedStatic(final DiagnosticPosition pos,
                                   final TypeSymbol tsym,
                                   final Name name,
                                   final Env<AttrContext> env) {
        if (tsym.kind != TYP) {
            log.error(DiagnosticFlag.RECOVERABLE, pos, "static.imp.only.classes.and.interfaces");
            return;
        }

        final Scope toScope = env.toplevel.namedImportScope;
        final PackageSymbol packge = env.toplevel.packge;
        final TypeSymbol origin = tsym;

        // enter imported types immediately
        new Object() {
            Set<Symbol> processed = new HashSet<Symbol>();
            void importFrom(TypeSymbol tsym) {
                if (tsym == null || !processed.add(tsym))
                    return;

                // also import inherited names
                importFrom(types.supertype(tsym.type).tsym);
                for (Type t : types.interfaces(tsym.type))
                    importFrom(t.tsym);

                for (Scope.Entry e = tsym.members().lookup(name);
                     e.scope != null;
                     e = e.next()) {
                    Symbol sym = e.sym;
                    if (sym.isStatic() &&
                        sym.kind == TYP &&
                        staticImportAccessible(sym, packge) &&
                        sym.isMemberOf(origin, types) &&
                        chk.checkUniqueStaticImport(pos, sym, toScope))
                        toScope.enter(sym, sym.owner.members(), origin.members());
                }
            }
        }.importFrom(tsym);

        // enter non-types before annotations that might use them
        annotate.earlier(new Annotate.Annotator() {
            Set<Symbol> processed = new HashSet<Symbol>();
            boolean found = false;

            public String toString() {
                return "import static " + tsym + "." + name;
            }
            void importFrom(TypeSymbol tsym) {
                if (tsym == null || !processed.add(tsym))
                    return;

                // also import inherited names
                importFrom(types.supertype(tsym.type).tsym);
                for (Type t : types.interfaces(tsym.type))
                    importFrom(t.tsym);

                for (Scope.Entry e = tsym.members().lookup(name);
                     e.scope != null;
                     e = e.next()) {
                    Symbol sym = e.sym;
                    if (sym.isStatic() &&
                        staticImportAccessible(sym, packge) &&
                        sym.isMemberOf(origin, types)) {
                        found = true;
                        if (sym.kind == MTH ||
                            sym.kind != TYP && chk.checkUniqueStaticImport(pos, sym, toScope))
                            toScope.enter(sym, sym.owner.members(), origin.members());
                    }
                }
            }
            public void enterAnnotation() {
                JavaFileObject prev = log.useSource(env.toplevel.sourcefile);
                try {
                    importFrom(tsym);
                    if (!found) {
                        log.error(pos, "cant.resolve.location",
                                  KindName.STATIC,
                                  name, List.<Type>nil(), List.<Type>nil(),
                                  Kinds.typeKindName(tsym.type),
                                  tsym.type);
                    }
                } finally {
                    log.useSource(prev);
                }
            }
        });
    }
    /** Import given class.
     *  @param pos           Position to be used for error reporting.
     *  @param tsym          The class to be imported.
     *  @param env           The environment containing the named import
     *                  scope to add to.
     */
    private void importNamed(DiagnosticPosition pos, Symbol tsym, Env<AttrContext> env) {
        if ((tsym.kind == TYP || tsym.kind == ERR) &&
            chk.checkUniqueImport(pos, tsym, env.toplevel.namedImportScope))
            env.toplevel.namedImportScope.enter(tsym, tsym.owner.members());
    }

    /** Construct method type from method signature.
     *  @param typarams    The method's type parameters.
     *  @param params      The method's value parameters.
     *  @param res             The method's result type,
     *                 null if it is a constructor.
     *  @param thrown      The method's thrown exceptions.
     *  @param env             The method's (local) environment.
     */
    Type signature(List<JCTypeParameter> typarams,
                   List<JCVariableDecl> params,
                   JCTree res,
                   List<JCExpression> thrown,
                   Env<AttrContext> env) {

        // Enter and attribute type parameters.
        List<Type> tvars = enter.classEnter(typarams, env);
        attr.attribTypeVariables(typarams, env);

        // Enter and attribute value parameters.
        ListBuffer<Type> argbuf = new ListBuffer<Type>();
        for (List<JCVariableDecl> l = params; l.nonEmpty(); l = l.tail) {
            memberEnter(l.head, env);
            argbuf.append(l.head.vartype.type);
        }

        // Attribute result type, if one is given.
        Type restype = res == null ? syms.voidType : attr.attribType(res, env);

        // Attribute thrown exceptions.
        ListBuffer<Type> thrownbuf = new ListBuffer<Type>();
        for (List<JCExpression> l = thrown; l.nonEmpty(); l = l.tail) {
            Type exc = attr.attribType(l.head, env);
            if (!exc.hasTag(TYPEVAR))
                exc = chk.checkClassType(l.head.pos(), exc);
            thrownbuf.append(exc);
        }
        Type mtype = new MethodType(argbuf.toList(),
                                    restype,
                                    thrownbuf.toList(),
                                    syms.methodClass);
        return tvars.isEmpty() ? mtype : new ForAll(tvars, mtype);
    }

/* ********************************************************************
 * Visitor methods for member enter
 *********************************************************************/

    /** Visitor argument: the current environment
     */
    protected Env<AttrContext> env;

    /** Enter field and method definitions and process import
     *  clauses, catching any completion failure exceptions.
     */
    protected void memberEnter(JCTree tree, Env<AttrContext> env) {
        Env<AttrContext> prevEnv = this.env;
        try {
            this.env = env;
            tree.accept(this);
        }  catch (CompletionFailure ex) {
            chk.completionError(tree.pos(), ex);
        } finally {
            this.env = prevEnv;
        }
    }

    /** Enter members from a list of trees.
     */
    void memberEnter(List<? extends JCTree> trees, Env<AttrContext> env) {
        for (List<? extends JCTree> l = trees; l.nonEmpty(); l = l.tail)
            memberEnter(l.head, env);
    }

    /** Enter members for a class.
     */
    void finishClass(JCClassDecl tree, Env<AttrContext> env) {
        if ((tree.mods.flags & Flags.ENUM) != 0 &&
            (types.supertype(tree.sym.type).tsym.flags() & Flags.ENUM) == 0) {
            if (tree.sym == null || (tree.sym.flags_field & Flags.FROMCLASS) == 0)
                addEnumMembers(tree, env);
        }
        memberEnter(tree.defs, env);
    }

    /** Add the implicit members for an enum type
     *  to the symbol table.
     */
    private void addEnumMembers(JCClassDecl tree, Env<AttrContext> env) {
        JCExpression valuesType = make.Type(new ArrayType(tree.sym.type, syms.arrayClass));

        // public static T[] values() { return ???; }
        JCMethodDecl values = make.
            MethodDef(make.Modifiers(Flags.PUBLIC|Flags.STATIC),
                      names.values,
                      valuesType,
                      List.<JCTypeParameter>nil(),
                      List.<JCVariableDecl>nil(),
                      List.<JCExpression>nil(), // thrown
                      null, //make.Block(0, Tree.emptyList.prepend(make.Return(make.Ident(names._null)))),
                      null);
        class HardcodedComment implements Comment {
            private final String key;
            public HardcodedComment(String key) {
                this.key = key;
            }
            public String getText() {
                return messages.getLocalizedString(key); //NOI18N
            }
            public int getSourcePos(int index) {
                return -1;
            }
            public CommentStyle getStyle() {
                return CommentStyle.JAVADOC;
            }
            public boolean isDeprecated() {
                return false;
            }
        }
        DocCommentTable docComments = env.toplevel.docComments;
        if (docComments != null)
            docComments.putComment(values, new HardcodedComment("compiler.javadoc.enum.values")); //NOI18N
        memberEnter(values, env);

        // public static T valueOf(String name) { return ???; }
        JCMethodDecl valueOf = make.
            MethodDef(make.Modifiers(Flags.PUBLIC|Flags.STATIC),
                      names.valueOf,
                      make.Type(tree.sym.type),
                      List.<JCTypeParameter>nil(),
                      List.of(make.VarDef(make.Modifiers(Flags.PARAMETER),
                                            names.fromString("name"),
                                            make.Type(syms.stringType), null)),
                      List.<JCExpression>nil(), // thrown
                      null, //make.Block(0, Tree.emptyList.prepend(make.Return(make.Ident(names._null)))),
                      null);
        if (docComments != null)
            docComments.putComment(valueOf, new HardcodedComment("compiler.javadoc.enum.valueOf")); //NOI18N
        memberEnter(valueOf, env);

        // the remaining members are for bootstrapping only
        if (!target.compilerBootstrap(tree.sym)) return;

        // public final int ordinal() { return ???; }
        JCMethodDecl ordinal = make.at(tree.pos).
            MethodDef(make.Modifiers(Flags.PUBLIC|Flags.FINAL),
                      names.ordinal,
                      make.Type(syms.intType),
                      List.<JCTypeParameter>nil(),
                      List.<JCVariableDecl>nil(),
                      List.<JCExpression>nil(),
                      null,
                      null);
        memberEnter(ordinal, env);

        // public final String name() { return ???; }
        JCMethodDecl name = make.
            MethodDef(make.Modifiers(Flags.PUBLIC|Flags.FINAL),
                      names._name,
                      make.Type(syms.stringType),
                      List.<JCTypeParameter>nil(),
                      List.<JCVariableDecl>nil(),
                      List.<JCExpression>nil(),
                      null,
                      null);
        memberEnter(name, env);

        // public int compareTo(E other) { return ???; }
        MethodSymbol compareTo = new
            MethodSymbol(Flags.PUBLIC,
                         names.compareTo,
                         new MethodType(List.of(tree.sym.type),
                                        syms.intType,
                                        List.<Type>nil(),
                                        syms.methodClass),
                         tree.sym);
        memberEnter(make.MethodDef(compareTo, null), env);
    }

    public void visitTopLevel(JCCompilationUnit tree) {
        if (tree.starImportScope.elems != null) {
            // we must have already processed this toplevel
            return;
        }

        // check that no class exists with same fully qualified name as
        // toplevel package
        if (checkClash && tree.pid != null) {
            Symbol p = tree.packge;
            while (p.owner != syms.rootPackage) {
                p.owner.complete(); // enter all class members of p
                if (syms.classes.get(p.getQualifiedName()) != null) {
                    log.error(tree.pos,
                              "pkg.clashes.with.class.of.same.name",
                              p);
                }
                p = p.owner;
            }
        }

        // process package annotations
        annotateLater(tree.packageAnnotations, env, tree.packge);

        // Import-on-demand java.lang.
        importAll(tree.pos, reader.enterPackage(names.java_lang), env);

        // Process all import clauses.
        memberEnter(tree.defs, env);
    }

    // process the non-static imports and the static imports of types.
    public void visitImport(JCImport tree) {
        JCFieldAccess imp = (JCFieldAccess)tree.qualid;
        Name name = TreeInfo.name(imp);

        // Create a local environment pointing to this tree to disable
        // effects of other imports in Resolve.findGlobalType
        Env<AttrContext> localEnv = env.dup(tree);

        TypeSymbol p = attr.attribImportQualifier(tree, localEnv).tsym;
        if (name == names.asterisk) {
            // Import on demand.
            chk.checkCanonical(imp.selected);
            if (tree.staticImport)
                importStaticAll(tree.pos, p, env);
            else
                importAll(tree.pos, p, env);
        } else {
            // Named type import.
            if (tree.staticImport) {
                importNamedStatic(tree.pos(), p, name, localEnv);
                chk.checkCanonical(imp.selected);
            } else {
                TypeSymbol c = attribImportType(imp, localEnv).tsym;
                chk.checkCanonical(imp);
                importNamed(tree.pos(), c, env);
            }
        }
    }

    boolean hasSameBounds(ForAll t, ForAll s) {
        List<Type> l1 = t.tvars;
        List<Type> l2 = s.tvars;
        while (l1.nonEmpty() && l2.nonEmpty() &&
               isSameAPType(l1.head.getUpperBound(),
                          types.subst(l2.head.getUpperBound(),
                                s.tvars,
                                t.tvars))) {
            l1 = l1.tail;
            l2 = l2.tail;
        }
        return l1.isEmpty() && l2.isEmpty();
    }
    
    private boolean isSameAPType(Type t, Type s) {
        return    types.isSameType(t, s)
               || t.tsym != null && s.tsym != null && s.isErroneous() && t.tsym.name == s.tsym.name;
    }
    
    private boolean isSameMethod(Type ot, Type os) {
        if (ot.getTag() != os.getTag()) return false;
        
        switch (ot.getTag()) {
            case METHOD: {
                MethodType t = (MethodType) ot;
                MethodType s = (MethodType) os;

                if (t.getParameterTypes().size() != s.getParameterTypes().size()) return false;
                if (!isSameAPType(t.getReturnType(), s.getReturnType())) return false;

                List<Type> tp = t.getParameterTypes();
                List<Type> sp = s.getParameterTypes();

                while (!tp.isEmpty() && !sp.isEmpty()) {
                    if (!isSameAPType(tp.head, sp.head)) return false;
                    tp = tp.tail;
                    sp = sp.tail;
                }

                return tp.isEmpty() == sp.isEmpty();
            }
            case FORALL: {
                ForAll t = (ForAll) ot;
                ForAll s = (ForAll) os;

                return    hasSameBounds(t, s)
                       && isSameMethod(t.qtype, types.subst(s.qtype, s.tvars, t.tvars));
            }
            default:
                return false;
        }
    }
    
    public void visitMethodDef(JCMethodDecl tree) {
        Scope enclScope = enter.enterScope(env);
        MethodSymbol m = new MethodSymbol(0, tree.name, null, enclScope.owner);
        m.flags_field = chk.checkFlags(tree.pos(), tree.mods.flags, m, tree);
        tree.sym = m;

        //if this is a default method, add the DEFAULT flag to the enclosing interface
        if ((tree.mods.flags & DEFAULT) != 0) {
            m.enclClass().flags_field |= DEFAULT;
        }

        Env<AttrContext> localEnv = methodEnv(tree, env);

        DeferredLintHandler prevLintHandler =
                chk.setDeferredLintHandler(deferredLintHandler.setPos(tree.pos()));
        try {
            // Compute the method type
            m.type = signature(tree.typarams, tree.params,
                               tree.restype, tree.thrown,
                               localEnv);
        } finally {
            chk.setDeferredLintHandler(prevLintHandler);
        }

        if ((enclScope.owner.flags_field & FROMCLASS) != 0) {
            for (Scope.Entry e = enclScope.lookup(tree.name); e.scope == enclScope; e = e.next()) {
                if (e.sym.kind == MTH) {
                    boolean sameType = (enclScope.owner.flags_field & APT_CLEANED) != 0 ? isSameMethod(m.type, e.sym.type) : types.isSameType(m.type, e.sym.type);
                    if (sameType || m.name == names.init && (m.owner.name.isEmpty() || (m.owner.owner.kind & (VAR | MTH)) != 0)) {
                        if ((e.sym.flags_field & FROMCLASS) != 0) {
                            treeCleaner.scan(tree);
                            tree.sym = (MethodSymbol)e.sym;
                            localEnv = methodEnv(tree, env);
                            tree.sym.flags_field = chk.checkFlags(tree.pos(), tree.mods.flags, tree.sym, tree) | (tree.sym.flags_field & APT_CLEANED);
                            tree.sym.flags_field |= FROMCLASS;
                            if (tree.sym.type.hasTag(FORALL)) {
                                for(List<Type> tvars = ((ForAll)tree.sym.type).tvars; tvars.nonEmpty(); tvars = tvars.tail)
                                    localEnv.info.scope.enter(tvars.head.tsym);
                            }
                            List<VarSymbol> p = tree.sym.params().reverse();
                            if (p != null) {
                                List<JCVariableDecl> l = tree.params.reverse();
                                while(l.nonEmpty() && p.nonEmpty()) {
                                    p.head.setName(l.head.name);
                                    if (l.head.getModifiers() != null && l.head.getModifiers().getFlags().contains(Modifier.FINAL)) {
                                        //copy the final flag, as the symbol might have come from the classfile:
                                        p.head.flags_field |= FINAL;
                                    }
                                    localEnv.info.scope.enter(p.head);
                                    p.head.flags_field |= FROMCLASS;
                                    p = p.tail;
                                    l = l.tail;
                                }
                                while(p.nonEmpty()) {
                                    p.head.setName(p.head.name);
                                    localEnv.info.scope.enter(p.head);
                                    p = p.tail;
                                }
                            }
                            prevLintHandler =
                                    chk.setDeferredLintHandler(deferredLintHandler.setPos(tree.pos()));
                            try {
                                tree.sym.type = signature(tree.typarams, tree.params,
                                        tree.restype, tree.thrown,
                                        localEnv);
                            } finally {
                                chk.setDeferredLintHandler(prevLintHandler);
                            }
                            tree.sym.flags_field &= ~FROMCLASS;
                            localEnv.info.scope.leave();

                            // Set m.params
                            ListBuffer<VarSymbol> params = new ListBuffer<VarSymbol>();
                            JCVariableDecl lastParam = null;
                            for (List<JCVariableDecl> l = tree.params; l.nonEmpty(); l = l.tail) {
                                JCVariableDecl param = lastParam = l.head;
                                params.append(Assert.checkNonNull(param.sym));
                            }
                            tree.sym.params = params.toList();

                            // mark the method varargs, if necessary
                            if (lastParam != null && (lastParam.mods.flags & Flags.VARARGS) != 0)
                                tree.sym.flags_field |= Flags.VARARGS;
                            tree.sym.flags_field &= ~APT_CLEANED;
                        }
                        break;
                    }
                }
            }
            if (tree.sym == m) {
                if ((enclScope.owner.flags_field & APT_CLEANED) == 0) {
                    ClassSymbol cs = enclScope.owner.outermostClass();
                    treeLoader.couplingError(cs, tree);
                } else {
                    localEnv.info.scope.leave();
                    // Set m.params
                    ListBuffer<VarSymbol> params = new ListBuffer<VarSymbol>();
                    JCVariableDecl lastParam = null;
                    for (List<JCVariableDecl> l = tree.params; l.nonEmpty(); l = l.tail) {
                        JCVariableDecl param = lastParam = l.head;
                        assert param.sym != null;
                        params.append(param.sym);
                    }
                    m.params = params.toList();

                    // mark the method varargs, if necessary
                    if (lastParam != null && (lastParam.mods.flags & Flags.VARARGS) != 0)
                        m.flags_field |= Flags.VARARGS;

                    if( chk.checkUnique(tree.pos(), m, enclScope))
                        enclScope.enter(m);
                }
            }
        } else {
            localEnv.info.scope.leave();
            // Set m.params
            ListBuffer<VarSymbol> params = new ListBuffer<VarSymbol>();
            JCVariableDecl lastParam = null;
            for (List<JCVariableDecl> l = tree.params; l.nonEmpty(); l = l.tail) {
                JCVariableDecl param = lastParam = l.head;
                assert param.sym != null;
                params.append(param.sym);
            }
            m.params = params.toList();

            // mark the method varargs, if necessary
            if (lastParam != null && (lastParam.mods.flags & Flags.VARARGS) != 0)
                m.flags_field |= Flags.VARARGS;

            if( chk.checkUnique(tree.pos(), m, enclScope))
                enclScope.enter(m);
        }
        annotateLater(tree.mods.annotations, localEnv, tree.sym);
        if (tree.defaultValue != null)
            annotateDefaultValueLater(tree.defaultValue, localEnv, tree.sym);
    }

    /** Create a fresh environment for method bodies.
     *  @param tree     The method definition.
     *  @param env      The environment current outside of the method definition.
     */
    Env<AttrContext> methodEnv(JCMethodDecl tree, Env<AttrContext> env) {
        Env<AttrContext> localEnv =
            env.dup(tree, env.info.dup(env.info.scope.dupUnshared()));
        localEnv.enclMethod = tree;
        localEnv.info.scope.owner = tree.sym;
        if (tree.sym.type != null) {
            //when this is called in the enter stage, there's no type to be set
            localEnv.info.returnResult = attr.new ResultInfo(VAL, tree.sym.type.getReturnType());
        }
        if ((tree.mods.flags & STATIC) != 0) localEnv.info.staticLevel++;
        return localEnv;
    }

    public void visitVarDef(JCVariableDecl tree) {
        Env<AttrContext> localEnv = env;
        if ((tree.mods.flags & STATIC) != 0 ||
            (env.info.scope.owner.flags() & INTERFACE) != 0) {
            localEnv = env.dup(tree, env.info.dup());
            localEnv.info.staticLevel++;
        }
        DeferredLintHandler prevLintHandler =
                chk.setDeferredLintHandler(deferredLintHandler.setPos(tree.pos()));
        try {
            if (TreeInfo.isEnumInit(tree)) {
                attr.attribIdentAsEnumType(localEnv, (JCIdent)tree.vartype);
            } else {
                attr.attribType(tree.vartype, localEnv);
            }
        } finally {
            chk.setDeferredLintHandler(prevLintHandler);
        }

        if ((tree.mods.flags & VARARGS) != 0) {
            //if we are entering a varargs parameter, we need to replace its type
            //(a plain array type) with the more precise VarargsType --- we need
            //to do it this way because varargs is represented in the tree as a modifier
            //on the parameter declaration, and not as a distinct type of array node.
            ArrayType atype = (ArrayType)tree.vartype.type;
            tree.vartype.type = atype.makeVarargs();
        }
        Scope enclScope = enter.enterScope(env);
        VarSymbol v = null;
        boolean doEnterSymbol = true;
        if ((enclScope.owner.flags_field & FROMCLASS) != 0) {
            for (Scope.Entry e = enclScope.lookup(tree.name); e.scope == enclScope; e = e.next()) {
                boolean sameType = (enclScope.owner.flags_field & APT_CLEANED) != 0 ? isSameAPType(tree.vartype.type, e.sym.type) : types.isSameType(tree.vartype.type, e.sym.type);
                if (e.sym.kind == VAR && sameType) {
                    if ((e.sym.flags_field & FROMCLASS) != 0) {
                        v = (VarSymbol)e.sym;
                        v.type = tree.vartype.type;
                        e.sym.flags_field &= ~FROMCLASS;
                    }
                    break;
                }
            }
            if (v != null) {
                doEnterSymbol = false;
            } else if ((enclScope.owner.flags_field & APT_CLEANED) == 0) {
                ClassSymbol cs = enclScope.owner.outermostClass();
                treeLoader.couplingError(cs, tree);
                doEnterSymbol = false;
            }
        }
        if (v == null) {
            v = new VarSymbol(0, tree.name, tree.vartype.type, enclScope.owner);
        }
        v.flags_field = chk.checkFlags(tree.pos(), tree.mods.flags, v, tree);
        tree.sym = v;
        if (tree.init != null) {
            v.flags_field |= HASINIT;
            if ((v.flags_field & FINAL) != 0 &&
                    !tree.init.hasTag(NEWCLASS) &&
                    !tree.init.hasTag(LAMBDA)) {
                Env<AttrContext> initEnv = getInitEnv(tree, env);
                initEnv.info.enclVar = v;
                v.setLazyConstValue(initEnv(tree, initEnv), attr, tree.init);
            }
        }
        if (doEnterSymbol) {
            if (chk.checkUnique(tree.pos(), v, enclScope)) {
                chk.checkTransparentVar(tree.pos(), v, enclScope);
                enclScope.enter(v);
            } else if (v.getKind() == ElementKind.LOCAL_VARIABLE) {
                enclScope.enter(v);
            }
        }
        annotateLater(tree.mods.annotations, localEnv, v);
        v.pos = tree.pos;
    }

    /** Create a fresh environment for a variable's initializer.
     *  If the variable is a field, the owner of the environment's scope
     *  is be the variable itself, otherwise the owner is the method
     *  enclosing the variable definition.
     *
     *  @param tree     The variable definition.
     *  @param env      The environment current outside of the variable definition.
     */
    Env<AttrContext> initEnv(JCVariableDecl tree, Env<AttrContext> env) {
        Env<AttrContext> localEnv = env.dupto(new AttrContextEnv(tree, env.info.dup()));
        if (tree.sym.owner.kind == TYP || tree.sym.owner.kind == ERR) {
            localEnv.info.scope = env.info.scope.dupUnshared();
            localEnv.info.scope.owner = tree.sym;
        }
        if ((tree.mods.flags & STATIC) != 0 ||
                ((env.enclClass.sym.flags() & INTERFACE) != 0 && env.enclMethod == null))
            localEnv.info.staticLevel++;
        return localEnv;
    }

    /** Default member enter visitor method: do nothing
     */
    public void visitTree(JCTree tree) {
    }

    public void visitErroneous(JCErroneous tree) {
        if (tree.errs != null)
            memberEnter(tree.errs, env);
    }

    public Env<AttrContext> getBaseEnv(JCClassDecl tree, Env<AttrContext> env) {
        Env<AttrContext> bEnv = baseEnv(tree, env);
        return bEnv;
    }

    public Env<AttrContext> getMethodEnv(JCMethodDecl tree, Env<AttrContext> env) {
        Env<AttrContext> mEnv = methodEnv(tree, env);
        mEnv.info.lint = mEnv.info.lint.augment(tree.sym.annotations, tree.sym.flags());
        for (List<JCTypeParameter> l = tree.typarams; l.nonEmpty(); l = l.tail)
            mEnv.info.scope.enterIfAbsent(l.head.type.tsym);
        for (List<JCVariableDecl> l = tree.params; l.nonEmpty(); l = l.tail)
            mEnv.info.scope.enterIfAbsent(l.head.sym);
        return mEnv;
    }

    public Env<AttrContext> getInitEnv(JCVariableDecl tree, Env<AttrContext> env) {
        Env<AttrContext> iEnv = initEnv(tree, env);
        return iEnv;
    }

/* ********************************************************************
 * Type completion
 *********************************************************************/

    Type attribImportType(JCTree tree, Env<AttrContext> env) {
        Assert.check(completionEnabled);
        try {
            // To prevent deep recursion, suppress completion of some
            // types.
            completionEnabled = false;
            return attr.attribType(tree, env);
        } finally {
            completionEnabled = true;
        }
    }

/* ********************************************************************
 * Annotation processing
 *********************************************************************/

    /** Queue annotations for later processing. */
    void annotateLater(final List<JCAnnotation> annotations,
                       final Env<AttrContext> localEnv,
                       final Symbol s) {
        if (annotations.isEmpty()) {
            return;
        }
        if (s.kind != PCK || (s.flags_field & APT_CLEANED) != 0) {
            s.annotations.reset(); // mark Annotations as incomplete for now
        }
        annotate.normal(new Annotate.Annotator() {
                @Override
                public String toString() {
                    return "annotate " + annotations + " onto " + s + " in " + s.owner;
                }

                @Override
                public void enterAnnotation() {
                    Assert.check(s.kind == PCK || s.annotations.pendingCompletion());
                    JavaFileObject prev = log.useSource(localEnv.toplevel.sourcefile);
                    try {
                        if (!s.annotations.isEmpty() &&
                            annotations.nonEmpty())
                            log.error(annotations.head.pos,
                                      "already.annotated",
                                      kindName(s), s);
                        enterAnnotations(annotations, localEnv, s);
                    } finally {
                        log.useSource(prev);
                    }
                }
            });
    }

    /**
     * Check if a list of annotations contains a reference to
     * java.lang.Deprecated.
     **/
    private boolean hasDeprecatedAnnotation(List<JCAnnotation> annotations) {
        for (List<JCAnnotation> al = annotations; !al.isEmpty(); al = al.tail) {
            JCAnnotation a = al.head;
            if (a.annotationType.type == syms.deprecatedType && a.args.isEmpty())
                return true;
        }
        return false;
    }

    /** Enter a set of annotations. */
    private void enterAnnotations(List<JCAnnotation> annotations,
                          Env<AttrContext> env,
                          Symbol s) {
        Map<TypeSymbol, ListBuffer<Attribute.Compound>> annotated =
                new LinkedHashMap<TypeSymbol, ListBuffer<Attribute.Compound>>();
        Map<Attribute.Compound, DiagnosticPosition> pos =
                new HashMap<Attribute.Compound, DiagnosticPosition>();

        for (List<JCAnnotation> al = annotations; !al.isEmpty(); al = al.tail) {
            JCAnnotation a = al.head;
            Attribute.Compound c = annotate.enterAnnotation(a,
                                                            syms.annotationType,
                                                            env);
            if (c == null) {
                continue;
            }

            if (annotated.containsKey(a.type.tsym)) {
                if (source.allowRepeatedAnnotations()) {
                    ListBuffer<Attribute.Compound> l = annotated.get(a.type.tsym);
                    l = l.append(c);
                    annotated.put(a.type.tsym, l);
                    pos.put(c, a.pos());
                } else {
                    log.error(a.pos(), "duplicate.annotation");
                }
            } else {
                annotated.put(a.type.tsym, ListBuffer.of(c));
                pos.put(c, a.pos());
            }

            // Note: @Deprecated has no effect on local variables and parameters
            if (!c.type.isErroneous()
                && s.owner.kind != MTH
                && types.isSameType(c.type, syms.deprecatedType)) {
                s.flags_field |= Flags.DEPRECATED;
        }
        }

        s.annotations.setAttributesWithCompletion(
                annotate.new AnnotateRepeatedContext(env, annotated, pos, log));
    }

    /** Queue processing of an attribute default value. */
    void annotateDefaultValueLater(final JCExpression defaultValue,
                                   final Env<AttrContext> localEnv,
                                   final MethodSymbol m) {
        annotate.normal(new Annotate.Annotator() {
                @Override
                public String toString() {
                    return "annotate " + m.owner + "." +
                        m + " default " + defaultValue;
                }

                @Override
                public void enterAnnotation() {
                    JavaFileObject prev = log.useSource(localEnv.toplevel.sourcefile);
                    try {
                        enterDefaultValue(defaultValue, localEnv, m);
                    } finally {
                        log.useSource(prev);
                    }
                }
            });
    }

    /** Enter a default value for an attribute method. */
    private void enterDefaultValue(final JCExpression defaultValue,
                                   final Env<AttrContext> localEnv,
                                   final MethodSymbol m) {
        m.defaultValue = annotate.enterAttributeValue(m.type.getReturnType(),
                                                      defaultValue,
                                                      localEnv);
    }

/* ********************************************************************
 * Source completer
 *********************************************************************/

    /** Complete entering a class.
     *  @param sym         The symbol of the class to be completed.
     */
    public void complete(Symbol sym) throws CompletionFailure {
        // Suppress some (recursive) MemberEnter invocations
        if (!completionEnabled) {
            // Re-install same completer for next time around and return.
            Assert.check((sym.flags() & Flags.COMPOUND) == 0);
            sym.completer = this;
            return;
        }

        ClassSymbol c = (ClassSymbol)sym;
        ClassType ct = (ClassType)c.type;
        Env<AttrContext> env = enter.typeEnvs.get(c);
        JCClassDecl tree = (JCClassDecl)env.tree;
        boolean wasFirst = isFirst;
        isFirst = false;

        JavaFileObject prev = log.useSource(env.toplevel.sourcefile);
        try {
            // Save class environment for later member enter (2) processing.
            halfcompleted.append(env);

            // Mark class as not yet attributed.
            c.flags_field |= UNATTRIBUTED;

            // If this is a toplevel-class, make sure any preceding import
            // clauses have been seen.
            if (c.owner.kind == PCK) {
                memberEnter(env.toplevel, env.enclosing(TOPLEVEL));
                if ((c.flags_field & APT_CLEANED) != 0) {
                    todo.remove(c);
                }
                todo.append(env);
            }

            if (c.owner.kind == TYP)
                c.owner.complete();

            // create an environment for evaluating the base clauses
            Env<AttrContext> baseEnv = baseEnv(tree, env);

            Type supertype = null;
            ListBuffer<Type> interfaces = new ListBuffer<Type>();
            ListBuffer<Type> all_interfaces = null; // lazy init
            
            DiagnosticPosition prevPos = deferredLintHandler.getPos();
            DeferredLintHandler prevLintHandler =
                    chk.setDeferredLintHandler(deferredLintHandler.setPos(tree.pos()));
            try {
                // Determine supertype.
                supertype =
                    (tree.extending != null)
                    ? attr.attribBase(tree.extending, baseEnv, true, false, true)
                    : ((tree.mods.flags & Flags.ENUM) != 0 && !target.compilerBootstrap(c))
                    ? attr.attribBase(enumBase(tree.pos, c), baseEnv,
                                      true, false, false)
                    : (c.fullname == names.java_lang_Object)
                    ? Type.noType
                    : syms.objectType;
                ct.supertype_field = modelMissingTypes(supertype, tree.extending, false);

                // Determine interfaces.
                Set<Type> interfaceSet = new HashSet<Type>();
                List<JCExpression> interfaceTrees = tree.implementing;
                if ((tree.mods.flags & Flags.ENUM) != 0 && target.compilerBootstrap(c)) {
                    // add interface Comparable<T>
                    interfaceTrees =
                        interfaceTrees.prepend(make.Type(new ClassType(syms.comparableType.getEnclosingType(),
                                                                       List.of(c.type),
                                                                       syms.comparableType.tsym)));
                    // add interface Serializable
                    interfaceTrees =
                        interfaceTrees.prepend(make.Type(syms.serializableType));
                }
                for (JCExpression iface : interfaceTrees) {
                    Type i = attr.attribBase(iface, baseEnv, false, true, true);
                    if (i.hasTag(CLASS)) {
                        interfaces.append(i);
                        if (all_interfaces != null) all_interfaces.append(i);
                        chk.checkNotRepeated(iface.pos(), types.erasure(i), interfaceSet);
                    } else {
                        if (all_interfaces == null)
                            all_interfaces = new ListBuffer<Type>().appendList(interfaces);
                        all_interfaces.append(modelMissingTypes(i, iface, true));
                    }
                }
            } finally {
                deferredLintHandler.setPos(prevPos);
                chk.setDeferredLintHandler(prevLintHandler);
            }
            
            if ((c.flags_field & ANNOTATION) != 0) {
                ct.interfaces_field = List.of(syms.annotationType);
                ct.all_interfaces_field = ct.interfaces_field;
            }  else {
                ct.interfaces_field = interfaces.toList();
                ct.all_interfaces_field = (all_interfaces == null)
                        ? ct.interfaces_field : all_interfaces.toList();
            }

            if (c.fullname == names.java_lang_Object) {
                if (tree.extending != null) {
                    chk.checkNonCyclic(tree.extending.pos(),
                                       supertype);
                    ct.supertype_field = Type.noType;
                }
                else if (tree.implementing.nonEmpty()) {
                    chk.checkNonCyclic(tree.implementing.head.pos(),
                                       ct.interfaces_field.head);
                    ct.interfaces_field = List.nil();
                }
            }

            // Annotations.
            // In general, we cannot fully process annotations yet,  but we
            // can attribute the annotation types and then check to see if the
            // @Deprecated annotation is present.
            attr.attribAnnotationTypes(tree.mods.annotations, baseEnv);
            if (hasDeprecatedAnnotation(tree.mods.annotations))
                c.flags_field |= DEPRECATED;
            annotateLater(tree.mods.annotations, baseEnv, c);

            chk.checkNonCyclicDecl(tree);

            prevPos = deferredLintHandler.getPos();
            prevLintHandler =
                    chk.setDeferredLintHandler(deferredLintHandler.setPos(tree.pos()));
            try {
                attr.attribTypeVariables(tree.typarams, baseEnv);
            } finally {
                deferredLintHandler.setPos(prevPos);
                chk.setDeferredLintHandler(prevLintHandler);
            }

            // Add default constructor if needed.
            if ((c.flags() & INTERFACE) == 0 &&
                !TreeInfo.hasConstructors(tree.defs)) {
                List<Type> argtypes = List.nil();
                List<Type> typarams = List.nil();
                List<Type> thrown = List.nil();
                long ctorFlags = 0;
                boolean based = false;
                boolean addConstructor = true;
                if (c.name.isEmpty()) {
                    JCNewClass nc = (JCNewClass)env.next.tree;
                    if (nc.constructor != null) {
                        addConstructor = nc.constructor.kind != ERR;
                        Type superConstrType = types.memberType(c.type,
                                                                nc.constructor);
                        argtypes = superConstrType.getParameterTypes();
                        typarams = superConstrType.getTypeArguments();
                        ctorFlags = nc.constructor.flags() & VARARGS;
                        if (nc.encl != null) {
                            argtypes = argtypes.prepend(nc.encl.type);
                            based = true;
                        }
                        thrown = superConstrType.getThrownTypes();
                    }
                }
                if (addConstructor) {
                    JCTree constrDef = DefaultConstructor(make.at(tree.pos), c,
                                                        typarams, argtypes, thrown,
                                                        ctorFlags, based);
                    tree.defs = tree.defs.prepend(constrDef);
                }
            }

            // enter symbols for 'this' into current scope.
            VarSymbol thisSym =
                new VarSymbol(FINAL | HASINIT, names._this, c.type, c);
            thisSym.pos = Position.FIRSTPOS;
            env.info.scope.enter(thisSym);
            // if this is a class, enter symbol for 'super' into current scope.
            if ((c.flags_field & INTERFACE) == 0 &&
                    ct.supertype_field.hasTag(CLASS)) {
                VarSymbol superSym =
                    new VarSymbol(FINAL | HASINIT, names._super,
                                  ct.supertype_field, c);
                superSym.pos = Position.FIRSTPOS;
                env.info.scope.enter(superSym);
            }

            // check that no package exists with same fully qualified name,
            // but admit classes in the unnamed package which have the same
            // name as a top-level package.
            if (checkClash &&
                c.owner.kind == PCK && c.owner != syms.unnamedPackage &&
                reader.packageExists(c.fullname)) {
                log.error(tree.pos, "clash.with.pkg.of.same.name", Kinds.kindName(sym), c);
            }
            if (c.owner.kind == PCK && (c.flags_field & PUBLIC) == 0 &&
                !env.toplevel.sourcefile.isNameCompatible(c.name.toString(),JavaFileObject.Kind.SOURCE)) {
                c.flags_field |= AUXILIARY;
            }
        } catch (CompletionFailure ex) {
            chk.completionError(tree.pos(), ex);
        } catch (Attr.BreakAttr br) {
            halfcompleted.clear();
            isFirst = true;
            throw br;
        } finally {
            log.useSource(prev);
        }

        // Enter all member fields and methods of a set of half completed
        // classes in a second phase.
        if (wasFirst) {
            try {
                while (halfcompleted.nonEmpty()) {
                    finish(halfcompleted.next());
                }
            } finally {
                isFirst = true;
            }

            // commit pending annotations
            annotate.flush();
        }
    }

    private Env<AttrContext> baseEnv(JCClassDecl tree, Env<AttrContext> env) {
        Scope baseScope = new Scope(tree.sym);
        //import already entered local classes into base scope
        for (Scope.Entry e = env.outer.info.scope.elems ; e != null ; e = e.sibling) {
            if (e.sym.isLocal()) {
                baseScope.enter(e.sym);
            }
        }
        //import current type-parameters into base scope
        if (tree.typarams != null)
            for (List<JCTypeParameter> typarams = tree.typarams;
                 typarams.nonEmpty();
                 typarams = typarams.tail)
                baseScope.enter(typarams.head.type.tsym);
        Env<AttrContext> outer = env.outer; // the base clause can't see members of this class
        Env<AttrContext> localEnv = outer.dup(tree, outer.info.dup(baseScope));
        localEnv.baseClause = true;
        localEnv.outer = outer;
        localEnv.info.isSelfCall = false;
        return localEnv;
    }

    /** Enter member fields and methods of a class
     *  @param env        the environment current for the class block.
     */
    private void finish(Env<AttrContext> env) {
        JavaFileObject prev = log.useSource(env.toplevel.sourcefile);
        try {
            JCClassDecl tree = (JCClassDecl)env.tree;
            finishClass(tree, env);
        } finally {
            log.useSource(prev);
        }
    }

    /** Generate a base clause for an enum type.
     *  @param pos              The position for trees and diagnostics, if any
     *  @param c                The class symbol of the enum
     */
    private JCExpression enumBase(int pos, ClassSymbol c) {
        JCExpression result = make.at(pos).
            TypeApply(make.QualIdent(syms.enumSym),
                      List.<JCExpression>of(make.Type(c.type)));
        return result;
    }

    Type modelMissingTypes(Type t, final JCExpression tree, final boolean interfaceExpected) {
        if (!t.hasTag(ERROR))
            return t;

        return new ErrorType(((ErrorType) t).getOriginalType(), t.tsym) {
            private Type modelType;

            @Override
            public Type getModelType() {
                if (modelType == null)
                    modelType = new Synthesizer(getOriginalType(), interfaceExpected).visit(tree);
                return modelType;
            }
        };
    }
    // where
    private class Synthesizer extends JCTree.Visitor {
        Type originalType;
        boolean interfaceExpected;
        List<ClassSymbol> synthesizedSymbols = List.nil();
        Type result;

        Synthesizer(Type originalType, boolean interfaceExpected) {
            this.originalType = originalType;
            this.interfaceExpected = interfaceExpected;
        }

        Type visit(JCTree tree) {
            tree.accept(this);
            return result;
        }

        List<Type> visit(List<? extends JCTree> trees) {
            ListBuffer<Type> lb = new ListBuffer<Type>();
            for (JCTree t: trees)
                lb.append(visit(t));
            return lb.toList();
        }

        @Override
        public void visitTree(JCTree tree) {
            result = syms.errType;
        }

        @Override
        public void visitIdent(JCIdent tree) {
            if (!tree.type.hasTag(ERROR)) {
                result = tree.type;
            } else {
                result = synthesizeClass(tree.name, syms.unnamedPackage).type;
            }
        }

        @Override
        public void visitSelect(JCFieldAccess tree) {
            if (!tree.type.hasTag(ERROR)) {
                result = tree.type;
            } else {
                Type selectedType;
                boolean prev = interfaceExpected;
                try {
                    interfaceExpected = false;
                    selectedType = visit(tree.selected);
                } finally {
                    interfaceExpected = prev;
                }
                ClassSymbol c = synthesizeClass(tree.name, selectedType.tsym);
                result = c.type;
            }
        }

        @Override
        public void visitTypeApply(JCTypeApply tree) {
            if (!tree.type.hasTag(ERROR)) {
                result = tree.type;
            } else {
                ClassType clazzType = (ClassType) visit(tree.clazz);
                if (synthesizedSymbols.contains(clazzType.tsym))
                    synthesizeTyparams((ClassSymbol) clazzType.tsym, tree.arguments.size());
                final List<Type> actuals = visit(tree.arguments);
                result = new ErrorType(tree.type, clazzType.tsym) {
                    @Override
                    public List<Type> getTypeArguments() {
                        return actuals;
                    }
                };
            }
        }

        ClassSymbol synthesizeClass(Name name, Symbol owner) {
            int flags = interfaceExpected ? INTERFACE : 0;
            ClassSymbol c = new ClassSymbol(flags, name, owner);
            c.members_field = new Scope.ErrorScope(c);
            c.type = new ErrorType(originalType, c) {
                @Override
                public List<Type> getTypeArguments() {
                    return typarams_field;
                }
            };
            synthesizedSymbols = synthesizedSymbols.prepend(c);
            return c;
        }

        void synthesizeTyparams(ClassSymbol sym, int n) {
            ClassType ct = (ClassType) sym.type;
            Assert.check(ct.typarams_field.isEmpty());
            if (n == 1) {
                TypeVar v = new TypeVar(names.fromString("T"), sym, syms.botType);
                ct.typarams_field = ct.typarams_field.prepend(v);
            } else {
                for (int i = n; i > 0; i--) {
                    TypeVar v = new TypeVar(names.fromString("T" + i), sym, syms.botType);
                    ct.typarams_field = ct.typarams_field.prepend(v);
                }
            }
        }
    }


/* ***************************************************************************
 * tree building
 ****************************************************************************/

    /** Generate default constructor for given class. For classes different
     *  from java.lang.Object, this is:
     *
     *    c(argtype_0 x_0, ..., argtype_n x_n) throws thrown {
     *      super(x_0, ..., x_n)
     *    }
     *
     *  or, if based == true:
     *
     *    c(argtype_0 x_0, ..., argtype_n x_n) throws thrown {
     *      x_0.super(x_1, ..., x_n)
     *    }
     *
     *  @param make     The tree factory.
     *  @param c        The class owning the default constructor.
     *  @param argtypes The parameter types of the constructor.
     *  @param thrown   The thrown exceptions of the constructor.
     *  @param based    Is first parameter a this$n?
     */
    JCTree DefaultConstructor(TreeMaker make,
                            ClassSymbol c,
                            List<Type> typarams,
                            List<Type> argtypes,
                            List<Type> thrown,
                            long flags,
                            boolean based) {
        List<JCVariableDecl> params = make.Params(argtypes, syms.noSymbol);
        List<JCStatement> stats = List.nil();
        if (!c.type.isErroneous() && c.type != syms.objectType)
            stats = stats.prepend(SuperCall(make, typarams, params, based));
        if ((c.flags() & ENUM) != 0 &&
            (types.supertype(c.type).tsym == syms.enumSym ||
             target.compilerBootstrap(c))) {
            // constructors of true enums are private
            flags = (flags & ~AccessFlags) | PRIVATE | GENERATEDCONSTR;
        } else
            flags |= (c.flags() & AccessFlags) | GENERATEDCONSTR;
        if (c.name.isEmpty()) flags |= ANONCONSTR;
        JCTree result = make.MethodDef(
            make.Modifiers(flags),
            names.init,
            null,
            make.TypeParams(typarams),
            params,
            make.Types(thrown),
            make.Block(0, stats),
            null);
        return result;
    }

    /** Generate call to superclass constructor. This is:
     *
     *    super(id_0, ..., id_n)
     *
     * or, if based == true
     *
     *    id_0.super(id_1,...,id_n)
     *
     *  where id_0, ..., id_n are the names of the given parameters.
     *
     *  @param make    The tree factory
     *  @param params  The parameters that need to be passed to super
     *  @param typarams  The type parameters that need to be passed to super
     *  @param based   Is first parameter a this$n?
     */
    public JCExpressionStatement SuperCall(TreeMaker make,
                   List<Type> typarams,
                   List<JCVariableDecl> params,
                   boolean based) {
        JCExpression meth;
        if (based) {
            meth = make.Select(make.Ident(params.head), names._super);
            params = params.tail;
        } else {
            meth = make.Ident(names._super);
        }
        List<JCExpression> typeargs = typarams.nonEmpty() ? make.Types(typarams) : null;
        return make.Exec(make.Apply(typeargs, meth, make.Idents(params)));
    }

    private static TreeScanner treeCleaner = new TreeScanner() {
            public void scan(JCTree node) {
                super.scan(node);
                if (node != null)
                    node.type = null;
            }
            public void visitTopLevel(JCCompilationUnit node) {
                node.packge = null;
                super.visitTopLevel(node);
            }
            public void visitClassDef(JCClassDecl node) {
                node.sym = null;
                super.visitClassDef(node);
            }
            public void visitMethodDef(JCMethodDecl node) {
                node.sym = null;
                super.visitMethodDef(node);
            }
            public void visitVarDef(JCVariableDecl node) {
                node.sym = null;
                super.visitVarDef(node);
            }
            public void visitNewClass(JCNewClass node) {
                node.constructor = null;
                super.visitNewClass(node);
            }
            public void visitAssignop(JCAssignOp node) {
                node.operator = null;
                super.visitAssignop(node);
            }
            public void visitUnary(JCUnary node) {
                node.operator = null;
                super.visitUnary(node);
            }
            public void visitBinary(JCBinary node) {
                node.operator = null;
                super.visitBinary(node);
            }
            public void visitSelect(JCFieldAccess node) {
                node.sym = null;
                super.visitSelect(node);
            }
            public void visitIdent(JCIdent node) {
                node.sym = null;
                super.visitIdent(node);
            }
        };
}
