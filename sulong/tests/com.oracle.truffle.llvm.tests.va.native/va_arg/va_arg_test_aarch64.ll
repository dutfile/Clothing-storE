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
  br i1 %13, label %5, label %7, !dbg !46, !llvm.loop !54
}

; Function Attrs: nounwind readnone speculatable willreturn
declare void @llvm.dbg.declare(metadata, metadata, metadata) #1

; Function Attrs: argmemonly nounwind willreturn
declare void @llvm.lifetime.start.p0i8(i64 immarg, i8* nocapture) #2

; Function Attrs: nounwind
declare void @llvm.va_start(i8*) #3

declare !dbg !4 dso_local double @va_argDouble(%struct.__va_list*) local_unnamed_addr #4

; Function Attrs: argmemonly nounwind willreturn
declare void @llvm.lifetime.end.p0i8(i64 immarg, i8* nocapture) #2

; Function Attrs: nounwind
declare void @llvm.va_end(i8*) #3

; Function Attrs: nounwind
define dso_local i32 @testVaArgInt(i32 %0, ...) local_unnamed_addr #0 !dbg !56 {
  %2 = alloca %struct.__va_list, align 8
  call void @llvm.dbg.value(metadata i32 %0, metadata !60, metadata !DIExpression()), !dbg !68
  call void @llvm.dbg.value(metadata i32 0, metadata !61, metadata !DIExpression()), !dbg !68
  %3 = bitcast %struct.__va_list* %2 to i8*, !dbg !69
  call void @llvm.lifetime.start.p0i8(i64 32, i8* nonnull %3) #3, !dbg !69
  call void @llvm.dbg.declare(metadata %struct.__va_list* %2, metadata !62, metadata !DIExpression()), !dbg !70
  call void @llvm.va_start(i8* %3), !dbg !71
  call void @llvm.dbg.value(metadata i32 0, metadata !63, metadata !DIExpression()), !dbg !72
  call void @llvm.dbg.value(metadata i32 0, metadata !61, metadata !DIExpression()), !dbg !68
  %4 = icmp sgt i32 %0, 0, !dbg !73
  br i1 %4, label %9, label %7, !dbg !74

5:                                                ; preds = %9
  %6 = trunc i64 %16 to i32, !dbg !75
  call void @llvm.dbg.value(metadata i32 %6, metadata !61, metadata !DIExpression()), !dbg !68
  br label %7, !dbg !76

7:                                                ; preds = %5, %1
  %8 = phi i32 [ 0, %1 ], [ %6, %5 ], !dbg !68
  call void @llvm.dbg.value(metadata i32 %8, metadata !61, metadata !DIExpression()), !dbg !68
  call void @llvm.va_end(i8* nonnull %3), !dbg !76
  call void @llvm.lifetime.end.p0i8(i64 32, i8* nonnull %3) #3, !dbg !77
  ret i32 %8, !dbg !78

9:                                                ; preds = %1, %9
  %10 = phi i64 [ %16, %9 ], [ 0, %1 ]
  %11 = phi i32 [ %17, %9 ], [ 0, %1 ]
  call void @llvm.dbg.value(metadata i32 undef, metadata !61, metadata !DIExpression()), !dbg !68
  call void @llvm.dbg.value(metadata i32 %11, metadata !63, metadata !DIExpression()), !dbg !72
  ;%12 = call i32 @va_argInt(%struct.__va_list* nonnull %2) #3, !dbg !79
  %12 = va_arg %struct.__va_list* %2, i32, !dbg !79
  %13 = sext i32 %12 to i64, !dbg !79
  %14 = shl i64 %10, 32, !dbg !75
  %15 = ashr exact i64 %14, 32, !dbg !75
  %16 = add nsw i64 %15, %13, !dbg !75
  call void @llvm.dbg.value(metadata i64 %16, metadata !61, metadata !DIExpression(DW_OP_LLVM_convert, 64, DW_ATE_unsigned, DW_OP_LLVM_convert, 32, DW_ATE_unsigned, DW_OP_stack_value)), !dbg !68
  %17 = add nuw nsw i32 %11, 1, !dbg !80
  call void @llvm.dbg.value(metadata i32 %17, metadata !63, metadata !DIExpression()), !dbg !72
  %18 = icmp eq i32 %17, %0, !dbg !73
  br i1 %18, label %5, label %9, !dbg !74, !llvm.loop !81
}

declare !dbg !18 dso_local i32 @va_argInt(%struct.__va_list*) local_unnamed_addr #4

; Function Attrs: nounwind
define dso_local i32 @main() local_unnamed_addr #0 !dbg !83 {
  %1 = call i32 (i32, ...) @testVaArgInt(i32 8, double 1.000000e+00, i32 2, double 3.000000e+00, i32 4, double 5.000000e+00, i32 6, double 7.000000e+00, i32 8, double 9.000000e+00, i32 10, double 1.100000e+01, i32 12, double 1.300000e+01, i32 14, double 1.500000e+01, i32 16), !dbg !86
  %2 = call i32 (i8*, ...) @printf(i8* nonnull dereferenceable(1) getelementptr inbounds ([25 x i8], [25 x i8]* @.str, i64 0, i64 0), i32 %1), !dbg !87
  %3 = call double (i32, ...) @testVaArgDouble(i32 8, double 1.000000e+00, i32 2, double 3.000000e+00, i32 4, double 5.000000e+00, i32 6, double 7.000000e+00, i32 8, double 9.000000e+00, i32 10, double 1.100000e+01, i32 12, double 1.300000e+01, i32 14, double 1.500000e+01, i32 16), !dbg !88
  %4 = call i32 (i8*, ...) @printf(i8* nonnull dereferenceable(1) getelementptr inbounds ([25 x i8], [25 x i8]* @.str.1, i64 0, i64 0), double %3), !dbg !89
  ret i32 0, !dbg !90
}

; Function Attrs: nofree nounwind
declare dso_local i32 @printf(i8* nocapture readonly, ...) local_unnamed_addr #5

; Function Attrs: nounwind readnone speculatable willreturn
declare void @llvm.dbg.value(metadata, metadata, metadata) #1

attributes #0 = { nounwind "correctly-rounded-divide-sqrt-fp-math"="false" "disable-tail-calls"="false" "frame-pointer"="non-leaf" "less-precise-fpmad"="false" "min-legal-vector-width"="0" "no-infs-fp-math"="false" "no-jump-tables"="false" "no-nans-fp-math"="false" "no-signed-zeros-fp-math"="false" "no-trapping-math"="false" "stack-protector-buffer-size"="8" "target-cpu"="generic" "target-features"="+neon" "unsafe-fp-math"="false" "use-soft-float"="false" }
attributes #1 = { nounwind readnone speculatable willreturn }
attributes #2 = { argmemonly nounwind willreturn }
attributes #3 = { nounwind }
attributes #4 = { "correctly-rounded-divide-sqrt-fp-math"="false" "disable-tail-calls"="false" "frame-pointer"="non-leaf" "less-precise-fpmad"="false" "no-infs-fp-math"="false" "no-nans-fp-math"="false" "no-signed-zeros-fp-math"="false" "no-trapping-math"="false" "stack-protector-buffer-size"="8" "target-cpu"="generic" "target-features"="+neon" "unsafe-fp-math"="false" "use-soft-float"="false" }
attributes #5 = { nofree nounwind "correctly-rounded-divide-sqrt-fp-math"="false" "disable-tail-calls"="false" "frame-pointer"="non-leaf" "less-precise-fpmad"="false" "no-infs-fp-math"="false" "no-nans-fp-math"="false" "no-signed-zeros-fp-math"="false" "no-trapping-math"="false" "stack-protector-buffer-size"="8" "target-cpu"="generic" "target-features"="+neon" "unsafe-fp-math"="false" "use-soft-float"="false" }

!llvm.dbg.cu = !{!0}
!llvm.module.flags = !{!21, !22, !23}
!llvm.ident = !{!24}

!0 = distinct !DICompileUnit(language: DW_LANG_C99, file: !1, producer: "clang version 10.0.0 (GraalVM.org llvmorg-10.0.0-4-g22d2637565-bg83994d0b4b 22d26375659ee388e18a96bf6b34e56299f75efc)", isOptimized: true, runtimeVersion: 0, emissionKind: FullDebug, enums: !2, retainedTypes: !3, splitDebugInlining: false, nameTableKind: None)
!1 = !DIFile(filename: "va_arg_test.c", directory: "/Users/zslajchrt/work/graaldev/graal/sulong/tests/com.oracle.truffle.llvm.tests.va.native/va_arg", checksumkind: CSK_MD5, checksum: "778392f10ba81717f6b292e9c3ffc9e3")
!2 = !{}
!3 = !{!4, !18}
!4 = !DISubprogram(name: "va_argDouble", scope: !1, file: !1, line: 34, type: !5, flags: DIFlagPrototyped, spFlags: DISPFlagOptimized, retainedNodes: !2)
!5 = !DISubroutineType(types: !6)
!6 = !{!7, !8}
!7 = !DIBasicType(name: "double", size: 64, encoding: DW_ATE_float)
!8 = !DIDerivedType(t