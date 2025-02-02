// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.analysis;

import com.intellij.codeInsight.daemon.impl.quickfix.MoveAnnotationOnStaticMemberQualifyingTypeFix;
import com.intellij.codeInsight.daemon.impl.quickfix.ReplaceVarWithExplicitTypeFix;
import com.intellij.codeInsight.intention.CommonIntentionAction;
import com.intellij.codeInsight.intention.QuickFixFactory;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.java.analysis.JavaAnalysisBundle;
import com.intellij.java.codeserver.highlighting.errors.JavaCompilationError;
import com.intellij.java.codeserver.highlighting.errors.JavaErrorKind;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.intellij.java.codeserver.highlighting.errors.JavaErrorKinds.*;

/**
 * Fixes attached to error messages provided by {@link com.intellij.java.codeserver.highlighting.JavaErrorCollector}
 */
final class JavaErrorFixProvider {
  @FunctionalInterface
  private interface JavaFixProvider<Psi extends PsiElement, Context> {
    @Nullable CommonIntentionAction provide(@NotNull JavaCompilationError<? extends Psi, ? extends Context> error);

    default JavaFixesProvider<Psi, Context> asMulti() {
      return error -> ContainerUtil.createMaybeSingletonList(provide(error));
    }
  }

  @FunctionalInterface
  private interface JavaFixesProvider<Psi extends PsiElement, Context> {
    @NotNull List<@NotNull CommonIntentionAction> provide(@NotNull JavaCompilationError<? extends Psi, ? extends Context> error);
  }

  private static final Map<JavaErrorKind<?, ?>, List<JavaFixesProvider<?, ?>>> FIXES = new HashMap<>();

  static {
    JavaFixProvider<PsiAnnotation, Object> annotationRemover = error -> 
      QuickFixFactory.getInstance().createDeleteFix(error.psi(), JavaAnalysisBundle.message("intention.text.remove.annotation"));
    single(ANNOTATION_NOT_ALLOWED_CLASS, annotationRemover);
    single(ANNOTATION_NOT_ALLOWED_HERE, annotationRemover);
    single(ANNOTATION_NOT_ALLOWED_REF, annotationRemover);
    single(ANNOTATION_NOT_ALLOWED_VAR, annotationRemover);
    single(ANNOTATION_NOT_ALLOWED_VOID, annotationRemover);
    single(ANNOTATION_NOT_APPLICABLE, annotationRemover);
    single(ANNOTATION_NOT_ALLOWED_VAR, error -> {
      PsiAnnotationOwner owner = error.psi().getOwner();
      PsiTypeElement type = owner instanceof PsiTypeElement te ? te :
                            PsiTreeUtil.skipSiblingsForward((PsiModifierList)owner, PsiComment.class, PsiWhiteSpace.class,
                                                            PsiTypeParameterList.class) instanceof PsiTypeElement te ? te :
                            null;
      return type != null && type.isInferredType() ? new ReplaceVarWithExplicitTypeFix(type) : null;
    });
    multi(ANNOTATION_NOT_APPLICABLE, error -> {
      if (!BaseIntentionAction.canModify(Objects.requireNonNull(error.psi().resolveAnnotationType()))) return List.of();
      return ContainerUtil.map(error.context(),
                               targetType -> QuickFixFactory.getInstance().createAddAnnotationTargetFix(error.psi(), targetType));
    });
    single(ANNOTATION_NOT_ALLOWED_STATIC, error -> new MoveAnnotationOnStaticMemberQualifyingTypeFix(error.psi()));
    multi(UNSUPPORTED_FEATURE, error -> HighlightUtil.getIncreaseLanguageLevelFixes(error.psi(), error.context()));
    single(ANNOTATION_MISSING_ATTRIBUTE, error -> QuickFixFactory.getInstance().createAddMissingRequiredAnnotationParametersFix(
      error.psi(), PsiMethod.EMPTY_ARRAY, error.context()));
  }

  private static <Psi extends PsiElement, Context> void single(@NotNull JavaErrorKind<Psi, Context> kind,
                                                               @NotNull JavaFixProvider<? super Psi, ? super Context> fixProvider) {
    multi(kind, fixProvider.asMulti());
  }

  private static <Psi extends PsiElement, Context> void multi(@NotNull JavaErrorKind<Psi, Context> kind,
                                                              @NotNull JavaFixesProvider<? super Psi, ? super Context> fixProvider) {
    FIXES.computeIfAbsent(kind, k -> new ArrayList<>()).add(fixProvider);
  }

  static @NotNull List<CommonIntentionAction> getFixes(@NotNull JavaCompilationError<?, ?> error) {
    var providers = FIXES.get(error.kind());
    if (providers == null) return List.of();
    List<CommonIntentionAction> fixes = new ArrayList<>();
    for (var provider : providers) {
      @SuppressWarnings("unchecked") var fn = (JavaFixesProvider<PsiElement, Object>)provider;
      fixes.addAll(fn.provide(error));
    }
    return fixes;
  }
}
