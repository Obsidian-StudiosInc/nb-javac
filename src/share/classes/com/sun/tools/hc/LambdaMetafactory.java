package com.sun.tools.hc;

import com.sun.tools.javac.util.Pair;
import java.io.UnsupportedEncodingException;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;



/**
 *
 * @author Tomas Zezula
 */
public class LambdaMetafactory {

    public static final int FLAG_SERIALIZABLE = 1 << 0;
    public static final int FLAG_MARKERS = 1 << 1;
    public static final int FLAG_BRIDGES = 1 << 2;

    private static final Logger LOG = Logger.getLogger(LambdaMetafactory.class.getName());
    private static final byte[] PATTERN;
    private static final byte[] REPLACE;
    private static final boolean DO_TRANSLATE;
    static {
        try {
            PATTERN = "java/lang/invoke/LambdaMetafactory".getBytes("UTF-8"); //NOI18N
            REPLACE = "com/sun/tools/hc/LambdaMetafactory".getBytes("UTF-8"); //NOI18N
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
        Pair<String,Integer> parsedJavaVersion = parseJavaVersion(System.getProperty("java.version"));  //NOI18N
        DO_TRANSLATE = "1.8.0".equals(parsedJavaVersion.fst) && parsedJavaVersion.snd < 51;
    }
    private static final Method mm;
    private static final Method am;
    private static final ReflectiveOperationException refOpEx;
    static {
        Method mfm = null;
        Method amm = null;
        ReflectiveOperationException roe = null;
        try {
            final Class<?> clz = Class.forName("java.lang.invoke.LambdaMetafactory"); //NOI18N
            mfm = clz.getMethod(
                    "metafactory",  //NOI18N
                    MethodHandles.Lookup.class,
                    String.class,
                    MethodType.class,
                    MethodType.class,
                    MethodHandle.class,
                    MethodType.class);
            amm = clz.getMethod(
                    "altMetafactory",   //NOI18N
                    MethodHandles.Lookup.class,
                    String.class,
                    MethodType.class,
                    new Object[0].getClass());
        } catch (ReflectiveOperationException e) {
            roe = e;
        }
        mm = mfm;
        am = amm;
        refOpEx = roe;
    }

    public static CallSite metafactory(MethodHandles.Lookup caller,
                                       String invokedName,
                                       MethodType invokedType,
                                       MethodType samMethodType,
                                       MethodHandle implMethod,
                                       MethodType instantiatedMethodType) {
        if (mm != null) {
            try {
                return (CallSite) mm.invoke(
                        null,
                        caller,
                        invokedName,
                        translate(invokedType),
                        translate(samMethodType),
                        translate(implMethod),
                        translate(instantiatedMethodType));
            } catch (InvocationTargetException e) {
                return LambdaMetafactory.<CallSite, RuntimeException>sthrow(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new RuntimeException(refOpEx);
        }
    }

    public static CallSite altMetafactory(MethodHandles.Lookup caller,
                                          String invokedName,
                                          MethodType invokedType,
                                          Object... args) {
        if (am != null) {
            try {
                return (CallSite) am.invoke(
                    null,
                    caller,
                    invokedName,
                    translate(invokedType),
                    args);
            } catch (InvocationTargetException e) {
                return LambdaMetafactory.<CallSite, RuntimeException>sthrow(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        } else {
            throw new RuntimeException(refOpEx);
        }
    }

    public static byte[] translateClassFile(
            final byte[] data,
            final int start,
            final int end) {
        if (DO_TRANSLATE) {
            for (int index = find(data,start,end,PATTERN);
                 index >= 0;
                 index = find(data,index+PATTERN.length, end, PATTERN)) {
                System.arraycopy(REPLACE, 0, data, index, REPLACE.length);
            }
        }
        return data;
    }

    private static int find(
        final byte[] data,
        final int start,
        final int end,
        final byte[] pattern)  {
        int i = start;
        int j = 0;
        for (; i < end && j < pattern.length; i++) {
            if (data[i] == pattern[j]) {
                j++;
            } else {
                j=0;
            }
        }
        return j == pattern.length ?
            i - j :
            -1;
    }

    private static MethodHandle translate(MethodHandle handle) {
        try {
            final Field typeFld = MethodHandle.class.getDeclaredField("type");  //NOI18N
            typeFld.setAccessible(true);
            translate((MethodType)typeFld.get(handle));
            final Method internalMemberName = MethodHandle.class.getDeclaredMethod("internalMemberName");   //NOI18N
            internalMemberName.setAccessible(true);
            final Object member = internalMemberName.invoke(handle);
            if (member != null) {
                final Class<?> memberNameClz = member.getClass();
                final Method getMethodType = memberNameClz.getDeclaredMethod("getMethodType");  //NOI18N
                getMethodType.setAccessible(true);
                final MethodType type = (MethodType) getMethodType.invoke(member);
                if (type != null) {
                    translate(type);
                }
            }
        } catch (ReflectiveOperationException e) {
            LOG.warning(e.getMessage());
        }
        return handle;
    }

    private static MethodType translate(MethodType mt) {
        final Class<?> origRtype = mt.returnType();
        final Class<?> rtype = translate(origRtype);
        final boolean changeRtype = origRtype != rtype;
        boolean changePtypes = false;
        final Class<?>[] ptypes = new Class<?>[mt.parameterCount()];
        for (int i = 0; i < ptypes.length; i++) {
            final Class<?> origPtype = mt.parameterType(i);
            ptypes[i] = translate(origPtype);
            changePtypes |= origPtype != ptypes[i];
        }
        try {
            if (changeRtype) {
                final Field rtypeFld = MethodType.class.getDeclaredField("rtype");   //NOI18N
                rtypeFld.setAccessible(true);
                rtypeFld.set(mt, rtype);
            }
            if (changePtypes) {
                final Field ptypesFld = MethodType.class.getDeclaredField("ptypes");   //NOI18N
                ptypesFld.setAccessible(true);
                ptypesFld.set(mt, ptypes);
            }
        } catch (ReflectiveOperationException e) {
            LOG.warning(e.getMessage());
        }
        return mt;
    }

    private static Class<?> translate(Class<?> clz) {
        if (clz.isPrimitive()) {
            return clz;
        } else if (clz.isArray()) {
            final Class<?> oldCompType = clz.getComponentType();
            final Class<?> newCompType = translate(oldCompType);
            if (oldCompType != newCompType) {
                clz = Array.newInstance(newCompType, 0).getClass();
            }
            return clz;
        } else {
            try {
                final String fqn = clz.getName();
                return LambdaMetafactory.class.getClassLoader().loadClass(fqn);
            } catch (ClassNotFoundException cnf) {
                return clz;
            }
        }
    }

    private static Pair<String,Integer> parseJavaVersion(final String version) {
        String ver = null;
        int update = 0;
        if (version != null) {
            final int index = version.lastIndexOf('_'); //NOI18N
            if (index > 0) {
                ver = version.substring(0, index);
                if (index+1 < version.length()) {
                    try {
                        update = Integer.parseInt(version.substring(index+1));
                    } catch (NumberFormatException e) {
                        LOG.log(Level.FINE, "Invalid update version in: {0}", version); //NOI18N
                    }
                }
            }
        }
        return Pair.of(ver, update);
    }

    private static <R, T extends Throwable> R sthrow(Throwable t) throws T {
        throw (T) t;
    }
}
