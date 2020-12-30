/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
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
#include <math.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include "harness.h"

#define MAX_EXPR_NODES (10000)
#define MAX_FUNCTION_NAME_LENGTH (10)
#define ENV_SIZE ('z' - 'a' + 1)
#define REGISTER_COUNT (10)

#define ID_CONSTANT (0)
#define ID_ADD (1)
#define ID_SUB (2)
#define ID_MUL (3)
#define ID_DIV (4)
#define ID_REM (5)
#define ID_SQRT (6)
#define ID_LOAD (7)
#define ID_STORE (8)
#define ID_IDENT (9)

#define PROGRAM_COUNT (19)
#define INPUT_COUNT (50000)

int8_t multiplicative_ops[3] = { ID_MUL, ID_DIV, ID_REM };
int8_t additive_ops[2] = { ID_ADD, ID_SUB };

const char* const functions[PROGRAM_COUNT] = {
  "1 / 1000 * x * x % 1143 + 4",
  "1 / 1000 * x * x % 1143 + 4 * x / 123 + 17",
  "x * x % 23 * 3",
  "19999 / 10000 * x * x + 5 * x / 51 + 93",
  "0 - 2 * x * x - 2 * x / 23 + 47",
  "x * x % 23 + 114 * x % 19",
  "x * x * x % 37 + x / 53",
  "x * x % 23 + @sqrt(x)",
  "x * x % 127 - 14 * x - x % 17",
  "x * x / @sqrt(x * x + 2 * x + 3) / @sqrt(3 * x * x + 1)",
  "1241051 * x % 11",
  "@sqrt(x) % 14 * 2",
  "@sqrt(x * x % 143)",
  "@sqrt(x * x % 19 - 2 * x % 113 + 371)",
  "x * x * x * @sqrt(x) % 139",
  "x * @sqrt(x) + x / @sqrt(x * x + 1)",
  "0 * @store(1, x * x) + 1 / @sqrt(@load(1) + 1) + @load(1) / 4 / @sqrt(@load(1) + 1)",
  "0 * @store(1, x * x) + 0 * @store(2, 1 + @load(1)) + 1 / @load(2) - @load(1) / @load(2)",
  "@store(5, x - 1) / @sqrt(1 + @load(5) * @load(5))",
};

double inputs[INPUT_COUNT];

/* Expression trees. */

typedef struct _expr expr;

typedef struct {
  double inputs[ENV_SIZE];
  double registers[REGISTER_COUNT];
} environment;

void environment_init(environment* env) {
  for (int32_t i = 0; i < ENV_SIZE; i++) {
    env->inputs[i] = 0.0;
  }
  for (int32_t i = 0; i < REGISTER_COUNT; i++) {
    env->registers[i] = 0.0;
  }
}

typedef double (*execute_t)(expr*, environment*);

typedef struct {
  double value;
} constant_data;

typedef struct {
  expr* argument;
} unary_data;

typedef struct {
  expr* left;
  expr* right;
} binary_data;

typedef struct {
  int8_t reg_index;
} load_data;

typedef struct {
  int8_t reg_index;
  expr* argument;
} store_data;

typedef struct {
  char name;
} ident_data;

union expr_data {
  constant_data constant;
  unary_data unary;
  binary_data binary;
  load_data load;
  store_data store;
  ident_data ident;
};

typedef struct _expr {
  int8_t type;
  execute_t exec;
  union expr_data data;
} expr;

double execute_env_read(environment* env, char name) {
  return env->inputs[name - 'a'];
}

double execute_env_reg_load(environment* env, int8_t index) {
  return env->registers[index];
}

double execute_env_reg_store(environment* env, int8_t index, double value) {
  env->registers[index] = value;
  return value;
}

double execute_constant(expr* e, environment* env) {
  return e->data.constant.value;
}

double execute_add(expr* e, environment* env) {
  double l = e->data.binary.left->exec(e->data.binary.left, env);
  double r = e->data.binary.right->exec(e->data.binary.right, env);
  return l + r;
}

double execute_sub(expr* e, environment* env) {
  double l = e->data.binary.left->exec(e->data.binary.left, env);
  double r = e->data.binary.right->exec(e->data.binary.right, env);
  return l - r;
}

double execute_mul(expr* e, environment* env) {
  double l = e->data.binary.left->exec(e->data.binary.left, env);
  double r = e->data.binary.right->exec(e->data.binary.right, env);
  return l * r;
}

double execute_div(expr* e, environment* env) {
  double l = e->data.binary.left->exec(e->data.binary.left, env);
  double r = e->data.binary.right->exec(e->data.binary.right, env);
  return l / r;
}

double execute_rem(expr* e, environment* env) {
  double l = e->data.binary.left->exec(e->data.binary.left, env);
  double r = e->data.binary.right->exec(e->data.binary.right, env);
  int64_t irem = ((int64_t) l) % ((int64_t) r);
  return (double) irem;
}

double execute_sqrt(expr* e, environment* env) {
  double a = e->data.unary.argument->exec(e->data.unary.argument, env);
  return sqrt(a);
}

double execute_load(expr* e, environment* env) {
  int8_t index = e->data.load.reg_index;
  return execute_env_reg_load(env, index);
}

double execute_store(expr* e, environment* env) {
  int8_t index = e->data.store.reg_index;
  double v = e->data.store.argument->exec(e->data.store.argument, env);
  return execute_env_reg_store(env, index, v);
}

double execute_ident(expr* e, environment* env) {
  char name = e->data.ident.name;
  return execute_env_read(env, name);
}

union expr_chunk {
  union expr_chunk* next;
  expr chunk;
};

union expr_chunk freelist_memory[MAX_EXPR_NODES];
union expr_chunk* freelist_head;

expr* allocate() {
  union expr_chunk* c = freelist_head;
  if (c == NULL) {
    return NULL;
  }
  freelist_head = c->next;
  return (expr*) c;
}

void deallocate(expr* e) {
  union expr_chunk* c = (union expr_chunk*) e;
  c->next = freelist_head;
  freelist_head = c;
}

void freelist_init() {
  for (int32_t i = 0; i < MAX_EXPR_NODES; i++) {
    freelist_memory[i].next = i == MAX_EXPR_NODES - 1 ? NULL : freelist_memory + i + 1;
  }
  freelist_head = freelist_memory;
}

expr* expr_create(int8_t type) {
  expr* e = allocate();
  if (e == NULL) abort();
  e->type = typ