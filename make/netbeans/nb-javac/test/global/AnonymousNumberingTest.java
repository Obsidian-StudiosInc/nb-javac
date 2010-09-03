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

package global;

import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Symtab;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import junit.framework.TestCase;

/**
 *
 * @author lahvac
 */
public class AnonymousNumberingTest extends TestCase {

    public AnonymousNumberingTest(String name) {
        super(name);
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

    public void testCorrectAnonymousIndicesForMethodInvocations() throws IOException {
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;

        String code = "package test;\n" +
                      "public class Test {\n" +
                      "    public Test main(Object o) {\n" +
                      "        return new Test().main(new Runnable() {\n" +
                      "            public void run() {\n" +
                      "                throw new UnsupportedOperationException();\n" +
                      "            }\n" +
                      "        }).main(new Iterable() {\n" +
                      "            public java.util.Iterator iterator() {\n" +
                      "                throw new UnsupportedOperationException();\n" +
                      "            }\n" +
                      "        });\n" +
                      "    }\n" +
                      "}";

        JavacTaskImpl ct = (JavacTaskImpl)tool.getTask(null, null, null, Arrays.asList("-bootclasspath",  bootPath, "-Xjcov"), null, Arrays.asList(new MyFileObject(code)));

        ct.analyze();
        
        Symtab symTab = Symtab.instance(ct.getContext());
        TypeElement first = symTab.classes.get(ct.getElements().getName("test.Test$1"));
        TypeElement second = symTab.classes.get(ct.getElements().getName("test.Test$2"));

        assertEquals("java.lang.Iterable", ((TypeElement) ((DeclaredType) first.getInterfaces().get(0)).asElement()).getQualifiedName().toString());
        assertEquals("java.lang.Runnable", ((TypeElement) ((DeclaredType) second.getInterfaces().get(0)).asElement()).getQualifiedName().toString());
    }

    public void testCorrectAnonymousIndicesForMultipleMethods() throws IOException {
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;

        String code = "package test;\n" +
                      "public class Test {\n" +
                      "    public Test main1(Object o) {\n" +
                      "        new Runnable() {\n" +
                      "            public void run() {\n" +
                      "                throw new UnsupportedOperationException();\n" +
                      "            }\n" +
                      "        };" +
                      "    }" +
                      "    public Test main2(Object o) {\n" +
                      "        new Iterable() {\n" +
                      "            public java.util.Iterator iterator() {\n" +
                      "                throw new UnsupportedOperationException();\n" +
                      "            }\n" +
                      "        };\n" +
                      "    }\n" +
                      "    public Test main3(Object o) {\n" +
                      "        new java.util.ArrayList() {};\n" +
                      "    }\n" +
                      "}";

        JavacTaskImpl ct = (JavacTaskImpl)tool.getTask(null, null, null, Arrays.asList("-bootclasspath",  bootPath, "-Xjcov"), null, Arrays.asList(new MyFileObject(code)));

        ct.analyze();

        Symtab symTab = Symtab.instance(ct.getContext());
        TypeElement first = symTab.classes.get(ct.getElements().getName("test.Test$1"));
        TypeElement second = symTab.classes.get(ct.getElements().getName("test.Test$2"));
        TypeElement third = symTab.classes.get(ct.getElements().getName("test.Test$3"));

        assertEquals("java.lang.Runnable", ((TypeElement) ((DeclaredType) first.getInterfaces().get(0)).asElement()).getQualifiedName().toString());
        assertEquals("java.lang.Iterable", ((TypeElement) ((DeclaredType) second.getInterfaces().get(0)).asElement()).getQualifiedName().toString());
        assertEquals("java.util.ArrayList", ((TypeElement) ((DeclaredType) third.getSuperclass()).asElement()).getQualifiedName().toString());
    }

    public void testCorrectNameForAnonymous() throws IOException {
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;

        String code = "package test;\n" +
                      "public class Test {\n" +
                      "    public Test main1(Object o) {\n" +
                      "        new Runnable() {\n" +
                      "            public void run() {\n" +
                      "                throw new UnsupportedOperationException();\n" +
                      "            }\n" +
                      "        };" +
                      "        new Iterable() {\n" +
                      "            public java.util.Iterator iterator() {\n" +
                      "                new java.util.ArrayList() {};\n" +
                      "            }\n" +
                      "        };\n" +
                      "    }\n" +
                      "}";

        JavacTaskImpl ct = (JavacTaskImpl)tool.getTask(null, null, null, Arrays.asList("-bootclasspath",  bootPath, "-Xjcov"), null, Arrays.asList(new MyFileObject(code)));

        ct.analyze();

        Symtab symTab = Symtab.instance(ct.getContext());
        TypeElement first = symTab.classes.get(ct.getElements().getName("test.Test$1"));
        TypeElement second = symTab.classes.get(ct.getElements().getName("test.Test$2"));
        TypeElement third = symTab.classes.get(ct.getElements().getName("test.Test$2$1"));

        assertEquals("java.lang.Runnable", ((TypeElement) ((DeclaredType) first.getInterfaces().get(0)).asElement()).getQualifiedName().toString());
        assertEquals("java.lang.Iterable", ((TypeElement) ((DeclaredType) second.getInterfaces().get(0)).asElement()).getQualifiedName().toString());
        assertEquals("java.util.ArrayList", ((TypeElement) ((DeclaredType) third.getSuperclass()).asElement()).getQualifiedName().toString());
    }

}
