/*
 * Copyright (c) 2020, 2020, Oracle and/or its affiliates. All rights reserved.
 * Copyright (c) 2020, 2020, Red Hat Inc. All rights reserved.
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

package com.oracle.objectfile.elf.dwarf;

import java.nio.ByteOrder;

import com.oracle.objectfile.debugentry.ClassEntry;
import com.oracle.objectfile.debugentry.DebugInfoBase;

import com.oracle.objectfile.debugentry.MethodEntry;
import com.oracle.objectfile.debugentry.range.Range;
import com.oracle.objectfile.debugentry.StructureTypeEntry;
import com.oracle.objectfile.debugentry.TypeEntry;
import com.oracle.objectfile.debuginfo.DebugInfoProvider.DebugLocalInfo;
import com.oracle.objectfile.elf.ELFMachine;
import org.graalvm.collections.EconomicMap;

/**
 * A class that models the debug info in an organization that facilitates generation of the required
 * DWARF sections. It groups common data and behaviours for use by the various subclasses of class
 * DwarfSectionImpl that take responsibility for generating content for a specific section type.
 */
public class DwarfDebugInfo extends DebugInfoBase {

    /*
     * Names of the different ELF sections we create or reference in reverse dependency order.
     */
    public static final String TEXT_SECTION_NAME = ".text";
    public static final String HEAP_BEGIN_NAME = "__svm_heap_begin";
    public static final String DW_STR_SECTION_NAME = ".debug_str";
    public static final String DW_LINE_SECTION_NAME = ".debug_line";
    public static final String DW_FRAME_SECTION_NAME = ".debug_frame";
    public static final String DW_ABBREV_SECTION_NAME = ".debug_abbrev";
    public static final String DW_INFO_SECTION_NAME = ".debug_info";
    public static final String DW_LOC_SECTION_NAME = ".debug_loc";
    public static final String DW_ARANGES_SECTION_NAME = ".debug_aranges";

    /**
     * Currently generated debug info relies on DWARF spec version 4.
     */
    public static final short DW_VERSION_2 = 2;
    public static final short DW_VERSION_4 = 4;

    /*
     * Define all the abbrev section codes we need for our DIEs.
     */
    @SuppressWarnings("unused") public static final int DW_ABBREV_CODE_null = 0;
    /* Level 0 DIEs. */
    public static final int DW_ABBREV_CODE_builtin_unit = 1;
    public static final int DW_ABBREV_CODE_class_unit1 = 2;
    public static final int DW_ABBREV_CODE_class_unit2 = 3;
    public static final int DW_ABBREV_CODE_array_unit = 4;
    /* Level 1 DIEs. */
    public static final int DW_ABBREV_CODE_primitive_type = 5;
    public static final int DW_ABBREV_CODE_void_type = 6;
    public static final int DW_ABBREV_CODE_object_header = 7;
    public static final int DW_ABBREV_CODE_namespace = 8;
    public static final int DW_ABBREV_CODE_class_layout1 = 9;
    public static final int DW_ABBREV_CODE_class_layout2 = 10;
    public static final int DW_ABBREV_CODE_class_pointer = 11;
    public static final int DW_ABBREV_CODE_method_location = 12;
    public static final int DW_ABBREV_CODE_abstract_inline_method = 13;
    public static final int DW_ABBREV_CODE_static_field_location = 14;
    public static final int DW_ABBREV_CODE_array_layout = 15;
    public static final int DW_ABBREV_CODE_array_pointer = 16;
    public static final int DW_ABBREV_CODE_interface_layout = 17;
    public static final int DW_ABBREV_CODE_interface_pointer = 18;
    public static final int DW_ABBREV_CODE_indirect_layout = 19;
    public static final int DW_ABBREV_CODE_indirect_pointer = 20;
    /* Level 2 DIEs. */
    public static final int DW_ABBREV_CODE_method_declaration = 21;
    public static final int DW_ABBREV_CODE_method_declaration_static = 22;
    public static final int DW_ABBREV_CODE_field_declaration1 = 23;
    public static final int DW_ABBREV_CODE_field_declaration2 = 24;
    public static final int DW_ABBREV_CODE_field_declaration3 = 25;
    public static final int DW_ABBREV_CODE_field_declaration4 = 26;
    public static final int DW_ABBREV_CODE_class_constant = 42;
    public static final int DW_ABBREV_CODE_header_field = 27;
    public static final int DW_ABBREV_CODE_array_data_type = 28;
    public static final int DW_ABBREV_CODE_super_reference = 29;
    public static final int DW_ABBREV_CODE_interface_implementor = 30;
    /* Level 2+K DIEs (where inline depth K >= 0) */
    public static final int DW_ABBREV_CODE_inlined_subroutine = 31;
    public static final int DW_ABBREV_CODE_inlined_subroutine_with_children = 32;
    /* Level 2 DIEs. */
    public static final int DW_ABBREV_CODE_method_parameter_declaration1 = 33;
    public static final int DW_ABBREV_CODE_method_parameter_declaration2 = 34;
    public static final int DW_ABBREV_CODE_method_parameter_declaration3 = 35;
    public static final int DW_ABBREV_CODE_method_local_declaration1 = 36;
    public static final int DW_ABBREV_CODE_method_local_declaration2 = 37;
    /* Level 3 DIEs. */
    public static final int DW_ABBREV_CODE_method_parameter_location1 = 38;
    public static final int DW_ABBREV_CODE_method_parameter_location2 = 39;
    public static final int DW_ABBREV_CODE_method_local_location1 = 40;
    public static final int DW_ABBREV_CODE_method_local_location2 = 41;

    /*
     * Define all the Dwarf tags we need for our DIEs.
     */
    public static final int DW_TAG_array_type = 0x01;
    public static final int DW_TAG_class_type = 0x02;
    public static final int DW_TAG_formal_parameter = 0x05;
    public static final int DW_TAG_member = 0x0d;
    public static final int DW_TAG_pointer_type = 0x0f;
    public static final int DW_TAG_compile_unit = 0x11;
    public static final int DW_TAG_structure_type = 0x13;
    public static final int DW_TAG_union_type = 0x17;
    public static final int DW_TAG_inheritance = 0x1c;
    public static final int DW_TAG_base_type = 0x24;
    public static final int DW_TAG_constant = 0x27;
    public static final int DW_TAG_subprogram = 0x2e;
    public static final int DW_TAG_variable = 0x34;
    public static final int DW_TAG_namespace = 0x39;
    public static final int DW_TAG_unspecified_type = 0x3b;
    public static final int DW_TAG_inlined_subroutine = 0x1d;

    /*
     * Define all the Dwarf attributes we need for our DIEs.
     */
    public static final int DW_AT_null = 0x0;
    public static final int DW_AT_location = 0x02;
    public static final int DW_AT_name = 0x3;
    public static final int DW_AT_byte_size = 0x0b;
    public static final int DW_AT_bit_size = 0x0d;
    public static final int DW_AT_stmt_list = 0x10;
    public static final int DW_AT_low_pc = 0x11;
    public static final int DW_AT_hi_pc = 0x12;
    public static final int DW_AT_language = 0x13;
    public static final int DW_AT_comp_dir = 0x1b;
    public static final int DW_AT_containing_type = 0x1d;
    public static final int DW_AT_inline = 0x20;
    public static final int DW_AT_abstract_origin = 0x31;
    public static final int DW_AT_accessibility = 0x32;
    public static final int DW_AT_artificial = 0x34;
    public static final int DW_AT_data_member_location = 0x38;
    @SuppressWarnings("unused") public static final int DW_AT_decl_column = 0x39;
    public static final int DW_AT_decl_file = 0x3a;
    @SuppressWarnings("unused") public static final int DW_AT_decl_line = 0x3b;
    public static final int DW_AT_declaration = 0x3c;
    public static final int DW_AT_encoding = 0x3e;
    public static final int DW_AT_external = 0x3f;
    @SuppressWarnings("unused") public static final int DW_AT_return_addr = 0x2a;
    @SuppressWarnings("unused") public static final int DW_AT_frame_base = 0x40;
    public static final int DW_AT_specification = 0x47;
    public static final int DW_AT_type = 0x49;
    public static final int DW_AT_data_location = 0x50;
    public static final int DW_AT_use_UTF8 = 0x53;
    public static final int DW_AT_call_file = 0x58;
    public static final int DW_AT_call_line = 0x59;
    public static final int DW_AT_object_pointer = 0x64;
    public static final int DW_AT_linkage_name = 0x6e;

    /*
     * Define all the Dwarf attribute forms we need for our DIEs.
     */
    public static final int DW_FORM_null = 0x0;
    public static final int DW_FORM_addr = 0x1;
    public static final int DW_FORM_data2 = 0x05;
    public static final int DW_FORM_data4 = 0x6;
    @SuppressWarnings("unused") public static final int DW_FORM_data8 = 0x7;
    @SuppressWarnings("unuse