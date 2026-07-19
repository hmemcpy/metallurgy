// Library dependency: dev.continuously.jing %% jing-openapi % 0.0.5 (Scala 3)
// Requires IvyManagedLoader("dev.continuously.jing", "jing-openapi_3", "0.0.5") in test fixture
// Simplified extract — demonstrates multi-level structural-type navigation from a macro
import scala.quoted.*
import scala.reflect.Selectable.reflectiveSelectable

class Endpoint[R](run: R)

transparent inline def apiSpec(inline spec: String): Any = ${ apiSpecImpl('spec) }

def apiSpecImpl(spec: Expr[String])(using Quotes): Expr[Any] =
  // Real jing parses OpenAPI JSON and builds a structural type.
  // This simplified version shows the shape: macro returns a value with a
  // deeply-nested structural type that exercises multi-level navigation.
  '{ new { def paths: Any = new { def `/pet`: Any = new { def Get: Endpoint[String] = new Endpoint("") } } } }

val api = apiSpec("https://example.com/openapi.json")
val get: Endpoint[String] = api.paths.`/pet`.Get
