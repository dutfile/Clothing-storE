/*
 * Copyright (c) 2011, 2023, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.core.common.type;

import static jdk.vm.ci.code.CodeUtil.signExtend;

import org.graalvm.compiler.core.common.NumUtil;
import org.graalvm.compiler.debug.GraalError;

import jdk.vm.ci.code.CodeUtil;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.JavaType;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;

public class StampFactory {

    // JaCoCo Exclude

    private static final Stamp[] stampCache = new Stamp[JavaKind.values().length];
    private static final Stamp[] emptyStampCache = new Stamp[JavaKind.values().length];
    private static final Stamp objectStamp = new ObjectStamp(null, false, false, false, false);
    private static final Stamp objectNonNullStamp = new ObjectStamp(null, false, true, false, false);
    private static final Stamp objectAlwaysNullStamp = new ObjectStamp(null, false, false, true, false);
    private static final Stamp positiveInt = forInteger(JavaKind.Int, 0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);
    private static final Stamp nonZeroInt = IntegerStamp.create(32, JavaKind.Int.getMinValue(), JavaKind.Int.getMaxValue(), 0, CodeUtil.mask(JavaKind.Int.getStackKind().getBitCount()), false);
    private static final Stamp nonZeroLong = IntegerStamp.create(64, JavaKind.Long.getMinValue(), JavaKind.Long.getMaxValue(), 0, CodeUtil.mask(JavaKind.Long.getStackKind().getBitCount()), false);
    private static final Stamp booleanTrue = forInteger(JavaKind.Boolean, -1, -1, 1, 1);
    private static final Stamp booleanFalse = forInteger(JavaKind.Boolean, 0, 0, 0, 0);
    private static final Stamp rawPointer = new RawPointerStamp();

    private static void setCache(JavaKind kind, Stamp stamp) {
        stampCache[kind.ordinal()] = stamp;
    }

    private static void setIntCache(JavaKind kind) {
        int bits = kind.getStackKind().getBitCount();
        long mask;
        if (kind.isUnsigned()) {
            mask = CodeUtil.mask(kind.getBitCount());
        } else {
            mask = CodeUtil.mask(bits);
        }
        setCache(kind, IntegerStamp.create(bits, kind.getMinValue(), kind.getMaxValue(), 0, mask));
    }

    private static void setFloatCache(JavaKind kind) {
        setCache(kind, new FloatStamp(kind.getBitCount()));
    }

    static {
        setIntCache(JavaKind.Boolean);
        setIntCache(JavaKind.Byte);
        setIntCache(JavaKind.Short);
        setIntCache(JavaKind.Char);
        setIntCache(JavaKind.Int);
        setIntCache(JavaKind.Long);

        setFloatCache(JavaKind.Float);
        setFloatCache(JavaKind.Double);

        setCache(JavaKind.Object, objectStamp);
        setCache(JavaKind.Void, VoidStamp.getInstance());
        setCache(JavaKind.Illegal, IllegalStamp.getInstance());

        for (JavaKind k : JavaKind.values()) {
            if (stampCache[k.ordinal()] != null) {
                emptyStampCache[k.ordinal()] = stampCache[k.ordinal()].empty();
            }
        }
    }

    public static Stamp tautology() {
        return booleanTrue;
    }

    public static Stamp contradiction() {
        return booleanFalse;
    }

    /**
     * Return a stamp for a Java kind, as it would be represented on the bytecode stack.
     */
    public static Stamp forKind(JavaKind kind) {
        assert stampCache[kind.ordinal()] != null : "unexpected forKind(" + kind + ")";
        return stampCache[kind.ordinal()];
    }

    /**
     * Return the stamp for the {@code void} type. This will return a singleton instance than can be
     * compared using {@code ==}.
     */
    public static Stamp forVoid() {
        return VoidStamp.getInstance();
    }

    public static Stamp intValue() {
        return forKind(JavaKind.Int);
    }

    public static Stamp positiveInt() {
        return positiveInt;
    }

    public static Stamp nonZeroInt() {
        return nonZeroInt;
    }

    public static Stamp nonZeroLong() {
        return nonZeroLong;
    }

    public static Stamp empty(JavaKind kind) {
        return emptyStampCache[kind.ordinal()];
    }

    public static IntegerStamp forInteger(JavaKind kind, long lowerBound, long upperBound, long downMask, long upMask) {
        return IntegerStamp.create(kind.getBitCount(), lowerBound, upperBound, downMask, upMask);
    }

    public static IntegerStamp forInteger(JavaKind kind, long lowerBound, long upperBound) {
        return forInteger(kind.getBitCount(), lowerBound, upperBound);
    }

    public static IntegerStamp forIntegerWithMask(int bits, long newLowerBound, long newUpperBound, long newDownMask, long newUpMask) {
        IntegerStamp limit = StampFactory.forInteger(bits, newLowerBound, newUpperBound);
        return IntegerStamp.create(bits, newLowerBound, newUpperBound, limit.downMask() | newDownMask, limit.upMask() & newUpMask);
    }

    public static IntegerStamp forInteger(int bits) {
        return IntegerStamp.create(bits, CodeUtil.minValue(bits), CodeUtil.maxValue(bits), 0, CodeUtil.mask(bits));
    }

    public static IntegerStamp forUnsignedInteger(int bits) {
        return forUnsignedInteger(bits, 0, NumUtil.maxValueUnsigned(bits), 0, CodeUtil.mask(bits));
    }

    public static IntegerStamp forUnsignedInteger(int bits, long unsignedLowerBound, long unsignedUpperBound) {
        return forUnsignedInteger(bits, unsignedLowerBound, unsignedUpperBound, 0, CodeUtil.mask(bits));
    }

    public static IntegerStamp forUnsignedInteger(int bits, long unsignedLowerBound, long unsignedUpperBound, long downMask, long upMask) {
        if (Long.compareUnsigned(unsignedLowerBound, unsignedUpperBound) > 0) {
            return IntegerStamp.createEmptyStamp(bits);
        }
        long lowerBound = signExtend(unsignedLowerBound, bits);
        long upperBound = signExtend(unsignedUpperBound, bits);
        if (!NumUtil.sameSign(lowerBound, upperBound)) {
            lowerBound = CodeUtil.minValue(bits);
            upperBound = CodeUtil.maxValue(bits);
        }
        long mask = CodeUtil.mask(bits);
        return IntegerStamp.create(bits, lowerBound, upperBound, downMask & mask, upMask & mask);
    }

    public static IntegerStamp forInteger(int bits, long lowerBound, long upperBound) {
        return IntegerStamp.create(bits, lowerBound, upperBound, 0, CodeUtil.mask(bits));
    }

    public static FloatStamp forFloat(JavaKind kind, double lowerBound, double upperBound, boolean nonNaN) {
        assert kind.isNumericFloat();
        return new FloatStamp(kind.getBitCount(), lowerBound, upperBound, nonNaN);
    }

    public static Stamp forConstant(JavaConstant value) {
        JavaKind kind = value.getJavaKind();
        switch (kind) {
            case Boolean:
            case Byte:
            case Char:
            case Short:
            case Int:
            case Long:
                long mask = value.asLong() & CodeUtil.mask(kind.getBitCount());
                return forInteger(kind.getStackKind(), value.asLong(), value.asLong(), mask, mask);
            case Float:
                return forFloat(kind, value.asFloat(), value.asFloat(), !Float.isNaN(value.asFloat()));
            case Double:
                return forFloat(kind, value.asDouble(), value.asDouble(), !Double.isNaN(value.asDouble()));
            case Illegal:
                return forKind(JavaKind.Illegal);
            case Object:
                if (value.isNull()) {
                    return alwaysNull();
                } else {
                    return objectNonNull();
                }
            default:
                throw new GraalError("unexpected kind: %s", kind);
        }
    }

    public static Stamp forConstant(JavaConstant value, MetaAccessProvider metaAccess) {
        if (value.getJavaKind() == JavaKind.Object) {
            ResolvedJavaType type = value.isNull() ? null : metaAccess.lookupJavaType(value);
            return new ObjectStamp(type, value.isNonNull(), value.isNonNull(), value.isNull(), false);
        } else {
            return forConstant(value);
        }
    }

    public static Stamp object() {
        return objectStamp;
    }

    public static Stamp objectNonNull() {
        return objectNonNullStamp;
    }

    public static Stamp alwaysNull() {
        return objectAlwaysNullStamp;
    }

    public static ObjectStamp object(TypeReference type) {
        return object(type, false);
    }

    public static ObjectStamp objectNonNull(TypeReference type) {
        return object(type, true);
    }

    public static ObjectStamp object(TypeReference type, boolean nonNull) {
 