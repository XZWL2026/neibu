---
name: YOMIO 管理后台
description: Navy navigation and light operational workspaces with indigo commands.
colors:
  accent: "#4f46e5"
  accent-hover: "#4338ca"
  accent-pressed: "#3730a3"
  accent-soft: "#eeedfc"
  accent-soft-line: "#d4d0fa"
  focus-soft: "#a5a0ef"
  panel: "#fff"
  surface: "#f6f7fa"
  surface-inset: "#f8f9fc"
  ink: "#202d43"
  muted: "#657187"
  line: "#e3e7ee"
  line-subtle: "#edf0f4"
  control-ink: "#465269"
  control-hover-line: "#bdc5d2"
  field-placeholder: "#6b7589"
  table-heading: "#596579"
  table-hover: "#f8f8fe"
  nav-ground: "#182231"
  nav-ink: "#c1ccdc"
  nav-label: "#9baac0"
  nav-hover: "#263246"
  nav-current: "#37345c"
  nav-indicator: "#c1baff"
  nav-focus: "#b6afff"
  nav-divider: "#344054"
  success: "#15805e"
  success-soft: "#edf8f2"
  error: "#c43d52"
  error-soft: "#fff0f3"
  warning: "#956014"
  warning-soft: "#fcf4e8"
  category-purple: "#7651a3"
  category-purple-soft: "#f4effa"
typography:
  title:
    fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
    fontSize: "28px"
    fontWeight: 650
    lineHeight: 1.4
    letterSpacing: "-.025em"
  title-mobile:
    fontSize: "24px"
    fontWeight: 650
    lineHeight: 1.4
  section:
    fontSize: "16px"
    fontWeight: 600
    lineHeight: 1.5
  body:
    fontFamily: '"Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
    fontSize: "14px"
    lineHeight: 1.6
  description:
    fontSize: "13px"
    lineHeight: 1.6
  label:
    fontSize: "12px"
    fontWeight: 500
    lineHeight: 1.5
  metadata:
    fontSize: "11px"
  metric:
    fontSize: "28px"
    fontWeight: 650
    lineHeight: 1.35
    letterSpacing: "-.025em"
  code:
    fontFamily: "ui-monospace, SFMono-Regular, Consolas, monospace"
    fontSize: "11px"
    lineHeight: 1.7
rounded:
  tag: "5px"
  control: "7px"
  inset: "8px"
  avatar: "10px"
  panel: "12px"
spacing:
  compact: "8px"
  field: "14px"
  mobile-inline: "16px"
  mobile-block: "18px"
  group: "20px"
  panel: "24px"
  workspace: "32px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.panel}"
    rounded: "{rounded.control}"
    padding: "7px 13px"
    height: "38px"
  button-primary-hover:
    backgroundColor: "{colors.accent-hover}"
    textColor: "{colors.panel}"
  button-secondary:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.control-ink}"
    rounded: "{rounded.control}"
    padding: "7px 13px"
    height: "36px"
  button-text:
    textColor: "{colors.accent}"
    padding: "3px 6px"
    height: "28px"
  input:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.ink}"
    rounded: "{rounded.control}"
    padding: "8px 11px"
    height: "38px"
  navigation-current:
    backgroundColor: "{colors.nav-current}"
    textColor: "{colors.panel}"
    rounded: "{rounded.control}"
    padding: "9px 12px"
    height: "40px"
  filter-selected:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.accent}"
    rounded: "{rounded.control}"
  tag-success:
    backgroundColor: "{colors.success-soft}"
    textColor: "{colors.success}"
    rounded: "{rounded.tag}"
    padding: "3px 8px"
  panel:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.ink}"
    rounded: "{rounded.panel}"
    padding: "24px"
  ledger-band:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.ink}"
    rounded: "{rounded.panel}"
---

# Design System: YOMIO 管理后台

## Overview

**Creative North Star: "YOMIO Operational Map"**

YOMIO is a restrained workspace for extended daily administration. A navy navigation spine keeps the operational map stable while pale working surfaces organize dense records, totals and deliberate commands. Indigo connects actions and selection across directories, detail drawers and configuration forms.

The system is code-led and data-first. Joined ledger bands and continuous filter-to-record surfaces create hierarchy through grouping rather than ornament. Naive UI supplies interactive primitives, Lucide supplies consistent outline icons, and ECharts supplies genuine chart rendering. Loading, unknown, empty and failure states keep their business meaning.

**Key Characteristics:**

- Navy navigation, cool workspace, white working surfaces and indigo commands.
- Joined totals and continuous filter-to-record containers.
- Compact Chinese interface typography with tabular operational numbers.
- Grouped navigation, focused drawers and local mobile table scrolling.
- Accessible control states and honest data states.

Source authority: `src/design-system.css`, `src/admin-business.css`, the Naive UI theme in `src/App.vue`, `src/pageMotion.ts`, `src/pages/AdminLogin.vue`, `src/provider-logs.css` and `src/PromoterIcon.vue`. `src/main.ts` imports base styles before shared foundations and business styles; `#app .admin-frame` specificity keeps shared values effective when legacy route CSS loads lazily. Unreferenced legacy colors are not system tokens.

The user authorized replacement of the former all-light blue identity. This document records the implemented replacement. No shipping raster assets were created. The sidecar's synthesized OKLCH ramps are preview metadata, not extra implementation tokens; component specimens illustrate implemented visual patterns without business actions.

## Colors

Cool neutrals support long record-reading sessions; the dark navigation ground separates the persistent map from the active workspace.

### Primary

- **Operational Indigo** (`accent`): primary commands, links, selected controls and focus. `accent-hover` is native primary hover and Naive UI hover; `accent-pressed` is the Naive UI pressed state.
- **Indigo Wash** (`accent-soft`, `accent-soft-line`): selected filters, account avatars and mobile current navigation. `focus-soft` provides native-field focus and Naive UI field hover.

### Neutral

- **Navy Navigation** (`nav-ground`): desktop rail and login context. `nav-current` is the subdued indigo current-link ground; `nav-hover` is the neutral hover ground. `nav-ink`, `nav-label`, `nav-indicator`, `nav-focus` and `nav-divider` preserve readable rail hierarchy.
- **Cool Workspace** (`surface`), **White Working Surface** (`panel`) and **Inset Surface** (`surface-inset`): workspace, records/form panels and inset headers or previews.
- **Slate Ink** (`ink`) and **Muted Slate** (`muted`): records, headings and secondary context. Enabled Naive UI input/select placeholders use `muted`; native placeholders use `field-placeholder`.
- **Quiet Boundary** (`line`, `line-subtle`): outer panels/fields and inner record dividers. `control-ink`, `control-hover-line`, `table-heading` and `table-hover` define secondary control and table states.

Status pairs remain semantic: `success` / `success-soft`, `error` / `error-soft`, and `warning` / `warning-soft`. `category-purple` / `category-purple-soft` identify categories rather than commands. Text accompanies status color.

**The One Command Color Rule.** Use shared indigo for commands and selection; preserve status colors for their actual meaning.

## Typography

**Interface Font:** Segoe UI with PingFang SC, Microsoft YaHei and sans-serif fallbacks. The same system stack deliberately serves headings and Chinese operational records; there is no separate display-face system.

**Code Font:** `code`, only for provider payload/code data.

### Hierarchy

- **Page title:** `title`, reduced to `title-mobile` at the mobile breakpoint. Titles use tight tracking and medium-heavy weight without eyebrows.
- **Section title:** `section`; details and specialized editors retain their local hierarchy.
- **Body:** `body`; descriptions, fields and common table cells use `description` size. Table text remains (13px), with metadata at `metadata` size.
- **Label:** `label` for totals and compact form context; navigation and filter metadata may use (11px).
- **Metric:** `metric` with tabular numerals. Shared main totals reduce to (25px) at the intermediate breakpoint and (24px) on mobile; long values wrap.
- **Provider code:** `code`; preformatted payloads wrap and scroll within their container.

Login has a local context headline (40px), becoming (44px) above its wide breakpoint and (32px) at its narrower desktop breakpoint. Its form title is (28px), reducing to (26px) on mobile. These are login-specific, not the shared page-title scale.

**The Legible Number Rule.** Use tabular numerals for operational values; preserve exact meaning when values wrap.

## Layout

Desktop uses a sticky full-height navigation rail (232px), compacting to (76px), and a sticky white header (68px). The flexible content column has a maximum workspace width (1720px). Default content padding is (32px 32px 24px), with shared page gaps (24px). At widths up to (1200px), the expanded rail becomes (212px), content padding becomes (28px 24px), and header padding becomes (24px). From (1800px), content horizontal padding becomes (48px).

At widths up to (760px), navigation moves into a left drawer (280px), the header becomes (60px), and content padding becomes (24px 16px 20px). Shared page gaps become (20px). Totals become two columns; native form grids collapse to one column. Filters wrap, and table/tab overflow stays local. API quota presets use two columns; amount entry, presets and preview stack. Native coarse-pointer controls have a minimum height (42px).

Joined ledger cells use (23px 24px) padding and minimum height (136px); mobile cells use (18px 16px) and (125px). Shared panels use (24px) padding, mobile (18px 16px). Filter bars use (20px 24px); records headers and pagination use (18px 24px). These are component roles, not one universal padding.

Login uses a navy context and white form column; its form is capped at (360px). At widths up to (660px), the context reduces to a brand strip and the form occupies one column. Login-specific breakpoints also occur at (900px) and (1600px).

User records now open with a compact, expandable statistics summary and a search row. Advanced filters are collapsed until requested; active conditions remain indicated on the disclosure. Consumption analysis is opt-in above the records. Special-agent and special-customer rule tables stack at full width. Audit filters use shrinkable columns and keep horizontal overflow inside the table.

**The Stable Workspace Rule.** Keep identity and grouped navigation steady while records and filters adapt inside the content column.

## Elevation & Depth

Working surfaces are flat and bordered. Tonal headers, inset previews and joined dividers convey depth without panel shadows. Native dialogs use `0 18px 60px #18223126` and unblurred backdrop `#18223166`. Column-settings popovers use `0 10px 30px #1822311a`. Naive UI overlays retain their library behavior.

**The Flat Working Surface Rule.** Use boundaries and tonal grouping for working panels; reserve lifted depth for overlays.

## Shapes

Panels and native dialogs use `panel` corners; controls use `control`, status tags use `tag`, inset detail groups use `inset`, and the header account avatar uses `avatar`. Joined cells stay square within the rounded outer boundary. Detail drawers have rounded leading desktop corners and square mobile edges. Borders are thin (1px). Lucide icons use the shared adapter (18px, stroke width 1.75); header icons range from (14px) to (20px) by role. Brand marks also use Lucide outlines.

## Components

### Buttons

Compact, deliberate commands. Native primary actions use indigo and white text, weight (550), `button-primary` padding and minimum height; secondary actions use white, a quiet border and weight (500). Native hover matches `accent-hover`; Naive UI uses theme hover and pressed states. Disabled native buttons use opacity (.48). Text actions use indigo with compact padding. Keyboard focus uses an indigo outline (2px), offset (3px).

Status changes that disable users require a focused native confirmation dialog listing the selected names and IDs, with cancellation focused initially. Results report successful and unfinished operations separately. Single-specification price saves merge only that dimension into the server catalog and preserve other unsaved drafts and deletions. Login accounts default to a normal administrator and remain discoverable through role filtering.

### Chips

Textual semantic markers with `tag` corners and `tag-success` padding. Apply matching foreground/background pairs. Selected native filters use indigo wash and its selected border while retaining `aria-pressed`. API creation's selected price segment uses solid indigo and white text, with visible focus-within.

### Cards / Containers

White, flat, bordered working panels. Filters and records share one continuous container; an internal divider establishes sequence. Totals share one joined ledger band with thin dividers and no decorative metric icons. Use component-specific padding from Layout.

### Inputs / Fields

White fields with slate ink, quiet boundaries and control corners. Native fields use `input` padding and (13px) text; focus uses an outline (2px) in `focus-soft`, offset (1px), and an indigo border. Hover uses `control-hover-line`. Readonly/disabled fields use inset surface and muted text. Enabled Naive UI input/select placeholders use `muted` through the theme; native placeholders use their recorded token. Searchable selectors retain explicit labels, keyboard combobox behavior, loading, empty and remote load-more states.

### Navigation

Navy rail links use (13px) text, weight (400), minimum height (40px), and (9px 12px) padding. Hover uses `nav-hover`; current uses `nav-current`, white text, weight (550) and a pale indigo indicator. Focus uses `nav-focus` with offset (-2px). Collapsed links preserve accessible names. The mobile drawer uses white, slate links, minimum height (44px) and indigo wash for current navigation.

Workspace changes focus main content and close mobile navigation. Incoming content fades from (.24) opacity and moves vertically (14px) in sidebar order over (260ms), using `cubic-bezier(0.16, 1, 0.3, 1)`; prior motion is canceled. The sidebar and header stay steady, and filters, pagination and detail navigation within a workspace do not replay the transition. Reduced motion removes translation and uses an (80ms) opacity transition. Global reduced-motion rules shorten CSS animation and transition durations to (.01ms).

### Data Tables and Finance Trend

Record lists default to ten records per page, including detail history and daily finance lists. Existing page-size selectors retain their larger options. Collection endpoints paginate locally after filtering; chart aggregation and exports still read their full required datasets.

Inset headers, thin row dividers, tabular values and local horizontal scrolling organize records. Paginated tables and generation-log lists expand to display every record on the current page; the document handles vertical scrolling. User-directory rows render without fixed-height virtualization. Skeleton rows reserve initial loading space. Native cells use (14px 16px) padding; Naive directory cells use (14px 12px). Hover uses the table-hover surface. Finance trends retain loading, retryable failure, honest empty state, genuine chart canvas and an expandable daily-value table. Unknown values stay unknown and create chart gaps. Decimal strings and BigInt remain authoritative for display and business calculations; Number conversion is only for plotting.

## Do's and Don'ts

### Do:

- **Do** reuse the navy rail, indigo commands, shared title scale and flat white working surfaces.
- **Do** join related totals and keep filters with their records.
- **Do** preserve visible focus, accessible names and textual status alongside color.
- **Do** keep mobile overflow local and use drawer navigation.
- **Do** use Lucide outline icons and the intentional interface font stack.
- **Do** preserve unknown, loading, empty and retry states and server-confirmed business authority.

### Don't:

- **Don't** reintroduce the superseded bright-blue command palette from lazy legacy declarations.
- **Don't** add title eyebrows, decorative metric icons or glyph substitutes for icons.
- **Don't** add working-panel shadows or imagery that competes with records.
- **Don't** use the provider code face for ordinary interface headings or labels.
- **Don't** turn unknown amounts into zero or use plotted numbers for financial operations.
