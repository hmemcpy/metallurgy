package com.hmemcpy.metallurgy.compilerbackend

private[compilerbackend] final case class CompilerBackendShimMethod(
    name: String,
    descriptor: String,
    role: CompilerBackendRole,
    elementLocal: Int,
    resultInternalName: String
)

private[compilerbackend] final case class CompilerBackendShimTarget(
    className: String,
    classFingerprint: String,
    methods: Vector[CompilerBackendShimMethod]
):
  val internalName: String = className.replace('.', '/')

/** Exact bundled Scala-plugin 2026.1.20 semantic roots. The pattern section inventories every concrete implementation
  * that supplies `Typeable.type()`, including methods materialized from Scala trait defaults.
  */
private[compilerbackend] object CompilerBackendShimManifest:

  private val EitherDescriptor = "()Lscala/util/Either;"

  private def either(name: String, role: CompilerBackendRole): Vector[CompilerBackendShimMethod] =
    Vector(CompilerBackendShimMethod(name, EitherDescriptor, role, 0, "scala/util/Either"))

  private def target(
      className: String,
      fingerprint: String,
      method: String,
      role: CompilerBackendRole
  ): CompilerBackendShimTarget =
    CompilerBackendShimTarget(className, fingerprint, either(method, role))

  private def pattern(className: String, fingerprint: String): CompilerBackendShimTarget =
    target(className, fingerprint, "type", CompilerBackendRole.Pattern)

  val targets: Vector[CompilerBackendShimTarget] = Vector(
    target(
      "org.jetbrains.plugins.scala.lang.psi.impl.statements.ScPatternDefinitionImpl",
      "2c08d9c89840e817bcd4c771aa17655147d0f4820d709e4031d116dd87b0f55f",
      "type",
      CompilerBackendRole.Definition
    ),
    target(
      "org.jetbrains.plugins.scala.lang.psi.impl.statements.ScVariableDefinitionImpl",
      "4d74fd49f5b7dbba102e87257a08175a176afd0701952158f4387743f324325f",
      "type",
      CompilerBackendRole.Definition
    ),
    target(
      "org.jetbrains.plugins.scala.lang.psi.impl.statements.ScValueDeclarationImpl",
      "8028820071f3940a6a2013fc3a7871df55bf14ae917ff161118c37a9a12f59fc",
      "type",
      CompilerBackendRole.Definition
    ),
    target(
      "org.jetbrains.plugins.scala.lang.psi.impl.statements.ScVariableDeclarationImpl",
      "782849aafcac670967721fce7ba145385ef884b5cbbdf7cc69b312ad240fdf37",
      "type",
      CompilerBackendRole.Definition
    ),
    target(
      "org.jetbrains.plugins.scala.lang.psi.impl.statements.ScFunctionDefinitionImpl",
      "a4db5ab6cf36814bf464936f09c3b6ce0ba0fd2ee15e280b607fde511ba2dbed",
      "returnType",
      CompilerBackendRole.FunctionResult
    ),
    target(
      "org.jetbrains.plugins.scala.lang.psi.impl.statements.ScFunctionDeclarationImpl",
      "b3083da2a575b10d7d7286a1298649f3e0c63295c03bf3227ffeddb3921aa918",
      "returnType",
      CompilerBackendRole.FunctionResult
    ),
    target(
      "org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.ScGivenAliasDefinitionImpl",
      "c495b3227f793e138189883253ba46234c2c7845bcfeb2a61e080da88f0d24d3",
      "returnType",
      CompilerBackendRole.FunctionResult
    ),
    target(
      "org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.ScGivenAliasDeclarationImpl",
      "e8b6673e983ed364a1335790cab31b6c9c66577aa7554a2198c91c912d63c1d5",
      "returnType",
      CompilerBackendRole.FunctionResult
    ),
    target(
      "org.jetbrains.plugins.scala.lang.psi.impl.statements.params.ScParameterImpl",
      "3118a6529c4845e24b91211ccb3d150a66fdff4fc4629555dd25d3c4b7ef74b4",
      "type",
      CompilerBackendRole.Parameter
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScReferencePatternImpl",
      "263e2c613f197a99c812c85258d202198ef693c401c34449379fc5412add41e1"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScCompositePatternImpl",
      "ab1c4f42db591852f3aee906db51297aab3b25a9060caad85c1b5a39f9317b5f"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScNamingPatternImpl",
      "e621c7455b6ad3b42e420049e5f208bf0956214e0a79e3c2b1491a6e89b19928"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScLiteralPatternImpl",
      "842a27928b2887b5864d6c4145187bb47129e207a7172d1ecded0a86a5fa1d0b"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScQuotedPatternImpl",
      "dcfdee3271053f1eb35c952cdb227ee1ff1d4a4be948677d09d522432e22e441"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScTypedPatternImpl",
      "3d82cfe7aecd29ca2e2636c11bb9821364b7eb24c0232363fc6d95b825ab82b2"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.Sc3TypedPatternImpl",
      "cf07be326834884f355ad64d602809d2a1a05bd9a171367ccf0b42a9fb0e9351"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScExtractorPatternImpl",
      "426a4d64bb6bacfa19b80045aacbc2fae234980bddf06c4920705a58597b50cb"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScNamedConstructorArgPatternImpl",
      "de2ace6c416459ebff62a8662f364329755012808041490cf12a7a3273b3832b"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScParenthesisedPatternImpl",
      "6d4b1ed1e7542b562d725e784235741ca7add7e91b1c670a103b1b6dd802d9e0"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScWildcardPatternImpl",
      "d3a66ae4f9a1a8551a7011f209b87ad47553469b6380c021a7cbd3ecb2fd3715"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScSeqWildcardPatternImpl",
      "fcd09f8f77da0c6e3148e6ed1df41b4d0c2375335531ef4404f50268cdfc66b1"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScGivenPatternImpl",
      "7c1b05d64151cf8ff61b95dbf0425b0ea74164a5e7329daeabe53ad2683e1dac"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScNamedTuplePatternComponentImpl",
      "0d2e5e6ae27135b738bc7a54ef1a8e92154f2888b12cd9fbad695d1568b8cda5"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScNamedTuplePatternImpl",
      "854bd9d683035f5c0dc2b9a976436921975e99c2f26430c404e751c72fdf1abc"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScStableReferencePatternImpl",
      "04893c0bfe22a00aa9aaaca7ea6489325ecfc8305d7ea42803d87066473bc745"
    ),
    pattern(
      "org.jetbrains.plugins.scala.lang.psi.impl.base.patterns.ScTuplePatternImpl",
      "6d067389c4ac16db6b0fea65f563ef95029a81fe7c311a66ee96925085817905"
    ),
    CompilerBackendShimTarget(
      "org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScPattern$Ext$",
      "c153a9564137ce8e043488cdbe10902c745747de3d0969c5fe6c04cb125b05d8",
      Vector(
        CompilerBackendShimMethod(
          "expectedType$extension",
          "(Lorg/jetbrains/plugins/scala/lang/psi/api/base/patterns/ScPattern;)Lscala/Option;",
          CompilerBackendRole.PatternExpected,
          1,
          "scala/Option"
        )
      )
    )
  )
