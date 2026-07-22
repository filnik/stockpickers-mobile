# Google Stitch — UI design via REST API

Stitch generates and iterates screen mockups from natural-language prompts. It is
used here to explore visual directions **before** committing them to Compose or
SwiftUI, which is much cheaper than discovering a layout doesn't work after it's
built twice.

> The Stitch MCP plugin does not work with Claude Code (OAuth DCR bug). Call the
> REST API directly with `curl`.

## Configuration

Identifiers live in `docs/stitch.env`, which is git-ignored. Copy the template and
fill in your own:

```bash
cp docs/stitch.env.example docs/stitch.env
```

```bash
set -a; . docs/stitch.env; set +a          # STITCH_QUOTA_PROJECT, STITCH_PROJECT_ID
export PATH=$PATH:/opt/homebrew/bin
TOKEN=$(gcloud auth application-default print-access-token 2>/dev/null)
```

Tokens last about an hour. When one expires:

```bash
gcloud auth application-default login
gcloud auth application-default set-quota-project "$STITCH_QUOTA_PROJECT"
```

## Endpoint

Everything is JSON-RPC 2.0 against a single endpoint, with `method: "tools/call"`:

```
POST https://stitch.googleapis.com/mcp
Authorization: Bearer $TOKEN
Content-Type: application/json
x-goog-user-project: $STITCH_QUOTA_PROJECT
```

## Tools

| Tool | What it does |
|------|--------------|
| `list_projects` | list your projects |
| `create_project` | create a project (a container of screens) |
| `get_project` | project details + screen instances |
| `list_screens` | list a project's screens |
| `get_screen` | one screen's details, including its rendered image |
| `generate_screen_from_text` | generate a screen from a prompt |
| `edit_screens` | modify existing screens with a prompt |
| `generate_variants` | derive variations of existing screens |
| `create_design_system` | define colors, fonts, shapes, appearance |
| `update_design_system` / `apply_design_system` | modify / apply a design system |

## Calling shape

```bash
curl -s -X POST "https://stitch.googleapis.com/mcp" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "x-goog-user-project: $STITCH_QUOTA_PROJECT" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
       "params":{"name":"TOOL","arguments":{...}}}'
```

`generate_screen_from_text` takes `projectId`, `prompt`, and `deviceType`
(`MOBILE` | `DESKTOP` | `TABLET` | `AGNOSTIC`) — **`MOBILE`** for this app.

`edit_screens` takes `projectId`, `selectedScreenIds` (array), and `prompt`.

## Reading a response

Responses are JSON-RPC wrapped and the payload is a **JSON string that must be
parsed again**:

```python
import json
data = json.loads(response_text)
content = json.loads(data["result"]["content"][0]["text"])
```

## Practical notes

- **Generation takes 1–2 minutes per screen.** Don't retry — wait.
- Iterate with `edit_screens`, which keeps context; regenerating starts from scratch.
- Specific prompts beat vague ones: name the sections, the hierarchy, the exact colors.
- `generate_variants` is the cheap way to explore alternative directions side by side.
