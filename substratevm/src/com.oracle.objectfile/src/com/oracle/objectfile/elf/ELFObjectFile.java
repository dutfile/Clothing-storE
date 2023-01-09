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
        return symtab.newDefinedEntry(name, (Section) baseSection, position, size, isGlobal, isCode);
    }

    @Override
    public Symbol createUndefinedSymbol(String name, int size, boolean isCode) {
        ELFSymtab symtab = createSymbolTable();
        return symtab.newUndefinedEntry(name, isCode);
    }

    @Override
    protected Segment getOrCreateSegment(String maybeSegmentName, String sectionName, boolean writable, boolean executable) {
        return null;
    }

    @Override
    public ELFUserDefinedSection newUserDefinedSection(Segment segment, String name, int alignment, ElementImpl impl) {
        ELFUserDefinedSection userDefined = new ELFUserDefinedSection(this, name, alignment, SectionType.PROGBITS, impl);
        assert userDefined.getImpl() == impl;
        if (segment != null) {
            getOrCreateSegment(segment.getName(), name, true, false).add(userDefined);
        }
        if (impl != null) {
            impl.setElement(userDefined);
        }
        return userDefined;
    }

    @Override
    public ELFProgbitsSection newProgbitsSection(Segment segment, String name, int alignment, boolean writable, boolean executable, ProgbitsSectionImpl impl) {
        EnumSet<ELFSectionFlag> flags = EnumSet.noneOf(ELFSectionFlag.class);
        flags.add(ELFSectionFlag.ALLOC);
        if (executable) {
            flags.add(ELFSectionFlag.EXECINSTR);
        }
        if (writable) {
            flags.add(ELFSectionFlag.WRITE);
        }
        ELFProgbitsSection progbits = new ELFProgbitsSection(this, name, alignment, impl, flags);
        impl.setElement(progbits);
        return progbits;
    }

    @Override
    public ELFNobitsSection newNobitsSection(Segment segment, String name, NobitsSectionImpl impl) {
        ELFNobitsSection nobits = new ELFNobitsSection(this, name, impl);
        impl.setElement(nobits);
        return nobits;
    }

    public ELFSection getSectionByIndex(int i) {
        // if this cast fails, our sectionIndexToElementIndex logic is wrong
        return (ELFSection) elements.get(elements.sectionIndexToElementIndex(i - 1));
        // NOTE: two levels of translation here: ELF (1-based) shndx to section index (0-based) to
        // element index
    }

    public int getIndexForSection(ELFSection s) {
        return elements.elementIndexToSectionIndex(elements.indexOf(s)) + 1;
    }

    @Override
    protected boolean elementsCanSharePage(Element s1, Element s2, int off1, int off2) {
        assert s1 instanceof ELFSection;
        assert s2 instanceof ELFSection;
        ELFSection es1 = (ELFSection) s1;
        ELFSection es2 = (ELFSection) s2;

        boolean flagsCompatible = ELFSectionFlag.flagSetAsIfSegmentFlags(es1.getFlags()).equals(ELFSectionFlag.flagSetAsIfSegmentFlags(es2.getFlags()));

        return flagsCompatible && super.elementsCanSharePage(es1, es2, off1, off2);
    }

    public abstract class ELFSection extends ObjectFile.Section {

        final SectionType type;

        EnumSet<ELFSectionFlag> flags;

        public ELFSection(String name, SectionType type) {
            this(name, type, EnumSet.noneOf(ELFSectionFlag.class));
        }

        public ELFSection(String name, SectionType type, EnumSet<ELFSectionFlag> flags) {
            this(name, getWordSizeInBytes(), type, flags, -1);
        }

        /**
         * Constructs an ELF section of given name, type, flags and section index.
         *
         * @param name the section name
         * @param type the section type
         * @param flags the section's flags
         * @param sectionIndex the desired index in the ELF section header table
         */
        public ELFSection(String name, int alignment, SectionType type, EnumSet<ELFSectionFlag> flags, int sectionIndex) {
            // ELF sections are aligned at least to a word boundary.
            super(name, alignment, (sectionIndex == -1) ? -1 : elements.sectionIndexToElementIndex(sectionIndex - 1));
            this.type = type;
            this.flags = flags;
        }

        @Override
        public ELFObjectFile getOwner() {
            return ELFObjectFile.this;
        }

        public SectionType getType() {
            return type;
        }

        @Override
        public boolean isLoadable() {
            /*
             * NOTE the following distinction: whether a section is loadable is a property of the
             * section (abstractly). (This is also why we we delegate to the impl.)
             *
             * Whether an ELF section is explicitly loaded is a property of the PHT contents. The
             * code in ObjectFile WILL assign vaddrs for all loadable sections! So
             * isExplicitlyLoaded is actually irrelevant.
             */

            // if we are our own impl, just go with what the flags say
            if (getImpl() == this) {
                return flags.contains(ELFSectionFlag.ALLOC);
            }

            // otherwise, the impl and flags should agree
            boolean implIsLoadable = getImpl().isLoadable();
            // our constructors and impl-setter are responsible for syncing flags with impl
            assert implIsLoadable == flags.contains(ELFSectionFlag.ALLOC);

            return implIsLoadable;
        }

        @Override
        public boolean isReferenceable() {
            if (getImpl() == this) {
                return isLoadable();
            }

            return getImpl().isReferenceable();
        }

        /*
         * NOTE that ELF has sh_link and sh_info for recording section links, but since these are
         * specific to particular section types, we leave their representation to subclasses (i.e.
         * an ELFSymtab has a reference to its strtab, etc.). We just define no-op getters which
         * selected subclasses will overrode
         */

        public ELFSection getLinkedSection() {
            return null;
        }

        public long getLinkedInfo() {
            return 0;
        }

        public int getEntrySize() {
            return 0; // means "does not hold a table of fixed-size entries"
        }

        public EnumSet<ELFSectionFlag> getFlags() {
            return flags;
        }

        public void setFlags(EnumSet<ELFSectionFlag> flags) {
            this.flags = flags;
        }
    }

    /**
     * ELF file type.
     */
    public enum ELFType {
        NONE,
        REL,
        EXEC,
        DYN,
        CORE,
        LOOS,
        HIOS,
        LOPROC,
        HIPROC;

        public short toShort() {
            if (ordinal() < 5) {
                return (short) ordinal();
            } else {
                // TODO: use explicit enum values
                switch (this) {
                    case LOOS:
                        return (short) 0xFE00;
                    case HIOS:
                        return (short) 0xFEFF;
                    case LOPROC:
                        return (short) 0xFF00;
                    case HIPROC:
                        return (short) 0xFFFF;
                }
            }
            throw new IllegalStateException("should not reach here");
        }
    }

    /**
     * Encoding: little endian or big endian.
     */
    public enum ELFEncoding {
        ELFDATA2LSB(1),
        ELFDATA2MSB(2);

        private final int value;

        ELFEncoding(int value) {
            this.value = value;
        }

        public byte value() {
            return (byte) value;
        }

        public ByteOrder toByteOrder() {
            return (this == ELFEncoding.ELFDATA2LSB) ? ByteOrder.LITTLE_ENDIAN : (this == ELFEncoding.ELFDATA2MSB) ? ByteOrder.BIG_ENDIAN : ByteOrder.nativeOrder();
        }

        public static ELFEncoding getSystemNativeValue() {
            return ELFDATA2LSB; // FIXME: query
        }
    }

    /**
     * ABI encoding.
     */
    public enum ELFOsAbi {
        ELFOSABI_SYSV(0),
        ELFOSABI_HPUX(1),
        ELFOSABI_STANDALONE(255);

        private final int value;

        ELFOsAbi(int value) {
            this.value = value;
        }

        public byte value() {
            return (byte) value;
        }

        public static ELFOsAbi getSystemNativeValue() {
            return ELFOSABI_SYSV; // FIXME: query system
        }
    }

    /**
     * File class: 32 or 64 bit.
     */
    public enum ELFClass {
        ELFCLASS32(1),
        ELFCLASS64(2);

        private final int value;

        ELFClass(int value) {
            this.value = value;
        }

        public byte value() {
            return (byte) value;
        }

        public static ELFClass getSystemNativeValue() {
            return ELFCLASS64; // FIXME: query system
        }
    }

    /**
     * Representation of an ELF binary header. ELF stores the section count in the header itself.
     */
    public class ELFHeader extends ObjectFile.Header {

        /*
         * We no longer store every constituent field. Rather, some are modeled by the containing
         * ELFObjectFile's contents.
         */
        class Struct {

            final IdentStruct ident;
            final ELFType type;
            final ELFMachine machine;
            int version;
            long entry;
            long phoff;
            long shoff;
            int flags;
            short ehsize;
            short phentsize;
            short phnum;
            short shentsize;
            short shnum;
            short shstrndx;

            Struct(ELFType type, ELFMachine machine) {
                ident = new IdentStruct();
                this.type = type;
                this.machine = machine;
            }

            /**
             * The very first 16 bytes of an {@link ELFHeader ELF header} are the so-called ident.
             * The ident encodes various low-level file characteristics. It is a str
             */
            class IdentStruct {

                public char[] magic = new char[4];
                public ELFClass fileClass = ELFClass.getSystemNativeValue(); // default
                public ELFEncoding dataEncoding = ELFEncoding.getSystemNativeValue(); // default
                public char version;
                public ELFOsAbi osabi = ELFOsAbi.getSystemNativeValue();
                public char abiVersion;

                IdentStruct(char[] magic, ELFClass fileClass, ELFEncoding dataEncoding, char version, ELFOsAbi osabi, char abiVersion) {
                    this.magic = magic;
                    this.fileClass = fileClass;
                    this.dataEncoding = dataEncoding;
                    this.version = version;
                    this.osabi = osabi;
                    this.abiVersion = abiVersion;
                }

                IdentStruct() {
                    this.magic = Arrays.copyOf(IDENT_MAGIC, IDENT_MAGIC.length);
                    assert Arrays.equals(IDENT_MAGIC, magic);
                }

                void write(OutputAssembler out) {
                    int pos = out.pos();

                    byte[] magicBlob = new byte[IDENT_MAGIC.length];
                    for (int i = 0; i < IDENT_MAGIC.length; ++i) {
                        magicBlob[i] = (byte) magic[i];
                    }
                    out.writeBlob(magicBlob);

                    out.writeByte(fileClass.value());
                    out.writeByte(dataEncoding.value());
                    out.writeByte((byte) version);
                    out.writeByte(osabi.value());
                    out.writeByte((byte) abiVersion);

                    int nWritten = out.pos() - pos;
                    for (int i = 0; i < IDENT_LENGTH - nWritten; ++i) {
                        out.writeByte((byte) 0);
                    }
                }

                @Override
                public String toString() {
                    return String.format("ELF Ident:%n\t[class %s, encoding %s, version %d, OS/ABI %s, ABI version %d]", fileClass, dataEncoding, (int) version, osabi, (int) abiVersion);
                }
            }

            public void write(OutputAssembler out) {
                ident.write(out);
                // FIXME: the following is specific to 64-bit ELF files
                out.write2Byte(type.toShort());
                out.write2Byte(machine.toShort());
                out.write4Byte(version);
                switch (getFileClass()) {
                    case ELFCLASS32:
                        out.write4Byte(toIntExact(entry));
                        out.write4Byte(toIntExact(phoff));
                        out.write4Byte(toIntExact(shoff));
                        break;
                    case ELFCLASS64:
                        out.write8Byte(entry);
                        out.write8Byte(phoff);
                        out.write8Byte(shoff);
                        break;
                    default:
                        throw new RuntimeException(getFileClass().toString());
                }
                out.write4Byte(flags);
                out.write2Byte(ehsize);
                out.write2Byte(phentsize);
                out.write2Byte(phnum);
                out.write2Byte(shentsize);
                out.write2Byte(shnum);
                out.write2Byte(shstrndx);
            }

            public int getWrittenSize() {
                // we just write ourselves to a dummy buffer and count
                OutputAssembler oa = AssemblyBuffer.createOutputAssembler();
                write(oa);
                return oa.pos();
            }
        }

        public ELFHeader(String n