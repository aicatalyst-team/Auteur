# Security Policy

## Supported Versions

Auteur is at an early stage; security fixes only target the latest release on `main`.

| Version | Supported          |
| ------- | ------------------ |
| `main`  | ✅                 |
| < 0.1   | ❌                 |

## Reporting a Vulnerability

**Do not open a public issue for security problems.**

Instead, email the maintainer:

📧 **xin.ning [at] bluefocus.com**

Include:
- A short description of the issue
- Steps to reproduce (or a PoC)
- Affected versions / commit hash
- Your contact info (so we can credit you)

We aim to acknowledge within **3 business days** and ship a fix or mitigation within **14 days** for high-severity issues. Lower severity may take longer; we'll keep you posted.

## Scope

In scope:
- Auteur backend (Spring Boot REST API)
- Auteur frontend (Vue 3)
- Auteur browser extension (Chrome MV3)
- Auteur Remotion renderer

Out of scope:
- Vulnerabilities in upstream dependencies (please report to the upstream project)
- Issues in self-hosted LLM gateways (vLLM, etc.) — report to the gateway project
- Issues in Volcano TOS / Doubao / Jamendo APIs — report to those vendors

## Hardening Recommendations for Self-Hosters

- `application-local.yml` is gitignored — **never commit real credentials**.
- Override the default `auteur.extension.token` via env var in production.
- Preset `visibility=private` is a **soft flag**, not real authz. For public deployments, put a real auth layer (Nginx basic auth, OAuth2 proxy) in front of the backend.
- For self-hosted LLM gateway: vLLM + Caddy reverse proxy + IP allow-list is the recommended baseline.
- For commercial LLM gateways (DeepSeek / Anthropic / Zhipu): use HTTPS + key rotation.
- The `app_config` table stores third-party credentials in plaintext. If that's unacceptable for your threat model, run MySQL with at-rest encryption or wrap with HashiCorp Vault.

## Disclosure Policy

We follow **coordinated disclosure**: we'll work with you on a fix timeline before public disclosure. Once a fix is shipped, we'll credit reporters in the changelog (unless you prefer to remain anonymous).
