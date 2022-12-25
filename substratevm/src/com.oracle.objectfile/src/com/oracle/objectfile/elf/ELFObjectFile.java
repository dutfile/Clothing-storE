/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.objectfile.elf;

import static java.lang.Math.toIntExact;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.oracle.objectfile.elf.dwarf.DwarfLocSectionImpl;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;

import com.oracle.objectfile.BuildDependency;
import com.oracle.objectfile.ElementImpl;
import com.oracle.objectfile.LayoutDecision;
import com.oracle.objectfile.LayoutDecisionMap;
import com.oracle.objectfile.ObjectFile;
import com.oracle.objectfile.StringTable;
import com.oracle.objectfile.SymbolTable;
import com.oracle.objectfile.debuginfo.DebugInfoProvider;
import com.oracle.objectfile.elf.dwarf.DwarfARangesSectionImpl;
import com.oracle.objectfile.elf.dwarf.DwarfAbbrevSectionImpl;
import com.oracle.objectfile.elf.dwarf.DwarfDebugInfo;
import com.oracle.objectfile.elf.dwarf.DwarfFrameSectionImpl;
import com.oracle.objectfile.elf.dwarf.DwarfInfoSectionImpl;
import com.oracle.objectfile.elf.dwarf.DwarfLineSectionImpl;
import com.oracle.objectfile.elf.dwarf.DwarfStrSectionImpl;
import com.oracle.objectfile.io.AssemblyBuffer;
import com.oracle.objectfile.io.OutputAssembler;

/**
 * Represents an ELF object file (of any kind: relocatable, shared library, executable, core, ...).
 *
 * The main job of this class is to maintain the essential structure of an ELF file, meaning its
 * header, section header table, shstrtab, and (if present) program header table. Note that header
 * tables are neither headers nor sections, but since they have dependencies, they are Elements.
 */
public class ELFObjectFile extends ObjectFile {

    public static final int IDENT_LENGTH = 16;
    public static final char[] IDENT_MAGIC = new char[]{0x7f, 'E', 'L', 'F'};

    @SuppressWarnings("unused") private final ELFHeader header;
    private final ELFStrtab shstrtab;

    private final SectionHeaderTable sht;
    protected ELFSection interp;

    private ELFEncoding dataEncoding = ELFEncoding.getSystemNativeValue();
    private char version;
    private ELFOsAbi osabi = ELFOsAbi.getSystemNativeValue();
    private char abiVersion;
    private ELFClass fileClass = ELFClass.getSystemNativeValue();
    private ELFMachine machine;
    private long processorFlags; // FIXME: to encapsulate (EF_* in elf.h)
    private final boolean runtimeDebugInfoGeneration;

    private ELFObjectFile(int pageSize, ELFMachine machine, boolean runtimeDebugInfoGeneration) {
        super(pageSize);
        this.runtimeDebugInfoGeneration = runtimeDebugInfoGeneration;
        // Create the elements of an empty ELF file:
        // 1. create header
        header = new ELFHeader("ELFHeader", machine.flags());
        this.machine = machine;
        // 2. create shstrtab
        shstrtab = new SectionHeaderStrtab();
        // 3. create section header table
        sht = new SectionHeaderTable(/* shstrtab */);
    }

    public ELFObjectFile(int pageSize, ELFMachine machine) {
        this(pageSize, machine, false);
    }

    public ELFObjectFile(int pageSize) {
        this(pageSize, false);
    }

    public ELFObjectFile(int pageSize, boolean runtimeDebugInfoGeneration) {
        this(pageSize, ELFMachine.from(ImageSingletons.lookup(Platform.class).getArchitecture()), runtimeDebugInfoGeneration);
    }

    @Override
    public Format getFormat() {
        return Format.ELF;
    }

    public void setFileClass(ELFClass fileClass) {
        this.fileClass = fileClass;
    }

    /**
     * This class implements the shstrtab section. It's simply a {@link ELFStrtab} whose content is
     * grabbed from the set of section names.
     */
    protected class SectionHeaderStrtab extends ELFStrtab {

        SectionHeaderStrtab() {
            super(ELFObjectFile.this, ".shstrtab", SectionType.STRTAB);
        }

        @Override
        public boolean isLoadable() {
            // although we have a loadable impl, we're not actually loadable
            return false;
        }

        {
            addContentProvider(new Iterable<String>() {

                @Override
                public Iterator<String> iterator() {
                    final Iterator<Section> underlyingIterator = elements.sectionsIterator();
                    return new Iterator<>() {

                        @Override
                        public boolean hasNext() {
                            return underlyingIterator.hasNext();
                        }

                        @Override
                        public String next() {
                            return underlyingIterator.next().getName();
                        }

                        @Override
                        public void remove() {
                            throw new UnsupportedOperationException();
                        }
                    };
                }
            });
        }
    }

    @SuppressWarnings("unused")
    private ELFSymtab getSymtab(boolean isDynamic) {
        ELFSymtab symtab = (ELFSymtab) (isDynamic ? elementForName(".dynsym") : elementForName(".symtab"));
        if (symtab == null) {
            throw new IllegalStateException("no appropriate symtab");
        }
        return symtab;
    }

    @Override
    protected ELFSymtab createSymbolTable() {
        String name = ".symtab";
        ELFSymtab symtab = (ELFSymtab) elementForName(".symtab");
        if (symtab == null) {
            symtab = new ELFSymtab(this, name, false);
        }
        return symtab;
    }

    @Override
    public Symbol createDefinedSymbol(String name, Element baseSection, long position, int size, boolean isCode, boolean isGlobal) {
        ELFSymtab symtab = createSymbolTable();
        return symtab.newDefinedEntry(name, (Section) baseSection, position, size, isGloba