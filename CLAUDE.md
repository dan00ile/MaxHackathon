# MaxHackathon — agent instructions

## Project context

Хакатон-проект: бот в MAX для приёмки актов работ по содержанию/ремонту МКД
(приказ Минстроя № 318/пр). Председатель совета МКД получает акт, собирает
замечания жителей, LLM формализует их в юридический черновик, председатель
подписывает акт (Госключ) или формирует мотивированный отказ — всё в срок
10/30 дней, без юриста.

**Дедлайн MVP: 30 сентября 2026.** Стек зафиксирован: бэкенд Kotlin (Ktor),
фронтенд — мини-приложение MAX, хостится на GitHub Pages.

Перед началом любой задачи по продукту читай:

- [`docs/build-brief.md`](docs/build-brief.md) — стек, архитектура,
  интеграция с MAX Bot API/Mini App/initData, структура Kotlin-бэкенда. Читай
  первым, если задача — писать код.
- [`docs/requirements.md`](docs/requirements.md) — актуальные системные
  требования: роли, MoSCoW, функциональные/нефункциональные требования,
  машина состояний акта, модель данных, интеграции. Это первичный источник
  правды для того, что нужно реализовать.
- [`docs/tasks.md`](docs/tasks.md) — снэпшот канбан-доски (модули, статусы,
  зависимости задач); актуальный статус — в `docs/original/kanban-tasks.xlsx`.
- [`docs/idea.md`](docs/idea.md) и [`docs/pitch-analysis.md`](docs/pitch-analysis.md) —
  зачем сделаны те или иные развилки (Госключ вместо своей подписи,
  обязательное фотоподтверждение, чек-лист вместо свободного текста и т.д.) и
  как продукт защищается перед жюри. Читай, если решение кажется избыточным —
  скорее всего у него есть нормативное или продуктовое обоснование здесь.
- [`docs/requirements-changelog.md`](docs/requirements-changelog.md) — история
  правок требований; полезно, если формулировка в другом документе (например,
  в `docs/original/system-requirements-v1.pdf`) расходится с requirements.md —
  requirements.md всегда приоритетнее.

<!-- TODO: стек, репозиторий/CI, среда запуска бота — добавить по мере появления кода. -->

## Git workflow

- **Автор коммитов — пользователь**, не AI-сервис: `dan00ile
  <nikelodeon53@gmail.com>` (уже стоит в `git config` этого репозитория, не
  переопределять). Коммиты должны выглядеть как обычная работа пользователя.
- **Без строки `Co-Authored-By: Claude ...`** в коммитах этого репозитория —
  это осознанный оverride дефолтной конвенции атрибуции Claude Code именно
  для `MaxHackathon`, по прямому указанию пользователя.
- **Работать по веткам**, не коммитить в `master` напрямую для сколь-либо
  значимой единицы работы (фича, документ, рефакторинг) — одна ветка на
  логическую единицу работы, мержить в `master`, когда она закончена и
  проверена. Мелкие точечные правки поверх уже запушенного в текущей ветке —
  можно без новой ветки.
- **Формат сообщений — Conventional Commits, описание короткое и на
  русском:** `type(scope): краткое описание`. Типы — стандартные английские
  ключевые слова (`feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `ci`),
  сам текст — по-русски. Примеры: `docs: добавить бриф хакатона`,
  `feat(bot): загрузка акта председателем`, `fix(timers): не терять 30-й
  день при обрыве связи`.
- Коммиты и пуши в свою рабочую ветку — обычная часть работы, без запроса
  подтверждения на каждый раз. Мерж ветки в `master` (или PR на неё) —
  отдельное решение, подтверждать с пользователем, если явно не оговорено
  иное на сессию.

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

### caveman + ponytail together

The two compose freely, no overlap: caveman shrinks what you *say*, ponytail
shrinks what you *build*. Caveman leaves code byte-for-byte exact; ponytail
never touches prose style. Default posture in this repo: ponytail always on
for code, caveman only when the user asks — running both at once just means
terse talk about minimal code.

### Slash commands

Every bundled skill is also wired as a project slash command under
`.claude/commands/` (mirrors the source plugins' command set): `/caveman
[level]`, `/caveman-commit`, `/caveman-review`, `/ponytail [level]`,
`/ponytail-review`, `/ponytail-audit`, `/ponytail-debt`, `/ponytail-gain`,
`/ponytail-help`. Skills without a matching command (`caveman-help`,
`caveman-stats`, `compress`, `cavecrew-*`) are invoked by description match or
by name, not a dedicated slash command — same as upstream.

## Default behavior

- Apply `ponytail` discipline to all code you write in this repo unless the
  user says otherwise.
- Do not switch to caveman-style responses unless the user asks for it.
- Prefer `cavecrew-*` subagents over vanilla exploration/edit/review agents
  when the task fits their bounded scope (see table above), to conserve
  context on long-running cloud sessions.
