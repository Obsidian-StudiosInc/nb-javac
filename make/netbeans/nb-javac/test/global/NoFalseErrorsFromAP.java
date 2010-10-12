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

import com.sun.source.util.JavacTask;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import junit.framework.TestCase;

/**
 *
 * @author lahvac
 */
public class NoFalseErrorsFromAP extends TestCase {

    public NoFalseErrorsFromAP(String name) {
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

    public void testNoFalseEnterErrors() throws IOException {
        String code = "package test; @global.ap1.Ann(fqnToGenerate=\"test.G\", content=\"package test; public class G {}\") public class Test extends G {}";

        performTest(code, 0);
    }

    public void testCorrectEnterErrors() throws IOException {
        String code = "package test; @global.ap1.Ann(fqnToGenerate=\"test.H\", content=\"package test; public class H {}\") public class Test extends Undefined {}";

        performTest(code, 1);
    }

    private void performTest(String code, int expectedErrors) throws IOException {
        File sourceOutput = File.createTempFile("NoFalseErrorsFromAP", "");
        sourceOutput.delete();
        assertTrue(sourceOutput.mkdirs());

        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;

        URL myself = NoFalseErrorsFromAP.class.getProtectionDomain().getCodeSource().getLocation();
        DiagnosticCollector<JavaFileObject> diagnostic = new DiagnosticCollector<JavaFileObject>();
        JavacTask ct = (JavacTask)tool.getTask(null, null, diagnostic, Arrays.asList("-bootclasspath",  bootPath, "-source", "1.6", "-classpath", myself.toExternalForm(), "-processor", "global.ap1.AP", "-s", sourceOutput.getAbsolutePath(), "-XDbackgroundCompilation"), null, Arrays.asList(new MyFileObject(code)));
        ct.analyze();
        assertEquals(diagnostic.getDiagnostics().toString(), expectedErrors, diagnostic.getDiagnostics().size());

        //intentionally not deleting thwn the test fails to simply diagnostic
        delete(sourceOutput);
    }

    private static void delete(File d) {
        if (d.isDirectory()) {
            for (File f : d.listFiles()) {
                delete(f);
            }
        }
        d.delete();
    }
}
