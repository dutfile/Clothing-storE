/*
 * Copyright (c) 2009, 2022, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.code;

import static org.graalvm.compiler.code.CompilationResult.JumpTable.EntryFormat.OFFSET_ONLY;
import static org.graalvm.compiler.code.CompilationResult.JumpTable.EntryFormat.VALUE_AND_OFFSET;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.graalvm.compiler.code.CompilationResult.CodeAnnotation;
import org.graalvm.compiler.code.CompilationResult.CodeComment;
import org.graalvm.compiler.code.CompilationResult.JumpTable;
import org.graalvm.compiler.code.CompilationResult.JumpTable.EntryFormat;

/**
 * A HexCodeFile is a textual format for representing a chunk of machine code along with extra
 * information that can be used to enhance a disassembly of the code.
 *
 * A pseudo grammar for a HexCodeFile is given below.
 *
 * <pre>
 *     HexCodeFile ::= Platform Delim HexCode Delim (OptionalSection Delim)*
 *
 *     OptionalSection ::= Comment | OperandComment | JumpTable | LookupTable
 *
 *     Platform ::= "Platform" ISA WordWidth
 *
 *     HexCode ::= "HexCode" StartAddress HexDigits
 *
 *     Comment ::= "Comment" Position String
 *
 *     OperandComment ::= "OperandComment" Position String
 *
 *     EntryFormat ::= 4 | 8 | "OFFSET" | "KEY2_OFFSET"
 *
 *     JumpTable ::= "JumpTable" Position EntryFormat Low High
 *
 *     LookupTable ::= "LookupTable" Position NPairs KeySize OffsetSize
 *
 *     Position, EntrySize, Low, High, NPairs KeySize OffsetSize ::= int
 *
 *     Delim := "&lt;||@"
 * </pre>
 *
 * There must be exactly one HexCode and Platform part in a HexCodeFile. The length of HexDigits
 * must be even as each pair of digits represents a single byte.
 * <p>
 * Below is an example of a valid Code input:
 *
 * <pre>
 *
 *  Platform AMD64 64  &lt;||@
 *  HexCode 0 e8000000009090904883ec084889842410d0ffff48893c24e800000000488b3c24488bf0e8000000004883c408c3  &lt;||@
 *  Comment 24 frame-ref-map: +0 {0}
 *  at java.lang.String.toLowerCase(String.java:2496) [bci: 1]
 *              |0
 *     locals:  |stack:0:a
 *     stack:   |stack:0:a
 *    &lt;||@
 *  OperandComment 24 {java.util.Locale.getDefault()}  &lt;||@
 *  Comment 36 frame-ref-map: +0 {0}
 *  at java.lang.String.toLowerCase(String.java:2496) [bci: 4]
 *              |0
 *     locals:  |stack:0:a
 *    &lt;||@
 *  OperandComment 36 {java.lang.String.toLowerCase(Locale)}  lt;||@
 *
 * </pre>
 */
public class HexCodeFile {

    public static final String NEW_LINE = System.lineSeparator();
    public static final String SECTION_DELIM = " <||@";
    public static final String COLUMN_END = " <|@";
    public static final Pattern SECTION = Pattern.compile("(\\S+)\\s+(.*)", Pattern.DOTALL);
    public static final Pattern COMMENT = Pattern.compile("(\\d+)\\s+(.*)", Pattern.DOTALL);
    public static final Pattern OPERAND_COMMENT = COMMENT;
    public static final Pattern JUMP_TABLE = Pattern.compile("(\\d+)\\s+(\\S+)\\s+(-{0,1}\\d+)\\s+(-{0,1}\\d+)\\s*");
    public static final Pattern LOOKUP_TABLE = Pattern.compile("(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*");
    public static final Pattern HEX_CODE = Pattern.compile("(\\p{XDigit}+)(?:\\s+(\\p{XDigit}*))?");
    public static final Pattern PLATFORM = Pattern.compile("(\\S+)\\s+(\\S+)", Pattern.DOTALL);

    /**
     * Delimiter placed before a HexCodeFile when embedded in a string/stream.
     */
    public static final String EMBEDDED_HCF_OPEN = "<<<HexCodeFile";

    /**
     * Delimiter placed after a HexCodeFile when embedded in a string/stream.
     */
    public static final String EMBEDDED_HCF_CLOSE = "HexCodeFile>>>";

    /**
     * Map from a machine code position to a list of comments for the position.
     */
    public final Map<Integer, List<String>> comments = new TreeMap<>();

    /**
     * Map from a machine code position to a comment for the operands of the instruction at the
     * position.
     */
    public final Map<Integer, List<String>> operandComments = new TreeMap<>();

    public final byte[] code;

    public final ArrayList<JumpTable> jumpTables = new ArrayList<>();

    public final String isa;

    public final int wordWidth;

    public final long startAddress;

    public HexCodeFile(byte[] code, long startAddress, String isa, int wordWidth) {
        this.code = code;
        this.startAddress = startAddress;
        this.isa = isa;
        this.wordWidth = wordWidth;
    }

    /**
     * Parses a string in the format produced by {@link #toString()} to produce a
     * {@link HexCodeFile} object.
     */
    public static HexCodeFile parse(String input, int sourceOffset, String source, String sourceName) {
        return new Parser(input, sourceOffset, source, sourceName).hcf;
    }

    /**
     * Formats this HexCodeFile as a string that can be parsed with
     * {@link #parse(String, int, String, String)}.
     */
    @Override
    public String toString() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeTo(baos);
        return baos.toString();
    }

    public String toEmbeddedString() {
        return EMBEDDED_HCF_OPEN + NEW_LINE + toString() + EMBEDDED_HCF_CLOSE;
    }

    public void writeTo(OutputStream out) {
        PrintStream ps = out instanceof PrintStream ? (PrintStream) out : new