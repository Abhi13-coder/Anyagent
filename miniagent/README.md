# MiniAgent

Native Android coding agent. Pure Kotlin + OkHttp for everything except the
one place a real Linux environment is unavoidable (shell execution) — that
part uses a bundled static `proot` + Alpine, which is genuinely 32-bit-armv7
compatible (unlike the arm64-only Claude Code/Codex binaries that started
this whole conversation).

## v0.3 - what's here

**From v0.2:** provider/model manager (add/edit/delete/copy/paste/active-toggle),
multi-model runs, OpenAI-compatible client (OpenRouter + NIM shape), HTML
screenshot preview via an off-screen WebView, `open_in_browser`.

**New:**
- **`run_shell` tool** — runs commands inside a sandboxed Alpine armv7
  rootfs via a bundled static `proot` binary. `proot -0` fakes root **inside
  that sandbox only** — this does not root your device, need bootloader
  unlock, or touch anything outside the rootfs plus the explicit `/dev`,
  `/proc`, `/sys` binds. Real device root is a separate, much bigger thing
  and isn't what this does.
- **Risk-gated confirmation** — most shell commands just run. A command
  matching a destructive-pattern list (`rm -rf`, `mkfs`, `dd`, `chmod 777 /`,
  `curl | sh`, `reboot`, etc. — see `ShellEnvironment.RISKY_PATTERNS`) pops a
  blocking confirm dialog first. This is the "only ask when it matters"
  behavior you asked for, not per-tool-call confirmation.
- **Conversation continuity** — check "Continue previous conversation" and
  your next task box entry is appended to the *same* message history per
  provider, so if the model asks a clarifying question, you can actually
  answer it with context instead of starting over. (In-memory only — killing
  the app clears it; see limits.)
- **Setup button** — one-time download+extract of the Alpine armv7
  minirootfs (~3MB compressed) into app-private storage.

## How the proot binary got in here (technical note, not something you need to redo)

The static `proot` + `loader` binaries are real 32-bit ARM ELF executables,
verified before bundling (`file` reported: `ELF 32-bit LSB executable, ARM,
EABI5, statically linked`), sourced from
`github.com/ZhymabekRoman/proot-static`. They're placed at
`app/src/main/jniLibs/armeabi-v7a/libproot.so` and `libprootloader.so` (also
duplicated under `arm64-v8a/` for portability). This naming is deliberate:
Android 10+ blocks executing arbitrary files copied into app-private storage
at runtime (W^X policy), but files extracted from the APK's native-library
directory at *install* time are exempt — which only happens for files
matching `lib*.so` inside `jniLibs/<abi>/`. `extractNativeLibs="true"` in
the manifest and `jniLibs.useLegacyPackaging = true` in the Gradle config
are both required for this to actually extract them (rather than leaving
them zip-aligned and unexecutable inside the APK). This is the whole trick
that makes shell execution possible without Termux at all.

## Known limits / honest gaps

- **Symlinks are skipped during Alpine extraction** — Java has no portable
  symlink API here, so any busybox applet or Alpine package relying on a
  symlink may be missing. If `run_shell` complains a command isn't found
  that should be there, this is the likely cause. Fixable by shelling a
  `tar` binary for symlink handling once one exists inside the rootfs
  (chicken-and-egg on first extraction, solvable by extracting the base
  layer with Java then re-running a proper `tar -xf` from inside proot for
  anything added afterward, e.g. `apk add`).
- **Conversations aren't persisted to disk** — closing the app loses them.
- **No MCP support yet** — `run_shell` gets you a real environment to
  install/run things by hand (`apk add python3`, `pip install ...`, etc.),
  but there's no MCP server wiring into the tool-call loop itself. That's a
  distinct next piece: an MCP tool would need to spawn a process inside the
  rootfs and speak its JSON-RPC-over-stdio protocol, translating between
  that and the OpenAI tool-call shape already in use.
- **This is unverified by an actual build** — I don't have an Android
  toolchain to compile and run this. The architecture (jniLibs W^X trick,
  proot bind mounts, OpenAI tool-call shape) is real and documented, but
  first-run debugging of Gradle/manifest/permission edge cases is still
  likely. Report back what breaks and it's fixable from there.

## Get the APK

Same as before: push to GitHub, Actions tab builds it, download the
`miniagent-debug-apk` artifact, install.

## Setting up OpenRouter / NIM entries

**OpenRouter** — Base URL: `https://openrouter.ai/api/v1/chat/completions`,
Model: e.g. `moonshotai/kimi-k2` or any slug from openrouter.ai/models

**NVIDIA NIM** — Base URL: `https://integrate.api.nvidia.com/v1/chat/completions`,
Model: e.g. `mistralai/mistral-nemotron`
