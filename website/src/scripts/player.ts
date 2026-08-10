/**
 * Turn a `<figure data-player>` into the branded player.
 *
 * The whole file is progressive enhancement. Until it runs, each video is a
 * plain `<video controls>` and works; the first thing this does is take the
 * native controls away, and it only does that once it is certain it can put
 * something better in their place.
 *
 * Nothing here reimplements a control that the platform already gets right. The
 * scrubber is a real `<input type="range">`, so arrow keys, Home, End and every
 * screen reader's slider handling come for free and are not approximated with
 * a div and a pointer listener.
 */

const clock = (s: number): string => {
  if (!Number.isFinite(s) || s < 0) s = 0;
  return `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`;
};

const stillness = matchMedia('(prefers-reduced-motion: reduce)');

/**
 * Show or hide an element by attribute, because half of these are SVG.
 *
 * `hidden` is defined on `HTMLElement`, which reflects it to the attribute.
 * `SVGElement` does not inherit from `HTMLElement`, so `svg.hidden = true` is
 * not a hidden element: it is a plain expando property on the object. Nothing
 * is hidden, nothing repaints, and reading it back afterwards returns `true`,
 * which is the shape of bug that survives being tested.
 *
 * The play, pause, volume and muted glyphs are all SVG, and all four were being
 * toggled that way, so the toggle button drew a play triangle while playing and
 * the mute button never changed at all. `aria-label` was correct throughout,
 * which is the part that made it look fine to a script and wrong to a person.
 *
 * `toggleAttribute` is on `Element`, so it works on both.
 */
const show = (el: Element | null, on: boolean): void => {
  // `data-off` and not `hidden`, because all four of these are SVG and the `hidden`
  // content attribute is not permitted on a foreign element. It worked, since the
  // UA stylesheet's `[hidden] { display: none }` matches any element, but the W3C
  // validator rejects it and being right in practice is not the same as being
  // right. The paired rule is `svg[data-off]` in global.css.
  el?.toggleAttribute('data-off', !on);
};

function enhance(fig: HTMLElement): void {
  const video = fig.querySelector<HTMLVideoElement>('[data-video]');
  const bar = fig.querySelector<HTMLElement>('[data-bar]');
  const cover = fig.querySelector<HTMLButtonElement>('[data-cover]');
  const scrub = fig.querySelector<HTMLInputElement>('[data-scrub]');
  const toggle = fig.querySelector<HTMLButtonElement>('[data-toggle]');
  const elapsed = fig.querySelector<HTMLElement>('[data-elapsed]');
  if (!video || !bar || !scrub || !toggle || !elapsed) return;

  const shell = fig.querySelector<HTMLElement>('[data-shell]') ?? fig;
  const now = fig.querySelector<HTMLElement>('[data-now]');
  const cueBox = fig.querySelector<HTMLElement>('[data-captions]');
  const ccButton = fig.querySelector<HTMLButtonElement>('[data-cc]');
  const muteButton = fig.querySelector<HTMLButtonElement>('[data-mute]');
  const fullButton = fig.querySelector<HTMLButtonElement>('[data-full]');
  const iconPlay = fig.querySelector<HTMLElement>('[data-icon-play]');
  const iconPause = fig.querySelector<HTMLElement>('[data-icon-pause]');
  const iconVol = fig.querySelector<HTMLElement>('[data-icon-vol]');
  const iconMuted = fig.querySelector<HTMLElement>('[data-icon-muted]');
  const chapters = [...fig.querySelectorAll<HTMLButtonElement>('.chapter')];

  // Handing over: the native bar goes, ours arrives. In that order, so there is
  // never a frame with neither.
  video.removeAttribute('controls');
  bar.hidden = false;
  if (cover) cover.hidden = false;
  fig.dataset.enhanced = 'true';

  /**
   * The duration measured at build time, used until the file reports its own.
   *
   * `preload="none"` means `video.duration` is NaN until something is fetched, so
   * without this the readout would sit at 0:00 / 0:00 and the scrubber would have
   * nothing to scale against. The number comes from ffprobe by way of
   * src/media.json, so the player is accurate before it has downloaded anything.
   */
  const declared = Number(fig.dataset.duration) || 0;
  const runtime = () => (Number.isFinite(video.duration) && video.duration > 0 ? video.duration : declared);

  let dragging = false;

  const paint = (): void => {
    const total = runtime();
    const at = video.currentTime;
    const p = total > 0 ? Math.min(at / total, 1) : 0;

    if (!dragging) scrub.value = String(Math.round(p * 10000));
    scrub.style.setProperty('--p', String(p));
    elapsed.textContent = clock(at);

    // Buffered, drawn behind the played run. One range is enough: past the first
    // seek there can be several, and the one under the playhead is the only one
    // that tells you anything.
    let buffered = p;
    for (let i = 0; i < video.buffered.length; i += 1) {
      if (video.buffered.start(i) <= at && at <= video.buffered.end(i)) {
        buffered = total > 0 ? video.buffered.end(i) / total : p;
        break;
      }
    }
    scrub.style.setProperty('--b', String(Math.max(buffered, p)));

    if (chapters.length) {
      let index = 0;
      for (let i = 0; i < chapters.length; i += 1) {
        if (at + 0.05 >= Number(chapters[i].dataset.seek)) index = i;
      }
      for (const [i, c] of chapters.entries()) {
        c.setAttribute('aria-current', i === index ? 'true' : 'false');
      }
      if (now) now.textContent = chapters[index].textContent?.trim() ?? '';
    }
  };

  // While playing, paint on the frame clock rather than on `timeupdate`, which
  // fires about four times a second and makes a scrubber visibly step. The loop
  // exists only while playing, so an idle player costs nothing.
  let frame = 0;
  const loop = (): void => {
    paint();
    frame = requestAnimationFrame(loop);
  };
  const startLoop = (): void => {
    if (!frame) frame = requestAnimationFrame(loop);
  };
  const stopLoop = (): void => {
    if (frame) cancelAnimationFrame(frame);
    frame = 0;
    paint();
  };

  const setPlayingUi = (playing: boolean): void => {
    show(iconPlay, !playing);
    show(iconPause, playing);
    toggle.setAttribute('aria-label', playing ? 'Pause' : 'Play');
    fig.dataset.state = playing ? 'playing' : 'paused';
  };

  const play = (): void => {
    void video.play().catch(() => {
      /* A browser that refuses playback keeps its poster and its cover button. */
    });
  };

  video.addEventListener('play', () => {
    if (cover) cover.hidden = true;
    // The scrubber is worth showing from the first frame played and not before:
    // a full-width empty track over an untouched poster is a readout of nothing.
    fig.dataset.started = 'true';
    setPlayingUi(true);
    startLoop();
  });
  video.addEventListener('pause', () => {
    setPlayingUi(false);
    stopLoop();
    // Pausing puts the mark back over the picture, so a paused player says so
    // in the middle of the frame rather than only in the corner of the bar.
    if (cover && !video.ended) cover.hidden = false;
  });
  video.addEventListener('ended', () => {
    setPlayingUi(false);
    stopLoop();
    if (cover && !video.loop) cover.hidden = false;
  });
  video.addEventListener('loadedmetadata', paint);
  video.addEventListener('progress', paint);
  video.addEventListener('seeked', paint);
  video.addEventListener('timeupdate', () => {
    if (!frame) paint();
  });

  toggle.addEventListener('click', () => {
    if (video.paused) play();
    else video.pause();
  });
  cover?.addEventListener('click', play);

  // Clicking the picture is the one gesture every player has, so it is here too.
  // The cover button owns the first press; after that the video itself does.
  video.addEventListener('click', () => {
    if (video.paused) play();
    else video.pause();
  });

  scrub.addEventListener('pointerdown', () => {
    dragging = true;
  });
  const release = (): void => {
    dragging = false;
  };
  scrub.addEventListener('pointerup', release);
  scrub.addEventListener('pointercancel', release);
  scrub.addEventListener('blur', release);
  scrub.addEventListener('input', () => {
    const total = runtime();
    if (total > 0) video.currentTime = (Number(scrub.value) / 10000) * total;
    paint();
  });
  scrub.addEventListener('change', release);

  for (const c of chapters) {
    c.addEventListener('click', () => {
      video.currentTime = Number(c.dataset.seek);
      paint();
      if (video.paused) play();
    });
  }

  const setMuteUi = (): void => {
    show(iconVol, !video.muted);
    show(iconMuted, video.muted);
    muteButton?.setAttribute('aria-label', video.muted ? 'Unmute' : 'Mute');
  };

  muteButton?.addEventListener('click', () => {
    video.muted = !video.muted;
    setMuteUi();
  });

  // Drawn from the element's real state rather than assumed unmuted, so the
  // glyph is right on the first frame whatever the markup asked for.
  setMuteUi();

  /*
    Captions, drawn by this page.

    `mode = 'hidden'` still loads the track and still fires `cuechange`, but the
    browser stops painting its own overlay. So the cue text can be set in Inter,
    positioned where this composition wants it, and sized against the page rather
    than against the video element.
  */
  const track = video.textTracks[0];
  if (track && cueBox) {
    /*
      Off until asked for. The cut is built to be read with the sound off, so a
      caption band over every frame is a second copy of what the picture is
      already saying. The button is right there, it starts unpressed, and `c`
      turns them on from the keyboard.
    */
    let wanted = false;
    ccButton?.setAttribute('aria-pressed', 'false');
    track.mode = 'hidden';
    const draw = (): void => {
      const cue = track.activeCues?.[0] as VTTCue | undefined;
      const text = wanted && cue ? cue.text : '';
      cueBox.textContent = text;
      cueBox.hidden = text === '';
    };
    track.addEventListener('cuechange', draw);
    ccButton?.addEventListener('click', () => {
      wanted = !wanted;
      ccButton.setAttribute('aria-pressed', String(wanted));
      draw();
    });
  } else if (ccButton) {
    ccButton.hidden = true;
  }

  fullButton?.addEventListener('click', () => {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void shell.requestFullscreen?.().catch(() => video.requestFullscreen?.());
  });
  document.addEventListener('fullscreenchange', () => {
    const on = document.fullscreenElement === shell;
    fig.dataset.fullscreen = on ? 'true' : 'false';
    fullButton?.setAttribute('aria-label', on ? 'Exit fullscreen' : 'Fullscreen');
  });

  /*
    Keyboard, scoped to the player.

    Only while focus is inside this figure, so none of it competes with the page.
    Keys that a focused control already handles are left alone: Space on a button
    activates the button, and arrows on the scrubber are the range input's own.
  */
  fig.addEventListener('keydown', (event: KeyboardEvent) => {
    const target = event.target as HTMLElement;
    const onControl = target.closest('button, input, a');
    const seek = (by: number): void => {
      video.currentTime = Math.max(0, Math.min(runtime(), video.currentTime + by));
      paint();
    };

    switch (event.key) {
      case ' ':
      case 'k':
        if (onControl) return;
        event.preventDefault();
        if (video.paused) play();
        else video.pause();
        return;
      case 'ArrowLeft':
        if (target === scrub) return;
        event.preventDefault();
        seek(-5);
        return;
      case 'ArrowRight':
        if (target === scrub) return;
        event.preventDefault();
        seek(5);
        return;
      case 'Home':
        if (target === scrub) return;
        event.preventDefault();
        video.currentTime = 0;
        paint();
        return;
      case 'm':
        if (muteButton) muteButton.click();
        return;
      case 'c':
        if (ccButton && !ccButton.hidden) ccButton.click();
        return;
      case 'f':
        fullButton?.click();
        return;
      default:
    }
  });

  /*
    The payoff clip starts itself once it is on screen.

    It is eleven seconds, muted, and carries nothing the poster frame does not,
    so it is safe to start unasked. It does not start for anyone who asked for
    reduced motion, it pauses when it scrolls away rather than looping into an
    empty room, and a deliberate pause is respected from then on.

    The promo does not do this and should not be given it. It is forty seconds
    and six megabytes, so starting it on scroll would spend that on every reader
    who got as far as the film, and every browser refuses autoplay with sound,
    so what they would get for it is a narrated film playing silently. It stays
    behind its poster until somebody asks for it.
  */
  if (fig.dataset.autoplay === 'view' && 'IntersectionObserver' in window) {
    let auto = false;
    new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (stillness.matches) return;
          if (entry.isIntersecting) {
            auto = true;
            play();
          } else if (auto) {
            video.pause();
          }
        }
      },
      { threshold: 0.45 },
    ).observe(video);
    video.addEventListener('pause', () => {
      if (!video.ended) auto = false;
    });
  }

  paint();
  setPlayingUi(!video.paused);
}

export function enhancePlayers(): void {
  for (const fig of document.querySelectorAll<HTMLElement>('[data-player]')) enhance(fig);
}
