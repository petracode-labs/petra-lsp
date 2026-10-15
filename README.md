# Petra Universal LSP

A lightweight, multi-language Language Server Protocol (LSP) server implementing the **Universal OOP AST (UAST)** engine. It validates **Petra Base** and **Petra Coord** constraint subsets across Java, Python, TypeScript, C++, Go, and Rust using ANTLR 4 over standard I/O (`stdio`).

---

## Prerequisites

- **Java Development Kit (JDK):** Version 17, 21, or 23+
- **Apache Maven:** Version 3.8+

---

## Build

Compile the ANTLR 4 grammar definitions and build the executable fat JAR:

```bash
mvn clean package
```

The compiled standalone JAR with all dependencies will be located at:
```text
target/petra-lsp-1.0-SNAPSHOT-jar-with-dependencies.jar
```

---

## Running the Server Directly

The server communicates via JSON-RPC over standard input/output (`stdio`). You can launch the server directly from the command line:

```bash
java -jar target\petra-lsp-1.0-SNAPSHOT-jar-with-dependencies.jar
```

*(The process will start silently and wait for JSON-RPC initialization messages from an LSP client).*

---

## IntelliJ IDEA Integration (via LSP4IJ)

### Does IntelliJ support arbitrary LSP servers out of the box?
**No.** While IntelliJ IDEA Ultimate has internal LSP APIs, neither Community nor Ultimate includes a built-in user interface to launch and bind arbitrary external `stdio` language servers without an extension.

To use Petra Universal LSP in IntelliJ IDEA, install the open-source **LSP4IJ** plugin (by Red Hat).

---

### Step 1: Install the LSP4IJ Plugin
1. Open IntelliJ IDEA.
2. Open Settings:
    - **Windows/Linux:** `File` → `Settings` (or `Ctrl + Alt + S`)
    - **macOS:** `IntelliJ IDEA` → `Settings...` (or `Cmd + ,`)
3. Select **Plugins** from the left-hand navigation.
4. Select the **Marketplace** tab, search for **`LSP4IJ`**, and click **Install**.
5. Restart IntelliJ IDEA if prompted.

---

### Step 2: Add Petra LSP as a User-Defined Language Server
1. Navigate to:  
   **Settings** → **Languages & Frameworks** → **Language Servers**.
2. Click the **`+`** (Add) button to create a new **User-defined Language Server**.
3. In the **Server** tab:
    - **Name:** `Petra LSP`
    - **Command:**
       ```bash
       java -jar target\petra-lsp-1.0-SNAPSHOT-jar-with-dependencies.jar
       ```
      *(Note: On Windows, if environment paths require a shell wrapper, you can alternatively use `cmd /c java -jar target\petra-lsp-1.0-SNAPSHOT-jar-with-dependencies.jar`).*
4. In the **Mappings** tab:
    - Click **`+`** to map file types to the server.
    - Add file extensions or pattern matches for the languages you want validated:
        - `*.java`
        - `*.py`
        - `*.ts`
        - `*.cpp`
        - `*.go`
        - `*.rs`
5. Click **Apply** and **OK**.

---

### Step 3: Verify Integration
1. Open a project file (such as a Java file annotated with `@Base` or `@Coord`).
2. Open the **LSP Consoles** tool window (`View` → `Tool Windows` → `LSP Consoles`) to verify `Petra LSP` started successfully.
3. Errors and warnings will display inline in the editor and in the standard **Problems** tool window (`Alt + 6` / `Cmd + 6`).

---

## Visual Studio Code Integration (Alternative)

To run with VS Code using a generic LSP extension (such as *Generic LSP Client*), add the following server configuration to your `settings.json`:

```json
{
  "languageServerExample.trace.server": "verbose",
  "lsp.servers": {
    "petra-lsp": {
      "command": "java",
      "args": [
        "-jar",
        "target\\petra-lsp-1.0-SNAPSHOT-jar-with-dependencies.jar"
      ],
      "filetypes": [
        "java",
        "python",
        "typescript",
        "cpp",
        "go",
        "rust"
      ]
    }
  }
}
```

---

## Validation Rules Enforced

The LSP strictly enforces mathematical verification rules based on class and method annotations.

### 1. Petra Coord Rules (`@Coord`)
*Enforces the Command/Expression orchestration language.*

| Rule | Severity | Description |
|---|---|---|
| **Visibility** | Error | Methods must be strictly `public`. |
| **Parameters** | Error | Methods must have exactly `0` parameters (zero-arg). |
| **Return Types** | Error | Methods must strictly return `boolean` (Expressions/Predicates) or `void` (Commands/Transitions). |
| **Post-Conditions** | Error | Command methods (`void`) must conclude with an `assert` statement verifying the state transition. |
| **Branching** | Error | `switch` blocks are forbidden. Must use strict `if-then` structures. |
| **Loops** | Error | Raw `while` and `for` loops are forbidden. Flow must use formal sep/seq/par control. |

### 2. Petra Base Rules (`@Base`)
*Enforces strict Object-Oriented state and memory boundaries.*

| Rule | Severity | Description |
|---|---|---|
| **Inheritance** | Error | Classes cannot use `extends`. Polymorphism via interfaces is required instead. |
| **Object Immutability** | Error | Non-primitive object references (fields, local variables, parameters) must be strictly `final` / `const` / `readonly`. |
| **Exceptions** | Error | Explicit exception handling (`throw`, `try`, `catch`) is forbidden. |
| **Typing** | Error | Explicit types are required. Type inference (`var`, `auto`) is forbidden. |
| **Returns** | Error | Methods can only return primitive values or `void`. |
| **Entry Points** | Error | Methods marked as `@Entry` (or `main`) must have 0 parameters. |

### 3. Petra Coord+Base Weaving (`@Coord @Base`)
*Applies when a class or method operates as both an orchestrator and a state leaf node. The LSP automatically weaves the rules together.*

| Resulting Woven Constraint | Inherited From |
|---|---|
| Must be strictly `public` | `@Coord` |
| Exactly `0` parameters | `@Coord` |
| Returns only `boolean` or `void` | `@Coord` (overrides `@Base` primitive allowance) |
| `void` methods must end in `assert` | `@Coord` |
| No `switch` blocks, no raw loops | `@Coord` |
| No class inheritance (`extends`) | `@Base` |
| Object references must be strictly `final` | `@Base` |
| No Exceptions, No Type Inference (`var`) | `@Base` |

### Global Ignored Contexts

| Rule | Description |
|---|---|
| **External Bypass** | Any class or method annotated with `@External` (or language equivalent like `//petra:external`) is completely bypassed by the validator. |
| **Test Files** | Files located under `/src/test/`, `/tests/`, or matching test naming patterns (e.g., `*.spec.ts`, `*_test.go`) are automatically ignored. |
| **Enum Primitives** | Types ending in `Enum`, annotated with `@Enum`, or assigned via constants (`State.WAITING`, `State::WAITING`) are treated as primitive values. |