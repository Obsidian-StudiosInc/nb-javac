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
package com.sun.tools.javac.comp;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTaskImpl;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import junit.framework.TestCase;

/**
 *
 * @author Jan Lahoda
 */
public class AttrTest extends TestCase {

    public AttrTest(String testName) {
        super(testName);
    }

    static class MyFileObject extends SimpleJavaFileObject {
        private String text;
        public MyFileObject(String text) {
            super(URI.create("myfo:/Test.java"), JavaFileObject.Kind.SOURCE);
            this.text = text;
        }
        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return text;
        }
    }

    public void testExceptionParameterCorrectKind() throws IOException {
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;

        String code = "package test; public class Test { { try { } catch (NullPointerException ex) {} } }";

        final JavacTaskImpl ct = (JavacTaskImpl)tool.getTask(null, null, null, Arrays.asList("-bootclasspath",  bootPath, "-Xjcov"), null, Arrays.asList(new MyFileObject(code)));
        CompilationUnitTree cut = ct.parse().iterator().next();

        ct.analyze();

        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree node, Void p) {
                Element el = Trees.instance(ct).getElement(getCurrentPath());

                assertNotNull(el);
                assertEquals(ElementKind.EXCEPTION_PARAMETER, el.getKind());

                return super.visitVariable(node, p);
            }
        }.scan(cut, null);
    }

}
