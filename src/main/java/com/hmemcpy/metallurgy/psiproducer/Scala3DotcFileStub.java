package com.hmemcpy.metallurgy.psiproducer;

import com.intellij.psi.PsiClass;
import com.intellij.psi.stubs.PsiFileStubImpl;
import org.jetbrains.plugins.scala.lang.TokenSets;
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile;
import org.jetbrains.plugins.scala.lang.psi.stubs.ScFileStub;

@SuppressWarnings({"rawtypes", "unchecked"})
final class Scala3DotcFileStub extends PsiFileStubImpl<ScalaFile> implements ScFileStub {
  private final Scala3DotcFileElementType elementType;

  Scala3DotcFileStub(ScalaFile file, Scala3DotcFileElementType elementType) {
    super(file);
    this.elementType = elementType;
  }

  @Override
  public Scala3DotcFileElementType getType() {
    return elementType;
  }

  @Override
  public PsiClass[] getClasses() {
    return getChildrenByType(TokenSets.TYPE_DEFINITIONS(), PsiClass.ARRAY_FACTORY);
  }
}
