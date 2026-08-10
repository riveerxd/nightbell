# Design notes

What was read before anything was drawn, what was taken from it, and what was
deliberately not done. Written so the next person to touch this page knows which
decisions were arguments and which were taste.

## What was read

| Source | Read for |
| --- | --- |
| [Web Interface Guidelines](https://interfaces.rauno.me/) ([source](https://github.com/raunofreiberg/interfaces)) | Concrete interface details: focus rings, motion duration, typography mechanics |
| [925 Studios, spotting and fixing generic websites](https://www.925studios.co/blog/ai-slop-web-design-guide) | A named list of the tells a template page gives off, and what to do instead |
| [SaaSFrame, landing page trends 2026](https://www.saasframe.io/blog/10-saas-landing-page-trends-for-2026-with-real-examples) | Editorial composition as the alternative to the hero, pills, three-column pattern |
| [Muzli, accessibility checklist 2026](https://muz.li/blog/how-to-make-your-ui-accessible-a-practical-checklist-for-2026/) and [Atomic a11y, animation](https://www.atomica11y.com/accessible-design/animation/) | Reduced motion, focus visibility, keyboard-only pass |
| [Astro SEO checklist](https://neciudan.dev/astro-seo-checklist-2026) and [Astro SEO guide](https://eastondev.com/blog/en/posts/dev/20251202-astro-seo-complete-guide/) | Canonical discipline, JSON-LD placement, sitemap generation, zero-JS defaults |
| [Bricolage Grotesque](https://ateliertriay.github.io/bricolage/), [font pairing for developer products](https://www.oneminutebranding.com/blog/font-pairing-for-developers) | Whether to bring in a display face, and what the alternatives were |

Everything above is inspiration and evidence. No layout, component or paragraph
was copied from any of them.

## The principles that were actually applied

**Structure carries more than style.** The strongest point in the trends
reading was that a good page reduces risk in the order a reader feels it:
promise, then the product, then the next objection, then the ask. That is the
whole section order here. "Honest Android constraints" is deliberately placed
directly after the loudest section, because the objection a technical reader has
after being shown a full-screen alarm is "what does this not do", and answering
it late reads as hiding it.

**Editorial composition instead of a card wall.** Long column, numbered
sections, hairline rules, figures inline, a rail down the left. Nothing on this
page is a rounded card inside a rounded card. The only large radii belong to the
phone screenshots, because a phone has one.

**Explicit negative constraints.** The list under "What was refused" below was
written before the first line of CSS. Deciding in advance what the page will not
contain removes the defaults that otherwise fill the gaps.

**Motion has to explain something.** From the interface guidelines: keep
interaction motion under about 200 ms, keep animation proportional to what
triggered it, and pause looping animation that is off screen. Nothing on this
page moves decoratively. Every animated thing is replaying a cause and the
effect it had, which is the argument the product is making and the one thing a
still cannot make. What each of them is, and what is left when they are all
switched off, is in "The five instruments" below.

**Focus rings as box shadow, not outline.** Straight out of the interface
guidelines, and it matters here because every button on the page is a pill.

**Font weight never changes on hover**, for the same reason the guidelines give:
it reflows the element under the pointer.

## The typeface decision, and why the trend was refused

The reading was consistent that a distinctive display face is the fastest way
out of the default look, and that Inter is the default. Inter is used here
anyway, and the reasoning is worth writing down because it looks like the wrong
call.

The promo video plays in the hero. It is set in Inter and JetBrains Mono, both
sourced from `promo-video/node_modules/@fontsource-variable`, and the app's own
UI is Inter-adjacent. Putting a fashionable grotesque in the page headline would
mean the first thing a visitor sees is a headline in one voice sitting directly
above a film in another. Agreement with the product beats novelty against the
product.

So distinction is bought elsewhere, where it costs nothing in coherence:

- Inter at weight 800 with `-0.038em` tracking, which is the video's own
  headline setting, not the web default.
- JetBrains Mono uppercase at `0.19em` for every label, count, host, status word
  and section number. That single decision does most of the work: the page reads
  as an instrument rather than a brochure, and it is the video's scene-slug
  treatment carried onto the web.
- A numbered rail, which no template ships with.

The two faces are the latin subsets only, 88 KB together, copied by
`scripts/sync-assets.mjs` and preloaded. Nothing is fetched from a font CDN.

## Colour

Lifted value for value from `app/src/main/kotlin/me/river/nightbell/ui/theme/Theme.kt`
(`NightbellDarkColors`) by way of `promo-video/src/theme.ts`. The rule both of those
files state out loud, and this stylesheet keeps:

> Blue is the app. Green is the thing it measures.

Chrome, rules, the mark, the section numbers and the focus ring are Aqua
`#2F6BFF`. Mint `#2FD98A`, Amber `#FFB020` and Rose `#FF4D57` appear only where
they mean up, degraded and down. There is no decorative use of a health colour
anywhere on the page, which is why the one rose section lands.

**Contrast, measured against `#000000`:** Mint 11.4:1, Amber 11.4:1, Rose 6.5:1,
Sky `#6AA8FF` 8.6:1, secondary text `#D6D6D6` 15.9:1, tertiary `#8A8A8A` 5.3:1.
Aqua measures 4.68:1, which passes for text but is the tightest value on the
page, so it is used for rules, marks and the focus ring rather than for body
copy. Links take Sky.

The primary button is bone `#F2F5F8` on ink, not blue. White on Aqua measures
4.49:1, a hair under AA for body-sized text, and darkening the brand blue to
clear the bar would have meant two blues. A bone pill on black is also simply
the strongest thing on the page, and it leaves blue doing its actual job.

## The composition

One long readout, running from a calm instrument to a red intervention and back
to a calm install section. The spine is a one-pixel rule down the left of every
section that takes the state that section is about, and the masthead reports
which section that is while you read it:

| Section | Spine | The line into it |
| --- | --- | --- |
| 01 Hero | Aqua | |
| 02 Why it exists | Grey. The section is about an alert that failed to matter | Calm, aqua |
| 03 On the device | Aqua | Calm |
| 04 What it watches | Mint. This is the section where the data is healthy | Calm, mint |
| 05 Setup | Aqua | Calm |
| 06 The payoff | Rose, plus the page's only tinted background | The mark, at page width, in rose |
| 07 Honest limits | Amber. Degraded, not broken | Decay, amber |
| 08 Install | Aqua | Calm, aqua |
| 09 Questions | Grey | Calm |

No section other than 06 is allowed a background wash. If every section could
raise its voice, the one that needs to could not. That is the same argument
`healthRim()` makes in the app: outline every card and the broken one stops
standing out.

The one thing under all of them is the field, in `Field.astro`: eight blue
fields a thousand pixels across at 5 to 10 per cent, fixed to the viewport and
drifting about six per cent of it over three and a half minutes. It is the room
the page is in rather than anything a section owns, it is identical under all
nine of them, and it is set below the hero's graticule so the grid stays the
only background element with an edge on it. It is deliberately below the
threshold at which you can point at one of them; what it changes is that black
stops reading as an unpainted surface. See the refused list for where the line
is between this and the thing it is next to.

### The hero says each thing once

The fold used to say three things six times. "Nothing leaves your phone" was in
the eyebrow as "on-device", in the headline as "needs no server", in the lede as
"keeps every token on the device", on two of the three rules, and in the spec
plate as "no account, no telemetry". The alarm was in the headline, the lede, a
rule, the rule's own explanation, and the panel. Every claim restated in a second
register within one viewport is not rigour, it is a first draft nobody trusted.

So each claim has exactly one home. The three rules carry the three claims and
nothing else repeats them. The eyebrow is metadata only: platform, licence,
version. The lede is one sentence, and it earns its place by adding the mental
model the headline cannot state, which is that the phone is the thing doing the
checking. The spec plate lost the two facts that had moved elsewhere and is a row
of two rather than a two by two of four.

The headline went from 62 characters to 45. At 52 px in the column it had, the old
one wrapped to five lines with two of them stubs and put "wakes you up" on line
four, which is the best five words on the page arriving after the reader has
decided. The columns are `1.32fr 0.68fr` rather than `1.02fr 1fr`, because a
demonstration does not need the same width as the only headline, and the headline
is held to 22ch so the rag belongs to the type rather than to the grid.

The download moved above the rules. It was the fifth block down, which put it
under the fold of a 1440 by 900 laptop and off a phone entirely: on a page whose
whole job is handing over a 2 MB file, the button was the one thing you had to
scroll to find. Claim, one line of what it is, the button, then the evidence. It
now clears the fold at 1440 by 789 and on a 360 by 640 phone.

Both columns are aligned to `start`. Centring floated the phone to the optical
middle of whatever the copy happened to measure, so its top edge sat 170 px below
the eyebrow and lined up with nothing, in a hero that draws a graticule behind
itself.

The masthead's section counter is hidden while you are at the top. It reads
"03 · URGENT ALERTS" once you are somewhere, which is worth the space; it read
"00 · TOP" before you had gone anywhere, which meant the first instrument a
stranger met was a counter at zero naming the place they had not left. It keeps
its width, so nothing shifts when the first section fills it.

### Cutting the copy without losing it

Four sections were argued at rather than shown. Section 02 had a paragraph, a
`<details>` holding two more, and a third paragraph introducing its table, all of
it under two cards that demonstrate the same point by moving. Section 07 hid the
six limits the section is named after inside a collapsed accordion. Section 08 spent
three prose cards and two more paragraphs on handing over a 2 MB file, and did not
contain a download button.

Nothing was deleted to achieve any of that. `<details>` content is in the markup
and indexed like any other text, so what came out of the flow went in there rather
than out of the document, and the summary now says what is inside rather than
addressing crawlers out loud, which is what "keep the longer reasoning for crawlers
and patient readers" was doing. Three things moved instead of being cut twice: the
hosted and self-hosted examples went into the table's own column headings, where a
column can name itself; "where Android makes you grant it" became a tag on each
grant rather than dim body copy trailing the sentence; and the six platform limits
came out of the accordion and onto the page beside the screenshot of the four
grants being counted.

### The video needed no introduction

The promo band opened with a mono label reading "The forty second version" and a
sentence listing the four things you were about to watch, over a poster frame with
a play button on it and a chapter row naming all four. Both are gone.

The caption under it was two tracked uppercase spans: "40 SECONDS · CAPTIONS · 6 MB"
and "JUMP TO ALERT FOR THE PART NOTHING ELSE DOES". Mono caps is this page's voice
for units, counts and status words, and neither of those is a unit: one is three
machine facts already on the scrubber's own clock and the CC button, the other is
ad copy telling you which part of the video to like. One sentence, in the text face.

The chapter labels were the cut's internal scene names, Hook / Wedge / Proof / Cta,
which are the right names when you are budgeting scenes and meaningless to somebody
deciding where to skip to. They name what you will see now. Renaming them is an
edit to `CHAPTER_NAMES` in `sync-assets.mjs`, not to `media.json`, because the
chapter table is generated.

### The line between sections is the mark

Sections have no border. What separates them is a stretch of trace at page
width, in the state the page is moving into, so scrolling the document reads as
one continuous strip chart: calm, calm, calm, then one enormous complex where the
service fails, then a decay back down.

The spike is not drawn by eye. It is the six trace points of
`res/drawable/ic_launcher_mark.xml` mapped off the 108 unit icon grid onto a
1200 by 96 one, with the flat run extended to both edges. The divider into the
payoff section is literally the app icon, stretched to the width of the page.
`preserveAspectRatio="none"` is deliberate rather than a compromise: a strip
chart is already a non-uniform projection of time against a value, so stretching
one horizontally is the correct thing to do to it.

Every variant is a single path with `pathLength="1"`, which is what lets the
stylesheet dash it to exactly one unit and draw it in on arrival without
measuring anything in script. With motion off the dash is never applied and the
line is simply there.

## The screenshots, and why none of them has a background

This is the thing the page got wrong for longest, so it is worth being blunt
about what was wrong with it.

Every screenshot the app pipeline produces is composed for the README: an opaque
raster with a blue-black gradient behind a drawn phone shell.
`docs/screens/hero-b.png` measures `#070910` in the corners and rises to about
`#1E2850` at the top edge. The first version of this page imported those
directly and put a hairline and a radius around them.

What that produces is a rounded blue rectangle sitting on a black document with
a second frame drawn inside it, and the page had no say in any of it. The scale
was fixed by the render, the shadow was fixed by the render, the ground under
the phone was a colour this stylesheet does not contain, and it read exactly like
what it was: a cropped README asset pasted onto a website.

`scripts/clean-screens.mjs` takes the raster apart. The ground is found by
flooding inward from the four corners, with a neighbour joining only if it is
within eight levels of the pixel it was reached from, which crosses a smooth
gradient and stops dead at any real edge. What is left is labelled into connected
pieces. Phones are cropped to their screens, with the drawn shell measured rather
than assumed. The widget plate is cut into the three separate widgets that were
photographed on it. Everything the flood reached is written at alpha 0.

So an `<img>` on this page now contains the pixels the app drew and nothing else,
and the rest is the stylesheet's:

- **The phone is four rings on one element** rather than a stack of nested boxes:
  the edge of the glass, the bezel, the body, and the light on the outside of it.
  Rings follow the border radius, so the outer corner is the screen radius plus
  the spread, which is the relationship a real phone has and one that nesting
  gets right only by accident.
- **The radius is the page's number, not the raster's.** The crops come off the
  plates with between 0.6% and 4.7% of their own corner arc still on them,
  depending on how much of it the shell inset ate. Rounding past all of them at
  about 5% of the rendered width is what makes eight screenshots look like one
  device.
- **`--device-cast` is what the phone is standing in.** It is transparent
  everywhere except the payoff, which is the only section where the ground is
  doing anything.
- **The widget is three objects, not a picture of three objects.** As one raster
  it could only ever be as wide as its column, which on a phone made all three
  about a hundred pixels across and the type in them unreadable. Cut apart, they
  are a bottom-aligned row where there is room and a stack where there is not.

Two things keep it honest. Sources are never edited, so re-shooting a screen and
running `npm run assets && npm run screens` regenerates everything without a
number changing. And `npm run screens:check`, which `npm run verify` runs,
asserts that all four corners of every derived asset are transparent, so a ground
cannot get baked back in without the build failing.

### The phone was always 18 px too wide for its column

A second, quieter version of the same bug. `.device` was `width: 100%` with
`margin: 9px`, and 100% of a container plus 18 px of margin is 18 px more than the
container: every phone sitting in a fixed grid track overflowed it by exactly the
width of its own bezel, and the outer ring finished 18 px past the column it
belonged to. In a 220 px wizard column that is a 220 px picture starting 9 px in and
ending 9 px out.

Nothing showed it while nothing clipped. The moment the wizard's panels became a
slider and took `overflow-x: clip`, the right hand edge of the phone was cut off,
which is how it was found. `width: calc(100% - 18px)` reserves the ring room, so
the picture shrinks only in containers too narrow to hold it and its rings, which
is the only case that was ever wrong. `.esc-screen` restates the subtraction
because it sets `width` outright and would otherwise put it back.

### The rings were being clipped off

Everything above was true of the stylesheet and false on the screen. A later pass
added `clip-path: inset(0 round var(--r-screen))` to `.device` alongside its
`picture` and `img`, and `clip-path` clips an element's own outset shadows, so it
was cutting away all four rings: the glass edge, the bezel, the body, and the
light on the outside of it. Every phone on the page had been rendering as a flat
rounded rectangle with a radius on it, which is precisely the cropped-asset-with-a-
frame-round-it this section was written about.

The clip belongs on what is inside the phone and never on the phone. `overflow:
hidden` with the radius the element already has holds the raster in; the clip is
only there to stop a webp's corner pixels crawling out from under an anti-aliased
edge.

It also caught a real bug. The crops used to be hardcoded boxes stepping a fixed
562 px across the wizard plate, and the fourth phone had moved: step four of the
setup section had been shipping with its right hand edge sliced off, and nothing
in the build could tell, because a crop box cannot know whether it landed on
anything. The script finds the phones instead, and fails if it finds a different
number of them than it expects.

## The five instruments

The complaint the first version of this page earned was that after the hero it
read as a technical article with good screenshots in it. The fix was not less
copy. It was giving each thing the page has to prove its own instrument, so the
argument is made by something you watch and the prose under it is corroboration
rather than the whole exhibit.

They all follow one rule, and it is the rule the video player already followed:
**the document ships the finished state, and script is what temporarily hides it
in order to replay how it got there.** Nothing below reveals content that was
otherwise unreachable. Switch JavaScript off, or ask for less motion, and each
one is a complete, legible, static readout rather than an empty box.

**01 The hero readout** (`Instrument.astro`). One monitor, sixty checks, and the
moment it pages you. Latency is the height and health is the colour, which is how
`ResponseTimeChart` draws it in the app; a refused connection is drawn full
height in rose rather than as a zero, so a failure is never a short bar you have
to hunt for. Script replays the sixty checks in order at 70 ms each, and both
the verdict chip and the urgent card are read off the bars rather than set
alongside them, so the flip to Down and the page arriving are genuinely caused by
the checks rather than choreographed next to them. The dashed line across the
chart is the 2.5 s latency budget the three amber checks crossed, and it is
labelled on the left because the newest check is on the right, which is where a
failure always is. The card that lands is drawn in HTML rather than pasted in,
because it has to arrive on cue, and every word and colour in it is the one in
the photograph of the real notification further down the page. Before it lands
the same cell holds a dashed slot reading "urgent alerting armed", so the panel
is exactly as tall armed as it is paged and the readout under the chart cannot
jump. The card sits under the monitor's own header and above its chart, which is
where a heads-up notification lands on Android. Shipped state: sixty bars,
verdict Down, card arrived.

### The order the replay runs in, which was backwards

Shipping the finished state and letting script hide it is the rule above, and for
a while this panel obeyed the letter of it and broke the point of it. Sixty checks
at 112 ms is 6.7 s of replay against a 4.2 s hold, and arming happened on the
same frame the panel first intersected. So the shipped payoff was thrown away
before anyone saw it, every visitor spent their first seven seconds watching an
outage not happen, the paged state was 38% of the cycle on a page whose headline
is about being woken up, and the only reader who got the finished readout first
was the one with JavaScript switched off.

The mechanism was right and the sequence was wrong. The replay is 70 ms a check,
the hold is 5.2 s, and `INTRO` holds the shipped state for 3.6 s before the first
replay, so the paged frame is what a visitor arrives on and about 55% of what they
see if they stay. Nothing about what the document ships changed.

### It is a phone, so it is on a phone

Off the device frame this panel was a dark rounded card holding a bar chart, a
status pill and a four up stat row, which is the furniture every hosted checker
ships. The fold argued "no server" in type while its only picture said "server
dashboard", and nothing above the fold said the word phone at all, for an app
that only exists on one.

It is in `.device` now, the same four rings as every screenshot on the page, with
two changes for a screen that is markup rather than a raster: a painted surface,
because there is no opaque image to supply one, and 392 px rather than 300,
because this is the one device here that is the subject. The panel drops its own
border and radius, since a frame inside a bezel is the two-frames problem the
screenshots section exists to describe.

The status bar is the only invented chrome in it, and it is carrying the argument
rather than dressing it: 3:12, Do Not Disturb, paged anyway. Without it a live
screen inside a bezel reads as a card with a thick border, because every other
phone here is recognisable from the status bar baked into its raster. Fixed, like
every other number in the panel. Nothing in it reads a clock.

### Eleven pixels is the floor

The budget label, the readout terms and the footnote were all set at 0.5625rem,
which is nine pixels, and the verdict pill at ten. Uppercase, tracked at 0.14em
and up, in `--text3` on `--plate`: fine in a screenshot at 2x and not legible on
a laptop at arm's length. The hero's eyebrow was eleven pixels of `--aqua`, which
measures 4.67 against this background, in the least legible shape small type
comes in. Nothing in the hero is under eleven pixels now, the eyebrow is twelve
and mixed toward the text colour at 11.7, and the video chapter chips came with
it. A page that spends its copy on being precise cannot ask to be squinted at.

**02 The shade** (`Shade.astro`). Two notifications on one phone: an ordinary one
and the one Nightbell posts. The difference between them is about behaviour rather
than looks, so it is shown as behaviour. The first card takes a swipe and is
gone. The second takes the same swipe, leaves, and drops straight back in from
the top. No script at all: both are CSS animations started by the reveal class the
section already gets. At reduced motion neither is declared, and the figcaption
under each column says in one line what the movement said.

It used to run three passes and stop, on the argument that a loop would compete with
the paragraph beside it. The paragraph is one sentence now, so there is nothing to
compete with, and a demonstration that has quietly finished before you scroll to it
is a still photograph of two notifications, which is the one thing this section
cannot afford to be. It loops until you leave, and both cycles are written to reach
their own first frame without a visible seam: the ordinary card cannot slide back in
from the right, because returning is the claim the other column is making, so it
fades in where it stands and its transform is reset under cover of opacity nought.
The easing is an ease-out throughout. A swipe and a card being posted both start at
their fastest and settle, which is what the platform's own animator does to them.

**05 The stepper** (`Wizard.astro`). Four real screens off a real device, one at
a time, advancing themselves every 3.8 s because the claim being made is that
this is a short flow, and four screens changing says that faster than a sentence
does. The first interaction of any kind stops that permanently: a control that
keeps moving under the hand of somebody reading it is worse than no control. The
tab strip ships `hidden`, exactly as the player's control bar does, so a page
with no JavaScript is four figures under four headings in order rather than a row
of buttons that do nothing. The chips under each step are the field names off the
screen beside it.

Two things about it were wrong for a long time and are worth naming. Advancing was
one panel going `hidden` and the next un-hiding on the same frame, and `hidden`
cannot be transitioned, so a step changed by the page appearing to redraw: nothing
told you that you had moved, or which way. All four panels share one cell now and
are placed by `data-pos`, so the one you are leaving exits the way you came in and
the one arriving comes from the side it was waiting on, over 620 ms of ease-out,
with the device travelling a little further than the copy it belongs to and the
step count arriving a beat behind both. Inactive panels are held out of the
accessibility tree with `inert` and `aria-hidden`, which is what `hidden` was
buying.

And the countdown was a one pixel hairline that appeared under the step you were
already looking at. Correct, and useless: a single filling hairline says nothing
about where you are in a set of four. Every step carries its own three pixel track
now, spent steps stay filled at 45 per cent, the current one fills, the ones ahead
are empty, and the selected tab is lit behind its type as well. It is the grammar
of the bars over a story, which is the one progress idiom every reader already
owns.

**06 The stage** (`Escalation.astro`). The three beats of an outage, pinned, with
the screen each beat happens on. All three beats stay on the stage and the active
one lights: the claim is that an outage has an order to it, and hiding two thirds
of that order makes the point worse rather than cleaner.

The stage used to be `min-height: calc(100vh - 6rem)` with its contents centred, on
the argument that a pinned stage stopping short of the bottom of the window reads as
a section that happens to stick. What that produced, for the whole time the section
head was still on screen, was 290 px of nothing between the lede and beat 01 and as
much again under beat 03: three beats centred in a full viewport under a heading
pinned to the top of it. The stage is the height of its own contents now, and the
three beats are distributed down their column rather than stacked at the top of it,
because a phone cropped to its screen is about twice as tall as three short
paragraphs and the space between beats is the one place on this page where a gap is
carrying meaning. Both columns end on the same line and beat 01 sits directly under
the lede. 240vh of scroll room rather than 280, because the stage got shorter.

The scroll is read and
never intercepted. No wheel handler, no `preventDefault`, no easing of somebody
else's input; the reader's own scroll decides the speed and the stage reports
where it got to. It is refused below 62 rem, where there is no room for a pinned
stage beside its own captions, and at reduced motion, where a section that
changes under a scroll is precisely the thing being asked for less of. In both
cases what is left is the ordered list, which is why the list is the shipped
state rather than the fallback.

**The film.** The promo gets a band of its own at page width under the hero
rather than a slot beside a wall of body copy. It is the only forty seconds that
shows the app moving, and putting the best asset the project has into a column so
a paragraph could sit next to it was the worst call in the first version of this
page. It carries no heading and no standfirst, for the reasons under "The video
needed no introduction" above.

**The masthead readout.** A document this long with no sidebar has to say where
you are, and the honest way to say that on an instrument is a position and a
scale rather than a highlighted nav item. The number, the section name and the
rule along the bottom edge are written by `motion.ts`, in the section's own state
colour. It is the one thing in that file that runs at reduced motion as well,
because a progress readout is information and taking it away from somebody who
asked for less movement would remove the only thing on the page that orients
them.

## The player

The first build of this page used the browser's native video controls. That was
the wrong call and it is worth recording why, because the reasoning sounded fine.

The argument was: native controls are zero JavaScript, impossible to get wrong
for a keyboard or a screen reader, and the brief asked for small, progressive
script. All true. What it missed is that a Chrome-grey control bar was then the
only un-branded element on a page whose entire job is to look like a specific
instrument rather than a template. The page spent nine sections refusing default
styling and then handed the most prominent element on it straight back to the
defaults. Roughly 4.5 kB of script to fix that is an obvious trade.

**It is still progressive.** The `<video>` ships with `controls` and the custom
bar ships `hidden`. The script's first act is to swap them, so a reader with no
JavaScript, or one whose bundle failed, gets a working native player rather than
a poster with no way to press it. `npm run validate` treats that as an
invariant and fails the build if the markup ever ships the other way round.

**Nothing reimplements a control the platform gets right.** The scrubber is a
real `<input type="range">`, so arrow keys, Home, End and every screen reader's
slider handling are the browser's, not an approximation built from a div and a
pointer listener.

What is actually custom:

- **The glyphs are the app's.** Play, Pause, Volume and VolumeOff are the exact
  path data from `ui/icons/NightbellIcons.kt`, on the same 24-unit grid with the same
  1.7 px round strokes. The buttons under the film are drawn in the same hand as
  the buttons inside it. Only the fullscreen glyph is new, because the app has no
  use for one, and it is drawn to that spec rather than borrowed.
- **The scrubber is the app's timeline.** A hairline track, the played run in
  Aqua, what has been fetched in a dim grey behind it, a marker at each milestone
  and a short tracker bar riding along. That is the picture `ProgressStyle` draws
  in the live notification and `domain/LiveTimeline.kt` computes for it. The
  tracker is a bar rather than a dot for the same reason it is there: it is easier
  to land on, and it is what the app already rides along a line.
- **The markers are the real cut.** `scripts/sync-assets.mjs` parses `SCENES` and
  `OVERLAPS` out of `promo-video/src/NightbellPromo.tsx` and folds them into start
  frames exactly as `narration.mjs` does, so the six chapter buttons under the bar
  seek to genuine scene boundaries. If the arithmetic stops agreeing with the
  rendered duration, `npm run assets` says so rather than shipping a chapter list
  that is quietly wrong. This is the reason the chapters are worth having at all:
  the payoff is at 0:22, and a reader who wants to see the one thing a competitor
  cannot claim should not have to hunt for it.
- **The readout is correct before the video loads.** `preload="none"` means
  `video.duration` is NaN until something is fetched, so the total comes from the
  duration ffprobe measured at sync time. The bar says `0:00 / 0:40` having
  downloaded nothing.
- **Captions are painted by this page.** The track is set to `hidden` rather than
  `showing`, which still fires `cuechange` but stops the browser drawing its own
  white-on-black box over a graded frame. Cue text goes into an element this
  stylesheet owns, set in Inter, sized in container units so it scales with the
  picture instead of the viewport and needs no special case for fullscreen.
- **Blue, and only blue.** A player is chrome, so the bar takes Aqua. Mint, amber
  and rose are spoken for, and a rose progress bar in the payoff section would
  have been a health colour used as decoration.

Keyboard, scoped to the player so none of it competes with the page: `Space` or
`K` to toggle, arrows to seek five seconds, `Home` to restart, `M`, `C` and `F`
for mute, captions and fullscreen. Keys a focused control already handles are
left alone, so `Space` on a button presses the button and arrows on the scrubber
stay the range input's own.

## What was refused

Written down in advance, and checked against the finished page.

- No gradient blob behind a heading, which is a narrower rule than it sounds and
  is worth stating precisely, because the page now has two background layers and
  for a while it had a third it should not have.

  The two it has: a faint 68 px graticule in the hero, masked out before it
  reaches any text, which is the same grid the video draws behind the payoff;
  and under everything, the field described in the composition section.

  What is refused is a coloured shape you can point at behind type. The test is
  whether a reader could describe it. A wash bright enough to be noticed
  competes with the words in front of it and says nothing about the product,
  which is the entire objection. The field passes that test at 5 to 10 per cent
  over a thousand pixels; the two radial washes that sat in the hero until
  2026-08-08 did not, and one of them was rose, spending the page's down colour
  as decoration five sections before the outage it belongs to.

  The reference for the field was the drifting blob background on videre.cz, and
  the differences are the point: eight rather than thirty, an order of magnitude
  fainter, four times slower, translate only with no scale, and radial gradients
  rather than 64 px blur filters, because this page runs a scroll-driven stage
  that reads layout every frame and cannot afford to share a compositor with
  thirty blurred layers. Half of them are dropped and none of them move below
  48 rem for the same reason.
- No centred hero with three pills and two buttons. The hero is left-aligned,
  asymmetric, and its second column is a live readout of the product doing the
  one thing the product is for, with the film directly under it at page width.
- No rounded cards inside rounded cards.
- No three-column feature grid. The three kinds of check are a spec sheet with a
  term, its real modes as code chips, and one sentence about what each costs,
  because the three are not interchangeable and equal boxes claim they are.

  Section 03 kept one anyway until 2026-08-08, and it is the clearest example of
  why the rule exists, because the shape was the second problem with it. Three
  articles in a bordered row, each a tinted icon tile over a heading over one
  sentence: Direct request, Local state, No proxy. The path diagram immediately
  above them already draws the request going to the service and back, already
  draws the monitoring company crossed out, and already says "state stays here:
  one JSON document in DataStore". So the cards restated the diagram, and two of
  the three restated the hero's rules on top of that, which made this the third
  time one viewport had made the same claim.

  What is there instead is an inventory of absences: nine struck-through chips
  naming things a monitoring app normally has and this one does not, from relay
  and queue through to analytics SDK, crash reporter and icon proxy, every one of
  them checkable against the dependency list. A diagram can draw what the path
  is. It cannot list what is missing from the build, and that list is the actual
  claim. The honest half sits directly under it rather than three sections away:
  your service still sees the request and the IP it came from.
- No screenshot floating in a glass pane, and no screenshot arriving with a
  background of its own either. Every raster on the page is product UI with a
  transparent surround, and the phone around it is drawn by the stylesheet. See
  the section on the screenshots for what that took and why.
- No fake anything. No testimonials, no customer logos, no star counts, no
  download counts, no avatars, no invented metrics. The only numbers on the page
  are the version, the APK size, two file hashes, the test counts and Android's
  own 15 minute floor, and each of those can be checked against the repository.
- No stock photography and no illustration standing in for a product that
  exists and can be photographed.
- No cursor-following effects, no particles, no glow, no parallax. No scroll
  hijacking either, and the pinned payoff stage is the case worth being precise
  about: it is a `position: sticky` element and a scroll listener that reads
  `getBoundingClientRect`. There is no wheel handler, nothing calls
  `preventDefault`, and nothing animates the scroll position. Scroll away from it
  at any speed and it lets you.
- No copy that could describe a different monitoring product. Every paragraph
  either names something specific to this app or admits something specific about
  it.

## The 404

The second page, and the only other one. It exists because Nginx serves this build
off a VPS rather than a host with an opinion, and without a `404.html` the reply to a
mistyped path is Nginx's own grey wall with a version number on it. That page says
nothing, matches nothing, and is the one screen a visitor sees that would look like a
different site.

**The first version was scrapped.** It was a four row list of every destination on
the site: the landing page, the APK, the reference docs, the issue tracker. That is a
navigation menu wearing an error page's clothes. Somebody who lands here followed a
link that did not work; they do not need a directory, they need to be told plainly
and handed the one door that is open.

What replaced it is the number, at the largest size the viewport will carry, then one
status line, one sentence and one button:

```
                          404
              ────────┴────────┴────────
                ● NO ROUTE TO THIS PATH

        The link you followed points at something this
        site does not serve. Nothing on your phone or
        your network is broken, and there is nothing
        to retry.

                 [ ⌂ Go to the landing page ]
```

Everything except the number and the axis is a primitive that already exists.
`.label`, `.lede`, `.actions` and `.btn-primary` arrive looking like the landing page
because they are the landing page's rules, which is the whole reason the page reads as
part of this site rather than as a bolted-on error screen.

**Centred, which is an exception to the rest of the site.** Every other block of type
here is set against the left of the content column. The 404 earns the exception by
having no column to belong to: no rail, no section number, no second block to align
with, so the axis the left edge normally provides is not there to hold. A single stack
centred on the page is a composition; the same stack pushed left with two thirds of
the viewport empty beside it is an accident.

**Rose appears once, and means what it always means.** The stylesheet and the app's
own theme both keep the rule that rose is the down colour and never decoration. A
request that did not resolve is a down state, so the status strip under the number is
the one place on this page rose is correct. The number itself stays bone, because the
number is the subject and not the alarm. The dot blinks at 1.6 s because that is what
the app and the widget do while a monitor is failing, which makes it the one animation
here replaying a cause rather than dressing a page.

**The rule under the number is a drawn axis, not a divider.** It carries tick marks,
which is the same instrument language as the hero graticule and the trace between
sections. It passes the test "What was refused" sets for anything sitting behind or
beneath type: a reader could describe it, because it is an axis with ticks on it.

Three things about it were measured rather than judged, and all three were wrong on
the first attempt:

- **The section is not a `.section`.** That class turns its `.wrap` into the
  instrument grid, `var(--rail) minmax(0, 1fr)`, and every direct child is then
  auto-placed into a cell of its own. The number landed in the 5.5 rem rail column,
  the axis landed beside it instead of under it, and the button got a 5.5 rem track
  and rendered as a circle with four lines of wrapped text in it.
- **The status line needed `max-width: none`.** The stylesheet sets
  `p, li { max-width: var(--measure) }`, and a `ch` is measured in the element's own
  font, so 68 ch on an 11 px mono label is about 451 px rather than the 640 the same
  rule gives the lede. The box was 451 px wide, parked at the left of a 1100 px
  column, with its content perfectly centred inside it, which is why
  `justify-content: center` looked like it had done nothing at all. It took a debug
  outline to see.
- **The number needed `margin-right: 0.055em`.** Tracking is applied after every
  glyph including the last, so with negative tracking the line's advance box is
  narrower than its ink and centring the box puts the ink half a tracking step right
  of true centre. At 21 rem that is 18 px and plainly visible. A full tracking step as
  right margin moves it left by half of one, which is exactly the correction. The
  first attempt had the sign inverted and doubled the error.

The mobile floor is doing real work: 27 vw of a 390 px screen is 105 px, which lands
the number at about half the column width and stops it being the page. The clamp
floors at 8.5 rem, roughly 250 px of a 350 px column, which reads as boldly on a phone
as 21 rem does at 1440. At 320 px it still has 30 px to spare.

### The footer, once

The 404 shipped briefly with a one line footer of its own while the landing page kept
its three column one. That is how a site ends up with two footers disagreeing about
what the licence is. `Footer.astro` is the extraction: one footer, both pages render
it, and the only thing that differs is where the brand lockup points, because the
landing page's is an in-page jump to the top and the 404's has to be a real
navigation. Verified in the built output as byte-identical apart from that `href` and
the `Mark` component's per-instance mask id.

### A home glyph, and the second exception

`Glyph` is otherwise verbatim from `NightbellIcons.kt`, on the same 24 unit grid at
the same stroke widths, so a button drawn on this page is drawn in the same hand as
the button it describes. `book` was the one exception, because the app has nothing to
read and so has no such glyph.

`home` is the second, on the same terms: the app has no home screen to navigate to.
It is drawn to that file's spec rather than lifted from a set, at the 1.7 stroke
`globe`, `clock` and `bell` use, with the roof apex on the vertical centre, eaves
overhanging the walls by 2.4 units either side, and a door on the centre line so the
glyph stays symmetrical at 17 px, where a lopsided one would show.

### `data-off` instead of `hidden`

The player's play, pause, volume and muted glyphs are all SVG, and their visibility
is scripted. They used to be toggled with the `hidden` attribute, which **worked** in
every browser, because the UA stylesheet's `[hidden] { display: none }` is a plain
attribute selector that matches any element. It is also invalid: `hidden` is not
permitted on a foreign element, and the W3C validator rejects it.

They now carry `data-off`, with `svg[data-off] { display: none !important }` in
`global.css` and `player.ts` toggling the attribute. The important is there for the
same reason the `[hidden]` rule has one: the reset sets `svg { display: block }`, this
visibility is load-bearing, and the failure mode is the player drawing its play and
pause glyphs on top of each other. The specificity would have been sufficient without
it; a future `.player-btn svg { display: grid }` would not be.

Verified by driving the real player in headless Chromium rather than by reading the
diff: play visible and pause hidden at rest, swapped while playing, swapped back on
pause, zero console errors.

### Two smaller validity fixes in the same pass

- `Instrument.astro` had its `figcaption` in the middle of the `figure`, with the
  status bar above it and the urgent page and chart below. A `figure` allows a
  `figcaption` only as its first or last child, so the validator rejected the whole
  subtree. Moving it was not the fix, because it is not a caption of the illustration:
  it is the monitor header inside the illustration, the same row the app's own detail
  screen draws. It is a `div` now, styled by the same `.ins-head`.
- `Shade.astro` used `<article>` for its two notification mockups. Those are
  illustrations of UI rather than independent articles, and an article with no heading
  is what the validator warned about. Both are `div`s.

## Accessibility, deliberately

- Every section has a complete static state, and it is the shipped state rather
  than a fallback. Switching motion off removes an embellishment and never a
  fact: the hero readout's sixty bars are in the markup with their states, so the
  story reads green to amber to red with no animation at all and the verdict
  holds on Down; the payoff is an ordered list beside three real screenshots; the
  wizard is four figures under four headings; both shade cards rest under
  captions that say what the movement would have said.
- `prefers-reduced-motion: reduce` drops animation to nothing, refuses the pinned
  stage and the stepper's auto-advance, and stops the payoff clip from
  auto-playing. This mirrors `rememberLoopingFloat()` in the app, which genuinely
  stops rather than speeding up. The one thing that keeps running is the masthead
  readout, because it is information rather than movement.
- The pinned stage never touches the scroll. There is no wheel handler and no
  `preventDefault` anywhere in `motion.ts`, so a reader who scrolls fast gets
  through it fast, and assistive technology that jumps by heading is not fighting
  an interpolation.
- Semantic landmarks: one `header`, one `nav`, one `main`, one `footer`, one
  `h1`, no skipped heading levels. A skip link is the first focusable thing on
  the page.
- The FAQ is `<details>` elements, so every answer is in the HTML for a crawler
  and reachable by keyboard without any script.
- The player is custom, and every control in it is reachable and labelled. See
  the section on it below for what that cost and how the fallback works.
- The path diagram is an inline SVG with `role="img"`, a `<title>` and a
  `<desc>`, and every fact it draws is also in the prose beside it.
- The hero readout is a single `role="img"` with a label describing the shape,
  rather than sixty unlabelled elements.

## Performance

One document, one round trip for the page itself. The stylesheet is inlined,
both videos are `preload="none"` behind posters, and every screenshot is lazy
with explicit dimensions. One module carries the player and all of the motion,
and it is the only extra request; the page is fully readable, both videos are
fully playable and every instrument is showing its finished state before it
arrives.

Measured with Lighthouse 12 against the live URL, and stable across three runs:

```
              perf   a11y   best-practices   SEO
desktop        100    100         100        100
mobile          98    100         100        100

TBT 0 ms   CLS 0   desktop LCP 0.5 s   first load 9 requests, 176 kB
```

| | |
| --- | --- |
| Requests for a full page load | 9 |
| Transferred | 176 kB |
| Of which typefaces | 89 kB, two woff2, preloaded, cached a year immutable |
| Of which video posters | 42 kB, two WebP. The videos themselves are 0 kB until pressed |
| HTML | 155 kB, 34 kB over the wire as gzip, 26 kB as Brotli, stylesheet inlined |
| JavaScript | 10 kB in one module chunk, 3.4 kB over the wire, one request. The player and all five instruments |
| Cumulative layout shift | 0 |

**The line above about every screenshot being lazy was a claim before it was a
fact.** Eight images across `Escalation.astro`, `Wizard.astro` and `index.astro`
carried an unexplained `loading="eager"`, all of them below the fold, and the first
load was therefore twenty requests and 401 kB of content nobody had scrolled to.
Removing the overrides was the entire fix, because Astro's `<Image>` already defaults
to lazy. It halved the page weight and took mobile performance from 95 to 98. If an
`eager` is ever added back it needs a comment saying why.

### Why mobile stops at 98

The remaining gap is LCP around 2.3 s against the roughly 1.4 s that would score 100,
and the cause was measured rather than guessed: 512 ms of observed Style & Layout,
1087 ms of total main-thread work, multiplied by Lighthouse's 4x CPU throttle. It is
style and layout over 1,074 DOM elements against about 100 kB of inlined CSS. Nothing
is render-blocking, no CSS is unused, and the LCP element is the headline text rather
than an image.

Two candidate fixes were tried and both failed, and both were reverted:

- **`content-visibility: auto` on the off-screen sections**, which is the textbook
  fix for exactly this shape of problem. LCP render delay 1503 ms before, 1503 ms
  after. No effect on LCP at all; it only took TBT from 40 ms to 0 ms, which was
  already scoring full marks, so keeping a change to rendering behaviour that buys
  nothing scored would have been pure risk.
- **`font-display: optional`**, to test whether the headline repainting when Inter
  arrives was driving LCP. Also 1503 ms. Not the font either, which is worth knowing
  because it is the answer everyone reaches for first.

That the number was identical to the millisecond across three very different builds
is the tell: Lantern derives it from observed main-thread work, not from network or
paint behaviour.

What would move it is splitting the CSS so less of it is parsed before first paint,
which contradicts `inlineStylesheets: 'always'` and risks a flash of unstyled content,
or cutting DOM size, which is deleting content. Neither is worth two points that no
visitor experiences.

Every screenshot is lazy and re-encoded to webp at build time. Cutting the ground
off them made them smaller as well as better: a transparent surround compresses
to almost nothing, where a gradient does not.

Every behaviour that needs script works without it. The copy button stays hidden
unless the clipboard API is present, the videos keep native controls until the
player replaces them, and the payoff clip keeps its poster and its play button if
autoplay is refused or reduced motion is asked for.

The promo is not given the same treatment, and that is the reason the row above
still reads the way it does. Forty seconds and six megabytes started on scroll
would be spent on every reader who got that far, and autoplay with sound is
refused everywhere, so what it would buy them is a narrated film playing
silently.
