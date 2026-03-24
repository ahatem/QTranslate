# Security Policy

## Supported Versions

Only the latest release receives security fixes. If you are on an older version, please update first.

| Version | Supported |
|---------|-----------|
| Latest release | ✅ |
| Older versions | ❌ |

---

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.** A public issue exposes the vulnerability to everyone before it can be fixed.

Instead, use one of these private channels:

- **GitHub private vulnerability reporting** — click **"Report a vulnerability"** on the [Security tab](https://github.com/ahatem/qtranslate/security/advisories/new) of this repository *(preferred)*
- **Email** — send details to `ahatem925@gmail.com` with the subject line `[QTranslate Security]`

---

## What to Include

A good report helps us fix the issue faster. Please include:

- A description of the vulnerability and its potential impact
- Steps to reproduce it
- The QTranslate version you are using
- Your operating system and Java version
- Any relevant logs, screenshots, or proof-of-concept code

---

## What to Expect

- **Acknowledgement** within 3 days
- **Status update** within 7 days — confirmed, needs more info, or not a vulnerability
- **Fix** as soon as reasonably possible depending on severity
- **Credit** in the release notes if you want it

We follow responsible disclosure — we ask that you give us reasonable time to fix the issue before making it public.

---

## Scope

### In scope
- Vulnerabilities in QTranslate itself (the app, the plugin system, the API)
- Issues that could allow a malicious plugin to escape its sandbox
- Credential or API key exposure

### Out of scope
- Vulnerabilities in third-party plugins not maintained in this repository
- Issues that require physical access to the machine
- Social engineering attacks
- Theoretical vulnerabilities with no practical exploitation path

---

## Plugin security note

QTranslate plugins run inside the JVM with access to the local filesystem and network. **Only install plugins from sources you trust.** The plugin verification system (JAR hash checking) protects against silent plugin replacement, but it is not a substitute for trusting the source.