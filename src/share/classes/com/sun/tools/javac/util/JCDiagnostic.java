/*
 * Copyright 2003-2006 Sun Microsystems, Inc.  All Rights Reserved.
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

package com.sun.tools.javac.util;

import java.net.URI;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;

import com.sun.tools.javac.tree.JCTree;

import static com.sun.tools.javac.util.JCDiagnostic.DiagnosticType.*;

/** An abstraction of a diagnostic message generated by the compiler.
 *
 *  <p><b>This is NOT part of any API supported by Sun Microsystems.  If
 *  you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class JCDiagnostic implements Diagnostic<JavaFileObject> {
    /** A factory for creating diagnostic objects. */
    public static class Factory {
        /** The context key for the diagnostic factory. */
        protected static final Context.Key<JCDiagnostic.Factory> diagnosticFactoryKey =
            new Context.Key<JCDiagnostic.Factory>();

        /** Get the Factory instance for this context. */
        public static Factory instance(Context context) {
            Factory instance = context.get(diagnosticFactoryKey);
            if (instance == null)
                instance = new Factory(context);
            return instance;
        }

        final Messages messages;
        final String prefix;

        /** Create a new diagnostic factory. */
        protected Factory(Context context) {
            context.put(diagnosticFactoryKey, this);
            messages = Messages.instance(context);
            prefix = "compiler";
        }

        /** Create a new diagnostic factory. */
        public Factory(Messages messages, String prefix) {
            this.messages = messages;
            this.prefix = prefix;
        }

        /**
         * Create an error diagnostic.
         *  @param source The source of the compilation unit, if any, in which to report the error.
         *  @param pos    The source position at which to report the error.
         *  @param key    The key for the localized error message.
         *  @param args   Fields of the error message.
         */
        public JCDiagnostic error(
                DiagnosticSource source, DiagnosticPosition pos, String key, Object... args) {
            return new JCDiagnostic(messages, ERROR, true, source, pos, qualify(ERROR, key), args);
        }

        /**
         * Create a warning diagnostic that will not be hidden by the -nowarn or -Xlint:none options.
         *  @param source The source of the compilation unit, if any, in which to report the warning.
         *  @param pos    The source position at which to report the warning.
         *  @param key    The key for the localized error message.
         *  @param args   Fields of the error message.
         *  @see MandatoryWarningHandler
         */
        public JCDiagnostic mandatoryWarning(
                 DiagnosticSource source, DiagnosticPosition pos, String key, Object... args) {
            return new JCDiagnostic(messages, WARNING, true, source, pos, qualify(WARNING, key), args);
        }

        /**
         * Create a warning diagnostic.
         *  @param source The source of the compilation unit, if any, in which to report the warning.
         *  @param pos    The source position at which to report the warning.
         *  @param key    The key for the localized error message.
         *  @param args   Fields of the error message.
         */
        public JCDiagnostic warning(
                DiagnosticSource source, DiagnosticPosition pos, String key, Object... args) {
            return new JCDiagnostic(messages, WARNING, false, source, pos, qualify(WARNING, key), args);
        }

        /**
         * Create a note diagnostic that will not be hidden by the -nowarn or -Xlint:none options.
         *  @param key    The key for the localized error message.
         *  @param args   Fields of the error message.
         *  @see MandatoryWarningHandler
         */
        public JCDiagnostic mandatoryNote(DiagnosticSource source, String key, Object... args) {
            return new JCDiagnostic(messages, NOTE, true, source, null, qualify(NOTE, key), args);
        }

        /**
         * Create a note diagnostic.
         *  @param key    The key for the localized error message.
         *  @param args   Fields of the error message.
         */
        public JCDiagnostic note(String key, Object... args) {
            return note(null, null, key, args);
        }

        /**
         * Create a note diagnostic.
         *  @param source The source of the compilation unit, if any, in which to report the note.
         *  @param pos    The source position at which to report the note.
         *  @param key    The key for the localized error message.
         *  @param args   Fields of the error message.
         */
        public JCDiagnostic note(
                DiagnosticSource source, DiagnosticPosition pos, String key, Object... args) {
            return new JCDiagnostic(messages, NOTE, false, source, pos, qualify(NOTE, key), args);
        }

        /**
         * Create a fragment diagnostic, for use as an argument in other diagnostics
         *  @param key    The key for the localized error message.
         *  @param args   Fields of the error message.
         */
        public JCDiagnostic fragment(String key, Object... args) {
            return new JCDiagnostic(messages, FRAGMENT, false, null, null, qualify(FRAGMENT, key), args);
        }

        protected String qualify(DiagnosticType t, String key) {
            return prefix + "." + t.key + "." + key;
        }
    }



    /**
     * Create a fragment diagnostic, for use as an argument in other diagnostics
     *  @param key    The key for the localized error message.
     *  @param args   Fields of the error message.
     */
    // should be deprecated
    public static JCDiagnostic fragment(String key, Object... args) {
        return new JCDiagnostic(Messages.getDefaultMessages(),
                              FRAGMENT,
                              false,
                              null,
                              null,
                              "compiler." + FRAGMENT.key + "." + key,
                              args);
    }

    /**
     * A simple abstraction of a source file, as needed for use in a diagnostic message.
     */
    // Note: This class may be superceded by a more general abstraction
    public interface DiagnosticSource {
        JavaFileObject getFile();
        CharSequence getName();
        int getLineNumber(int pos);
        int getColumnNumber(int pos);
        Map<JCTree, Integer> getEndPosTable();
    };

    /**
     * A DiagnosticType defines the type of the diagnostic.
     **/
    public enum DiagnosticType {
        /** A fragment of an enclosing diagnostic. */
        FRAGMENT("misc"),
        /** A note: similar to, but less serious than, a warning. */
        NOTE("note"),
        /** A warning. */
        WARNING("warn"),
        /** An error. */
        ERROR("err");

        final String key;

        /** Create a DiagnosticType.
         * @param key A string used to create the resource key for the diagnostic.
         */
        DiagnosticType(String key) {
            this.key = key;
        }
    };

    /**
     * A DiagnosticPosition provides information about the positions in a file
     * that gave rise to a diagnostic. It always defines a "preferred" position
     * that most accurately defines the location of the diagnostic, it may also
     * provide a related tree node that spans that location.
     */
    public static interface DiagnosticPosition {
        /** Gets the tree node, if any, to which the diagnostic applies. */
        JCTree getTree();
        /** If there is a tree node, get the start position of the tree node.
         *  Otherwise, just returns the same as getPreferredPosition(). */
        int getStartPosition();
        /** Get the position within the file that most accurately defines the
         *  location for the diagnostic. */
        int getPreferredPosition();
        /** If there is a tree node, and if endPositions are available, get
         *  the end position of the tree node. Otherwise, just returns the
         *  same as getPreferredPosition(). */
        int getEndPosition(Map<JCTree, Integer> endPosTable);
    }

    /**
     * A DiagnosticPosition that simply identifies a position, but no related
     * tree node, as the location for a diagnostic. Used for scanner and parser
     * diagnostics. */
    public static class SimpleDiagnosticPosition implements DiagnosticPosition {
        public SimpleDiagnosticPosition(int pos) {
            this.pos = pos;
        }

        public JCTree getTree() {
            return null;
        }

        public int getStartPosition() {
            return pos;
        }

        public int getPreferredPosition() {
            return pos;
        }

        public int getEndPosition(Map<JCTree, Integer> endPosTable) {
            return pos;
        }

        private final int pos;
        }

    private final Messages messages;
    private final DiagnosticType type;
    private final DiagnosticSource source;
    private final DiagnosticPosition position;
    private final int line;
    private final int column;
    private final String key;
    private final Object[] args;
    private boolean mandatory;

    /**
     * Create a diagnostic object.
     * @param messages the resource for localized messages
     * @param dt the type of diagnostic
     * @param name the name of the source file, or null if none.
     * @param pos the character offset within the source file, if given.
     * @param key a resource key to identify the text of the diagnostic
     * @param args arguments to be included in the text of the diagnostic
     */
    protected JCDiagnostic(Messages messages,
                       DiagnosticType dt,
                       boolean mandatory,
                       DiagnosticSource source,
                       DiagnosticPosition pos,
                       String key,
                       Object ... args) {
        if (source == null && pos != null && pos.getPreferredPosition() != Position.NOPOS)
            throw new IllegalArgumentException();

        this.messages = messages;
        this.type = dt;
        this.mandatory = mandatory;
        this.source = source;
        this.position = pos;
        this.key = key;
        this.args = args;

        int n = (pos == null ? Position.NOPOS : pos.getPreferredPosition());
        if (n == Position.NOPOS || source == null)
            line = column = -1;
        else {
            line = source.getLineNumber(n);
            column = source.getColumnNumber(n);
        }
    }

    /**
     * Get the type of this diagnostic.
     * @return the type of this diagnostic
     */
    public DiagnosticType getType() {
        return type;
    }

    /**
     * Check whether or not this diagnostic is required to be shown.
     * @return true if this diagnostic is required to be shown.
     */
    public boolean isMandatory() {
        return mandatory;
    }

    /**
     * Get the name of the source file referred to by this diagnostic.
     * @return the name of the source referred to with this diagnostic, or null if none
     */
    public JavaFileObject getSource() {
        if (source == null)
            return null;
        else
            return source.getFile();
    }

    /**
     * Get the name of the source file referred to by this diagnostic.
     * @return the name of the source referred to with this diagnostic, or null if none
     */
    public String getSourceName() {
        JavaFileObject s = getSource();
        return s == null ? null : JavacFileManager.getJavacFileName(s);
    }

    /**
     * Get the source referred to by this diagnostic.
     * @return the source referred to with this diagnostic, or null if none
     */
    public DiagnosticSource getDiagnosticSource() {
        return source;
    }

    protected int getIntStartPosition() {
        return (position == null ? Position.NOPOS : position.getStartPosition());
    }

    protected int getIntPosition() {
        return (position == null ? Position.NOPOS : position.getPreferredPosition());
    }

    protected int getIntEndPosition() {
        return (position == null ? Position.NOPOS : position.getEndPosition(source.getEndPosTable()));
    }

    public long getStartPosition() {
        return getIntStartPosition();
    }

    public long getPosition() {
        return getIntPosition();
    }

    public long getEndPosition() {
        return getIntEndPosition();
    }
    
    public JCTree getTree() {
        return position == null ? null : position.getTree();
    }

    /**
     * Get the line number within the source referred to by this diagnostic.
     * @return  the line number within the source referred to by this diagnostic
     */
    public long getLineNumber() {
        return line;
    }

    /**
     * Get the column number within the line of source referred to by this diagnostic.
     * @return  the column number within the line of source referred to by this diagnostic
     */
    public long getColumnNumber() {
        return column;
    }

    /**
     * Get the arguments to be included in the text of the diagnostic.
     * @return  the arguments to be included in the text of the diagnostic
     */
    public Object[] getArgs() {
        return args;
    }

    /**
     * Get the prefix string associated with this type of diagnostic.
     * @return the prefix string associated with this type of diagnostic
     */
    public String getPrefix() {
        return getPrefix(type);
    }

    /**
     * Get the prefix string associated with a particular type of diagnostic.
     * @return the prefix string associated with a particular type of diagnostic
     */
    public String getPrefix(DiagnosticType dt) {
        switch (dt) {
        case FRAGMENT: return "";
        case NOTE:     return getLocalizedString("compiler.note.note");
        case WARNING:  return getLocalizedString("compiler.warn.warning");
        case ERROR:    return getLocalizedString("compiler.err.error");
        default:
            throw new AssertionError("Unknown diagnostic type: " + dt);
        }
    }

    /**
     * Return the standard presentation of this diagnostic.
     */
    public String toString() {
        if (defaultFormatter == null) {
            defaultFormatter =
                new DiagnosticFormatter();
        }
        return defaultFormatter.format(this);
    }

    private static DiagnosticFormatter defaultFormatter;

    private static final String messageBundleName =
        "com.sun.tools.javac.resources.compiler";

    private String getLocalizedString(String key, Object... args) {
        String[] strings = new String[args.length];
        for (int i = 0; i < strings.length; i++) {
            Object arg = args[i];
            if (arg == null)
                strings[i] = null;
            else if (arg instanceof JCDiagnostic)
                strings[i] = ((JCDiagnostic) arg).getMessage(null);
            else
                strings[i] = arg.toString();
        }

        return messages.getLocalizedString(key, (Object[]) strings);
    }

    // Methods for javax.tools.Diagnostic

    public Diagnostic.Kind getKind() {
        switch (type) {
        case NOTE:
            return Diagnostic.Kind.NOTE;
        case WARNING:
            return mandatory ? Diagnostic.Kind.MANDATORY_WARNING
                             : Diagnostic.Kind.WARNING;
        case ERROR:
            return Diagnostic.Kind.ERROR;
        default:
            return Diagnostic.Kind.OTHER;
        }
    }

    public String getCode() {
        return key;
    }

    public String getMessage(Locale locale) {
        // RFE 6406133: JCDiagnostic.getMessage ignores locale argument
        return getLocalizedString(key, args);
    }

    public boolean hasFixedPositions () {
        return this.position.getTree() == null;
    }
}
