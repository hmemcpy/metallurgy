# Metallurgy requires compiler-based highlighting

Metallurgy requires the bundled plugin's compiler-based highlighting (CBH). The presentation compiler reads the `.class`/`.tasty`/`.betasty` the compile server writes; without CBH there is nothing to read, so **Metallurgy does not run without CBH.**

- **CBH off → Metallurgy is a complete no-op.** Activation gates on `usesCompilerTypes`; no sessions, no completion, no highlight suppression.
- **The opt-in setting lives under the CBH checkbox** in the Scala project settings — branded, disabled unless CBH is on (refines ADR 0006: co-located, not a standalone panel).

Storage is unchanged (ADR 0006): `MetallurgySettings.isEnabled`, never `isUseCompilerTypes`.
