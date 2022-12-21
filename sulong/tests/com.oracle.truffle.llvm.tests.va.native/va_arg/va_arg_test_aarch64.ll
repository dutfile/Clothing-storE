; ModuleID = 'va_arg_test.c'
source_filename = "va_arg_test.c"
target datalayout = "e-m:e-i8:8:32-i16:16:32-i64:64-i128:128-n32:64-S128"
target triple = "aarch64-unknown-linux-gnu"

%struct.__va_list = type { i8*, i8*, i8*, i32, i32 }

@.str = private unnamed_addr constant [25 x i8] c"Test int va_arg    : %d\0A\00", align 1
@.str.1 = private unnamed_addr constant [25 x i8] c"Test double va_arg : %f\0A\00", align 1

; Function Attrs: nounwind
define dso_local double @testVaArgDouble(i32 %0, ...) local_unnamed_addr #0 !dbg !25 {
  %2 = alloca %struct.__va_list, align 8
  call void @llvm.dbg.value(metadata i32 %0, metadata !29, metadata !DIExpression()), !dbg !40
  call void @llvm.dbg.value(metadata double 0.000000e+00, metadata !30, metadata !DIExpression()), !dbg !40
  %3 = bitcast %struct.__va_list* %2 to i8*, !dbg !41
  call void @llvm.lifetime.start.p0i8(i64 32, i8* nonnull %3) #3, !dbg !41
  call void @llvm.dbg.declare(metadata %struct.__va_list* %2, metadata !31, metadata !DIExpression()), !dbg !42
  call void @llvm.va_start(i8* %3), !dbg !43
  call void @llvm.dbg.value(metadata i32 0, metadata !35, metadata !DIExpression()), !dbg !44
  call void @llvm.dbg.value(metadata double 0.000000e+00, metadata !30, metadata !DIExpression()), !dbg !40
  %4 = icmp sgt i32 %0, 0, !dbg !45
  br i1 %4, label %7, label %5, !dbg !46

5:                                                ; preds = %7, %1
  %6 = phi double [ 0.000000e+00, %1 ], [ %11, %7 ], !dbg !40
  call void @llvm.dbg.value(metadata double %6, metadata !30, metadata !DIExpression()), !dbg !40
  call void @llvm.va_end(i8* nonnull %3), !dbg !47
  call void @llvm.lifetime.end.p0i8(i64 32, i8* nonnull %3) #3, !dbg !48
  ret double %6, !dbg !49

7:                                                ; preds = %1, %7
  %8 = phi double [ %11, %7 ], [ 0.000000e+00, %1 ]
  %9 = phi i32 [ %12, %7 ], [ 0, %1 ]
  call void @llvm.dbg.value(metadata double %8, metadata !30, metadata !DIExpression()), !dbg !40
  call void @llvm.dbg.value(metadata i32 %9, metadata !35, metadata !DIExpression()), !dbg !44
  ;%10 = call double @va_argDouble(%struct.__va_list* nonnull %2) #3, !dbg !50
  %10 = va_arg %struct.__va_list* %2, double, !dbg !50
  call void @llvm.dbg.value(metadata double %10, metadata !37, metadata !DIExpression()), !dbg !51
  %11 = fadd double %8, %10, !dbg !52
  call void @llvm.dbg.value(metadata double %11, metadata !30, metadata !DIExpression()), !dbg !40
  %12 = add nuw nsw i32 %9, 1, !dbg !53
  call void @llvm.dbg.value(metadata i32 %12, metadata !35, metadata !DIExpression()), !dbg !44
  %13 = icmp eq i32 %12, %0, !dbg !45
  br i1 %