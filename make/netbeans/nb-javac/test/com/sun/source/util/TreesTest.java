/*
 * Copyright 2003-2004 Sun Microsystems, Inc.  All Rights Reserved.
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

package com.sun.source.util;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.api.JavacTrees;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import junit.framework.TestCase;

/**
 *
 * @author Jan Lahoda
 */
public class TreesTest extends TestCase {

    /** Creates a new instance of TreesTest */
    public TreesTest(String name) {
        super(name);
    }

    static class MyFileObject extends SimpleJavaFileObject {
        private String code;
        public MyFileObject() {
            this("public class Test<TTT> { public void test() {TTT ttt;}}");
        }
        public MyFileObject(String code) {
            super(URI.create("myfo:/Test.java"), JavaFileObject.Kind.SOURCE);
            this.code = code;
        }
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    public void testElementToTreeForTypeVariable() throws IOException {
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;
        final JavacTask ct = (JavacTask)tool.getTask(null, null, null, Arrays.asList("-bootclasspath",  bootPath), null, Arrays.asList(new MyFileObject()));

        CompilationUnitTree cut = ct.parse().iterator().next();

        ct.analyze();

        Trees trees = JavacTrees.instance(ct);

        new Scanner().scan(cut, trees);
    }

    public void XtestIsAccessible99346() throws IOException {
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;
        final JavacTask ct = (JavacTask)tool.getTask(null, null, null, Arrays.asList("-bootclasspath",  bootPath), null, Arrays.asList(new MyFileObject()));

        CompilationUnitTree cut = ct.parse().iterator().next();
        TreePath tp = new TreePath(new TreePath(cut), cut.getTypeDecls().get(0));
        Scope s = Trees.instance(ct).getScope(tp);
        TypeElement type = ct.getElements().getTypeElement("com.sun.java.util.jar.pack.Package.File");

        assertFalse(Trees.instance(ct).isAccessible(s, type));
    }

    public void testScope129282() throws IOException {
        String code = "package test;\n" +
                  "public class Test {\n" +
                  "\n" +
                  "    void method() {\n" +
                  "        label:\n" +
                  "        for (int i = 0; i < 1; i++) {\n" +
                  "            String foo = null;\n" +
                  "            System.out.println(foo);\n" +
                  "        }\n" +
                  "    }\n" +
                  "\n" +
                  "}\n";
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;
        final JavacTask ct = (JavacTask)tool.getTask(null, null, null, Arrays.asList("-bootclasspath",  bootPath), null, Arrays.asList(new MyFileObject(code)));

        CompilationUnitTree cut = ct.parse().iterator().next();

        ct.analyze();

        Trees trees = JavacTrees.instance(ct);
        final AtomicReference<TreePath> result = new AtomicReference<TreePath>();

        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree node, Void p) {
                if ("foo".equals(node.getName().toString())) {
                    result.set(getCurrentPath());
                }
                return super.visitVariable(node, p);
            }
        }.scan(cut, null);
        
        assertNotNull(result.get());
        
        Scope s = trees.getScope(result.get());
        boolean found = false;
        
        for (Element e : s.getLocalElements()) {
            if ("foo".equals(e.getSimpleName().toString())) {
                found = true;
                break;
            }
        }
        
        assertTrue(found);
    }
    
    private class Scanner extends TreePathScanner<Void, Trees> {

        private Tree typeParam;

        @Override
        public Void visitIdentifier(IdentifierTree node, Trees t) {
            if ("TTT".equals(node.getName().toString())) {
                Element el = t.getElement(getCurrentPath());
                assertNotNull(el);
                Tree tree = t.getTree(el);
                assertTrue(tree == typeParam);

                //the following asserts can be fixed by adding this into TreeInfo.declarationFor.DeclScanner:
//                public @Override void visitTypeParameter(JCTypeParameter tree) {
//                    if (tree.type.tsym == sym) result = tree;
//                    else super.visitTypeParameter(tree);
//                }
//
//                TreePath path = t.getPath(el);
//                assertTrue(path != null);
//                assertTrue(path.getLeaf() == tree);
            }
            return null;
        }

        @Override
        public Void visitTypeParameter(TypeParameterTree node, Trees t) {
            assertTrue(t.getElement(getCurrentPath()) != null);

            typeParam = node;

            return super.visitTypeParameter(node, t);
        }

    }

}
