package com.cognitionbox.petra.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.*;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.*;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 - Universal Petra Coord & Base Language Server (Pure Java ANTLR 4 Edition).

 - Validates Petra Base and Petra Coord subset constraints across multiple languages.

 */
public class PetraUniversalLSP implements LanguageServer, TextDocumentService, WorkspaceService, LanguageClientAware {

    private LanguageClient client;

    // =========================================================================
    // 1. UNIVERSAL OOP AST (UAST)
    // =========================================================================

    public static class UProgram {
        public final List<UClass> classes = new ArrayList<>();
    }

    public static class UClass {
        public boolean isExternal = false;
        public boolean hasInheritance = false;
        public final List<UField> fields = new ArrayList<>();
        public final List<UMethod> methods = new ArrayList<>();
        public Range range;
        public UClass(Range range) { this.range = range; }
    }

    public static class UField {
        public boolean isPrimitive = false;
        public boolean isFinal = false;
        public Range range;
        public UField(Range range) { this.range = range; }
    }

    public static class UMethod {
        public boolean isEntry = false;
        public boolean hasParams = false;
        public boolean returnsPrimitive = true;
        public boolean hasLoops = false;
        public boolean hasExceptions = false;
        public boolean hasSwitch = false;
        public boolean hasUntyped = false;
        public final List<UVar> locals = new ArrayList<>();
        public Range range;
        public UMethod(Range range) { this.range = range; }
    }

    public static class UVar {
        public boolean isPrimitive = false;
        public boolean isFinal = false;
        public Range range;
        public UVar(Range range) { this.range = range; }
    }

    // =========================================================================
    // 2. MULTI-LANGUAGE SPECIFICATIONS (ANTLR RULE MAPPINGS)
    // =========================================================================

    public static class LangSpec {
        public final String lexerClass, parserClass, rootRule;
        public final Set<String> classNodes, extendsNodes, methodNodes, fieldNodes,
                varNodes, loopNodes, exceptionNodes, switchNodes, untypedNodes;

        // Language-specific semantic rules
        public final Set<String> finalKeywords;
        public final String externalMarker;
        public final String enumMarker;
        public final boolean isFinalByDefault;

        public LangSpec(String lexerClass, String parserClass, String rootRule,
                        String[] cls, String[] ext, String[] meth, String[] fld,
                        String[] var, String[] loop, String[] exc, String[] sw, String[] untyped,
                        String[] finalKeywords, String externalMarker, String enumMarker, boolean isFinalByDefault) {
            this.lexerClass = lexerClass; this.parserClass = parserClass; this.rootRule = rootRule;
            this.classNodes = new HashSet<>(Arrays.asList(cls));
            this.extendsNodes = new HashSet<>(Arrays.asList(ext));
            this.methodNodes = new HashSet<>(Arrays.asList(meth));
            this.fieldNodes = new HashSet<>(Arrays.asList(fld));
            this.varNodes = new HashSet<>(Arrays.asList(var));
            this.loopNodes = new HashSet<>(Arrays.asList(loop));
            this.exceptionNodes = new HashSet<>(Arrays.asList(exc));
            this.switchNodes = new HashSet<>(Arrays.asList(sw));
            this.untypedNodes = new HashSet<>(Arrays.asList(untyped));

            this.finalKeywords = new HashSet<>(Arrays.asList(finalKeywords));
            this.externalMarker = externalMarker;
            this.enumMarker = enumMarker;
            this.isFinalByDefault = isFinalByDefault;
        }
    }

    private static final Map<String, LangSpec> SPECS = new HashMap<>();

    static {
        SPECS.put(".java", new LangSpec(
                "com.cognitionbox.petra.antlr.JavaLexer", "com.cognitionbox.petra.antlr.JavaParser", "compilationUnit",
                new String[]{"classDeclaration"}, new String[]{},
                new String[]{"methodDeclaration"}, new String[]{"fieldDeclaration"},
                new String[]{"localVariableDeclaration", "formalParameter"},
                new String[]{"whileStatement", "forStatement", "doStatement"},
                new String[]{"throwStatement", "tryStatement"}, new String[]{"switchStatement"},
                new String[]{"var"},
                new String[]{"final"}, "@External", "@Enum", false
        ));

        SPECS.put(".py", new LangSpec(
                "com.cognitionbox.petra.antlr.Python3Lexer", "com.cognitionbox.petra.antlr.Python3Parser", "file_input",
                new String[]{"classdef"}, new String[]{},
                new String[]{"funcdef"}, new String[]{"expr_stmt"},
                new String[]{"parameters", "expr_stmt"},
                new String[]{"while_stmt", "for_stmt"},
                new String[]{"raise_stmt", "try_stmt"}, new String[]{"match_stmt"},
                new String[]{},
                new String[]{}, "@External", "@Enum", false
        ));

        SPECS.put(".ts", new LangSpec(
                "com.cognitionbox.petra.antlr.TypeScriptLexer", "com.cognitionbox.petra.antlr.TypeScriptParser", "program",
                new String[]{"classDeclaration"}, new String[]{},
                new String[]{"methodDefinition", "functionDeclaration"}, new String[]{"propertySignature", "propertyDeclaration"},
                new String[]{"variableDeclaration", "requiredParameter"},
                new String[]{"whileStatement", "forStatement", "doStatement"},
                new String[]{"throwStatement", "tryStatement"}, new String[]{"switchStatement"},
                new String[]{},
                new String[]{"readonly", "const"}, "@External", "@Enum", false
        ));

        SPECS.put(".cpp", new LangSpec(
                "com.cognitionbox.petra.antlr.CPP14Lexer", "com.cognitionbox.petra.antlr.CPP14Parser", "translationUnit",
                new String[]{"classSpecifier"}, new String[]{},
                new String[]{"functionDefinition"}, new String[]{"memberDeclaration"},
                new String[]{"simpleDeclaration", "parameterDeclaration"},
                new String[]{"iterationStatement"},
                new String[]{"tryBlock", "throwExpression"}, new String[]{"selectionStatement"},
                new String[]{"auto"},
                new String[]{"const"}, "PETRA_EXTERNAL", "PETRA_ENUM", false
        ));

        SPECS.put(".go", new LangSpec(
                "com.cognitionbox.petra.antlr.GoLexer", "com.cognitionbox.petra.antlr.GoParser", "sourceFile",
                new String[]{"typeSpec"}, new String[]{},
                new String[]{"methodDecl", "functionDecl"}, new String[]{"fieldDecl"},
                new String[]{"varDecl", "parameterDecl"},
                new String[]{"forStmt"},
                new String[]{"deferStmt"}, new String[]{"switchStmt", "typeSwitchStmt"},
                new String[]{"shortVarDecl"},
                new String[]{}, "//petra:external", "//petra:enum", false
        ));

        SPECS.put(".rs", new LangSpec(
                "com.cognitionbox.petra.antlr.RustLexer", "com.cognitionbox.petra.antlr.RustParser", "crate",
                new String[]{"structItem"}, new String[]{},
                new String[]{"functionItem"}, new String[]{"structField"},
                new String[]{"letDeclaration", "functionParam"},
                new String[]{"whileExpression", "loopExpression", "forExpression"},
                new String[]{}, new String[]{"matchExpression"},
                new String[]{},
                new String[]{"mut"}, "#[petra::external]", "#[petra::enum]", true
        ));
    }

    // =========================================================================
    // 3. ANTLR PARSER ENGINE & UAST MAPPER
    // =========================================================================

    private static class ParseResult {
        public final ParserRuleContext root;
        public final Parser parser;
        public ParseResult(ParserRuleContext root, Parser parser) { this.root = root; this.parser = parser; }
    }

    private static ParseResult parseDynamically(String sourceCode, LangSpec spec) {
        try {
            Class<?> lexerClass = Class.forName(spec.lexerClass);
            Lexer lexer = (Lexer) lexerClass.getConstructor(CharStream.class).newInstance(CharStreams.fromString(sourceCode));

            Class<?> parserClass = Class.forName(spec.parserClass);
            Class<?> tokenStreamClass = Class.forName("org.antlr.v4.runtime.TokenStream");
            Parser parser = (Parser) parserClass.getConstructor(tokenStreamClass).newInstance(new CommonTokenStream(lexer));

            Method rootMethod = parserClass.getMethod(spec.rootRule);
            ParserRuleContext rootContext = (ParserRuleContext) rootMethod.invoke(parser);
            return new ParseResult(rootContext, parser);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static UProgram mapToUAST(ParseTree root, String sourceCode, LangSpec spec, Parser parser) {
        UProgram prog = new UProgram();
        walk(root, prog, null, null, sourceCode, spec, parser);
        return prog;
    }

    private static boolean isExternalClass(ParseTree node, String sourceCode, Parser parser, LangSpec spec) {
        // 1. Direct text check using language-specific marker (RESTRICTED TO HEADER ONLY)
        String classText = extractSource(node, sourceCode);
        int bodyStart = classText.indexOf('{');
        if (bodyStart == -1) bodyStart = classText.indexOf(':');
        String header = bodyStart != -1 ? classText.substring(0, bodyStart) : classText;

        if (header.contains(spec.externalMarker)) return true;

        // 2. Parent node check (e.g. typeDeclaration or decorated rule)
        ParseTree parent = node.getParent();
        if (parent instanceof RuleContext) {
            String parentRule = parser.getRuleNames()[((RuleContext) parent).getRuleIndex()];
            if (!parentRule.equalsIgnoreCase(spec.rootRule)) {
                String parentText = extractSource(parent, sourceCode);
                int cut = parentText.indexOf('{');
                if (cut == -1) cut = parentText.indexOf(':');
                String parentHeader = (cut != -1) ? parentText.substring(0, cut) : parentText;
                if (parentHeader.contains(spec.externalMarker)) return true;
            }
        }

        // 3. Raw buffer fallback
        if (node instanceof ParserRuleContext) {
            Token start = ((ParserRuleContext) node).getStart();
            if (start != null) {
                int startIdx = start.getStartIndex();
                if (startIdx > 0 && startIdx <= sourceCode.length()) {
                    int prevBoundary = Math.max(0, Math.max(
                            sourceCode.lastIndexOf('}', startIdx - 1),
                            sourceCode.lastIndexOf(';', startIdx - 1)
                    ));
                    String preamble = sourceCode.substring(prevBoundary, startIdx);
                    if (preamble.contains(spec.externalMarker)) return true;
                }
            }
        }
        return false;
    }

    private static void walk(ParseTree node, UProgram prog, UClass currentClass, UMethod currentMethod, String sourceCode, LangSpec spec, Parser parser) {
        String type = "";
        if (node instanceof RuleContext) {
            type = parser.getRuleNames()[((RuleContext) node).getRuleIndex()];
        }

        if (spec.classNodes.contains(type)) {
            currentClass = new UClass(range(node));
            currentClass.isExternal = isExternalClass(node, sourceCode, parser, spec);
            prog.classes.add(currentClass);

            if (!currentClass.isExternal) {
                String classText = extractSource(node, sourceCode);
                if (spec.lexerClass.contains("Python")) {
                    int colonIdx = classText.indexOf(':');
                    String pyHeader = (colonIdx != -1) ? classText.substring(0, colonIdx) : classText;
                    Matcher pyExt = Pattern.compile("\\(([^)]+)\\)").matcher(pyHeader);
                    if (pyExt.find() && !pyExt.group(1).trim().isEmpty()) {
                        currentClass.hasInheritance = true;
                    }
                } else {
                    int braceIdx = classText.indexOf('{');
                    String header = (braceIdx != -1) ? classText.substring(0, braceIdx) : classText;
                    if (Pattern.compile("\\bextends\\b").matcher(header).find()
                            || Pattern.compile(":\\s*(public|private|protected)?\\s*\\w+").matcher(header).find()) {
                        currentClass.hasInheritance = true;
                    }
                }
            }
        }
        else if (currentClass != null && !currentClass.isExternal && spec.fieldNodes.contains(type)) {
            UField field = new UField(range(node));
            String text = extractSource(node, sourceCode);
            String typeStr = extractTypeStr(node, parser, sourceCode);

            boolean hasKeyword = hasModifier(node, sourceCode, parser, spec, spec.finalKeywords.toArray(new String[0]));
            field.isFinal = spec.isFinalByDefault ? !hasKeyword : hasKeyword;

            // Unified Regex Enum Inference: Matches Java/TS/Python `State.WAITING` and Rust/C++ `State::WAITING`
            boolean looksLikeEnumAssignment = Pattern.compile("=\\s*[A-Z]\\w*(?:\\.|::)[A-Z_0-9]+").matcher(text).find();
            field.isPrimitive = isPrimitiveType(typeStr) || text.contains(spec.enumMarker) || looksLikeEnumAssignment;

            currentClass.fields.add(field);
        }
        else if (currentClass != null && !currentClass.isExternal && spec.methodNodes.contains(type)) {
            currentMethod = new UMethod(range(node));
            String text = extractSource(node, sourceCode);

            currentMethod.isEntry = text.contains("@Entry") || Pattern.compile("\\bmain\\s*\\(").matcher(text).find();

            int braceIndex = text.indexOf('{');
            String header = (braceIndex != -1) ? text.substring(0, braceIndex) : text;
            Matcher paramMatcher = Pattern.compile("\\(([^)]*)\\)", Pattern.DOTALL).matcher(header);
            if (paramMatcher.find()) {
                String params = paramMatcher.group(1).trim();
                currentMethod.hasParams = !params.isEmpty() && !params.equalsIgnoreCase("void");
            } else {
                currentMethod.hasParams = false;
            }

            String typeStr = extractTypeStr(node, parser, sourceCode);
            currentMethod.returnsPrimitive = isPrimitiveType(typeStr) || text.contains(spec.enumMarker);
            currentClass.methods.add(currentMethod);
        }
        else if (currentMethod != null && (currentClass == null || !currentClass.isExternal)) {
            if (spec.loopNodes.contains(type)) currentMethod.hasLoops = true;
            if (spec.exceptionNodes.contains(type)) currentMethod.hasExceptions = true;
            if (spec.switchNodes.contains(type)) currentMethod.hasSwitch = true;
            if (spec.untypedNodes.contains(type)) currentMethod.hasUntyped = true;

            if (spec.varNodes.contains(type)) {
                UVar var = new UVar(range(node));
                String text = extractSource(node, sourceCode);
                String typeStr = extractTypeStr(node, parser, sourceCode);

                boolean hasKeyword = hasModifier(node, sourceCode, parser, spec, spec.finalKeywords.toArray(new String[0]));
                var.isFinal = spec.isFinalByDefault ? !hasKeyword : hasKeyword;

                // Unified Regex Enum Inference
                boolean looksLikeEnumAssignment = Pattern.compile("=\\s*[A-Z]\\w*(?:\\.|::)[A-Z_0-9]+").matcher(text).find();
                var.isPrimitive = isPrimitiveType(typeStr) || text.contains(spec.enumMarker) || looksLikeEnumAssignment;

                if (typeStr.equals("UnknownType") || typeStr.equals("var") || typeStr.equals("auto")) {
                    currentMethod.hasUntyped = true;
                }
                currentMethod.locals.add(var);
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            walk(node.getChild(i), prog, currentClass, currentMethod, sourceCode, spec, parser);
        }
    }

    private static String extractTypeStr(ParseTree node, Parser parser, String sourceCode) {
        ParseTree typeNode = findChildRuleContaining(node, parser, "type");
        if (typeNode != null) {
            String extracted = extractSource(typeNode, sourceCode).trim();
            if (!extracted.isEmpty()) return extracted;
        }
        return "UnknownType";
    }

    private static ParseTree findChildRuleContaining(ParseTree node, Parser parser, String keyword) {
        if (node instanceof RuleContext) {
            String ruleName = parser.getRuleNames()[((RuleContext) node).getRuleIndex()].toLowerCase();
            if (ruleName.contains(keyword)) return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            ParseTree found = findChildRuleContaining(node.getChild(i), parser, keyword);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isPrimitiveType(String typeStr) {
        if (typeStr == null || typeStr.trim().isEmpty() || typeStr.equals("UnknownType")) {
            return false;
        }
        if (typeStr.equalsIgnoreCase("void")) return true;

        String clean = typeStr.replaceAll("@\\w+\\s*", "").trim();
        if (clean.endsWith("Enum")) return true;

        if (clean.equalsIgnoreCase("int") || clean.equalsIgnoreCase("long")
                || clean.equalsIgnoreCase("double") || clean.equalsIgnoreCase("float")
                || clean.equalsIgnoreCase("boolean") || clean.equalsIgnoreCase("bool")
                || clean.equalsIgnoreCase("byte") || clean.equalsIgnoreCase("short")
                || clean.equalsIgnoreCase("char") || clean.equalsIgnoreCase("number")
                || clean.equalsIgnoreCase("string") || clean.equalsIgnoreCase("str")) {
            return true;
        }

        return Pattern.matches("^[a-z].*", clean) && !clean.contains("[") && !clean.contains("<");
    }

    private static boolean hasModifier(ParseTree node, String sourceCode, Parser parser, LangSpec spec, String... modifiers) {
        if (modifiers == null || modifiers.length == 0) return false;

        ParseTree target = node;
        while (target != null) {
            if (target instanceof RuleContext) {
                String rawRule = parser.getRuleNames()[((RuleContext) target).getRuleIndex()];
                String lowerRule = rawRule.toLowerCase();

                // Stop ascending once reaching the enclosing class body boundary, class node, or root
                if (lowerRule.equals("classbody")
                        || lowerRule.equals("memberspecification")
                        || spec.classNodes.contains(rawRule)
                        || rawRule.equalsIgnoreCase(spec.rootRule)) {
                    break;
                }
            }
            String text = extractSource(target, sourceCode);
            for (String mod : modifiers) {
                if (Pattern.compile("\\b" + Pattern.quote(mod) + "\\b").matcher(text).find()) {
                    return true;
                }
            }
            target = target.getParent();
        }
        return false;
    }

    private static String extractSource(ParseTree node, String sourceCode) {
        if (node instanceof ParserRuleContext) {
            ParserRuleContext ctx = (ParserRuleContext) node;
            Token start = ctx.getStart();
            Token stop = ctx.getStop();
            if (start != null && stop != null) {
                int s = start.getStartIndex();
                int e = stop.getStopIndex();
                if (s >= 0 && e >= 0 && e < sourceCode.length() && s <= e) {
                    return sourceCode.substring(s, e + 1);
                }
            }
        }
        return node.getText();
    }

    private static Range range(ParseTree node) {
        if (node instanceof ParserRuleContext) {
            ParserRuleContext ctx = (ParserRuleContext) node;
            Token start = ctx.getStart();
            Token stop = ctx.getStop();
            if (start != null && stop != null) {
                return new Range(
                        new Position(Math.max(0, start.getLine() - 1), Math.max(0, start.getCharPositionInLine())),
                        new Position(Math.max(0, stop.getLine() - 1), Math.max(0, stop.getCharPositionInLine() + (stop.getText() != null ? stop.getText().length() : 0)))
                );
            }
        }
        return new Range(new Position(0,0), new Position(0,0));
    }

    // =========================================================================
    // 4. THE SINGLE UNIFIED SUBSET VALIDATOR
    // =========================================================================

    private void validateDocument(String uri, String sourceCode) {
        // Skip validation completely for test files
        if (isTestFile(uri)) {
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, Collections.emptyList()));
            return;
        }

        String ext = getExtension(uri);
        if (!SPECS.containsKey(ext)) return;

        LangSpec spec = SPECS.get(ext);
        List<Diagnostic> diagnostics = new ArrayList<>();

        ParseResult parseResult = parseDynamically(sourceCode, spec);

        if (parseResult == null) {
            Diagnostic diag = createDiag(null, "ANTLR Grammar missing. Please add " + ext.replace(".", "") + " .g4 file to src/main/antlr4 and rebuild.");
            diag.setSeverity(DiagnosticSeverity.Warning);
            client.publishDiagnostics(new PublishDiagnosticsParams(uri, Collections.singletonList(diag)));
            return;
        }

        try {
            UProgram program = mapToUAST(parseResult.root, sourceCode, spec, parseResult.parser);

            boolean baseFailed = false;
            boolean coordFailed = false;

            for (UClass uClass : program.classes) {
                // Skip entirely if annotated with external marker
                if (uClass.isExternal) continue;

                if (uClass.hasInheritance) {
                    baseFailed = true;
                    diagnostics.add(createDiag(uClass.range, "Petra Base: Inheritance is strictly forbidden. Use interfaces."));
                }

                for (UField f : uClass.fields) {
                    if (!f.isPrimitive && !f.isFinal) {
                        baseFailed = true;
                        diagnostics.add(createDiag(f.range, "Petra Base: Object references must be strictly final."));
                    }
                }

                for (UMethod m : uClass.methods) {
                    if (m.hasExceptions) { baseFailed = true; diagnostics.add(createDiag(m.range, "Petra Base: Exceptions are forbidden.")); }
                    if (m.hasSwitch) { baseFailed = true; diagnostics.add(createDiag(m.range, "Petra Base: Switch blocks are forbidden. Use if-else.")); }
                    if (m.hasUntyped) { baseFailed = true; diagnostics.add(createDiag(m.range, "Petra Base: Explicit types required. Type inference is forbidden.")); }
                    if (!m.returnsPrimitive) { baseFailed = true; diagnostics.add(createDiag(m.range, "Petra Base: Methods can only return primitives or void.")); }
                    if (m.isEntry && m.hasParams) { baseFailed = true; diagnostics.add(createDiag(m.range, "Petra Base: Entry points must have 0 parameters.")); }

                    for (UVar v : m.locals) {
                        // Allow object references locally, as long as they are final
                        if (!v.isPrimitive && !v.isFinal) {
                            baseFailed = true;
                            diagnostics.add(createDiag(v.range, "Petra Base: Local variables and parameters must be primitives or strictly final object references."));
                        }
                    }

                    if (m.hasLoops) {
                        coordFailed = true;
                        diagnostics.add(createDiag(m.range, "Petra Coord: Raw while/for loops are forbidden. Use sep/seq/par flow control."));
                    }
                }
            }

            if (!baseFailed && !coordFailed) {
                diagnostics.add(createTag("System Tag: \u2714 Petra Coord Compliant (Inherently Base Compliant)"));
            } else if (!baseFailed) {
                diagnostics.add(createTag("System Tag: \u2714 Petra Base Compliant (Fails stricter Coord constraints due to raw loops)"));
            } else {
                diagnostics.add(createTag("System Tag: \u274C Non-Compliant (Violates core Petra Base OOP subsets)"));
            }

        } catch (Exception e) {
            System.err.println("Parse error: " + e.getMessage());
        }

        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    }

    private Diagnostic createDiag(Range range, String msg) {
        Diagnostic diag = new Diagnostic();
        diag.setSeverity(DiagnosticSeverity.Error);
        diag.setMessage(msg);
        diag.setRange(range != null ? range : new Range(new Position(0,0), new Position(0,0)));
        diag.setSource("Petra-UAST-Validator");
        return diag;
    }

    private Diagnostic createTag(String msg) {
        Diagnostic diag = new Diagnostic();
        diag.setSeverity(DiagnosticSeverity.Information);
        diag.setMessage(msg);
        diag.setRange(new Range(new Position(0,0), new Position(0,0)));
        diag.setSource("Petra-Compliance");
        return diag;
    }

    // =========================================================================
    // 5. LSP SERVER LIFECYCLE BOILERPLATE
    // =========================================================================

    public static void main(String[] args) {
        try {
            PetraUniversalLSP server = new PetraUniversalLSP();
            org.eclipse.lsp4j.jsonrpc.Launcher<LanguageClient> launcher = LSPLauncher.createServerLauncher(server, System.in, System.out);
            server.connect(launcher.getRemoteProxy());
            launcher.startListening().get();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override public void connect(LanguageClient client) { this.client = client; }

    @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override public CompletableFuture<Object> shutdown() { return CompletableFuture.completedFuture(null); }
    @Override public void exit() { System.exit(0); }
    @Override public TextDocumentService getTextDocumentService() { return this; }
    @Override public WorkspaceService getWorkspaceService() { return this; }

    @Override public void didOpen(DidOpenTextDocumentParams p) { validateDocument(p.getTextDocument().getUri(), p.getTextDocument().getText()); }
    @Override public void didChange(DidChangeTextDocumentParams p) { validateDocument(p.getTextDocument().getUri(), p.getContentChanges().get(0).getText()); }
    @Override public void didClose(DidCloseTextDocumentParams p) { client.publishDiagnostics(new PublishDiagnosticsParams(p.getTextDocument().getUri(), Collections.emptyList())); }
    @Override public void didSave(DidSaveTextDocumentParams p) { }
    @Override public void didChangeConfiguration(DidChangeConfigurationParams params) {}
    @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}

    private String getExtension(String uri) {
        int dotIndex = uri.lastIndexOf('.');
        return dotIndex == -1 ? "" : uri.substring(dotIndex).toLowerCase();
    }

    private boolean isTestFile(String uri) {
        if (uri == null) return false;
        String lowerUri = uri.toLowerCase();

        // 1. Directory-based heuristics (Maven/Gradle, Python, JS/TS)
        if (lowerUri.contains("/src/test/") ||
                lowerUri.contains("/tests/") ||
                lowerUri.contains("/__tests__/")) {
            return true;
        }

        // 2. File-name-based heuristics
        if (lowerUri.endsWith("test.java") || lowerUri.endsWith("tests.java") ||
                lowerUri.endsWith("_test.go") || lowerUri.endsWith("_test.py") ||
                lowerUri.endsWith("_test.cpp") || lowerUri.endsWith("_test.rs") ||
                lowerUri.contains(".test.") || lowerUri.contains(".spec.")) {
            return true;
        }

        return false;
    }
}