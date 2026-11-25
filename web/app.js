(() => {
  "use strict";
  const $ = (s, r = document) => r.querySelector(s), $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const calm = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const clamp = (v, a = 0, b = 1) => Math.min(b, Math.max(a, v)), mix = (a, b, t) => a + (b - a) * t;
  const smooth = (t) => t * t * (3 - 2 * t);
  let LIME = "200,242,80";

  // Each animated part runs only while it is on screen, off one shared frame loop.
  const parts = [];
  let raf = 0;
  const frame = (now) => {
    raf = 0;
    if (document.hidden) return;
    let busy = false;
    for (const p of parts) if (p.seen && p.draw(now)) busy = true;
    if (busy) wake();
  };
  const wake = () => { if (!raf) raf = requestAnimationFrame(frame); };
  const part = (el, draw) => {
    const p = { seen: false, draw };
    parts.push(p);
    new IntersectionObserver(([e]) => { p.seen = e.isIntersecting; if (p.seen) wake(); }, { rootMargin: "80px" }).observe(el);
    return p;
  };
  const fit = (c) => {
    const d = Math.min(devicePixelRatio || 1, 2), w = c.clientWidth, h = c.clientHeight;
    if (c.width !== Math.round(w * d) || c.height !== Math.round(h * d)) { c.width = Math.round(w * d); c.height = Math.round(h * d); }
    const g = c.getContext("2d");
    g.setTransform(d, 0, 0, d, 0, 0);
    return [g, w, h];
  };
  addEventListener("scroll", wake, { passive: true });
  addEventListener("resize", wake, { passive: true });
  document.addEventListener("visibilitychange", wake);

  const fine = matchMedia("(hover: hover) and (pointer: fine)").matches;

  // ---- the page is the pad: dots wake up near the pointer, as they do in the app, and waves cross them
  const waves = [];
  let ripple = () => {};
  if (!calm) {
    const c = $(".field"), at = { x: -999, y: -999, k: 0, goal: 0 };
    let used = false;
    ripple = (x, y, big = 1) => { waves.push({ x, y, t: performance.now(), big }); wake(); };
    if (fine) {
      addEventListener("pointermove", (e) => {
        at.x = e.clientX; at.y = e.clientY;
        at.goal = e.target.closest?.(".hero-phone, a, button, summary") ? 0 : 1;
        wake();
      }, { passive: true });
      document.documentElement.addEventListener("pointerleave", () => { at.goal = 0; wake(); });
      addEventListener("pointerdown", (e) => { if (!e.target.closest(".hero-phone, a, button, summary")) ripple(e.clientX, e.clientY, .45); }, { passive: true });
    }
    parts.push({ seen: true, draw(now) {
      at.k += (at.goal - at.k) * .16;
      const spot = at.k > .01;
      while (waves.length && now - waves[0].t > 1500) waves.shift();
      if (!spot && !waves.length) { if (used) { const [g, w, h] = fit(c); g.clearRect(0, 0, w, h); used = false; } return Math.abs(at.goal - at.k) > .01; }
      const [g, w, h] = fit(c), S = 26, R = 112;
      g.clearRect(0, 0, w, h); used = true;
      const dot = (x, y, n) => { g.fillStyle = `rgba(${LIME},${.15 + .75 * n})`; g.beginPath(); g.arc(x, y, 1 + 1.9 * n, 0, 7); g.fill(); };
      const box = (x, y, r, f) => {
        for (let b = Math.max(0, Math.floor((y - r) / S)) * S + S / 2; b < Math.min(h, y + r); b += S)
          for (let q = Math.max(0, Math.floor((x - r) / S)) * S + S / 2; q < Math.min(w, x + r); q += S) { const n = f(q, b); if (n > .03) dot(q, b, n); }
      };
      if (spot) box(at.x, at.y, R, (q, b) => (1 - Math.hypot(q - at.x, b - at.y) / R) * at.k);
      for (const v of waves) {
        const t = (now - v.t) / 1500, r = 1500 * v.big * (1 - (1 - t) ** 2.2), band = 90 * (.5 + v.big);
        box(v.x, v.y, r + band, (q, b) => Math.max(0, 1 - Math.abs(Math.hypot(q - v.x, b - v.y) - r) / band) * (1 - t) ** 1.4);
      }
      return waves.length > 0 || Math.abs(at.goal - at.k) > .01;
    } });
  }

  // ---- smooth scrolling: mouse wheels glide. Touchpads, touch and keys are already smooth, so they are left alone.
  const glide = { on: false, to: 0, pos: 0, last: 0, tau: 95 };
  const limit = () => document.documentElement.scrollHeight - innerHeight;
  const glideTo = (y, tau = 95) => {
    if (calm) return scrollTo(0, y);
    if (!glide.on) { glide.on = true; glide.pos = scrollY; glide.last = performance.now(); requestAnimationFrame(roll); }
    glide.to = clamp(y, 0, limit()); glide.tau = tau;
  };
  const roll = (now) => {
    if (!glide.on) return;
    if (Math.abs(scrollY - Math.round(glide.pos)) > 3 && Math.abs(scrollY - glide.pos) > 3) { glide.on = false; return; }   // something else scrolled
    glide.pos += (glide.to - glide.pos) * (1 - Math.exp(-clamp(now - glide.last, 0, 50) / glide.tau));
    glide.last = now;
    if (Math.abs(glide.to - glide.pos) < .6) { scrollTo(0, glide.to); glide.on = false; return; }
    scrollTo(0, glide.pos);
    requestAnimationFrame(roll);
  };
  if (!calm) {
    addEventListener("wheel", (e) => {
      if (e.ctrlKey || e.shiftKey || e.metaKey || !e.deltaY) return;
      const notch = e.deltaMode === 1 || (Math.abs(e.wheelDeltaY) >= 120 && e.wheelDeltaY % 120 === 0);
      if (!notch) { glide.on = false; return; }
      e.preventDefault();
      glideTo((glide.on ? glide.to : scrollY) + (e.deltaMode === 1 ? e.deltaY * 34 : e.deltaY));
    }, { passive: false });
    for (const t of ["keydown", "pointerdown", "touchstart"]) addEventListener(t, () => { glide.on = false; }, { passive: true });
  }
  document.addEventListener("click", (e) => {
    const l = e.target.closest?.('a[href^="#"]');
    if (!l || e.metaKey || e.ctrlKey) return;
    const to = document.getElementById(l.hash.slice(1));
    if (!to) return;
    e.preventDefault();
    const pad = l.hash === "#top" ? 1e6 : to.classList.contains("connect") ? 0 : 60;
    glideTo(Math.max(0, scrollY + to.getBoundingClientRect().top - pad + (to.classList.contains("connect") ? 0 : 20)), 190);
    history.replaceState(null, "", l.hash);
    to.setAttribute("tabindex", "-1"); to.focus({ preventScroll: true });
  });

  // ---- the page's own pointer: the arrow is exact, the halo follows and wraps what it can press
  if (fine && !calm) {
    const root = document.documentElement;
    const p = document.createElement("div"), o = document.createElement("div");
    p.className = "ptr"; o.className = "aura";
    p.setAttribute("aria-hidden", "true"); o.setAttribute("aria-hidden", "true");
    p.innerHTML = '<svg viewBox="0 0 24 30"><path d="M2 2v21.5l5.4-5 3.7 8.6 4-1.7-3.7-8.4h7.6z"/></svg>';
    document.body.append(o, p);
    const m = { x: 0, y: 0 }, v = { x: 0, y: 0, w: 38, h: 38, r: 19 };
    let hug = null, started = false, down = 0, last = 0;
    const move = (e) => {
      if (e.pointerType !== "mouse") { root.classList.remove("own"); return; }
      m.x = e.clientX; m.y = e.clientY;
      if (!started) { started = true; v.x = m.x - 19; v.y = m.y - 19; }
      root.classList.add("own");
      const t = document.elementFromPoint(m.x, m.y);
      root.classList.toggle("away", !!t?.closest(".hero-phone"));
      hug = t?.closest(".btn, .nav a, .deck button, .bar a, .modes li, summary, .sum, footer a, .fine a, dd a") || null;
      p.style.transform = `translate(${m.x - 1.5}px,${m.y - 1.5}px) scale(${down ? .86 : 1})`;
      wake();
    };
    addEventListener("pointermove", move, { passive: true });
    addEventListener("pointerdown", (e) => { down = 1; move(e); }, { passive: true });
    addEventListener("pointerup", (e) => { down = 0; move(e); }, { passive: true });
    root.addEventListener("pointerleave", () => root.classList.add("away"));
    root.addEventListener("pointerenter", () => root.classList.remove("away"));
    parts.push({ seen: true, draw(now) {
      if (!started) return false;
      const dt = clamp(now - last || 16, 0, 40); last = now;
      let x = m.x - 19, y = m.y - 19, w = 38, h = 38, r = 19;
      if (hug?.isConnected) {
        const b = hug.getBoundingClientRect(), s = getComputedStyle(hug), g = hug.matches(".btn, .deck button, .nav a, .bar a") ? 5 : 8;
        // It leans a little towards the pointer, so it feels held rather than snapped.
        const lx = clamp((m.x - b.left) / b.width) - .5, ly = clamp((m.y - b.top) / b.height) - .5;
        x = b.left - g + lx * 6; y = b.top - g + ly * 5; w = b.width + 2 * g; h = b.height + 2 * g;
        r = Math.min(h / 2, (parseFloat(s.borderTopLeftRadius) || 10) + g);
      }
      o.classList.toggle("hug", !!hug);
      const k = 1 - Math.exp(-dt / (hug ? 58 : 72));
      let far = 0;
      for (const [n, t] of [["x", x], ["y", y], ["w", w], ["h", h], ["r", r]]) { v[n] += (t - v[n]) * k; far = Math.max(far, Math.abs(t - v[n])); }
      o.style.transform = `translate(${v.x}px,${v.y}px)`;
      o.style.width = `${v.w}px`; o.style.height = `${v.h}px`; o.style.borderRadius = `${v.r}px`;
      return far > .15;
    } });
    addEventListener("scroll", () => { if (started) { const t = document.elementFromPoint(m.x, m.y); hug = t?.closest(".btn, .nav a, .deck button, .bar a, .modes li, summary, .sum, footer a, .fine a, dd a") || null; } }, { passive: true });
  }

  // ---- hero: drag on the phone, the laptop's cursor follows; tap picks up the sun
  {
    const stage = $(".stage"), lcd = $(".lcd"), cur = $(".cursor"), pad = $(".touch"), finger = $(".finger"), phone = $(".hero-phone"), clock = $(".clock");
    const sun = $(".sun"), halo = $(".halo"), stars = $(".stars");
    const el = { s0: $(".s0"), s1: $(".s1"), h0: $(".h0"), h1: $(".h1"), m1: $(".m1"), m2: $(".m2"), m3: $(".m3") };
    const hex = (s) => [1, 3, 5].map((i) => parseInt(s.slice(i, i + 2), 16));
    const DAY = { s0: ["#0a1022", "#3a3f74", "#4d9bdc"], s1: ["#1c2740", "#f3a273", "#d9eef3"], m1: ["#1b263c", "#80607a", "#78ada2"], m2: ["#121b2d", "#4e3c58", "#417f71"],
      m3: ["#0a111e", "#2a2438", "#1f584a"], sun: ["#e6ebf6", "#ffd49a", "#fff4cb"] };
    for (const k in DAY) DAY[k] = DAY[k].map(hex);
    const tone = (k, t) => { const i = t < .5 ? 0 : 1, f = smooth(t < .5 ? t * 2 : t * 2 - 1), a = DAY[k][i], b = DAY[k][i + 1]; return `rgb(${a.map((v, n) => Math.round(mix(v, b[n], f)))})`; };
    let seed = 7;
    const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
    let s = "";
    for (let i = 0; i < 70; i++) s += `<circle cx="${(rnd() * 1600) | 0}" cy="${(rnd() * 560) | 0}" r="${(.8 + rnd() * 1.9).toFixed(1)}" opacity="${(.3 + rnd() * .6).toFixed(2)}"/>`;
    stars.innerHTML = s;

    const st = { cx: .34, cy: .6, sx: .6, sy: .56, fx: .4, fy: .62, held: false, mine: false, down: false, last: 0, t: -1 };
    const near = () => Math.hypot((st.cx - st.sx) * 1.6, st.cy - st.sy) < .13;
    const scene = () => {
      const t = clamp((.64 - st.sy) / .46);
      if (Math.abs(t - st.t) > .002) {
        st.t = t;
        for (const k of ["s0", "s1"]) el[k].setAttribute("stop-color", tone(k, t));
        for (const k of ["m1", "m2", "m3"]) el[k].setAttribute("fill", tone(k, t));
        const c = tone("sun", t);
        sun.setAttribute("fill", c); el.h0.setAttribute("stop-color", c); el.h1.setAttribute("stop-color", c);
        stars.setAttribute("opacity", clamp(1 - t * 2.4));
        const m = Math.round(mix(282, 742, t));
        clock.textContent = `${Math.floor(m / 60)}:${String(m % 60).padStart(2, "0")}`;
      }
      const x = st.sx * 1600, y = st.sy * 1000;
      for (const e of [sun, halo]) { e.setAttribute("cx", x); e.setAttribute("cy", y); }
      sun.setAttribute("r", st.held ? 92 : near() ? 88 : 84);
    };
    const place = () => {
      cur.style.transform = `translate(${st.cx * lcd.clientWidth}px,${st.cy * lcd.clientHeight}px)`;
      finger.style.transform = `translate(${st.fx * pad.clientWidth}px,${st.fy * pad.clientHeight}px)`;
      scene();
    };
    const by = (dx, dy) => {
      const nx = clamp(st.cx + dx, .01, .97), ny = clamp(st.cy + dy, .01, .96);
      if (st.held) { st.sx = clamp(st.sx + nx - st.cx, .07, .93); st.sy = clamp(st.sy + ny - st.cy, .14, .68); }
      st.cx = nx; st.cy = ny;
    };
    const tap = () => {
      finger.classList.remove("tap"); void finger.offsetWidth; finger.classList.add("tap");
      if (st.held) st.held = false; else if (near()) st.held = true;
      wake();
    };
    const on = (e) => { const r = pad.getBoundingClientRect(); return [clamp((e.clientX - r.left) / r.width), clamp((e.clientY - r.top) / r.height), r]; };
    let prev = null, from = null;
    const start = (e) => { st.mine = true; st.last = performance.now(); const [x, y] = on(e); st.fx = x; st.fy = y; prev = [e.clientX, e.clientY]; wake(); };
    phone.addEventListener("pointerenter", (e) => { if (e.pointerType === "mouse") start(e); });
    phone.addEventListener("pointerdown", (e) => { try { phone.setPointerCapture(e.pointerId); } catch {} start(e); st.down = true; from = [e.clientX, e.clientY, performance.now()]; e.preventDefault(); });
    phone.addEventListener("pointermove", (e) => {
      if (!prev || (e.pointerType !== "mouse" && !st.down)) return;
      const [x, y, r] = on(e), dx = e.clientX - prev[0], dy = e.clientY - prev[1];
      // The app's idea in one line: slow is precise, fast crosses the screen.
      const gain = .85 + Math.min(1.5, Math.hypot(dx, dy) / 14);
      by(dx / r.width * .52 * gain, dy / r.width * .83 * gain);
      st.fx = x; st.fy = y; prev = [e.clientX, e.clientY]; st.last = performance.now();
      phone.classList.add("used"); wake();
    });
    const end = (e) => {
      if (st.down && from && performance.now() - from[2] < 260 && Math.hypot(e.clientX - from[0], e.clientY - from[1]) < 9) tap();
      st.down = false; st.last = performance.now(); if (e.pointerType !== "mouse") prev = null;
    };
    phone.addEventListener("pointerup", end);
    phone.addEventListener("pointercancel", end);

    // Until someone takes over: reach the sun, pick it up, carry it into the sky, set it down, bring it back.
    const ROUTE = [[0, .34, .6], [1.2, .34, .6], [2.6, .6, .56], [3, .6, .56, "tap"], [5.2, .5, .2], [5.9, .5, .2, "tap"], [7.2, .74, .42], [8.2, .74, .42], [9.2, .5, .2], [9.6, .5, .2, "tap"], [11.6, .6, .56], [12, .6, .56, "tap"], [13.4, .34, .6]];
    let t0 = 0, step = 0;
    const reset = (now) => { t0 = now; step = 0; st.held = false; st.auto = [st.cx, st.cy, st.sx, st.sy]; };
    part(stage, (now) => {
      if (st.mine && !st.down && now - st.last > 5200) { st.mine = false; reset(now); }
      if (!st.mine && !calm) {
        if (!t0) reset(now);
        let t = (now - t0) / 1000;
        // Ease from wherever the visitor left things to the start of the route.
        if (st.auto && t < 1.2) {
          const k = smooth(t / 1.2), a = st.auto;
          st.cx = mix(a[0], .34, k); st.cy = mix(a[1], .6, k); st.sx = mix(a[2], .6, k); st.sy = mix(a[3], .56, k);
        } else {
          st.auto = null;
          if (t >= ROUTE.at(-1)[0]) { t0 = now; step = 0; t = 0; st.auto = [st.cx, st.cy, st.sx, st.sy]; }
          while (step < ROUTE.length - 2 && t >= ROUTE[step + 1][0]) { step++; if (ROUTE[step][3]) tap(); }
          const a = ROUTE[step], b = ROUTE[step + 1], k = smooth(clamp((t - a[0]) / (b[0] - a[0])));
          const nx = mix(a[1], b[1], k), ny = mix(a[2], b[2], k);
          by(nx - st.cx, ny - st.cy);
        }
        st.fx = clamp(.5 + (st.cx - .52) * 1.15, .12, .88); st.fy = clamp(.5 + (st.cy - .42) * .72, .12, .86);
      }
      place();
      return !calm;
    });
    place();
    addEventListener("resize", place);
  }

  // ---- three connections: one line that becomes a cable, a wave, then a trail of dots
  {
    const sec = $(".connect"), c = $(".wire"), items = $$(".modes li"), words = $$(".word span");
    let pos = 0, goal = 0;
    items.forEach((li, i) => li.addEventListener("click", () => {
      const r = sec.getBoundingClientRect();
      glideTo(scrollY + r.top + (r.height - innerHeight) * (i + .5) / 3, 170);
    }));
    const shot = new Image();
    shot.src = "/img/pad.webp";
    const face = (g, x, y, w, h, r, f) => { g.save(); g.beginPath(); g.roundRect(x - w / 2 + 5, y - h / 2 + 5, w - 10, h - 10, Math.max(2, r - 4)); g.clip(); f(x - w / 2 + 5, y - h / 2 + 5, w - 10, h - 10); g.restore(); };
    const hills = (g, x, y, w, h) => {
      const s = g.createLinearGradient(0, y, 0, y + h);
      s.addColorStop(0, "#39407a"); s.addColorStop(.78, "#f0a074"); g.fillStyle = s; g.fillRect(x, y, w, h);
      g.fillStyle = "#ffd9a0"; g.beginPath(); g.arc(x + w * .66, y + h * .46, h * .1, 0, 7); g.fill();
      for (const [k, c] of [[.62, "#80607a"], [.74, "#4e3c58"], [.87, "#2a2438"]]) {
        g.fillStyle = c; g.beginPath(); g.moveTo(x, y + h);
        for (let i = 0; i <= 24; i++) g.lineTo(x + w * i / 24, y + h * (k + .055 * Math.sin(i / 24 * 7 + k * 21)));
        g.lineTo(x + w, y + h); g.fill();
      }
    };
    const device = (g, x, y, w, h, r, lit) => {
      g.beginPath(); g.roundRect(x - w / 2, y - h / 2, w, h, r); g.fillStyle = "#30353b"; g.fill();
      g.beginPath(); g.roundRect(x - w / 2 + 5, y - h / 2 + 5, w - 10, h - 10, Math.max(2, r - 4)); g.fillStyle = "#15171a"; g.fill();
      g.beginPath(); g.arc(x, y, 4.5, 0, 7); g.fillStyle = `rgba(${LIME},${lit})`; g.fill();
    };
    part(sec, (now) => {
      const r = sec.getBoundingClientRect(), p = clamp(-r.top / Math.max(1, r.height - innerHeight));
      const idx = Math.min(2, Math.floor(p * 3));
      goal = calm ? 1 : clamp(p * 3 - .5, 0, 2);
      pos += (goal - pos) * (calm ? 1 : .14);
      items.forEach((li, i) => { li.classList.toggle("on", i === idx); li.firstElementChild.style.setProperty("--p", `${clamp(p * 3 - i) * 100}%`); });
      words.forEach((s, i) => { const d = pos - i; s.style.setProperty("--o", clamp(1 - Math.abs(d) * 1.7, .004).toFixed(3)); s.style.setProperty("--x", `${(-d * 34).toFixed(2)}vw`); });
      const [g, w, h] = fit(c), t = now / 1000, mid = h / 2, small = w < 600;
      g.clearRect(0, 0, w, h);
      const W = [clamp(1 - pos), clamp(1 - Math.abs(pos - 1)), clamp(pos - 1)];
      const ph = small ? 92 : Math.min(236, h * .74), pw = ph * .49, lh = small ? 78 : Math.min(224, h * .68), lw = lh * 1.6;
      const x0 = pw / 2 + 2, x1 = w - lw / 2 - 10, a = x0 + pw / 2 + 12, b = x1 - lw / 2 - 12, L = b - a, amp = Math.min(h * .36, small ? 46 : 112);
      const y = (u) => mid + Math.sin(u * Math.PI) * (amp * .78 * W[0] + Math.sin(u * 15 - t * 5) * amp * W[1] - amp * .5 * Math.abs(Math.sin(u * Math.PI * 5)) * W[2]);
      if (W[0] + W[1] > .01) {
        g.beginPath();
        for (let i = 0; i <= 140; i++) { const u = i / 140; g.lineTo(a + L * u, y(u)); }
        g.lineWidth = 2 + 6 * W[0]; g.lineCap = "round"; g.lineJoin = "round";
        g.strokeStyle = `rgba(${W[0] > .5 ? "72,79,87" : LIME},${W[0] > .5 ? 1 : .35 + .4 * W[1]})`; g.stroke();
      }
      if (W[0] > .02) { g.fillStyle = `rgba(150,158,166,${W[0]})`; for (const [x, s] of [[a, 1], [b, -1]]) { g.beginPath(); g.roundRect(x - 8 + s * 2, mid - 8, 16, 16, 4); g.fill(); } }
      if (W[2] > .02) for (let i = 0; i < 42; i++) { const u = (i + .5) / 42; g.beginPath(); g.arc(a + L * u, y(u), 2, 0, 7); g.fillStyle = `rgba(150,158,166,${.5 * W[2]})`; g.fill(); }
      // What travels: steady over the cable, riding the wave, hopping over Bluetooth.
      const n = small ? 5 : 9, speed = .5 * W[0] + .34 * W[1] + .3 * W[2];
      for (let i = 0; i < n; i++) {
        const u = (i / n + t * speed) % 1, f = Math.sin(u * Math.PI);
        g.beginPath(); g.arc(a + L * u, y(u), (3 + 2.4 * W[2]) * (.5 + .5 * f), 0, 7); g.fillStyle = `rgba(${LIME},${.25 + .75 * f})`; g.fill();
      }
      device(g, x0, mid, pw, ph, pw * .2, 1); device(g, x1, mid - 6, lw, lh, 12, 1);
      if (shot.complete && shot.naturalWidth) face(g, x0, mid, pw, ph, pw * .2, (x, y, w2, h2) => g.drawImage(shot, x, y, w2, h2));
      face(g, x1, mid - 6, lw, lh, 12, (x, y, w2, h2) => hills(g, x, y, w2, h2));
      g.beginPath(); g.roundRect(x1 - lw / 2 - 9, mid + lh / 2 - 4, lw + 18, 9, 4); g.fillStyle = "#30353b"; g.fill();
      return !calm;
    });
  }

  // ---- features: one phone that turns itself to suit what is being said
  {
    const show = $(".show"), imgs = $$(".show img"), steps = $$(".steps article"), dots = $(".dots"), fx = $$(".fx");
    const POSE = [{}, { "--ry": "-13deg", "--rx": "3deg" }, { "--rx": "28deg", "--rz": "-13deg", "--ry": "9deg" }, { "--ry": "12deg" }, { "--ry": "-7deg", "--rx": "5deg" }, { "--rz": "-90deg", "--k": "1.16" }];
    let cur = -1;
    // Lying down, the phone is as wide as it was tall: shrink it to its column.
    const lie = () => show.style.setProperty("--k", String(Math.min(1.16, show.parentElement.clientWidth * .97 / show.offsetHeight).toFixed(3)));
    addEventListener("resize", () => { if (cur === 5) lie(); });
    const set = (i) => {
      if (i === cur) return;
      cur = i;
      steps.forEach((s, n) => s.classList.toggle("on", n === i));
      imgs.forEach((m, n) => m.classList.toggle("on", n === i));
      dots.classList.toggle("on", i === 0 && !calm);
      fx.forEach((f) => f.classList.toggle("on", +f.dataset.f === i && !calm));
      for (const k of ["--rx", "--ry", "--rz", "--k"]) show.style.setProperty(k, calm && k !== "--rz" ? (k === "--k" ? "1" : "0deg") : POSE[i][k] || (k === "--k" ? "1" : "0deg"));
      if (i === 5) lie();
    };
    set(0);
    part($(".track"), () => {
      const line = innerHeight * (innerWidth < 861 ? .72 : .5);
      let best = 0, d = 1e9;
      steps.forEach((s, i) => { const r = s.getBoundingClientRect(), m = Math.abs((innerWidth < 861 ? r.bottom - innerHeight * .26 : r.top + r.height / 2) - line); if (m < d) { d = m; best = i; } });
      set(best);
      return false;
    });
    // The air mouse scene answers the visitor's own pointer.
    if (!calm) addEventListener("pointermove", (e) => {
      if (cur !== 2 || e.pointerType !== "mouse") return;
      show.style.setProperty("--ry", `${mix(-26, 26, e.clientX / innerWidth)}deg`);
      show.style.setProperty("--rx", `${mix(26, -20, e.clientY / innerHeight)}deg`);
    }, { passive: true });
  }

  // ---- a deck whose keys run this page
  {
    const HUES = ["#c8f250", "#7fe0c3", "#9db8ff", "#e3b7ff", "#ff9e8a", "#ffd27a"];
    let hue = 0, typing = 0;
    const toast = (t) => { $(".toast")?.remove(); const d = document.createElement("div"); d.className = "toast"; d.setAttribute("role", "status"); d.textContent = t; document.body.append(d); setTimeout(() => d.remove(), 2300); };
    const centre = (b) => { const r = b.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; };
    const DO = {
      colour(b) {
        hue = (hue + 1) % HUES.length;
        const rgb = [1, 3, 5].map((i) => parseInt(HUES[hue].slice(i, i + 2), 16)), s = document.documentElement.style;
        LIME = rgb.join(","); s.setProperty("--lime", HUES[hue]); s.setProperty("--rgb", rgb.join(" ")); b.style.setProperty("--t", HUES[hue]);
        ripple(...centre(b), .6);
      },
      ripple: (b) => ripple(...centre(b), 1),
      shot() { const f = document.createElement("div"); f.className = "flash"; document.body.append(f); setTimeout(() => f.remove(), 600); toast("Captured. Well, it would be."); },
      type() {
        const h = $(".typed"), s = $("span", h), full = "Typed by a deck key.", id = ++typing;
        h.classList.add("go"); s.textContent = "";
        if (calm) { s.textContent = full; return; }
        let n = 0;
        const step = () => {
          if (id !== typing) return;
          s.textContent = full.slice(0, ++n);
          if (n < full.length) setTimeout(step, 34 + Math.random() * 46);
          else setTimeout(() => { if (id === typing) { h.classList.remove("go"); s.textContent = "A deck you can press right now."; } }, 3200);
        };
        setTimeout(step, 180);
      },
      async copy() { try { await navigator.clipboard.writeText(location.origin); toast("Link copied"); } catch { toast(location.host); } },
      open: () => glideTo(scrollY + $("#download").getBoundingClientRect().top, 200),
    };
    $$(".deck button").forEach((b) => b.addEventListener("click", () => DO[b.dataset.k](b)));
    // Keys tip towards the pointer, as a pressed key would.
    if (fine && !calm) $$(".deck button").forEach((b) => {
      b.addEventListener("pointermove", (e) => { const r = b.getBoundingClientRect(); b.style.transform = `rotateY(${((e.clientX - r.left) / r.width - .5) * 14}deg) rotateX(${(.5 - (e.clientY - r.top) / r.height) * 14}deg)${e.buttons ? " scale(.94)" : ""}`; });
      b.addEventListener("pointerleave", () => { b.style.transform = ""; });
    });
  }

  // ---- which section the nav is in
  {
    const links = $$(".nav .opt"), seen = new Map(links.map((l) => [document.getElementById(l.hash.slice(1)), l]));
    const io = new IntersectionObserver((es) => es.forEach((e) => { if (e.isIntersecting) links.forEach((l) => l.classList.toggle("on", l === seen.get(e.target))); else seen.get(e.target).classList.remove("on"); }), { rootMargin: "-48% 0px -48% 0px" });
    seen.forEach((_, el) => io.observe(el));
  }

  // ---- setup: a code dissolves into the pad's dot grid
  {
    const sec = $(".setup"), c = $(".qr"), N = 25, cells = [];
    let seed = 11;
    const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
    const eye = (x, y) => [[0, 0], [N - 7, 0], [0, N - 7]].map(([a, b]) => [x - a, y - b]).find(([a, b]) => a >= -1 && b >= -1 && a <= 7 && b <= 7);
    for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
      const e = eye(x, y);
      let on = rnd() > .52;
      if (e) { const [a, b] = e; on = a >= 0 && b >= 0 && a <= 6 && b <= 6 && (a === 0 || b === 0 || a === 6 || b === 6 || (a > 1 && a < 5 && b > 1 && b < 5)); }
      cells.push({ x, y, on, d: rnd() * .5 });
    }
    let k = 0;
    part(sec, (now) => {
      const r = sec.getBoundingClientRect(), goal = calm ? 0 : clamp((innerHeight * .62 - r.top) / (innerHeight * .75));
      k += (goal - k) * .12;
      const [g, w] = fit(c), s = w / N, fx = w * (.5 + .3 * Math.sin(now / 900)), fy = w * (.5 + .26 * Math.sin(now / 640 + 1));
      g.clearRect(0, 0, w, w);
      for (const q of cells) {
        const t = smooth(clamp((k - q.d) / .5)), px = (q.x + .5) * s, py = (q.y + .5) * s;
        const n = t * Math.max(0, 1 - Math.hypot(px - fx, py - fy) / (w * .24));
        const size = mix(q.on ? s * .96 : 0, s * .17, t) + n * s * .34, rad = mix(1, size / 2, t);
        if (size < .4) continue;
        g.fillStyle = n > 0 ? `rgba(${LIME},${.35 + .65 * n})` : t > .5 ? "#3a4047" : "#f1f3f2";
        g.beginPath(); g.roundRect(px - size / 2, py - size / 2, size, size, rad); g.fill();
      }
      return !calm && (k > .3 || Math.abs(goal - k) > .002);
    });
  }

  // ---- downloads
  {
    const ua = navigator.userAgent, os = /Android/i.test(ua) ? "android" : /Windows/i.test(ua) ? "windows" : null;
    if (os) {
      $(".pair").classList.add("known");
      $(`[data-os=${os}]`).classList.add("mine");
      $$(".hero .btn").forEach((b) => b.classList.toggle("main", b.dataset.dl === os));
      $$(".bar a")[os === "windows" ? 0 : 1].classList.add("mine");
      if (os === "android") $(".acts").prepend($("[data-dl=android]"));
    }
    $$(".sum").forEach((b) => b.addEventListener("click", async () => {
      const em = $("em", b);
      try { await navigator.clipboard.writeText(b.dataset.sum); em.textContent = "Copied"; } catch { $("code", b).textContent = b.dataset.sum; em.textContent = ""; }
      setTimeout(() => { if (em.textContent) em.textContent = "Copy"; }, 1800);
    }));
    // The bar rests while the hero, the pinned section's captions or the downloads are there to do its job.
    const bar = $(".bar"), away = new Set();
    const io = new IntersectionObserver((es) => {
      es.forEach((e) => e.isIntersecting ? away.add(e.target) : away.delete(e.target));
      const up = !away.size;
      bar.classList.toggle("up", up);
      bar.setAttribute("aria-hidden", String(!up));
      $$("a", bar).forEach((a) => { a.tabIndex = up ? 0 : -1; });
    });
    [$(".hero"), $(".connect"), $(".get"), $("footer")].forEach((e) => io.observe(e));
  }

  // ---- stars and downloads, straight from GitHub. If it does not answer, nothing is shown.
  {
    const KEY = "syncpad-stats", API = "https://api.github.com/repos/DarkNull07/SyncPad";
    const short = (n) => n < 1000 ? String(n) : (n / 1000).toFixed(n < 10000 ? 1 : 0).replace(/\.0$/, "") + "k";
    const show = ({ stars, exe, apk }) => {
      const s = $(".stars-n"), t = $(".total"), all = exe + apk;
      if (stars > 0) { s.textContent = short(stars); s.setAttribute("aria-label", `${stars} stars`); s.hidden = false; }
      if (all > 0) { t.textContent = `${all.toLocaleString("en")} download${all === 1 ? "" : "s"} so far. `; t.hidden = false; }
    };
    const load = async () => {
      try {
        const c = JSON.parse(sessionStorage.getItem(KEY));
        if (c && Date.now() - c.at < 600000) return show(c);
      } catch {}
      try {
        const get = async (p) => { const r = await fetch(API + p, { headers: { Accept: "application/vnd.github+json" } }); if (!r.ok) throw 0; return r.json(); };
        const [repo, rel] = await Promise.all([get(""), get("/releases?per_page=100")]);
        const sum = (re) => rel.flatMap((x) => x.assets || []).filter((x) => re.test(x.name)).reduce((n, x) => n + x.download_count, 0);
        const d = { at: Date.now(), stars: repo.stargazers_count | 0, exe: sum(/\.(exe|zip)$/), apk: sum(/\.apk$/) };
        show(d);
        try { sessionStorage.setItem(KEY, JSON.stringify(d)); } catch {}
      } catch {}
    };
    // After everything that matters has loaded.
    if (document.readyState === "complete") load(); else addEventListener("load", load);
  }
})();
