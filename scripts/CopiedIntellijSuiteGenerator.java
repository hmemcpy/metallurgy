import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class CopiedIntellijSuiteGenerator {
  private record Wiring(
      String originPackage,
      String generatedPackage,
      String originOwner,
      String generatedOwner,
      String originBase,
      String generatedBase,
      String importAnchor,
      String adapterImport) {}

  private record Token(String text, int start, int end) {}

  private record SourceRange(int start, int end) {
    boolean intersects(SourceRange other) {
      return start < other.end && other.start < end;
    }
  }

  private record ClassNode(SourceRange declaration, SourceRange body) {}

  private record HostTree(SourceRange packageDeclaration, SourceRange importDeclaration, ClassNode owner) {}

  private record Edit(SourceRange range, String replacement, String label) {}

  private record ProtectedBody(String text, int startByte, int endByte) {}

  public static void main(String[] args) throws Exception {
    if (args.length == 1 && args[0].equals("self-test")) {
      selfTest();
      return;
    }
    if (args.length < 2) {
      throw new IllegalArgumentException("expected command and arguments");
    }
    switch (args[0]) {
      case "generate" -> generate(args);
      case "verify-body" -> verifyBody(args);
      default -> throw new IllegalArgumentException("unknown command: " + args[0]);
    }
  }

  private static void generate(String[] args) throws Exception {
    if (args.length != 11) {
      throw new IllegalArgumentException("generate expects source, output, and eight wiring arguments");
    }
    var sourcePath = Path.of(args[1]);
    var outputPath = Path.of(args[2]);
    var wiring = wiring(args, 3);
    var source = Files.readString(sourcePath, StandardCharsets.UTF_8);
    var generated = generate(source, wiring);
    Files.createDirectories(outputPath.getParent());
    Files.writeString(outputPath, generated, StandardCharsets.UTF_8);
  }

  private static void verifyBody(String[] args) throws Exception {
    if (args.length != 11) {
      throw new IllegalArgumentException("verify-body expects source, generated, and eight wiring arguments");
    }
    var source = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
    var generated = Files.readString(Path.of(args[2]), StandardCharsets.UTF_8);
    var wiring = wiring(args, 3);
    var originBody = protectedBody(source, parseHost(source, wiring, false).owner().body());
    var generatedBody = protectedBody(generated, parseHost(generated, wiring, true).owner().body());
    if (!originBody.text().equals(generatedBody.text())) {
      throw new IllegalStateException("generated class body differs from the pinned source");
    }
    System.out.println(
        originBody.startByte()
            + "\t"
            + originBody.endByte()
            + "\t"
            + generatedBody.startByte()
            + "\t"
            + generatedBody.endByte()
            + "\t"
            + sha256(originBody.text()));
  }

  private static Wiring wiring(String[] args, int offset) {
    return new Wiring(
        args[offset],
        args[offset + 1],
        args[offset + 2],
        args[offset + 3],
        args[offset + 4],
        args[offset + 5],
        args[offset + 6],
        args[offset + 7]);
  }

  private static void selfTest() {
    var wiring =
        new Wiring(
            "example.origin",
            "example.generated",
            "Owner",
            "GeneratedOwner",
            "Base",
            "GeneratedBase",
            "import example.Anchor",
            "import example.Adapter");
    var source =
        """
        package example.origin

        // package example.origin
        import example.Anchor

        class Owner extends Base {
          val text = \"\"\"class Owner extends Base { }\"\"\"
          def nested = { 1 }
        }
        """;
    var generated = generate(source, wiring);
    var originBody = parseHost(source, wiring, false).owner().body();
    var generatedBody = parseHost(generated, wiring, true).owner().body();
    if (!source
        .substring(originBody.start(), originBody.end())
        .equals(generated.substring(generatedBody.start(), generatedBody.end()))) {
      throw new IllegalStateException("syntax-aware host parser changed an executable body");
    }
    expectFailure(() -> generate(source + "\nobject Unexpected {}\n", wiring));
    expectFailure(() -> generate(source + "\nclass Owner extends Base {}\n", wiring));
  }

  private static void expectFailure(Runnable body) {
    var failed = false;
    try {
      body.run();
    } catch (IllegalStateException expected) {
      failed = true;
    }
    if (!failed) {
      throw new IllegalStateException("invalid host syntax unexpectedly passed");
    }
  }

  private static String generate(String source, Wiring wiring) {
    var tree = parseHost(source, wiring, false);
    var body = tree.owner().body();
    var edits =
        List.of(
            new Edit(
                tree.packageDeclaration(), "package " + wiring.generatedPackage(), "package declaration"),
            new Edit(
                new SourceRange(tree.importDeclaration().end(), tree.importDeclaration().end()),
                "\n" + wiring.adapterImport(),
                "adapter import"),
            new Edit(
                tree.owner().declaration(),
                "final class " + wiring.generatedOwner() + " extends " + wiring.generatedBase(),
                "owner declaration"));
    edits.forEach(
        edit -> {
          if (edit.range().intersects(body)) {
            throw new IllegalStateException(edit.label() + " intersects the protected class body");
          }
        });

    var result = applyEdits(source, edits);
    var notice =
        "/*\n"
            + " * Metallurgy adaptation: package, owner, and fixture wiring.\n"
            + " * Executable class-body bytes equal the licensed source snapshot.\n"
            + " */\n";
    result = notice + result;
    var generatedTree = parseHost(result, wiring, true);
    var originBody = source.substring(body.start(), body.end());
    var generatedBody =
        result.substring(generatedTree.owner().body().start(), generatedTree.owner().body().end());
    if (!originBody.equals(generatedBody)) {
      throw new IllegalStateException("a host rewrite changed the protected class body");
    }
    return result;
  }

  private static String applyEdits(String source, List<Edit> edits) {
    var ordered = new ArrayList<>(edits);
    ordered.sort(Comparator.comparingInt((Edit edit) -> edit.range().start()).reversed());
    for (var index = 0; index + 1 < ordered.size(); index++) {
      var right = ordered.get(index).range();
      var left = ordered.get(index + 1).range();
      if (left.end() > right.start()) {
        throw new IllegalStateException("host rewrites overlap");
      }
    }
    var result = source;
    for (var edit : ordered) {
      result =
          result.substring(0, edit.range().start())
              + edit.replacement()
              + result.substring(edit.range().end());
    }
    return result;
  }

  private static HostTree parseHost(String source, Wiring wiring, boolean generated) {
    var tokens = tokenize(source);
    var packageName = generated ? wiring.generatedPackage() : wiring.originPackage();
    var owner = generated ? wiring.generatedOwner() : wiring.originOwner();
    var base = generated ? wiring.generatedBase() : wiring.originBase();
    var packageDeclaration = uniqueQualified(tokens, "package", packageName);
    var importDeclaration = uniqueQualified(tokens, "import", importPath(wiring.importAnchor()));
    var classNode = uniqueClass(tokens, owner, base, generated);
    if (tokens.stream().anyMatch(token -> token.start() > classNode.body().end())) {
      throw new IllegalStateException("owner closing brace is not the final syntax token");
    }
    return new HostTree(packageDeclaration, importDeclaration, classNode);
  }

  private static String importPath(String declaration) {
    var prefix = "import ";
    if (!declaration.startsWith(prefix)) {
      throw new IllegalStateException("import anchor must be a complete import declaration");
    }
    return declaration.substring(prefix.length());
  }

  private static SourceRange uniqueQualified(List<Token> tokens, String keyword, String qualifiedName) {
    var parts = qualifiedName.split("\\.");
    var matches = new ArrayList<SourceRange>();
    for (var index = 0; index < tokens.size(); index++) {
      if (!tokens.get(index).text().equals(keyword)) {
        continue;
      }
      var cursor = index + 1;
      var matched = true;
      for (var partIndex = 0; partIndex < parts.length; partIndex++) {
        if (cursor >= tokens.size() || !tokens.get(cursor).text().equals(parts[partIndex])) {
          matched = false;
          break;
        }
        cursor++;
        if (partIndex + 1 < parts.length) {
          if (cursor >= tokens.size() || !tokens.get(cursor).text().equals(".")) {
            matched = false;
            break;
          }
          cursor++;
        }
      }
      if (matched) {
        matches.add(new SourceRange(tokens.get(index).start(), tokens.get(cursor - 1).end()));
      }
    }
    if (matches.size() != 1) {
      throw new IllegalStateException(
          keyword + " " + qualifiedName + " must resolve to exactly one syntax node");
    }
    return matches.getFirst();
  }

  private static ClassNode uniqueClass(
      List<Token> tokens, String owner, String base, boolean finalExpected) {
    var matches = new ArrayList<ClassNode>();
    for (var index = 0; index + 4 < tokens.size(); index++) {
      if (!tokens.get(index).text().equals("class")
          || !tokens.get(index + 1).text().equals(owner)
          || !tokens.get(index + 2).text().equals("extends")
          || !tokens.get(index + 3).text().equals(base)
          || !tokens.get(index + 4).text().equals("{")) {
        continue;
      }
      var declarationStart = tokens.get(index).start();
      if (finalExpected) {
        if (index == 0 || !tokens.get(index - 1).text().equals("final")) {
          throw new IllegalStateException("generated owner is not final");
        }
        declarationStart = tokens.get(index - 1).start();
      }
      var closingIndex = matchingBrace(tokens, index + 4);
      matches.add(
          new ClassNode(
              new SourceRange(declarationStart, tokens.get(index + 3).end()),
              new SourceRange(tokens.get(index + 4).end(), tokens.get(closingIndex).start())));
    }
    if (matches.size() != 1) {
      throw new IllegalStateException("class " + owner + " must resolve to exactly one syntax node");
    }
    return matches.getFirst();
  }

  private static int matchingBrace(List<Token> tokens, int openingIndex) {
    var depth = 0;
    for (var index = openingIndex; index < tokens.size(); index++) {
      switch (tokens.get(index).text()) {
        case "{" -> depth++;
        case "}" -> {
          depth--;
          if (depth == 0) {
            return index;
          }
          if (depth < 0) {
            throw new IllegalStateException("unbalanced owner braces");
          }
        }
        default -> {}
      }
    }
    throw new IllegalStateException("owner closing brace not found");
  }

  private static List<Token> tokenize(String source) {
    var tokens = new ArrayList<Token>();
    var index = 0;
    while (index < source.length()) {
      var current = source.charAt(index);
      if (Character.isWhitespace(current)) {
        index++;
      } else if (startsWith(source, index, "//")) {
        index = skipLineComment(source, index + 2);
      } else if (startsWith(source, index, "/*")) {
        index = skipBlockComment(source, index);
      } else if (startsWith(source, index, "\"\"\"")) {
        var end = skipTripleQuotedString(source, index);
        tokens.add(new Token(source.substring(index, end), index, end));
        index = end;
      } else if (current == '"' || current == '\'') {
        var end = skipQuotedLiteral(source, index, current);
        tokens.add(new Token(source.substring(index, end), index, end));
        index = end;
      } else if (current == '`') {
        var end = source.indexOf('`', index + 1);
        if (end < 0) {
          throw new IllegalStateException("unterminated backticked identifier");
        }
        end++;
        tokens.add(new Token(source.substring(index, end), index, end));
        index = end;
      } else if (Character.isJavaIdentifierStart(current) || current == '$') {
        var end = index + 1;
        while (end < source.length()
            && (Character.isJavaIdentifierPart(source.charAt(end)) || source.charAt(end) == '$')) {
          end++;
        }
        tokens.add(new Token(source.substring(index, end), index, end));
        index = end;
      } else {
        tokens.add(new Token(String.valueOf(current), index, index + 1));
        index++;
      }
    }
    return List.copyOf(tokens);
  }

  private static int skipLineComment(String source, int index) {
    var newline = source.indexOf('\n', index);
    return newline < 0 ? source.length() : newline + 1;
  }

  private static int skipBlockComment(String source, int opening) {
    var depth = 1;
    var index = opening + 2;
    while (index < source.length() && depth > 0) {
      if (startsWith(source, index, "/*")) {
        depth++;
        index += 2;
      } else if (startsWith(source, index, "*/")) {
        depth--;
        index += 2;
      } else {
        index++;
      }
    }
    if (depth != 0) {
      throw new IllegalStateException("unterminated block comment");
    }
    return index;
  }

  private static int skipTripleQuotedString(String source, int opening) {
    var closing = source.indexOf("\"\"\"", opening + 3);
    if (closing < 0) {
      throw new IllegalStateException("unterminated triple-quoted string");
    }
    return closing + 3;
  }

  private static int skipQuotedLiteral(String source, int opening, char delimiter) {
    var index = opening + 1;
    while (index < source.length()) {
      var current = source.charAt(index);
      if (current == '\\') {
        index += 2;
      } else if (current == delimiter) {
        return index + 1;
      } else {
        index++;
      }
    }
    throw new IllegalStateException("unterminated quoted literal");
  }

  private static boolean startsWith(String source, int index, String value) {
    return source.regionMatches(index, value, 0, value.length());
  }

  private static ProtectedBody protectedBody(String source, SourceRange range) {
    return new ProtectedBody(
        source.substring(range.start(), range.end()),
        utf8Length(source.substring(0, range.start())),
        utf8Length(source.substring(0, range.end())));
  }

  private static int utf8Length(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length;
  }

  private static String sha256(String value) throws Exception {
    var digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
