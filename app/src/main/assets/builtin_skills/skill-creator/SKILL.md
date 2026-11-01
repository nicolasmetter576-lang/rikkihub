---
name: skill-creator
description: >-
  Create or improve reusable Agent Skills. Use when the user wants to turn a workflow,
  prompt, or set of instructions into a skill, or update a user skill.
---

# Skill Creator

Adapted for this fork from rikkahub/rikkahub's built-in Skill Creator, revision
2267943a (app/src/main/assets/builtin_skills/skill-creator/SKILL.md).
Copyright belongs to the upstream contributors; licensed under GNU AGPL-3.0,
as is this repository. The tool and exposure guidance below is fork-specific.

A skill is a directory with `SKILL.md` (YAML frontmatter and Markdown instructions)
and optional supporting files. Only enabled skills' names and descriptions are
included before loading. Load instructions with the available skill-reading tool
(normally `use_skill`; tool names may be customized), then load supporting text as
needed. Creating or importing a skill never enables it automatically.

## Storage and available capabilities

- User skills live in `/skills/<directory-name>/`. When mounted, this is writable.
- Packaged skills live separately in `/builtin_skills/<directory-name>/`. Treat
  them as read-only: copy to user storage before customizing. App management
  refuses packaged edits/deletions, but this is not an OS or PRoot restriction;
  shell writes could modify the bound source. Do not do that.
- User metadata takes precedence by frontmatter `name`, even if directory names
  differ. There is no combined or symlink-farm directory. Inspect both trees when
  they are exposed, and inspect a prospective user override before replacing it.
- These paths are only available if the workspace exposes skills. The workspace
  reminder and skill metadata locations describe actual exposure. A skill can be
  read through the skill tool even when these trees are not mounted.
- Use only tools actually offered in this conversation, under their offered names.
  Normal mode may offer `workspace_read_file`, `workspace_write_file`,
  `workspace_edit_file`, and `workspace_shell`. Minimal harness may instead offer
  persistent `bash` and `str_replace_editor`. Any of these may be disabled or
  renamed. File tools require rootfs absolute paths for skill storage, not paths
  relative to `/workspace`. Never use an Android host path.
- If there is no writable skill mount or no enabled way to write to it, provide
  the complete `SKILL.md` for manual addition on the Agent Skills page instead.
  The App supports manual Markdown, file/ZIP import, and GitHub import; do not
  invent a Web skills editor, install endpoint, or automatic enable API.

## Workflow

1. Understand the intended use cases, trigger phrases, and good output. Extract
   the workflow from the conversation when possible; ask only about missing facts.
2. Plan a concise `SKILL.md`, reference text, templates, and optional scripts.
3. Check for existing user and packaged skills if their mounts are available.
   Read an existing user skill and obtain permission before replacing its files.
   Preserve unrelated files when updating rather than blindly replacing a tree.
4. Write to user storage using an available file tool or shell. If unavailable,
   return the complete Markdown for the user to add manually.
5. Validate the frontmatter, links, and scripts against the checklist below. Do
   not claim scripts ran when shell access, interpreters, or dependencies are absent.
6. Report actual files written (or clearly say they were only provided as text).
   Remind the user to enable the skill for their assistant in the App's extensions
   Skills tab or assistant extension settings, and suggest a prompt to try.

## Format

```markdown
---
name: my-skill
description: What the skill does and when it should be used.
compatibility: Requires a workspace with python3
---

# My Skill

Instructions...
```

- Put `---` on the first line and close the frontmatter with `---`.
- `name` and `description` must be nonempty strings. Prefer a lowercase
  letters/digits/hyphens name of at most 64 characters and the same directory
  name. Existing skills may have different directory and metadata names: use
  the actual location provided by the skill catalog, not a guessed name path.
- Keep the description within 1024 characters (the prompt truncates it at that
  limit). Include both purpose and triggering use cases, in third person.
- `compatibility` is optional; declare actual environment requirements.
- Quote special YAML values, especially values containing `: ` or `#`, or use
  a folded `>-` block for a long description.
- Write imperative, focused instructions. Keep the body below about 500 lines
  and move detailed knowledge to supporting reference files.

## Supporting files

Use `references/` for detailed text, `scripts/` for deterministic helpers, and
`assets/` for templates or binary inputs. Link reference text from `SKILL.md`
using relative Markdown links such as `[Reference](references/api.md)` and say
when to load it. Do not guess file paths. The skill tool reads text; use binary
assets only through an exposed workspace with suitable tools.

Scripts require a workspace, an enabled shell, and installed interpreters.
Check requirements with available shell tools (for example `command -v python3`),
use a shebang, make the user script executable, and document arguments/output.
Use the actual user skill directory in script paths. Rootfs contents are chosen
by the user; never assume Python, a package manager, or network access exists.
Do not add a README or changelog unless it genuinely helps execute the skill.

## Validation checklist

- [ ] Frontmatter starts on line one, closes correctly, and has nonempty name/description.
- [ ] Description states what the skill does and when to use it, within 1024 characters.
- [ ] The metadata name and physical directory are intentional; no existing user work was overwritten without permission.
- [ ] Every linked supporting file exists at the stated relative path.
- [ ] Script requirements and executable permissions were checked; any untested scripts are explicitly identified.
- [ ] All modifications are in user storage, not in the packaged tree.
- [ ] The user was reminded to enable the skill; no assistant setting was changed automatically.
