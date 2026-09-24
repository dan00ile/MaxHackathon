# MaxHackathon — agent instructions

<!-- TODO: project context to be added here by the user (goals, stack, constraints). -->

## Bundled skills

This repo ships local copies of two skill families under `.claude/skills/` (and
three subagents under `.claude/agents/`) so a cloud Claude Code agent has them
available even without the `caveman` / `ponytail` marketplace plugins installed.

### ponytail — anti-over-engineering discipline

Use on **every** coding task: writing, adding, refactoring, fixing, or
reviewing code, and whenever picking a library or dependency. Default mindset:
question whether the task needs new code at all (YAGNI), prefer stdlib /
native platform features over dependencies, prefer the shortest correct
solution over a general one.

- `ponytail` — the core mode, apply by default to implementation work.
- `ponytail-review` — review a diff/PR for over-engineering only (not correctness).
- `ponytail-audit` — whole-repo sweep for bloat/dead flexibility to delete.
- `ponytail-debt` — harvest `ponytail:` shortcut comments into a debt ledger.
- `ponytail-gain` / `ponytail-help` — informational, invoke only if asked.

Do not invoke ponytail for non-coding requests (prose, translation, general Q&A).

### caveman — ultra-compressed communication

Use when the user explicitly asks for terse/compressed output ("caveman
mode", "less tokens", "be brief", `/caveman`), or when token efficiency is
clearly the priority. Keeps full technical accuracy, drops filler.

- `caveman` — core terse-response mode (levels: lite / full / ultra / wenyan-*).
- `caveman-commit` — compressed commit messages.
- `caveman-review` — compressed PR review comments.
- `caveman-help` / `caveman-stats` — informational, invoke only if asked.
- `compress` — compress a memory/instructions file (e.g. this file) into caveman format.

### cavecrew — compressed-output subagents for delegation

Use to delegate bounded work when you want the returned tool-result to cost
much less main-context budget than the vanilla equivalent:

- `cavecrew-investigator` — read-only "where is X defined / what calls Y" lookups.
- `cavecrew-builder` — surgical 1-2 file edits (typo fixes, single-function rewrites).
- `cavecrew-reviewer` — one-line-per-finding diff/branch review.

See `.claude/skills/cavecrew/SKILL.md` for the full decision table (when to
use cavecrew vs. vanilla `Explore`/`Plan`/review). Do not use `cavecrew-builder`
for new features, new files, or cross-file refactors — it hard-refuses 3+ file scope.

## Default behavior

- Apply `ponytail` discipline to all code you write in this repo unless the
  user says otherwise.
- Do not switch to caveman-style responses unless the user asks for it.
- Prefer `cavecrew-*` subagents over vanilla exploration/edit/review agents
  when the task fits their bounded scope (see table above), to conserve
  context on long-running cloud sessions.
