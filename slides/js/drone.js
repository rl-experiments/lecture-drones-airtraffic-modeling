/**
 * Animated SVG quadcopter drone that hovers on cover slides and roams the page.
 *
 * Usage (same contract as the other slide critters):
 *   const d = initDrone();                                   // roam the viewport
 *   const d = initDrone({ perched: true, anchor: '.cover-card' }); // hover at an element
 *   d.destroy();                                             // remove from DOM
 *
 * Options:
 *   perched        hover in place (near anchor) instead of roaming
 *   anchor         CSS selector to hover next to (with anchorOffsetX/Y)
 *   x, y           fallback position as viewport fractions (default 0.5/0.5)
 *   facing         initial body tilt in radians (visual only)
 *   scale          overall size multiplier
 *   fleeRadius     cursor distance that triggers evasive dodge (roaming mode)
 */
function initDrone(opts = {}) {
  const PERCHED_ONLY = !!opts.perched;
  const FLEE_R     = opts.fleeRadius || 130;
  const BASE_SCALE = opts.scale || 1;
  const BASE_TILT  = (opts.facing || 0) * 180 / Math.PI;

  // ── create DOM ──
  const host = document.createElement('div');
  host.id = 'drone-host';
  Object.assign(host.style, {
    position: 'fixed', top: '0', left: '0',
    pointerEvents: 'none', zIndex: '9999',
    willChange: 'transform'
  });

  host.innerHTML = `
<svg id="dr-svg" width="230" height="149" viewBox="0 0 170 110"
     xmlns="http://www.w3.org/2000/svg" overflow="visible"
     style="position:absolute;width:230px;height:149px;top:-75px;left:-115px;
            overflow:visible;will-change:transform;transform-origin:50% 50%;">
  <defs>
    <linearGradient id="drBody" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%"  stop-color="#8a929c"/>
      <stop offset="45%" stop-color="#5c6570"/>
      <stop offset="100%" stop-color="#343b44"/>
    </linearGradient>
    <linearGradient id="drArm" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%"  stop-color="#6d757f"/>
      <stop offset="100%" stop-color="#3a414a"/>
    </linearGradient>
    <radialGradient id="drLens" cx="35%" cy="35%" r="70%">
      <stop offset="0%"  stop-color="#9fd8ff"/>
      <stop offset="45%" stop-color="#1a4d70"/>
      <stop offset="100%" stop-color="#08121c"/>
    </radialGradient>
  </defs>

  <!-- rotor blur discs -->
  <ellipse id="dr-disc-l" cx="38"  cy="34" rx="30" ry="6" fill="#9aa4ae" opacity=".22"/>
  <ellipse id="dr-disc-r" cx="132" cy="34" rx="30" ry="6" fill="#9aa4ae" opacity=".22"/>

  <!-- spinning blades (squashed to fake the side view) -->
  <g transform="translate(38,34) scale(1,.2)">
    <g id="dr-prop-l">
      <rect x="-28" y="-2.4" width="56" height="4.8" rx="2.4" fill="#2b3138"/>
      <rect x="-2.4" y="-28" width="4.8" height="56" rx="2.4" fill="#2b3138" opacity=".85"/>
    </g>
  </g>
  <g transform="translate(132,34) scale(1,.2)">
    <g id="dr-prop-r">
      <rect x="-28" y="-2.4" width="56" height="4.8" rx="2.4" fill="#2b3138"/>
      <rect x="-2.4" y="-28" width="4.8" height="56" rx="2.4" fill="#2b3138" opacity=".85"/>
    </g>
  </g>

  <!-- motor pods -->
  <rect x="30"  y="34" width="16" height="11" rx="4" fill="url(#drArm)" stroke="#20262d" stroke-width=".8"/>
  <rect x="124" y="34" width="16" height="11" rx="4" fill="url(#drArm)" stroke="#20262d" stroke-width=".8"/>
  <circle cx="38"  cy="34.5" r="2.6" fill="#161a1f"/>
  <circle cx="132" cy="34.5" r="2.6" fill="#161a1f"/>

  <!-- arms -->
  <path d="M46,42 L70,53 L74,60 L64,60 Q52,52 44,44Z" fill="url(#drArm)" stroke="#20262d" stroke-width=".8"/>
  <path d="M124,42 L100,53 L96,60 L106,60 Q118,52 126,44Z" fill="url(#drArm)" stroke="#20262d" stroke-width=".8"/>

  <!-- landing skids -->
  <path d="M64,66 L58,84 M106,66 L112,84" stroke="#2b3138" stroke-width="3.4" stroke-linecap="round" fill="none"/>
  <path d="M50,85 L68,85 M102,85 L120,85" stroke="#2b3138" stroke-width="3.4" stroke-linecap="round" fill="none"/>

  <!-- body -->
  <path d="M60,50 Q62,44 72,43 L98,43 Q108,44 110,50 L112,58
           Q112,66 102,68 L68,68 Q58,66 58,58Z"
        fill="url(#drBody)" stroke="#1d232a" stroke-width="1.1"/>
  <path d="M62,50 Q72,46 85,46 Q98,46 108,50 L108,53 Q97,49 85,49 Q73,49 62,53Z"
        fill="#aab3bd" opacity=".5"/>
  <!-- vents -->
  <g stroke="#252b33" stroke-width="1.4" opacity=".75">
    <path d="M73,58 L73,63"/><path d="M79,58 L79,64"/><path d="M85,58 L85,64"/>
    <path d="M91,58 L91,64"/><path d="M97,58 L97,63"/>
  </g>

  <!-- camera gimbal -->
  <rect x="76" y="66" width="18" height="7" rx="3" fill="#3a414a" stroke="#1d232a" stroke-width=".8"/>
  <circle id="dr-cam" cx="85" cy="78" r="7.4" fill="#20262d" stroke="#12161b" stroke-width="1"/>
  <circle cx="85" cy="78" r="4.4" fill="url(#drLens)"/>
  <circle cx="83.4" cy="76.4" r="1.2" fill="#cfeaff" opacity=".8"/>

  <!-- status LEDs -->
  <circle id="dr-led-l" cx="62" cy="62" r="2.1" fill="#2bd96f"/>
  <circle id="dr-led-r" cx="108" cy="62" r="2.1" fill="#ff5252"/>
</svg>`;

  document.body.appendChild(host);

  const svg   = host.querySelector('#dr-svg');
  const propL = host.querySelector('#dr-prop-l');
  const propR = host.querySelector('#dr-prop-r');
  const ledL  = host.querySelector('#dr-led-l');
  const ledR  = host.querySelector('#dr-led-r');

  const vw = () => window.innerWidth;
  const vh = () => window.innerHeight;
  const rnd   = (a, b)    => a + Math.random() * (b - a);
  const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
  const lerp  = (a, b, t) => a + (b - a) * t;

  function anchoredPos() {
    if (opts.anchor) {
      const el = document.querySelector(opts.anchor);
      if (el) {
        const r = el.getBoundingClientRect();
        return [r.left + (opts.anchorOffsetX || 0), r.top + (opts.anchorOffsetY || 0)];
      }
    }
    return [vw() * (opts.x || 0.5), vh() * (opts.y || 0.5)];
  }

  // ── state ──
  const hp = anchoredPos();
  let px = hp[0], py = hp[1];
  let vx = 0, vy = 0;
  let t = rnd(0, 100);            // animation clock (bob, LEDs)
  let propA = 0;                  // rotor angle
  let tilt = BASE_TILT;
  let goalX = px, goalY = py;
  let holdLeft = rnd(2, 5);       // roaming: seconds to hover at current goal
  let mx = -9999, my = -9999;
  let lastTs = performance.now();
  let rafId = 0;

  function pickGoal() {
    const sp = Math.min(200, Math.min(vw(), vh()) * 0.2);
    goalX = rnd(sp, vw() - sp);
    goalY = rnd(sp, vh() * 0.72);
    holdLeft = rnd(1.5, 4.5);
  }

  // ── main loop ──
  function frame(ts) {
    rafId = requestAnimationFrame(frame);
    const dt = Math.min((ts - lastTs) / 1000, 0.05);
    lastTs = ts;
    t += dt;

    // rotors: constant fast spin, slightly different speeds so they shimmer
    propA += dt * 1500;
    propL.setAttribute('transform', `rotate(${(propA % 360).toFixed(1)})`);
    propR.setAttribute('transform', `rotate(${(-propA * 1.13 % 360).toFixed(1)})`);

    // nav lights blink
    ledL.style.opacity = (Math.sin(t * 4.2) > -0.4) ? 1 : 0.15;
    ledR.style.opacity = (Math.sin(t * 4.2 + Math.PI) > -0.4) ? 1 : 0.15;

    if (PERCHED_ONLY) {
      // re-anchor every frame so resize keeps it in place
      const ap = anchoredPos();
      px = lerp(px, ap[0], clamp(dt * 3, 0, 1));
      py = lerp(py, ap[1], clamp(dt * 3, 0, 1));
    } else {
      // dodge the cursor
      const md = Math.hypot(px - mx, py - my);
      if (md < FLEE_R) {
        const ang = Math.atan2(py - my, px - mx);
        vx += Math.cos(ang) * 900 * dt;
        vy += Math.sin(ang) * 900 * dt;
        holdLeft = 0;
      }

      // steer toward goal, hover there, then move on
      const gd = Math.hypot(goalX - px, goalY - py);
      if (gd < 24) {
        holdLeft -= dt;
        if (holdLeft <= 0) pickGoal();
      } else {
        const ang = Math.atan2(goalY - py, goalX - px);
        const thrust = clamp(gd * 2.2, 40, 190);
        vx += Math.cos(ang) * thrust * dt;
        vy += Math.sin(ang) * thrust * dt;
      }

      // air drag + speed cap
      const drag = Math.pow(0.35, dt);
      vx *= drag; vy *= drag;
      const spd = Math.hypot(vx, vy);
      if (spd > 240) { const s = 240 / spd; vx *= s; vy *= s; }

      px += vx * dt;
      py += vy * dt;
      px = clamp(px, 40, vw() - 40);
      py = clamp(py, 40, vh() - 40);
    }

    // hover bob + banking into lateral motion
    const bobY = Math.sin(t * 2.1) * 5 + Math.sin(t * 0.7) * 3;
    const bobX = Math.sin(t * 0.9) * 3;
    const targetTilt = BASE_TILT + clamp(vx * 0.07, -16, 16) + Math.sin(t * 1.3) * 1.5;
    tilt = lerp(tilt, targetTilt, clamp(dt * 5, 0, 1));

    svg.style.filter = 'drop-shadow(0 6px 8px rgba(0,0,0,.22))';
    svg.style.transform = `rotate(${tilt.toFixed(2)}deg) scale(${BASE_SCALE})`;
    host.style.transform = `translate(${(px + bobX).toFixed(1)}px,${(py + bobY).toFixed(1)}px)`;
  }

  // ── input listeners ──
  function onMouse(e) { mx = e.clientX; my = e.clientY; }
  function onTouch(e) { mx = e.touches[0].clientX; my = e.touches[0].clientY; }
  function onTouchEnd() { mx = -9999; my = -9999; }

  window.addEventListener('mousemove', onMouse);
  window.addEventListener('touchmove', onTouch, { passive: true });
  window.addEventListener('touchend', onTouchEnd);

  if (!PERCHED_ONLY) pickGoal();
  rafId = requestAnimationFrame(frame);

  return {
    destroy() {
      cancelAnimationFrame(rafId);
      window.removeEventListener('mousemove', onMouse);
      window.removeEventListener('touchmove', onTouch);
      window.removeEventListener('touchend', onTouchEnd);
      host.remove();
    }
  };
}

window.initDrone = initDrone;
