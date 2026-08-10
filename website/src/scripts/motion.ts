/**
 * The page's motion, all of it, and the page is complete with none of it running.
 *
 * Four things live here, and they share one rule: the document already ships the
 * finished state of everything. Nothing below reveals content that was otherwise
 * unreachable. What it does is take a picture that is already true and replay how
 * it got that way, because cause and effect is the argument this product is
 * making and a still cannot make it.
 *
 *   1. reveals        sections and rows arrive as you reach them
 *   2. readout        the masthead reports which section you are in, and how far
 *   3. the stage      the payoff section becomes four pinned beats
 *   4. the instrument the hero replays sixty checks and the page they cause
 *
 * `still` is checked live rather than once, so switching the system setting mid
 * visit takes effect on the next tick instead of on the next reload. Every
 * observer and interval is torn down when its subject leaves the screen: an idle
 * page here costs no frame budget at all.
 */

const still = matchMedia('(prefers-reduced-motion: reduce)');
const quiet = (): boolean => still.matches;

/* ---------------------------------------------------------------- reveals */

/**
 * Everything marked `data-reveal` starts visible in the stylesheet. Putting
 * `data-revealing` on the document is what arms the hidden state, and it happens
 * here, in script, one frame before the observer can start unhiding things.
 *
 * Written this way round so the failure mode is a page with no animation rather
 * than a page with no content.
 */
function reveals(): void {
  const marks = [...document.querySelectorAll<HTMLElement>('[data-reveal]')];
  if (!marks.length) return;

  if (quiet()) return;
  document.documentElement.dataset.revealing = 'true';

  const seen = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add('is-in');
        // One way only. A section that scrolls back out stays arrived, because
        // re-animating on the way up is motion with nothing to explain.
        seen.unobserve(entry.target);
      }
    },
    { rootMargin: '0px 0px -12% 0px', threshold: 0.06 },
  );

  for (const mark of marks) seen.observe(mark);

  // If the setting flips to reduce, drop the hidden state entirely rather than
  // leaving anything mid transition.
  still.addEventListener('change', () => {
    if (!quiet()) return;
    delete document.documentElement.dataset.revealing;
    for (const mark of marks) mark.classList.add('is-in');
  });
}

/* ---------------------------------------------------------------- readout */

/**
 * The masthead's status line: how far down the page you are, and which numbered
 * section you are in, in that section's own colour.
 *
 * This is the only navigation aid on a document this long, so it runs at reduced
 * motion too. A progress bar is a readout, not an animation, and hiding it from
 * somebody who asked for less movement would be taking away information.
 */
function readout(): void {
  const bar = document.querySelector<HTMLElement>('[data-progress]');
  const noOut = document.querySelector<HTMLElement>('[data-here-no]');
  const nameOut = document.querySelector<HTMLElement>('[data-here-name]');
  const sections = [...document.querySelectorAll<HTMLElement>('[data-section]')];
  if (!bar && !noOut) return;

  let queued = false;
  const paint = (): void => {
    queued = false;
    const height = document.documentElement.scrollHeight - window.innerHeight;
    const through = height > 0 ? Math.min(Math.max(window.scrollY / height, 0), 1) : 0;
    if (bar) bar.style.setProperty('--through', String(through));

    // The section whose top has most recently passed the reading line, a third
    // of the way down the viewport. Cheap, and it never disagrees with what is
    // actually filling the screen.
    const line = window.scrollY + window.innerHeight * 0.34;
    let here: HTMLElement | null = null;
    for (const section of sections) {
      if (section.offsetTop <= line) here = section;
    }
    const root = document.querySelector<HTMLElement>('[data-here]');
    if (here && root) {
      root.style.setProperty('--spine', getComputedStyle(here).getPropertyValue('--spine'));
      if (noOut) noOut.textContent = here.dataset.no ?? '';
      if (nameOut) nameOut.textContent = here.dataset.name ?? '';
      root.dataset.here = here.id;
    } else if (root) {
      if (noOut) noOut.textContent = '00';
      if (nameOut) nameOut.textContent = 'Top';
      root.dataset.here = 'top';
    }
  };

  const schedule = (): void => {
    if (queued) return;
    queued = true;
    requestAnimationFrame(paint);
  };

  addEventListener('scroll', schedule, { passive: true });
  addEventListener('resize', schedule, { passive: true });
  paint();
}

/* ------------------------------------------------------------------ stage */

/**
 * The payoff section, pinned.
 *
 * Shipped, the four beats are an ordered list and all four screens are on the
 * page. `data-on` is what makes the wrapper tall, pins the stage inside it and
 * shows one beat at a time. Scroll is read, never intercepted: no wheel handler,
 * no preventDefault, no easing of somebody else's scroll. The reader's input
 * decides the speed and this only reports where it got to.
 *
 * Refused on a narrow screen, where there is no room for a pinned stage beside
 * its own captions, and refused at reduced motion, where a section that changes
 * under a scroll is exactly the thing being asked for less of. In both cases the
 * list is what stays, which is why the list is the shipped state.
 */
function stage(): void {
  const wrap = document.querySelector<HTMLElement>('[data-escalation]');
  if (!wrap) return;

  const beats = [...wrap.querySelectorAll<HTMLElement>('[data-beat]')];
  const screens = [...wrap.querySelectorAll<HTMLElement>('[data-screen]')];
  if (beats.length < 2) return;

  const wide = matchMedia('(min-width: 62rem)');
  let on = false;
  let at = -1;
  let queued = false;

  const paint = (): void => {
    // First, and before the early return: this is what re-opens the frame gate
    // below. Leaving it until after the `!on` check would wedge the stage shut
    // the first time it painted while the section was switched off.
    queued = false;
    if (!on) return;
    const box = wrap.getBoundingClientRect();
    const run = box.height - window.innerHeight;
    const through = run > 0 ? Math.min(Math.max(-box.top / run, 0), 1) : 0;

    // Even bands with a dead zone at each end, so the first and last beats get a
    // beat of stillness rather than flicking past at the boundary.
    const index = Math.min(beats.length - 1, Math.floor(through * beats.length * 0.999));
    if (index === at) return;
    at = index;
    wrap.dataset.at = String(index);
    for (const [i, beat] of beats.entries()) beat.dataset.on = i === index ? 'true' : 'false';
    for (const [i, screen] of screens.entries()) screen.dataset.on = i === index ? 'true' : 'false';
  };

  const schedule = (): void => {
    if (queued) return;
    queued = true;
    requestAnimationFrame(paint);
  };

  const sync = (): void => {
    const want = wide.matches && !quiet();
    if (want === on) return;
    on = want;
    if (on) {
      wrap.dataset.on = 'true';
      at = -1;
      schedule();
    } else {
      delete wrap.dataset.on;
      delete wrap.dataset.at;
      for (const beat of beats) delete beat.dataset.on;
      for (const screen of screens) delete screen.dataset.on;
    }
  };

  addEventListener('scroll', schedule, { passive: true });
  addEventListener('resize', () => {
    sync();
    schedule();
  });
  wide.addEventListener('change', sync);
  still.addEventListener('change', sync);
  sync();
}

/* ------------------------------------------------------------- instrument */

/**
 * The hero readout, replayed.
 *
 * The panel already contains the finished story: sixty bars at their real
 * heights and colours, the verdict on Down, the urgent page arrived. `data-armed`
 * is the only thing that hides any of that, and it is set here. Then the checks
 * are put back one at a time, in order, and the verdict and the page follow from
 * the bars rather than from a second timer, so the causation is real and not
 * choreographed.
 *
 * The order matters as much as the mechanism. That finished story is held for
 * `INTRO` before the first replay, and `HOLD` after every one, so the paged
 * state is what a visitor arrives on and the majority of what they see if they
 * stay. Arming on the first intersect is what made the outage the thing you had
 * to wait seven seconds for.
 *
 * Off screen it stops. Not slows: `clearInterval`, so the tab can idle.
 */
function instrument(): void {
  const panel = document.querySelector<HTMLElement>('[data-instrument]');
  if (!panel) return;

  const bars = [...panel.querySelectorAll<HTMLElement>('.bars i')];
  const lastOut = panel.querySelector<HTMLElement>('[data-last]');
  const checksOut = panel.querySelector<HTMLElement>('[data-checks]');
  const failedOut = panel.querySelector<HTMLElement>('[data-failed]');
  if (!bars.length) return;

  /**
   * ms between checks arriving. Sixty of them is a shade over four seconds.
   *
   * It was 112, which made the replay 6.7 s against a 4.2 s hold, so the panel
   * spent 62% of every cycle showing a green chart and a verdict of Operational
   * on a page whose headline is about being woken up. The one frame that makes
   * the argument was the minority state.
   */
  const STEP = 70;
  /** How long the finished readout is held before it replays. */
  const HOLD = 5200;
  /**
   * How long the shipped payoff is held the first time the panel is seen, before
   * anything is hidden in order to replay it.
   *
   * The document ships the end of the story, and arming used to throw it away on
   * the same frame the panel came into view: every visitor spent their first
   * seven seconds watching an outage not happen, and the only one who got the
   * finished readout first was the one with JavaScript switched off.
   */
  const INTRO = 3600;

  let tick = 0;
  let intro = 0;
  let index = 0;
  let holding = false;
  let running = false;
  let seen = false;

  const write = (n: number): void => {
    panel.style.setProperty('--n', String(n));
    if (checksOut) checksOut.textContent = String(n);

    const failed = bars.slice(0, n).filter((b) => b.dataset.state === 'down').length;
    if (failedOut) failedOut.textContent = String(failed);

    const latest = bars[n - 1];
    if (lastOut) {
      const ms = latest?.dataset.ms;
      lastOut.textContent = !latest ? 'Waiting' : ms ? `${ms} ms` : 'No response';
    }

    // The verdict is read off the bars, never set alongside them.
    const state = latest?.dataset.state ?? 'up';
    panel.dataset.verdict = n === 0 ? 'up' : state;
    // The page is posted by the outage, one beat after it, the way a check
    // failing and a notification landing are one beat apart in the app.
    if (state === 'down' && n >= 1) panel.dataset.paged = 'true';
    else delete panel.dataset.paged;
  };

  const reset = (): void => {
    index = 0;
    holding = false;
    for (const bar of bars) delete bar.dataset.on;
    write(0);
  };

  const step = (): void => {
    if (holding) return;
    if (index >= bars.length) {
      holding = true;
      window.setTimeout(() => {
        if (running) reset();
      }, HOLD);
      return;
    }
    bars[index].dataset.on = 'true';
    index += 1;
    write(index);
  };

  const arm = (): void => {
    panel.dataset.armed = 'true';
    reset();
    tick = window.setInterval(step, STEP);
  };

  const start = (): void => {
    if (running || quiet()) return;
    running = true;
    // First time in view the payoff is what you get, held, and only then is it
    // taken apart. Afterwards the loop's own hold is doing that job.
    if (seen) {
      arm();
      return;
    }
    intro = window.setTimeout(() => {
      seen = true;
      if (running) arm();
    }, INTRO);
  };

  const stop = (): void => {
    running = false;
    window.clearInterval(tick);
    tick = 0;
    window.clearTimeout(intro);
    intro = 0;
    delete panel.dataset.armed;
    for (const bar of bars) delete bar.dataset.on;
    panel.style.removeProperty('--n');
    // Back to the state the document shipped: the whole readout, verdict Down,
    // page arrived.
    panel.dataset.verdict = 'down';
    delete panel.dataset.paged;
    if (checksOut) checksOut.textContent = String(bars.length);
    if (failedOut) {
      failedOut.textContent = String(bars.filter((b) => b.dataset.state === 'down').length);
    }
    if (lastOut) lastOut.textContent = 'No response';
  };

  new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (entry.isIntersecting && !quiet()) start();
        else stop();
      }
    },
    { threshold: 0.3 },
  ).observe(panel);

  still.addEventListener('change', () => {
    if (quiet()) stop();
  });
}

/* ----------------------------------------------------------------- wizard */

/**
 * The four setup steps, as a stepper.
 *
 * The tab strip ships `hidden` and the four panels ship visible, which is the
 * same handover the player makes with the browser's own controls: what is left
 * without script is a complete, ordered, readable account of the flow rather
 * than a row of buttons that do nothing.
 *
 * It advances itself, because the claim being made is that this is a short flow
 * and four screens changing on their own says that faster than a sentence does.
 * The first interaction of any kind stops that permanently. A control that keeps
 * moving under the hand of somebody reading it is worse than no control.
 */
function wizard(): void {
  const root = document.querySelector<HTMLElement>('[data-wizard]');
  if (!root) return;

  const tabs = [...root.querySelectorAll<HTMLButtonElement>('[role="tab"]')];
  const panels = [...root.querySelectorAll<HTMLElement>('[data-panel]')];
  const strip = root.querySelector<HTMLElement>('[data-tabs]');
  if (tabs.length !== panels.length || !panels.length || !strip) return;

  strip.hidden = false;
  root.dataset.on = 'true';
  // Height is the tallest panel from here on, set once, so the section does not
  // resize under the reader every time a step with more copy in it arrives.
  root.dataset.slider = 'true';

  let at = 0;
  let auto = 0;
  let touched = false;

  /**
   * Where each panel is, relative to the one you are on.
   *
   * `hidden` was doing this, and `hidden` cannot be transitioned: a step changed
   * by one panel vanishing and another appearing in the same frame, which reads as
   * a redraw rather than as a move. Every panel keeps its box and is placed
   * instead, so the outgoing one leaves to the side it is on and the incoming one
   * arrives from the side it was on.
   *
   * Left visible to the accessibility tree only while it is the current one, via
   * `inert` and `aria-hidden`, which is what `hidden` was buying.
   */
  const place = (): void => {
    for (const [i, panel] of panels.entries()) {
      panel.dataset.pos = i === at ? 'now' : i < at ? 'behind' : 'ahead';
      panel.inert = i !== at;
      panel.setAttribute('aria-hidden', i === at ? 'false' : 'true');
    }
  };

  const show = (next: number, focus = false): void => {
    at = (next + tabs.length) % tabs.length;
    for (const [i, tab] of tabs.entries()) {
      const is = i === at;
      tab.setAttribute('aria-selected', is ? 'true' : 'false');
      tab.tabIndex = is ? 0 : -1;
      // Which runners are spent, which is the stories row's whole job.
      if (i < at) tab.dataset.done = 'true';
      else delete tab.dataset.done;
    }
    place();
    /*
      The runner is a CSS animation keyed to the selected tab, so moving the
      selection restarts it. Re-inserting the element is the one reliable way to
      restart an animation on a node that is only now becoming the animated one,
      because the class change and the animation both land in the same frame.
    */
    const run = tabs[at].querySelector<HTMLElement>('.w-tab-run');
    if (run) run.replaceWith(run.cloneNode(true));
    if (focus) tabs[at].focus();
  };

  const settle = (): void => {
    touched = true;
    window.clearInterval(auto);
    auto = 0;
    delete root.dataset.auto;
  };

  for (const [i, tab] of tabs.entries()) {
    tab.addEventListener('click', () => {
      settle();
      show(i);
    });
    tab.addEventListener('keydown', (event: KeyboardEvent) => {
      const move = { ArrowRight: 1, ArrowLeft: -1 }[event.key];
      if (move) {
        event.preventDefault();
        settle();
        show(at + move, true);
        return;
      }
      if (event.key === 'Home' || event.key === 'End') {
        event.preventDefault();
        settle();
        show(event.key === 'Home' ? 0 : tabs.length - 1, true);
      }
    });
  }

  show(0);

  new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        const play = entry.isIntersecting && !touched && !quiet();
        if (play && !auto) {
          root.dataset.auto = 'true';
          auto = window.setInterval(() => show(at + 1), 3800);
        } else if (!play && auto) {
          window.clearInterval(auto);
          auto = 0;
          delete root.dataset.auto;
        }
      }
    },
    { threshold: 0.35 },
  ).observe(root);
}

export function enhanceMotion(): void {
  reveals();
  readout();
  stage();
  instrument();
  wizard();
}
