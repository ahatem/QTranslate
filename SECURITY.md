# Security Policy

## Supported versions

| Version | Supported |
|---------|-----------|
| Latest release | ✅ Security fixes backported |
| Previous minor (n-1) | ⚠️ Critical fixes only |
| Older releases | ❌ Not supported |

We recommend always running the latest release.

---

## Reporting a vulnerability

**Please do not report security vulnerabilities through public GitHub issues.** A public issue exposes the vulnerability to everyone before it can be fixed.

Use one of these private channels instead:

- **GitHub private vulnerability reporting** *(preferred)* — click **"Report a vulnerability"** on the [Security tab](https://github.com/ahatem/qtranslate/security/advisories/new)
- **Email** — send details to `ahatem925@gmail.com` with the subject line `[QTranslate Security]`

### What to include

- A description of the vulnerability and its potential impact
- The component or feature affected (plugin system, hotkey handler, update checker, marketplace download, etc.)
- Steps to reproduce or a proof-of-concept (if safe to share)
- The QTranslate version, operating system, and Java version you tested against
- Any mitigations or workarounds you are aware of

### Response timeline

| Stage | Target |
|-------|--------|
| Acknowledgement | Within 48 hours |
| Triage and severity assessment | Within 5 business days |
| Fix and coordinated disclosure | Depends on severity — critical issues within 14 days where possible |

We follow responsible disclosure — please give us reasonable time to fix the issue before making it public.

---

## Scope

### In scope

- Arbitrary code execution via the plugin system (e.g. unsigned plugin loading without user confirmation)
- Credential or API key leakage (e.g. keys logged in plain text or transmitted to unexpected hosts)
- Privilege escalation or sandbox escapes
- Unsafe deserialization in config loading or plugin manifests
- Remote code execution via the update checker or marketplace plugin download path
- Issues that could allow a malicious plugin to silently replace or tamper with other plugins

### Out of scope

- Vulnerabilities in third-party plugins not maintained in this repository
- Issues that require physical access to the machine
- Social engineering (convincing a user to install a malicious plugin)
- Vulnerabilities in third-party translation APIs (report those to the relevant service provider)
- Theoretical vulnerabilities with no practical exploitation path

---

## Plugin security note

QTranslate plugins run inside the JVM with full access to the local filesystem and network. **Only install plugins from sources you trust.** The plugin verification system (JAR hash checking on install) protects against silent plugin replacement after installation, but it is not a substitute for trusting the source in the first place.

---

## Credit

Reporters who responsibly disclose valid security issues will be credited in the release notes and CHANGELOG (unless they prefer to remain anonymous).
