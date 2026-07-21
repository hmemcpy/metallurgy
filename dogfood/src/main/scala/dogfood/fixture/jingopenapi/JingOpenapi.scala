package dogfood.fixture.jingopenapi

val api = jing.openapi.inlineYaml("""
  openapi: 3.0.0
  info:
    title: Pet service
    version: 1.0.0
  paths:
    /pet:
      get:
        responses:
          '200':
            description: Found
  """)

val endpoint = api.paths.`/pet`.Get
