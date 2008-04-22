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

package com.sun.tools.javac.code;

import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import junit.framework.TestCase;

/**
 *
 * @author Jan Lahoda
 */
public class TypesTest extends TestCase {

    public TypesTest(String testName) {
        super(testName);
    }

    static class MyFileObject extends SimpleJavaFileObject {
        private String code;
        public MyFileObject(String code) {
            super(URI.create("myfo:/Test.java"), JavaFileObject.Kind.SOURCE);
            this.code = code;
        }

        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    public void test120841() throws IOException {
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;
        String code = "import java.util.ArrayList; public class Test { private void test() {new ArrayList(1) {};}}";
        final JavacTask ct = (JavacTask) tool.getTask(null, null, null, Arrays.asList("-bootclasspath", bootPath), null, Arrays.asList(new MyFileObject(code)));

        ct.analyze();
    }

    public void test120543() throws IOException {
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;
        String code = "class Test { <T1 extends B<?>, T2 extends T1> Object a(T2 c) { return c.b(); } } interface B<T3>{ T3 b(); }";
        final JavacTask ct = (JavacTask) tool.getTask(null, null, null, Arrays.asList("-bootclasspath", bootPath), null, Arrays.asList(new MyFileObject(code)));

        ct.analyze();
    }

}
