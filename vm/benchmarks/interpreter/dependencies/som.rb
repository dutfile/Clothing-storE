# Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.  Oracle designates this
# particular file as subject to the "Classpath" exception as provided
# by Oracle in the LICENSE file that accompanied this code.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.


# This benchmark is is derived from Stefan Marr's Are-We-Fast-Yet benchmark
# suite available at https://github.com/smarr/are-we-fast-yet
INITIAL_SIZE = 10
INITIAL_CAPACITY = 16

class Pair
  attr_accessor :key, :value

  def initialize(key, value)
    @key   = key
    @value = value
  end
end

class Vector
  def self.with(elem)
    new_vector = new(1)
    new_vector.append(elem)
    new_vector
  end

  def initialize(size = 50)
    @storage   = Array.new(size)
    @first_idx = 0
    @last_idx  = 0
  end

  def at(idx)
    return nil if idx >= @storage.length

    @storage[idx]
  end

  def at_put(idx, val)
    if idx >= @storage.length
      new_length = @storage.length
      new_length *= 2 while new_length <= idx

      new_storage = Array.new(new_length)
      @storage.each_index do |i|
        new_storage[i] = @storage[i]
      end
      @storage = new_storage
    end
    @storage[idx] = val

    @last_idx = idx + 1 if @last_idx < idx + 1
  end

  def append(elem)
    if @last_idx >= @storage.size
      # Need to expand capacity first
      new_storage = Array.new(2 * @storage.size)
      @storage.each_index do |i|
        new_storage[i] = @storage[i]
      end
      @storage = new_storage
    end

    @storage[@last_idx] = elem
    @last_idx += 1
    self
  end

  def empty?
    @last_idx == @first_idx
  end

  def each
    (@first_idx..(@last_idx - 1)).each do |i|
      yield @storage[i]
    end
  end

  def has_some
    (@first_idx..(@last_idx - 1)).each do |i|
      return true if yield @storage[i]
    end
    false
  end

  