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

package com.sun.tools.javac.model;

import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.CouplingAbort;

/**
 *
 * @author Dusan Balek
 */
public class LazyTreeLoader {

    /** The context key for the parameter name resolver. */
    public static final Context.Key<LazyTreeLoader> lazyTreeLoaderKey =
        new Context.Key<LazyTreeLoader>();

    public static LazyTreeLoader instance(Context context) {
        LazyTreeLoader instance = context.get(lazyTreeLoaderKey);
        if (instance == null) {
            instance = new LazyTreeLoader();
            context.put(lazyTreeLoaderKey, instance);
        }
        return instance;
    }

    public boolean loadTreeFor(ClassSymbol clazz, boolean persist) {
        return false;
    }

    public boolean loadParamNames(ClassSymbol clazz) {
        return false;
    }

    public void couplingError(ClassSymbol clazz, Tree t) {
        throw new CouplingAbort(clazz, t);
    }

    public void updateContext(Context context) {
    }
}
