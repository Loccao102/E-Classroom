# 10. E-Classroom Product Design System

## Direction: calm institutional + editorial data UI

E-Classroom is primarily used in bright classrooms, offices and homes. The default experience therefore favors a light, paper-like environment instead of a dark developer dashboard. The product should feel trustworthy, calm and readable for long sessions while still having enough character to be memorable.

### Visual principles

1. **Institutional, not bureaucratic.** Deep navy anchors authority; cobalt is the action/data accent; tinted neutral surfaces keep long tables comfortable.
2. **Editorial hierarchy.** `Newsreader` is reserved for high-level titles and large numbers. `Manrope` handles navigation, forms and dense data.
3. **Open layout before cards.** Sections, rules and whitespace are preferred to nested rounded containers. A card exists only when a block is genuinely one object/work unit.
4. **Data before decoration.** Reporting surfaces prioritize the metric, its time range and its explanation before visual flourish.
5. **Vietnamese-first copy.** Actions use specific verbs (`Duyệt nghỉ`, `Công bố điểm`, `Xếp lớp`) rather than generic `Submit/OK`.
6. **Motion communicates state.** Entry/reveal motion is subtle and short. There is no bounce/elastic decoration; reduced-motion is respected.

## Tokens

Canonical CSS tokens live in `apps/web/src/styles.css`.

### Core colors

| Role | Token | Use |
| --- | --- | --- |
| Primary ink | `--navy` / `--ink` | headings, navigation, primary text |
| Action/data | `--blue` | primary action, selected state, chart series |
| Positive | `--teal` | healthy/approved/success |
| Attention | `--amber` | needs review, pending, medium risk |
| Critical | `--red` | destructive/error/high risk |
| Surfaces | `--paper`, `--paper-warm`, `--canvas` | hierarchy without nested cards |
| Rules | `--line`, `--line-soft` | grouping and table structure |

Color must never be the only carrier of meaning. Badges and charts always include text/value labels.

## Typography

- Display: `Newsreader`, fallback `Georgia`.
- Product/data: `Manrope`, fallback system UI.
- Hero/report numbers use the display face to create an editorial identity without sacrificing table readability.
- Dense labels use size + weight + case/letter-spacing hierarchy rather than many colors.

## Spacing and shape

- Major page sections use noticeably larger vertical gaps than internal control groups.
- Default product controls use ~40–42px minimum height.
- Rounded rectangles are restrained: inputs/buttons get small radii; dense data layouts prefer square/open separators.
- Avoid nesting `card` inside `card`. Use border rules or a new section instead.

## Product shell

Desktop:

```text
┌──────────────┬────────────────────────────────────────────┐
│ school/role  │ sticky context header                     │
│ navigation   ├────────────────────────────────────────────┤
│              │ page stage                                 │
│ profile      │                                            │
└──────────────┴────────────────────────────────────────────┘
```

Mobile/tablet:

- sticky compact header
- navigation becomes an off-canvas drawer
- Parent/Student pages collapse into single-column summaries
- tables remain keyboard-scrollable regions
- Teacher roll call avoids wide spreadsheet interaction

## Reporting patterns

### Admin command center

Order of information:

1. attendance and normalized score headline
2. headcount context
3. attendance/score trend
4. score distribution + subject performance
5. class drill-down
6. explainable attention list
7. leave/communication operational counts
8. recent absence detail

### Teacher command center

Teacher reporting begins with action, not analytics:

1. today schedule
2. missing attendance / pending leave / draft assessment queue
3. scoped attendance and grading metrics
4. trend and subject performance
5. explainable student-attention list

### Risk language

Never display only `HIGH/MEDIUM/LOW`. The UI shows factors with current value and threshold, e.g.:

```text
Tỷ lệ vắng cần chú ý: 14% · ngưỡng 10%
```

The current engine is deterministic; the UI must not present it as an AI prediction.

## Core components

Shared UI lives in `apps/web/src/components`:

- `Card`: one cohesive work/data object, not general spacing wrapper
- `SectionHeading`: page/section hierarchy with optional contextual actions
- `DataTable`: bounded visible columns, keyboard-scrollable region
- `Modal` + `DialogForm` + `Field`: consistent mutation flows
- `Badge`: semantic state with text
- reporting charts: lightweight responsive SVG/CSS; no chart framework required for V1

## Interaction rules

- No browser `prompt()` or `confirm()` for core workflows.
- No raw UUID is required in normal user flows.
- Mutations expose loading, success and error states.
- Destructive or terminal workflow actions use explicit verbs and context.
- The server remains authoritative for authorization; hiding a UI control is not considered access control.

## Accessibility baseline

- visible `:focus-visible` state on interactive elements
- target dimensions at or above WCAG 2.2 minimums for product controls
- semantic table headers and labelled scroll regions
- form labels are visible rather than placeholder-only
- status/errors use semantic status/alert roles where applicable
- charts include accessible labels and textual values; color is redundant
- `prefers-reduced-motion: reduce` disables nonessential animation

## Anti-patterns intentionally avoided

- generic purple/blue AI gradients and glow-heavy dark SaaS dashboards
- nested cards for every grouping level
- equal padding/spacing everywhere
- huge decorative hero text inside operational screens
- charts without ranges, labels or metric definitions
- raw JSON/UUIDs on user-facing pages
- decorative bounce/elastic motion
- mobile layouts that are simply a squeezed desktop grid

## References used during the redesign

The implementation was informed by the current Anthropic `frontend-design` skill, the Impeccable design critique/refinement system, UI UX Pro Max v2 patterns and WCAG 2.2 interaction guidance. The repository keeps the principles here rather than depending on any one external skill at runtime.
