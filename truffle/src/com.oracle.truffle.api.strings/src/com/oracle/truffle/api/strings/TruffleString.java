
/*
 * Copyright (c) 2021, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.api.strings;

import static com.oracle.truffle.api.CompilerDirectives.isPartialEvaluationConstant;
import static com.oracle.truffle.api.strings.TStringGuards.bigEndian;
import static com.oracle.truffle.api.strings.TStringGuards.indexOfCannotMatch;
import static com.oracle.truffle.api.strings.TStringGuards.is7BitCompatible;
import static com.oracle.truffle.api.strings.TStringGuards.is7Or8Bit;
import static com.oracle.truffle.api.strings.TStringGuards.is8BitCompatible;
import static com.oracle.truffle.api.strings.TStringGuards.isAscii;
import static com.oracle.truffle.api.strings.TStringGuards.isBroken;
import static com.oracle.truffle.api.strings.TStringGuards.isBrokenMultiByte;
import static com.oracle.truffle.api.strings.TStringGuards.isBytes;
import static com.oracle.truffle.api.strings.TStringGuards.isFixedWidth;
import static com.oracle.truffle.api.strings.TStringGuards.isInlinedJavaString;
import static com.oracle.truffle.api.strings.TStringGuards.isLatin1;
import static com.oracle.truffle.api.strings.TStringGuards.isStride0;
import static com.oracle.truffle.api.strings.TStringGuards.isStride1;
import static com.oracle.truffle.api.strings.TStringGuards.isSupportedEncoding;
import static com.oracle.truffle.api.strings.TStringGuards.isUTF16;
import static com.oracle.truffle.api.strings.TStringGuards.isUTF16Or32;
import static com.oracle.truffle.api.strings.TStringGuards.isUTF32;
import static com.oracle.truffle.api.strings.TStringGuards.isUTF8;
import static com.oracle.truffle.api.strings.TStringGuards.isUnsupportedEncoding;
import static com.oracle.truffle.api.strings.TStringGuards.littleEndian;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.BitSet;

import org.graalvm.collections.EconomicMap;
import org.graalvm.collections.EconomicSet;
import org.graalvm.collections.Equivalence;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Exclusive;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.NeverDefault;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.profiles.InlinedConditionProfile;
import com.oracle.truffle.api.profiles.InlinedIntValueProfile;
import com.oracle.truffle.api.strings.TStringInternalNodesFactory.CalcStringAttributesNodeGen;

/**
 * Represents a primitive String type, which can be reused across languages. Language implementers
 * are encouraged to use Truffle Strings as their language's string type for easier interoperability
 * and better performance. Truffle strings can be encoded in a number of {@link Encoding encodings}.
 * A {@link TruffleString} object can cache multiple representations (in multiple encodings) of the
 * same string in the string object itself. A single {@link TruffleString} instance can also
 * represent the same string in multiple encodings, if the string's content would be equal in all
 * such encodings (e.g. a string containing only ASCII characters can be viewed as being encoded in
 * almost any encoding, since the encoded bytes would be equal). To facilitate this, all methods
 * have an {@code expectedEncoding} parameter to indicate which encoding a given string should be
 * viewed in.
 * <p>
 * {@link TruffleString} instances can be created via one of the following nodes, or via
 * {@link TruffleStringBuilder}.
 * <ul>
 * <li>{@link FromByteArrayNode}</li>
 * <li>{@link FromCharArrayUTF16Node}</li>
 * <li>{@link FromJavaStringNode}</li>
 * <li>{@link FromIntArrayUTF32Node}</li>
 * <li>{@link FromNativePointerNode}</li>
 * <li>{@link FromCodePointNode}</li>
 * <li>{@link FromLongNode}</li>
 * </ul>
 *
 * For iteration use {@link TruffleStringIterator}. There is a version of {@link TruffleString} that
 * is also mutable. See {@link MutableTruffleString} for details.
 * <p>
 * Please see the
 * <a href="https://github.com/oracle/graal/tree/master/truffle/docs/TruffleStrings.md">tutorial</a>
 * for further usage instructions.
 *
 * @since 22.1
 */
public final class TruffleString extends AbstractTruffleString {
    private static final VarHandle NEXT_UPDATER = initializeNextUpdater();

    @TruffleBoundary
    private static VarHandle initializeNextUpdater() {
        try {
            return MethodHandles.lookup().findVarHandle(TruffleString.class, "next", TruffleString.class);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    private static final byte FLAG_CACHE_HEAD = (byte) 0x80;
    TruffleString next;

    private TruffleString(Object data, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, boolean isCacheHead) {
        super(data, offset, length, stride, encoding, isCacheHead ? FLAG_CACHE_HEAD : 0, codePointLength, codeRange);
    }

    private static TruffleString create(Object data, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, boolean isCacheHead) {
        TruffleString string = new TruffleString(data, offset, length, stride, encoding, codePointLength, codeRange, isCacheHead);
        if (AbstractTruffleString.DEBUG_ALWAYS_CREATE_JAVA_STRING) {
            string.toJavaStringUncached();
        }
        return string;
    }

    static TruffleString createFromByteArray(byte[] bytes, int length, int stride, Encoding encoding, int codePointLength, int codeRange) {
        return createFromByteArray(bytes, length, stride, encoding, codePointLength, codeRange, true);
    }

    static TruffleString createFromByteArray(byte[] bytes, int length, int stride, Encoding encoding, int codePointLength, int codeRange, boolean isCacheHead) {
        return createFromArray(bytes, 0, length, stride, encoding, codePointLength, codeRange, isCacheHead);
    }

    static TruffleString createFromArray(Object bytes, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange) {
        return createFromArray(bytes, offset, length, stride, encoding, codePointLength, codeRange, true);
    }

    static TruffleString createFromArray(Object bytes, int offset, int length, int stride, Encoding encoding, int codePointLength, int codeRange, boolean isCacheHead) {
        assert bytes instanceof byte[] || isInlinedJavaString(bytes) || bytes instanceof NativePointer;
        assert offset >= 0;
        assert bytes instanceof NativePointer || offset + ((long) length << stride) <= TStringOps.byteLength(bytes);
        assert attrsAreCorrect(bytes, encoding, offset, length, codePointLength, codeRange, stride);

        if (DEBUG_NON_ZERO_OFFSET && bytes instanceof byte[]) {
            int byteLength = Math.toIntExact((long) length << stride);
            int add = byteLength;
            byte[] copy = new byte[add + byteLength];
            System.arraycopy(bytes, offset, copy, add, byteLength);
            return TruffleString.create(copy, add, length, stride, encoding, codePointLength, codeRange, isCacheHead);
        }
        return TruffleString.create(bytes, offset, length, stride, encoding, codePointLength, codeRange, isCacheHead);
    }

    static TruffleString createConstant(byte[] bytes, int length, int stride, Encoding encoding, int codePointLength, int codeRange) {
        return createConstant(bytes, length, stride, encoding, codePointLength, codeRange, true);
    }

    static TruffleString createConstant(byte[] bytes, int length, int stride, Encoding encoding, int codePointLength, int codeRange, boolean isCacheHead) {
        TruffleString ret = createFromByteArray(bytes, length, stride, encoding, codePointLength, codeRange, isCacheHead);
        // eagerly compute cached hash
        ret.hashCode();
        return ret;
    }

    static TruffleString createLazyLong(long value, Encoding encoding) {
        int length = NumberConversion.stringLengthLong(value);
        return TruffleString.create(new LazyLong(value), 0, length, 0, encoding, length, TSCodeRange.get7Bit(), true);
    }

    static TruffleString createLazyConcat(TruffleString a, TruffleString b, Encoding encoding, int length, int stride, int codeRange) {
        assert !TSCodeRange.isBrokenMultiByte(a.codeRange());
        assert !TSCodeRange.isBrokenMultiByte(b.codeRange());
        assert a.isLooselyCompatibleTo(encoding);
        assert b.isLooselyCompatibleTo(encoding);
        assert length == a.length() + b.length();
        return TruffleString.create(new LazyConcat(a, b), 0, length, stride, encoding, a.codePointLength() + b.codePointLength(), codeRange, true);
    }

    static TruffleString createWrapJavaString(String str, int codePointLength, int codeRange) {
        int stride = TStringUnsafe.getJavaStringStride(str);
        return TruffleString.create(str, 0, str.length(), stride, Encoding.UTF_16, codePointLength, codeRange, false);
    }

    private static boolean attrsAreCorrect(Object bytes, Encoding encoding, int offset, int length, int codePointLength, int codeRange, int stride) {
        CompilerAsserts.neverPartOfCompilation();
        int knownCodeRange = TSCodeRange.getUnknownCodeRangeForEncoding(encoding.id);
        if (isUTF16Or32(encoding) && stride == 0) {
            knownCodeRange = TSCodeRange.get8Bit();
        } else if (isUTF32(encoding) && stride == 1) {
            knownCodeRange = TSCodeRange.get16Bit();
        }
        if (bytes instanceof NativePointer) {
            ((NativePointer) bytes).materializeByteArray(null, offset, length << stride, InlinedConditionProfile.getUncached());
        }
        long attrs = CalcStringAttributesNodeGen.getUncached().execute(CalcStringAttributesNodeGen.getUncached(), null, bytes, offset, length, stride, encoding, 0, knownCodeRange);
        int cpLengthCalc = StringAttributes.getCodePointLength(attrs);
        int codeRangeCalc = StringAttributes.getCodeRange(attrs);
        assert codePointLength == -1 || cpLengthCalc == codePointLength : "inconsistent codePointLength: " + cpLengthCalc + " != " + codePointLength;
        if (TSCodeRange.isPrecise(codeRange)) {
            assert codeRangeCalc == codeRange : "inconsistent codeRange: " + TSCodeRange.toString(codeRangeCalc) + " != " + TSCodeRange.toString(codeRange);
        } else {
            assert TSCodeRange.isMoreRestrictiveOrEqual(codeRangeCalc, codeRange) : "imprecise codeRange more restrictive than actual codeRange: " + TSCodeRange.toString(codeRangeCalc) + " > " +
                            TSCodeRange.toString(codeRange);
        }
        return true;
    }

    boolean isCacheHead() {
        assert ((flags() & FLAG_CACHE_HEAD) != 0) == (flags() < 0);
        return flags() < 0;
    }

    TruffleString getCacheHead() {
        assert cacheRingIsValid();
        TruffleString cur = next;
        if (cur == null) {
            assert isCacheHead();
            return this;
        }
        while (!cur.isCacheHead()) {
            cur = cur.next;
        }
        return cur;
    }

    @TruffleBoundary
    void cacheInsert(TruffleString entry) {
        assert !entry.isCacheHead();
        // the cache head does never change
        TruffleString cacheHead = getCacheHead();
        assert !cacheEntryEquals(cacheHead, entry);
        TruffleString cacheHeadNext;
        do {
            cacheHeadNext = cacheHead.next;
            if (hasDuplicateEncoding(cacheHead, cacheHeadNext, entry)) {
                return;
            }
            entry.next = cacheHeadNext == null ? cacheHead : cacheHeadNext;
        } while (!setNextAtomic(cacheHead, cacheHeadNext, entry));
    }

    /*
     * Simpler and faster insertion for the case `this` and `entry` were just allocated together and
     * before they are published. The CAS is not needed in that case since we know nobody could
     * write to `next` fields before us.
     */
    void cacheInsertFirstBeforePublished(TruffleString entry) {
        assert !entry.isCacheHead();
        assert isCacheHead();
        assert next == null;
        TruffleString cacheHead = this;
        entry.next = cacheHead;
        cacheHead.next = entry;
    }

    private static boolean hasDuplicateEncoding(TruffleString cacheHead, TruffleString start, TruffleString insertEntry) {
        if (start == null) {
            return false;
        }
        TruffleString current = start;
        while (current != cacheHead) {
            if (cacheEntryEquals(insertEntry, current)) {
                return true;
            }
            current = current.next;
        }
        return false;
    }

    private static boolean cacheEntryEquals(TruffleString a, TruffleString b) {
        return b.encoding() == a.encoding() && a.isNative() == b.isNative() && a.stride() == b.stride() && (!isUTF16(a.encoding()) || b.isJavaString() == a.isJavaString());
    }

    @TruffleBoundary
    private static boolean setNextAtomic(TruffleString cacheHead, TruffleString currentNext, TruffleString newNext) {
        return NEXT_UPDATER.compareAndSet(cacheHead, currentNext, newNext);
    }

    private boolean cacheRingIsValid() {
        CompilerAsserts.neverPartOfCompilation();
        TruffleString head = null;
        TruffleString cur = this;
        boolean javaStringVisited = false;
        BitSet visitedManaged = new BitSet(Encoding.values().length);
        BitSet visitedNativeRegular = new BitSet(Encoding.values().length);
        BitSet visitedNativeCompact = new BitSet(Encoding.values().length);
        EconomicSet<TruffleString> visited = EconomicSet.create(Equivalence.IDENTITY_WITH_SYSTEM_HASHCODE);
        do {
            if (cur.isCacheHead()) {
                assert head == null : "multiple cache heads";
                head = cur;
            }
            if (cur.isJavaString()) {
                assert !javaStringVisited : "duplicate cached java string";
                javaStringVisited = true;
            } else {
                Encoding encoding = Encoding.get(cur.encoding());
                if (cur.isManaged()) {
                    assert !visitedManaged.get(cur.encoding()) : "duplicate managed " + encoding;
                    visitedManaged.set(cur.encoding());
                } else {
                    if (cur.stride() == encoding.naturalStride) {
                        assert !visitedNativeRegular.get(cur.encoding()) : "duplicate native " + encoding;
                        visitedNativeRegular.set(cur.encoding());
                    } else {
                        assert !visitedNativeCompact.get(cur.encoding()) : "duplicate compact native " + encoding;
                        visitedNativeCompact.set(cur.encoding());
                    }
                }
            }
            assert visited.add(cur) : "not a ring structure";
            cur = cur.next;
        } while (cur != this && cur != null);
        return true;
    }

    /**
     * The list of encodings supported by {@link TruffleString}. {@link TruffleString} is especially
     * optimized for the following encodings:
     * <ul>
     * <li>{@code UTF-32}: this means UTF-32 <i>in your system's endianness</i>.
     * {@link TruffleString} transparently compacts UTF-32 strings to 8-bit or 16-bit
     * representations, where possible.</li>
     * <li>{@code UTF-16}: this means UTF-16 <i>in your system's endianness</i>.
     * {@link TruffleString} transparently compacts UTF-16 strings to 8-bit representations, where
     * possible.</li>
     * <li>{@code UTF-8}</li>
     * <li>{@code ISO-8859-1}</li>
     * <li>{@code US-ASCII}</li>
     * <li>{@code BYTES}, which is essentially identical to US-ASCII, with the only difference being
     * that {@code BYTES} treats all byte values as valid codepoints.</li>
     * </ul>
     * <p>
     * </p>
     * All other encodings are supported using the JRuby JCodings library, which incurs more
     * {@link TruffleBoundary} calls. NOTE: to enable support for these encodings,
     * {@code TruffleLanguage.Registration#needsAllEncodings()} must be set to {@code true} in the
     * truffle language's registration.
     *
     * @since 22.1
     */
    public enum Encoding {

        /* directly supported encodings */
        /**
         * UTF-32LE. Directly supported if the current system is little-endian.
         *
         * @since 22.1
         */
        UTF_32LE(littleEndian() ? 0 : 97, "UTF_32LE", littleEndian() ? 2 : 0, littleEndian()),
        /**
         * UTF-32BE. Directly supported if the current system is big-endian.
         *
         * @since 22.1
         */
        UTF_32BE(littleEndian() ? 97 : 0, "UTF_32BE", littleEndian() ? 0 : 2, bigEndian()),
        /**
         * UTF-16LE. Directly supported if the current system is little-endian.
         *
         * @since 22.1
         */
        UTF_16LE(littleEndian() ? 1 : 98, "UTF_16LE", littleEndian() ? 1 : 0, false),
        /**
         * UTF-16BE. Directly supported if the current system is big-endian.
         *
         * @since 22.1
         */
        UTF_16BE(littleEndian() ? 98 : 1, "UTF_16BE", littleEndian() ? 0 : 1, false),
        /**
         * ISO-8859-1, also known as LATIN-1, which is equivalent to US-ASCII + the LATIN-1
         * Supplement Unicode block.
         *
         * @since 22.1
         */
        ISO_8859_1(2, "ISO_8859_1", 0, true),
        /**
         * UTF-8.
         *
         * @since 22.1
         */
        UTF_8(3, "UTF_8", 0, false),
        /**
         * US-ASCII, which maps only 7-bit characters.
         *
         * @since 22.1
         */
        US_ASCII(4, "US_ASCII", 0, true),
        /**
         * Special "encoding" BYTES: This encoding is identical to US-ASCII, but treats all values
         * outside the us-ascii range as valid codepoints as well. Caution: no codepoint mappings
         * are defined for non-us-ascii values in this encoding, so {@link SwitchEncodingNode} will
         * replace all of them with {@code '?'} when converting from or to BYTES! To preserve all
         * bytes and "reinterpret" a BYTES string in another encoding, use
         * {@link ForceEncodingNode}.
         *
         * @since 22.1
         */
        BYTES(5, "BYTES", 0, true),

        /* encodings supported by falling back to JCodings */

        /**
         * Big5.
         *
         * @since 22.1
         */
        Big5(6, "Big5"),
        /**
         * Big5-HKSCS.
         *
         * @since 22.1
         */
        Big5_HKSCS(7, "Big5_HKSCS"),
        /**
         * Big5-UAO.
         *
         * @since 22.1
         */
        Big5_UAO(8, "Big5_UAO"),
        /**
         * CP51932.
         *
         * @since 22.1
         */
        CP51932(9, "CP51932"),
        /**
         * CP850.
         *
         * @since 22.1
         */
        CP850(10, "CP850"),
        /**
         * CP852.
         *
         * @since 22.1
         */
        CP852(11, "CP852"),
        /**
         * CP855.
         *
         * @since 22.1
         */
        CP855(12, "CP855"),
        /**
         * CP949.
         *
         * @since 22.1
         */
        CP949(13, "CP949"),
        /**
         * CP950.
         *
         * @since 22.1
         */
        CP950(14, "CP950"),
        /**
         * CP951.
         *
         * @since 22.1
         */
        CP951(15, "CP951"),
        /**
         * EUC-JIS-2004.
         *
         * @since 22.1
         */
        EUC_JIS_2004(16, "EUC_JIS_2004"),
        /**
         * EUC-JP.
         *
         * @since 22.1
         */
        EUC_JP(17, "EUC_JP"),
        /**
         * EUC-KR.
         *
         * @since 22.1
         */
        EUC_KR(18, "EUC_KR"),
        /**
         * EUC-TW.
         *
         * @since 22.1
         */
        EUC_TW(19, "EUC_TW"),
        /**
         * Emacs-Mule.
         *
         * @since 22.1
         */
        Emacs_Mule(20, "Emacs_Mule"),
        /**
         * EucJP-ms.
         *
         * @since 22.1
         */
        EucJP_ms(21, "EucJP_ms"),
        /**
         * GB12345.
         *
         * @since 22.1
         */
        GB12345(22, "GB12345"),
        /**
         * GB18030.
         *
         * @since 22.1
         */
        GB18030(23, "GB18030"),
        /**
         * GB1988.
         *
         * @since 22.1
         */
        GB1988(24, "GB1988"),
        /**
         * GB2312.
         *
         * @since 22.1
         */
        GB2312(25, "GB2312"),
        /**
         * GBK.
         *
         * @since 22.1
         */
        GBK(26, "GBK"),
        /**
         * IBM437.
         *
         * @since 22.1
         */
        IBM437(27, "IBM437"),
        /**
         * IBM737.
         *
         * @since 22.1
         */
        IBM737(28, "IBM737"),
        /**
         * IBM775.
         *
         * @since 22.1
         */
        IBM775(29, "IBM775"),
        /**
         * IBM852.
         *
         * @since 22.1
         */
        IBM852(30, "IBM852"),
        /**
         * IBM855.
         *
         * @since 22.1
         */
        IBM855(31, "IBM855"),
        /**
         * IBM857.
         *
         * @since 22.1
         */
        IBM857(32, "IBM857"),
        /**
         * IBM860.
         *
         * @since 22.1
         */
        IBM860(33, "IBM860"),
        /**
         * IBM861.
         *
         * @since 22.1
         */
        IBM861(34, "IBM861"),
        /**
         * IBM862.
         *
         * @since 22.1
         */
        IBM862(35, "IBM862"),
        /**
         * IBM863.
         *
         * @since 22.1
         */
        IBM863(36, "IBM863"),
        /**
         * IBM864.
         *
         * @since 22.1
         */
        IBM864(37, "IBM864"),
        /**
         * IBM865.
         *
         * @since 22.1
         */
        IBM865(38, "IBM865"),
        /**
         * IBM866.
         *
         * @since 22.1
         */
        IBM866(39, "IBM866"),
        /**
         * IBM869.
         *
         * @since 22.1
         */
        IBM869(40, "IBM869"),
        /**
         * ISO-8859-10.
         *
         * @since 22.1
         */
        ISO_8859_10(41, "ISO_8859_10"),
        /**
         * ISO-8859-11.
         *
         * @since 22.1
         */
        ISO_8859_11(42, "ISO_8859_11"),
        /**
         * ISO-8859-13.
         *
         * @since 22.1
         */
        ISO_8859_13(43, "ISO_8859_13"),
        /**
         * ISO-8859-14.
         *
         * @since 22.1
         */
        ISO_8859_14(44, "ISO_8859_14"),
        /**
         * ISO-8859-15.
         *
         * @since 22.1
         */
        ISO_8859_15(45, "ISO_8859_15"),
        /**
         * ISO-8859-16.
         *
         * @since 22.1
         */
        ISO_8859_16(46, "ISO_8859_16"),
        /**
         * ISO-8859-2.
         *
         * @since 22.1
         */
        ISO_8859_2(47, "ISO_8859_2"),
        /**
         * ISO-8859-3.
         *
         * @since 22.1
         */
        ISO_8859_3(48, "ISO_8859_3"),
        /**
         * ISO-8859-4.
         *
         * @since 22.1
         */
        ISO_8859_4(49, "ISO_8859_4"),
        /**
         * ISO-8859-5.
         *
         * @since 22.1
         */
        ISO_8859_5(50, "ISO_8859_5"),
        /**
         * ISO-8859-6.
         *
         * @since 22.1
         */
        ISO_8859_6(51, "ISO_8859_6"),
        /**
         * ISO-8859-7.
         *
         * @since 22.1
         */
        ISO_8859_7(52, "ISO_8859_7"),
        /**
         * ISO-8859-8.
         *
         * @since 22.1
         */
        ISO_8859_8(53, "ISO_8859_8"),
        /**
         * ISO-8859-9.
         *
         * @since 22.1
         */
        ISO_8859_9(54, "ISO_8859_9"),
        /**
         * KOI8-R.
         *
         * @since 22.1
         */
        KOI8_R(55, "KOI8_R"),
        /**
         * KOI8-U.
         *
         * @since 22.1
         */
        KOI8_U(56, "KOI8_U"),
        /**
         * MacCentEuro.
         *
         * @since 22.1
         */
        MacCentEuro(57, "MacCentEuro"),
        /**
         * MacCroatian.
         *
         * @since 22.1
         */
        MacCroatian(58, "MacCroatian"),
        /**
         * MacCyrillic.
         *
         * @since 22.1
         */
        MacCyrillic(59, "MacCyrillic"),
        /**
         * MacGreek.
         *
         * @since 22.1
         */
        MacGreek(60, "MacGreek"),
        /**
         * MacIceland.
         *
         * @since 22.1
         */
        MacIceland(61, "MacIceland"),
        /**
         * MacJapanese.
         *
         * @since 22.1
         */
        MacJapanese(62, "MacJapanese"),
        /**
         * MacRoman.
         *
         * @since 22.1
         */
        MacRoman(63, "MacRoman"),
        /**
         * MacRomania.
         *
         * @since 22.1
         */
        MacRomania(64, "MacRomania"),
        /**
         * MacThai.
         *
         * @since 22.1
         */
        MacThai(65, "MacThai"),
        /**
         * MacTurkish.
         *
         * @since 22.1
         */
        MacTurkish(66, "MacTurkish"),
        /**
         * MacUkraine.
         *
         * @since 22.1
         */
        MacUkraine(67, "MacUkraine"),
        /**
         * SJIS-DoCoMo.
         *
         * @since 22.1
         */
        SJIS_DoCoMo(68, "SJIS_DoCoMo"),
        /**
         * SJIS-KDDI.
         *
         * @since 22.1
         */
        SJIS_KDDI(69, "SJIS_KDDI"),
        /**
         * SJIS-SoftBank.
         *
         * @since 22.1
         */
        SJIS_SoftBank(70, "SJIS_SoftBank"),
        /**
         * Shift-JIS.
         *
         * @since 22.1
         */
        Shift_JIS(71, "Shift_JIS"),
        /**
         * Stateless-ISO-2022-JP.
         *
         * @since 22.1
         */
        Stateless_ISO_2022_JP(72, "Stateless_ISO_2022_JP"),
        /**
         * Stateless-ISO-2022-JP-KDDI.
         *
         * @since 22.1
         */
        Stateless_ISO_2022_JP_KDDI(73, "Stateless_ISO_2022_JP_KDDI"),
        /**
         * TIS-620.
         *
         * @since 22.1
         */
        TIS_620(74, "TIS_620"),
        /**
         * UTF8-DoCoMo.
         *
         * @since 22.1
         */
        UTF8_DoCoMo(75, "UTF8_DoCoMo"),
        /**
         * UTF8-KDDI.
         *
         * @since 22.1
         */
        UTF8_KDDI(76, "UTF8_KDDI"),
        /**
         * UTF8-MAC.
         *
         * @since 22.1
         */
        UTF8_MAC(77, "UTF8_MAC"),
        /**
         * UTF8-SoftBank.
         *
         * @since 22.1
         */
        UTF8_SoftBank(78, "UTF8_SoftBank"),
        /**
         * Windows-1250.
         *
         * @since 22.1
         */
        Windows_1250(79, "Windows_1250"),
        /**
         * Windows-1251.
         *
         * @since 22.1
         */
        Windows_1251(80, "Windows_1251"),
        /**
         * Windows-1252.
         *
         * @since 22.1
         */
        Windows_1252(81, "Windows_1252"),
        /**
         * Windows-1253.
         *
         * @since 22.1
         */
        Windows_1253(82, "Windows_1253"),
        /**
         * Windows-1254.
         *
         * @since 22.1
         */
        Windows_1254(83, "Windows_1254"),
        /**
         * Windows-1255.
         *
         * @since 22.1
         */
        Windows_1255(84, "Windows_1255"),
        /**
         * Windows-1256.
         *
         * @since 22.1
         */
        Windows_1256(85, "Windows_1256"),
        /**
         * Windows-1257.
         *
         * @since 22.1
         */
        Windows_1257(86, "Windows_1257"),
        /**
         * Windows-1258.
         *
         * @since 22.1
         */