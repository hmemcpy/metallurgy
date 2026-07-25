package dogfood.fixture.betasty

// Combined view of the two-module betasty fixture (module_a defines Person, module_b uses it).
// The real cross-module scenario (a *broken* upstream emitting only .betasty) lives in BetastyCrossModuleTest.
class Person

object UsesPerson:
  val person: Person = new Person
