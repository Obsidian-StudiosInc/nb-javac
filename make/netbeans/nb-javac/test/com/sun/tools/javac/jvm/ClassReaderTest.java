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

package com.sun.tools.javac.jvm;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import junit.framework.TestCase;

/**
 *
 * @author Jan Lahoda
 */
public class ClassReaderTest extends TestCase {

    public ClassReaderTest(String testName) {
        super(testName);
    }

    protected void setUp() throws Exception {
        super.setUp();
    }

    public void testX() throws Exception {
        byte[] array = "01234567890123456789012345678901234567890123456789".getBytes();
        InputStream ins = new TestInputStream(new ByteArrayInputStream(array));
        byte[] read = ClassReader.readInputStream(new byte[30], ins);
        byte[] readCanonical = new byte[array.length];

        System.arraycopy(read, 0, readCanonical, 0, array.length);
        assertTrue(Arrays.toString(read) + "vs." + Arrays.toString(array), Arrays.equals(array, readCanonical));
    }

    private static final class TestInputStream extends InputStream {

        private InputStream delegateTo;

        public TestInputStream(InputStream delegateTo) {
            this.delegateTo = delegateTo;
        }

        public int read() throws IOException {
            //not used by ClassReader.readInputStream:
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegateTo.read(b, off, Math.min(10, len));
        }

        @Override
        public int available() throws IOException {
            return 30;
        }

    }

    public void testOrderOnClassPathIsSignificant() throws Exception {
        final String bootPath = System.getProperty("sun.boot.class.path"); //NOI18N
        final JavaCompiler tool = ToolProvider.getSystemJavaCompiler();
        assert tool != null;

        JavacTask ct = (JavacTask)tool.getTask(null, new JFM(tool.getStandardFileManager(null, null, null)), null, Arrays.asList("-bootclasspath",  bootPath, "-Xjcov"), null, Arrays.<JavaFileObject>asList());

        TypeElement pack = ct.getElements().getTypeElement("Test");

        URI source = ((ClassSymbol) pack).classfile.toUri();

        assertTrue(source.toASCIIString(), source.getPath().endsWith("Test1.class"));
        assertEquals(1, pack.getEnclosedElements().size());
    }

    private static final class JFM extends ForwardingJavaFileManager<JavaFileManager> {

        public JFM(JavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<Kind> kinds, boolean recurse) throws IOException {
            if (StandardLocation.CLASS_PATH == location && "".equals(packageName) && kinds.contains(Kind.CLASS)) {
                try {
                    return Arrays.<JavaFileObject>asList(ClassJFO.create("Test1", 1000), ClassJFO.create("Test2", 2000));
                } catch (URISyntaxException ex) {
                    throw new IOException(ex.getMessage());
                }
            }

            Iterable<JavaFileObject> list = super.list(location, packageName, kinds, recurse);

            return list;
        }

        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (file instanceof ClassJFO) {
                return ((ClassJFO) file).binaryName;
            }

            return super.inferBinaryName(location, file);
        }

    }

    private static final class ClassJFO extends SimpleJavaFileObject {

        private final String binaryName;
        private final long lastModified;

        public ClassJFO(URI uri, String binaryName, long lastModified) {
            super(uri, Kind.CLASS);
            this.binaryName = binaryName;
            this.lastModified = lastModified;
        }

        public static final ClassJFO create(String name, long lastModified) throws URISyntaxException {
            return new ClassJFO(ClassReaderTest.class.getResource(name + ".class").toURI(), "Test", lastModified);
        }

        @Override
        public InputStream openInputStream() throws IOException {
            return uri.toURL().openStream();
        }

        @Override
        public long getLastModified() {
            return lastModified;
        }

    }

}
