// PreToolUse guard: block destructive / push commands before they run.
//
// Reads the Command Code hook payload as JSON on stdin, writes a JSON verdict to
// stdout. Never evals model input; matching is tokenization + prefix rules only.
// Exit 0 always (verdict carried in stdout); empty stdout means "no opinion".
//
// Set `failClosed: true` on this hook entry in .commandcode/settings.json so a
// crash or timeout blocks instead of silently allowing.

/** Split a command string into tokens, honouring single/double quotes. */
export function tokenize(src) {
  const out = [];
  let cur = "";
  let quote = null;
  for (const ch of String(src ?? "")) {
    if (quote) {
      if (ch === quote) quote = null;
      else cur += ch;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      continue;
    }
    if (/\s/.test(ch)) {
      if (cur) out.push(cur);
      cur = "";
      continue;
    }
    cur += ch;
  }
  if (cur) out.push(cur);
  return out;
}

/** Split on top-level compound operators, ignoring quoted regions. */
export function splitTop(src) {
  const parts = [];
  let cur = "";
  let quote = null;
  const text = String(src ?? "");
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quote) {
      cur += ch;
      if (ch === quote) quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") {
      quote = ch;
      cur += ch;
      continue;
    }
    const two = text.slice(i, i + 2);
    if (two === "&&" || two === "||") {
      parts.push(cur);
      cur = "";
      i++;
      continue;
    }
    if (ch === ";" || ch === "|") {
      parts.push(cur);
      cur = "";
      continue;
    }
    cur += ch;
  }
  parts.push(cur);
  return parts.map((s) => s.trim()).filter(Boolean);
}

/** Executable name of a token: basename, minus `.exe`, lowercased. */
export function exeName(token) {
  const base = String(token ?? "").split(/[\\/]/).pop() ?? "";
  return base.replace(/\.exe$/i, "").toLowerCase();
}

// Git global options that consume the following token as their value.
const GIT_VALUE_OPTS = new Set([
  "-c",
  "-C",
  "--git-dir",
  "--work-tree",
  "--namespace",
  "--exec-path",
  "--config-env",
]);

/** Strip Git global options and return the subcommand plus its arguments. */
export function parseGit(tokens) {
  const rest = tokens.slice(1);
  let i = 0;
  while (i < rest.length) {
    const t = rest[i];
    if (!t.startsWith("-")) break;
    if (GIT_VALUE_OPTS.has(t) || GIT_VALUE_OPTS.has(t.toLowerCase())) {
      i += 2; // option and its separate value
      continue;
    }
    if (t.includes("=")) {
      i += 1; // --git-dir=<path>
      continue;
    }
    i += 1;
  }
  return { sub: (rest[i] ?? "").toLowerCase(), args: rest.slice(i + 1) };
}

/** Force flag inside a short cluster such as `-fd`, `-xfd`, or `--force`. */
function hasForceFlag(args) {
  return args.some((a) => {
    if (a === "--force") return true;
    if (!a.startsWith("-") || a.startsWith("--") || a.length < 2) return false;
    return a.slice(1).includes("f");
  });
}

// Shell wrappers whose payload is another command line.
const WRAPPERS = new Set(["powershell", "pwsh", "cmd", "bash", "sh", "zsh"]);
const WRAPPER_CMD_FLAGS = new Set([
  "-command",
  "-c",
  "-encodedcommand",
  "-e",
  "/c",
  "/k",
]);

/**
 * Decide whether a command line must be blocked.
 * Returns a reason string, or null when the command is fine.
 */
export function inspect(src, depth = 0) {
  const text = String(src ?? "");
  if (!text.trim() || depth > 6) return null;

  // Pipe-to-shell install is a pipeline shape, so check the raw text first.
  if (/(curl|wget|invoke-webrequest)[^\n|]*\|\s*(sudo\s+)?(sh|bash|zsh|powershell|pwsh)/i.test(text)) {
    return "pipe-to-shell install";
  }

  for (const part of splitTop(text)) {
    const tokens = tokenize(part);
    if (!tokens.length) continue;
    const head = exeName(tokens[0]);

    if (head === "git") {
      const { sub, args } = parseGit(tokens);
      if (sub === "push") return "agents never push; the user pushes manually";
      if (sub === "reset" && args.includes("--hard")) return "git reset --hard discards work";
      if (sub === "clean" && hasForceFlag(args)) return "git clean -f deletes untracked files";
      continue;
    }

    if (WRAPPERS.has(head)) {
      const flagIdx = tokens.findIndex((t, i) => i > 0 && WRAPPER_CMD_FLAGS.has(t.toLowerCase()));
      const payload = flagIdx >= 0 ? tokens.slice(flagIdx + 1).join(" ") : "";
      if (payload) {
        const nested = inspect(payload, depth + 1);
        if (nested) return nested;
      }
      continue;
    }

    const line = part;
    if (/\brm\s+[^|;\n]*-[a-z]*r[a-z]*f?\s+\/(?![\w.-])/i.test(line)) {
      return "recursive delete from filesystem root";
    }
    if (/\brm\s+-rf\s+\.\s*(;|$)/i.test(line)) return "recursive delete of the working tree";
    if (/\brd\s+\/s(\s+\/q)?\s+[a-z]:[\\/]?\s*(;|$)/i.test(line)) {
      return "recursive delete of a drive root";
    }
    if (/\b(mkfs|diskpart\s+clean)\b/i.test(line) || /\bdd\s+[^|;\n]*of=\/dev\//i.test(line)) {
      return "raw disk operation";
    }
  }
  return null;
}

function readStdin() {
  return new Promise((resolve) => {
    let data = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (c) => {
      data += c;
    });
    process.stdin.on("end", () => resolve(data));
    setTimeout(() => resolve(data), 2000);
  });
}

async function main() {
  const raw = await readStdin();
  let cmd = "";
  try {
    const payload = JSON.parse(raw || "{}");
    const input = payload.tool_input || {};
    cmd = input.command || (Array.isArray(input.args) ? input.args.join(" ") : "");
  } catch {
    process.exit(0); // Unparseable payload: no opinion.
  }

  const why = inspect(cmd);
  if (!why) process.exit(0);

  process.stdout.write(
    JSON.stringify({
      systemMessage: `Blocked destructive command (${why})`,
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "deny",
        permissionDecisionReason:
          `Policy forbids this command (${why}). If you genuinely need it, ask the user to run it by hand. ` +
          `Command: ${String(cmd).slice(0, 160)}`,
      },
    })
  );
}

// Only run as a hook when executed directly, so the test harness can import it.
if (process.argv[1] && process.argv[1].endsWith("guard-shell.mjs")) {
  await main();
}