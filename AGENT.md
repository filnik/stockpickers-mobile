# Agent Configuration

## Project guidelines & context

For architecture, code style, testing workflow and the KMP/iOS gotchas that cost a
build or a device-only crash, **you must read and follow**:

- [CLAUDE.md](CLAUDE.md) — and the `docs/` guides it maps.

## Command permissions & safety

This repository ships a permissions deny-list. **You are bound by it**, whatever
your own defaults say:

- [.claude/settings.json](.claude/settings.json)

It exists because there is no CI here: an unwanted commit, push or reset is not
caught by anything downstream. Treat the `deny` list as non-negotiable.
