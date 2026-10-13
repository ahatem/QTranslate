// Stop hook: lightweight preflight at end of turn (whitespace errors +
// conflict markers). Must stay fast (<1s) and never loop: bail out when
// stop_hook_active is true. Violations exit 2 with stderr so the model
// retries once; the engine caps retries at 3.
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";

function readStdin() {
  return new Promise((resolve) => {
    let data = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (c) => { data += c; });
    process.stdin.on("end", () => resolve(data));
    setTimeout(() => resolve(data), 2000);
  });
}

const raw = await readStdin();
let payload = {};
try { payload = JSON.parse(raw || "{}"); } catch { process.exit(0); }
if (payload.stop_hook_active) process.exit(0); // Already retrying: let it end.

const cwd = process.env.COMMANDCODE_PROJECT_DIR || payload.cwd || process.cwd();
const problems = [];
const git = (args) => {
  try {
    return execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (e) {
    return (e && e.stdout) || "";
  }
};

const ws = (git(["diff", "--check"]) + git(["diff", "--cached", "--check"])).trim();
if (ws) problems.push(`git diff --check:${ws.split("\n").slice(0, 10).join("\n")}`);

// Conflict markers in files changed vs HEAD (cheap, scoped).
const files = git(["diff", "--name-only", "HEAD"]).split("\n").map((s) => s.trim()).filter(Boolean).slice(0, 50);
for (const f of files) {
  try {
    const text = readFileSync(`${cwd}/${f}`, "utf8");
    if (/^(<{7}|={7}|>{7}|\|{7})/m.test(text)) problems.push(`conflict markers in ${f}`);
  } catch { /* deleted/binary/unreadable: ignore */ }
}

if (!problems.length) process.exit(0);
process.stderr.write(`Preflight failed, fix before finishing:\n${problems.slice(0, 15).join("\n")}\n`);
process.exit(2);
