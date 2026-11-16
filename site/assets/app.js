(function () {
  var root = document.documentElement;
  var THEME_KEY = 'unroll-theme';
  var MOTION_KEY = 'unroll-reduce-motion';

  /* ---------- Motion preference ---------- */
  var osReduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var savedMotion = null;
  try { savedMotion = localStorage.getItem(MOTION_KEY); } catch (e) {}

  function isReducedMotion() {
    if (savedMotion === '1') return true;
    if (savedMotion === '0') return false;
    return !!osReduced;
  }

  function applyMotionState() {
    var reduced = isReducedMotion();
    root.dataset.motion = reduced ? 'off' : 'on';
    root.classList.toggle('has-motion', !reduced);
    root.classList.toggle('no-motion', reduced);
    document.querySelectorAll('[data-motion-toggle]').forEach(function (btn) {
      btn.setAttribute('aria-pressed', reduced ? 'true' : 'false');
      var onLabel = btn.getAttribute('data-on-label') || '✦';
      var offLabel = btn.getAttribute('data-off-label') || '⏸';
      btn.textContent = reduced ? offLabel : onLabel;
    });
    if (reduced) {
      document.querySelectorAll('[data-reveal]').forEach(function (el) {
        el.classList.add('is-revealed');
      });
    }
  }

  function animateEl(el, keyframes, opts) {
    if (!el || isReducedMotion() || typeof el.animate !== 'function') return null;
    try {
      return el.animate(keyframes, Object.assign({
        duration: 280,
        easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
        fill: 'none'
      }, opts || {}));
    } catch (e) {
      return null;
    }
  }

  applyMotionState();

  document.querySelectorAll('[data-motion-toggle]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      savedMotion = isReducedMotion() ? '0' : '1';
      try { localStorage.setItem(MOTION_KEY, savedMotion); } catch (e) {}
      applyMotionState();
    });
  });

  /* ---------- Theme toggle ---------- */
  function currentTheme() {
    if (root.dataset.theme) return root.dataset.theme;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  function applyTheme(t) {
    root.dataset.theme = t;
    document.querySelectorAll('[data-theme-toggle]').forEach(function (btn) {
      btn.setAttribute('aria-pressed', t === 'dark' ? 'true' : 'false');
    });
  }
  try {
    var savedTheme = localStorage.getItem(THEME_KEY);
    if (savedTheme === 'light' || savedTheme === 'dark') applyTheme(savedTheme);
  } catch (e) {}
  document.querySelectorAll('[data-theme-toggle]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var next = currentTheme() === 'dark' ? 'light' : 'dark';
      applyTheme(next);
      try { localStorage.setItem(THEME_KEY, next); } catch (e) {}
    });
  });

  /* ---------- Scroll hairline & Hero Film-Canister Unroll progress ---------- */
  var unrollStage = document.getElementById('unroll-stage');
  var scrollTicking = false;
  function updateScroll() {
    scrollTicking = false;
    var h = document.documentElement.scrollHeight - window.innerHeight;
    var ratio = h > 0 ? Math.min(1, Math.max(0, window.scrollY / h)) : 0;
    root.style.setProperty('--scroll', ratio.toFixed(4));
    if (unrollStage && !isReducedMotion()) {
      var unroll = Math.min(1, Math.max(0, window.scrollY / 420));
      unrollStage.style.setProperty('--unroll', unroll.toFixed(4));
    } else if (unrollStage) {
      unrollStage.style.setProperty('--unroll', '0');
    }
  }
  window.addEventListener('scroll', function () {
    if (!scrollTicking) {
      scrollTicking = true;
      requestAnimationFrame(updateScroll);
    }
  }, { passive: true });
  updateScroll();

  /* ---------- Scroll reveals ---------- */
  var revealNodes = Array.prototype.slice.call(document.querySelectorAll('[data-reveal]'));
  if (revealNodes.length) {
    revealNodes.forEach(function (el, idx) {
      el.style.setProperty('--delay', ((idx % 4) * 55) + 'ms');
    });
    if ('IntersectionObserver' in window && !isReducedMotion()) {
      var io = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            entry.target.classList.add('is-revealed');
            io.unobserve(entry.target);
          }
        });
      }, { threshold: 0.06, rootMargin: '0px 0px -24px 0px' });
      revealNodes.forEach(function (el) { io.observe(el); });
    } else {
      revealNodes.forEach(function (el) { el.classList.add('is-revealed'); });
    }
  }

  /* ---------- Lightbox modal ---------- */
  var lbDialog = document.getElementById('lightbox');
  var lbImg = document.getElementById('lb-img');
  var lbTitle = document.getElementById('lb-title');
  var lbCap = document.getElementById('lb-cap');
  var lbCount = document.getElementById('lb-count');
  var lbItems = [];
  var lbIdx = 0;

  function renderLightbox() {
    var item = lbItems[lbIdx];
    if (!item || !lbImg) return;
    lbImg.src = item.src;
    lbImg.alt = item.title || item.caption || '';
    if (lbTitle) lbTitle.textContent = item.title || '';
    if (lbCap) lbCap.textContent = item.caption || '';
    if (lbCount) lbCount.textContent = (lbIdx + 1) + ' / ' + lbItems.length;
    animateEl(lbImg, [
      { opacity: 0.35, transform: 'scale(0.97)' },
      { opacity: 1, transform: 'scale(1)' }
    ], { duration: 220 });
  }

  function openLightbox(items, startIndex) {
    if (!lbDialog || !items || !items.length) return;
    lbItems = items;
    lbIdx = Math.max(0, Math.min(items.length - 1, startIndex || 0));
    renderLightbox();
    if (typeof lbDialog.showModal === 'function') {
      if (!lbDialog.open) lbDialog.showModal();
      animateEl(lbDialog.querySelector('.lb-shell'), [
        { opacity: 0, transform: 'scale(0.96) translateY(8px)' },
        { opacity: 1, transform: 'scale(1) translateY(0)' }
      ], { duration: 240 });
    }
  }

  if (lbDialog) {
    var lbPrev = lbDialog.querySelector('[data-lb-prev]');
    var lbNext = lbDialog.querySelector('[data-lb-next]');
    var lbClose = lbDialog.querySelector('[data-lb-close]');
    if (lbPrev) lbPrev.addEventListener('click', function () {
      lbIdx = (lbIdx - 1 + lbItems.length) % lbItems.length;
      renderLightbox();
    });
    if (lbNext) lbNext.addEventListener('click', function () {
      lbIdx = (lbIdx + 1) % lbItems.length;
      renderLightbox();
    });
    if (lbClose) lbClose.addEventListener('click', function () { lbDialog.close(); });
    lbDialog.addEventListener('click', function (e) {
      if (e.target === lbDialog) lbDialog.close();
    });
    document.addEventListener('keydown', function (e) {
      if (!lbDialog.open) return;
      if (e.key === 'ArrowLeft') { e.preventDefault(); lbIdx = (lbIdx - 1 + lbItems.length) % lbItems.length; renderLightbox(); }
      if (e.key === 'ArrowRight') { e.preventDefault(); lbIdx = (lbIdx + 1) % lbItems.length; renderLightbox(); }
    });
  }

  /* ---------- Hero Unrolling 5-Phone Arc ---------- */
  var fanCards = Array.prototype.slice.call(document.querySelectorAll('.fan-card[data-screen]'));
  var fanCap = document.getElementById('unroll-caption');
  if (fanCards.length) {
    var defaultFanCap = fanCap ? fanCap.textContent : '';
    var fanItems = fanCards.map(function (c) {
      return {
        src: c.getAttribute('data-screen'),
        title: c.getAttribute('data-title') || '',
        caption: c.getAttribute('data-caption') || ''
      };
    });
    fanCards.forEach(function (card, idx) {
      card.addEventListener('mouseenter', function () {
        if (fanCap) fanCap.textContent = card.getAttribute('data-caption') || defaultFanCap;
      });
      card.addEventListener('focus', function () {
        if (fanCap) fanCap.textContent = card.getAttribute('data-caption') || defaultFanCap;
      });
      card.addEventListener('mouseleave', function () {
        if (fanCap) fanCap.textContent = defaultFanCap;
      });
      card.addEventListener('click', function () {
        openLightbox(fanItems, idx);
      });
    });
  }

  /* ---------- Sticky Cinema Scrollytelling Engine ---------- */
  var dataEl = document.getElementById('usecases-data');
  var chapterEls = Array.prototype.slice.call(document.querySelectorAll('.story-chapter[data-chapter]'));
  if (dataEl && chapterEls.length) {
    var chapters = [];
    try { chapters = JSON.parse(dataEl.textContent); } catch (e) {}

    var cBadge = document.getElementById('console-badge');
    var cPlayBtn = document.getElementById('console-play');
    var cCounter = document.getElementById('console-counter');
    var cFrame = document.getElementById('console-frame');
    var cImg = document.getElementById('console-img');
    var cShutter = document.getElementById('console-shutter');
    var cHotspot = document.getElementById('console-hotspot');
    var cTap = document.getElementById('console-tap');
    var cZoom = document.getElementById('console-zoom');
    var cThumbs = document.getElementById('console-thumbs');
    var cHint = document.getElementById('console-hint');
    var callLeft = document.getElementById('callout-left');
    var callRight = document.getElementById('callout-right');

    var consoleEl = document.getElementById('sticky-console');
    var playWord = consoleEl ? (consoleEl.getAttribute('data-play-label') || 'Play') : 'Play';
    var stopWord = consoleEl ? (consoleEl.getAttribute('data-stop-label') || 'Pause') : 'Pause';

    var curChap = 0;
    var curStep = 0;
    var isPlaying = false;
    var userPaused = false;
    var stepTimer = null;

    // Populate each chapter's clickable steps + inline mobile thumbnails
    chapterEls.forEach(function (chapEl, cIdx) {
      var data = chapters[cIdx];
      if (!data) return;
      var stepsWrap = chapEl.querySelector('[data-chap-steps]');
      var mobileWrap = chapEl.querySelector('[data-chap-mobile]');

      if (stepsWrap) {
        stepsWrap.innerHTML = '';
        data.steps.forEach(function (st, sIdx) {
          var btn = document.createElement('button');
          btn.type = 'button';
          btn.className = 'j-step';
          btn.setAttribute('aria-current', cIdx === 0 && sIdx === 0 ? 'step' : 'false');

          var num = document.createElement('span');
          num.className = 'j-step-num';
          num.textContent = String(sIdx + 1).padStart(2, '0');

          var body = document.createElement('span');
          body.className = 'j-step-body';
          var strong = document.createElement('strong');
          strong.textContent = st.title;
          var desc = document.createElement('span');
          desc.textContent = st.detail;
          body.appendChild(strong);
          body.appendChild(desc);

          var prog = document.createElement('span');
          prog.className = 'j-step-progress';

          btn.appendChild(num);
          btn.appendChild(body);
          btn.appendChild(prog);
          btn.addEventListener('click', function () {
            userPaused = true;
            stopConsolePlay();
            activateChapter(cIdx, sIdx, true);
          });
          stepsWrap.appendChild(btn);
        });
      }

      if (mobileWrap) {
        mobileWrap.innerHTML = '';
        var mobItems = data.steps.map(function (st) {
          return { src: st.img, title: st.title, caption: st.detail };
        });
        data.steps.forEach(function (st, sIdx) {
          var mbtn = document.createElement('button');
          mbtn.type = 'button';
          mbtn.className = 'chap-mini';
          mbtn.setAttribute('aria-label', st.title);
          var mi = document.createElement('img');
          mi.src = st.img;
          mi.alt = st.alt || st.title;
          mi.loading = 'lazy';
          mbtn.appendChild(mi);
          mbtn.addEventListener('click', function () { openLightbox(mobItems, sIdx); });
          mobileWrap.appendChild(mbtn);
        });
      }
    });

    function blinkShutter() {
      if (!cShutter || isReducedMotion()) return;
      cShutter.classList.remove('is-blinking');
      void cShutter.offsetWidth;
      cShutter.classList.add('is-blinking');
    }

    function updateCallouts(data) {
      if (callLeft && data.calloutLeft) {
        callLeft.innerHTML = '<b>' + data.calloutLeft.k + '</b><span>' + data.calloutLeft.v + '</span>';
        animateEl(callLeft, [
          { opacity: 0, transform: 'translateX(-12px) scale(0.92)' },
          { opacity: 1, transform: 'translateX(0) scale(1)' }
        ], { duration: 320 });
      }
      if (callRight && data.calloutRight) {
        callRight.innerHTML = '<b>' + data.calloutRight.k + '</b><span>' + data.calloutRight.v + '</span>';
        animateEl(callRight, [
          { opacity: 0, transform: 'translateX(12px) scale(0.92)' },
          { opacity: 1, transform: 'translateX(0) scale(1)' }
        ], { duration: 320 });
      }
    }

    function rebuildThumbs(data) {
      if (!cThumbs) return;
      cThumbs.innerHTML = '';
      data.steps.forEach(function (st, sIdx) {
        var tb = document.createElement('button');
        tb.type = 'button';
        tb.className = 'sb-thumb';
        tb.setAttribute('aria-current', sIdx === curStep ? 'step' : 'false');
        tb.setAttribute('aria-label', st.title);
        var ti = document.createElement('img');
        ti.src = st.img;
        ti.alt = st.alt || st.title;
        tb.appendChild(ti);
        tb.addEventListener('click', function () {
          userPaused = true;
          stopConsolePlay();
          renderStep(sIdx, true);
        });
        cThumbs.appendChild(tb);
      });
    }

    function renderStep(stepIdx, animate) {
      var data = chapters[curChap];
      if (!data || !data.steps.length) return;
      curStep = Math.max(0, Math.min(data.steps.length - 1, stepIdx));
      var st = data.steps[curStep];

      chapterEls.forEach(function (el, cIdx) {
        el.querySelectorAll('.j-step').forEach(function (sBtn, sIdx) {
          var active = cIdx === curChap && sIdx === curStep;
          sBtn.setAttribute('aria-current', active ? 'step' : 'false');
          sBtn.classList.remove('is-advancing');
          if (active && isPlaying && !isReducedMotion()) {
            void sBtn.offsetWidth;
            sBtn.classList.add('is-advancing');
          }
        });
      });

      if (cThumbs) {
        cThumbs.querySelectorAll('.sb-thumb').forEach(function (el, i) {
          el.setAttribute('aria-current', i === curStep ? 'step' : 'false');
        });
      }
      if (cCounter) cCounter.textContent = (curStep + 1) + ' / ' + data.steps.length;
      if (cFrame) cFrame.classList.toggle('is-android', st.platform === 'android');
      if (cImg && cImg.getAttribute('src') !== st.img) {
        cImg.src = st.img;
        cImg.alt = st.alt || st.title;
        if (animate !== false) {
          animateEl(cImg, [
            { opacity: 0.3, transform: 'translateX(12px) scale(0.98)' },
            { opacity: 1, transform: 'translateX(0) scale(1)' }
          ], { duration: 280 });
        }
      }
      if (cHint) cHint.textContent = st.hint || '';
      if (cHotspot && st.hotspot) {
        cHotspot.style.opacity = '1';
        cHotspot.style.left = st.hotspot.left + '%';
        cHotspot.style.top = st.hotspot.top + '%';
        cHotspot.style.width = st.hotspot.width + '%';
        cHotspot.style.height = st.hotspot.height + '%';
        if (cTap) {
          cTap.style.opacity = '1';
          cTap.style.left = (st.hotspot.left + st.hotspot.width / 2) + '%';
          cTap.style.top = (st.hotspot.top + st.hotspot.height / 2) + '%';
        }
      } else if (cHotspot) {
        cHotspot.style.opacity = '0';
        if (cTap) cTap.style.opacity = '0';
      }
    }

    function activateChapter(chapIdx, stepIdx, manual) {
      var changed = chapIdx !== curChap;
      curChap = Math.max(0, Math.min(chapters.length - 1, chapIdx));
      var data = chapters[curChap];
      if (!data) return;

      chapterEls.forEach(function (el, i) {
        el.classList.toggle('is-active', i === curChap);
      });
      if (cBadge) cBadge.textContent = data.badge || '';
      if (changed) {
        blinkShutter();
        updateCallouts(data);
        rebuildThumbs(data);
      }
      renderStep(typeof stepIdx === 'number' ? stepIdx : 0, changed || !!manual);
    }

    function tickConsole() {
      if (!isPlaying) return;
      var data = chapters[curChap];
      if (!data) return;
      renderStep((curStep + 1) % data.steps.length, true);
      stepTimer = setTimeout(tickConsole, 3400);
    }

    function stopConsolePlay() {
      isPlaying = false;
      clearTimeout(stepTimer);
      stepTimer = null;
      if (cPlayBtn) {
        cPlayBtn.setAttribute('aria-pressed', 'false');
        cPlayBtn.textContent = '▷ ' + playWord;
      }
      document.querySelectorAll('.j-step.is-advancing').forEach(function (s) {
        s.classList.remove('is-advancing');
      });
    }

    function startConsolePlay() {
      if (isPlaying || isReducedMotion()) return;
      isPlaying = true;
      if (cPlayBtn) {
        cPlayBtn.setAttribute('aria-pressed', 'true');
        cPlayBtn.textContent = '⏸ ' + stopWord;
      }
      renderStep(curStep, false);
      clearTimeout(stepTimer);
      stepTimer = setTimeout(tickConsole, 3400);
    }

    if (cPlayBtn) {
      cPlayBtn.addEventListener('click', function () {
        if (isPlaying) { userPaused = true; stopConsolePlay(); }
        else { userPaused = false; startConsolePlay(); }
      });
    }
    if (cZoom) {
      cZoom.addEventListener('click', function () {
        stopConsolePlay();
        var data = chapters[curChap];
        if (!data) return;
        openLightbox(data.steps.map(function (st) {
          return { src: st.img, title: st.title, caption: st.detail };
        }), curStep);
      });
    }

    // Init chapter 0
    if (chapters.length) {
      if (cBadge) cBadge.textContent = chapters[0].badge || '';
      updateCallouts(chapters[0]);
      rebuildThumbs(chapters[0]);
      renderStep(0, false);
    }

    if ('IntersectionObserver' in window) {
      var chapIo = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            var idx = parseInt(entry.target.getAttribute('data-chapter'), 10);
            if (!isNaN(idx) && idx !== curChap) {
              activateChapter(idx, 0, true);
              if (!userPaused && !isReducedMotion()) startConsolePlay();
            }
          }
        });
      }, { rootMargin: '-32% 0px -38% 0px', threshold: 0.01 });
      chapterEls.forEach(function (el) { chapIo.observe(el); });

      var stageParent = document.getElementById('use-cases');
      if (stageParent && !isReducedMotion()) {
        var playIo = new IntersectionObserver(function (entries) {
          entries.forEach(function (entry) {
            if (entry.isIntersecting) {
              if (!userPaused) startConsolePlay();
            } else {
              stopConsolePlay();
            }
          });
        }, { threshold: 0.08 });
        playIo.observe(stageParent);
      }
    }
  }

  /* ---------- Interactive Physical Print Deck Shuffle ---------- */
  var printStack = document.getElementById('print-stack');
  if (printStack) {
    var printCards = Array.prototype.slice.call(printStack.querySelectorAll('.print-card'));
    var deckChips = Array.prototype.slice.call(document.querySelectorAll('[data-deck-select]'));
    var nextBtn = document.getElementById('deck-next');
    var deckOrder = printCards.map(function (_, i) { return i; });

    function applyDeckOrder(animateTop) {
      var frontIdx = deckOrder[0];
      printCards.forEach(function (card, cardIdx) {
        var pos = deckOrder.indexOf(cardIdx);
        card.setAttribute('data-order', String(pos));
        if (animateTop && pos === printCards.length - 1) {
          animateEl(card, [
            { transform: 'translate(140px, -16px) rotate(12deg) scale(0.96)' },
            { transform: 'translate(-24px, 24px) rotate(-8deg) scale(0.84)' }
          ], { duration: 420 });
        }
      });
      deckChips.forEach(function (btn) {
        var target = parseInt(btn.getAttribute('data-deck-select'), 10);
        btn.setAttribute('aria-pressed', target === frontIdx ? 'true' : 'false');
      });
    }

    function rotateDeckTo(targetCardIdx) {
      var pos = deckOrder.indexOf(targetCardIdx);
      if (pos <= 0) {
        var shifted = deckOrder.shift();
        deckOrder.push(shifted);
        applyDeckOrder(true);
        return;
      }
      deckOrder = deckOrder.slice(pos).concat(deckOrder.slice(0, pos));
      applyDeckOrder(true);
    }

    printStack.addEventListener('click', function () { rotateDeckTo(-1); });
    if (nextBtn) nextBtn.addEventListener('click', function () { rotateDeckTo(-1); });
    deckChips.forEach(function (btn) {
      btn.addEventListener('click', function () {
        rotateDeckTo(parseInt(btn.getAttribute('data-deck-select'), 10));
      });
    });
  }

  /* ---------- Magnetic Draggable Shutter Wipe + Live Framing Lab ---------- */
  var wipeStage = document.getElementById('wipe-stage');
  var wipeHandle = document.getElementById('wipe-handle');
  if (wipeStage && wipeHandle) {
    var draggingWipe = false;
    var wipePct = 58;
    var wipeTouched = false;
    function setWipe(pct) {
      wipePct = Math.max(4, Math.min(96, pct));
      wipeStage.style.setProperty('--wipe', wipePct.toFixed(1) + '%');
      wipeHandle.setAttribute('aria-valuenow', String(Math.round(wipePct)));
    }
    function setWipeFromClientX(clientX) {
      var cmp = wipeStage.querySelector('.wipe-compare') || wipeStage;
      var rect = cmp.getBoundingClientRect();
      setWipe(((clientX - rect.left) / rect.width) * 100);
    }
    wipeStage.addEventListener('pointerdown', function (e) {
      draggingWipe = true;
      wipeTouched = true;
      if (wipeStage.setPointerCapture) wipeStage.setPointerCapture(e.pointerId);
      setWipeFromClientX(e.clientX);
    });
    wipeStage.addEventListener('pointermove', function (e) {
      if (!draggingWipe) return;
      setWipeFromClientX(e.clientX);
    });
    window.addEventListener('pointerup', function () { draggingWipe = false; });
    wipeHandle.addEventListener('keydown', function (e) {
      var d = e.key === 'ArrowLeft' ? -5 : e.key === 'ArrowRight' ? 5 : 0;
      if (e.key === 'Home') { e.preventDefault(); wipeTouched = true; setWipe(4); return; }
      if (e.key === 'End') { e.preventDefault(); wipeTouched = true; setWipe(96); return; }
      if (!d) return;
      e.preventDefault();
      wipeTouched = true;
      setWipe(wipePct + d);
    });
    setWipe(wipePct);
    // One gentle sweep when the bench first scrolls into view, so people see it can be dragged
    if ('IntersectionObserver' in window && !isReducedMotion()) {
      var sweepIo = new IntersectionObserver(function (entries) {
        if (!entries[0].isIntersecting) return;
        sweepIo.disconnect();
        var t0 = null;
        var path = [58, 88, 18, 50];
        var seg = 650;
        function frame(ts) {
          if (wipeTouched) return;
          if (t0 === null) t0 = ts;
          var t = (ts - t0) / seg;
          var i = Math.floor(t);
          if (i >= path.length - 1) { setWipe(path[path.length - 1]); return; }
          var k = t - i;
          k = k < 0.5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
          setWipe(path[i] + (path[i + 1] - path[i]) * k);
          requestAnimationFrame(frame);
        }
        setTimeout(function () { requestAnimationFrame(frame); }, 350);
      }, { threshold: 0.6 });
      sweepIo.observe(wipeStage);
    }
  }

  var canvas = document.getElementById('frame-canvas');
  var rawPhoto = document.getElementById('frame-photo-raw');
  if (canvas) {
    var camEl = document.getElementById('frame-cam');
    var subEl = document.getElementById('frame-sub');
    var markEl = document.getElementById('frame-mark');
    var photoEl = document.getElementById('frame-photo');
    var recipeGrid = document.getElementById('frame-recipe-grid');

    // Both sample photos are the demo roll inside the app, with their real EXIF and recipe values
    var SCENES = {
      land: {
        camera: 'Fujifilm X100VI',
        exposure: '23mm · f/2.8 · 1/2000s · ISO 1600',
        recipe: 'Nostalgic Neg.',
        date: '15 Nov 2026',
        place: 'Sài Gòn',
        rows: [
          'Film Sim: Nostalgic Neg.', 'Dynamic Range: DR400',
          'Grain: Weak, Small', 'WB: Daylight +2R -3B',
          'Highlight: 0 · Shadow: +0.5', 'Color: +1 · Sharpness: -1'
        ]
      },
      port: {
        camera: 'Fujifilm X100VI',
        exposure: '23mm · f/4 · 1/500s · ISO 200',
        recipe: 'Classic Chrome',
        date: '15 Nov 2026',
        place: 'Sài Gòn',
        rows: [
          'Film Sim: Classic Chrome', 'Dynamic Range: DR200',
          'Highlight: -1', 'Shadow: +1'
        ]
      }
    };

    var fstate = {
      style: 'gallery',
      ratio: 'fit',
      scene: 'land',
      dark: false,
      exposure: true,
      recipe: true,
      date: false,
      place: false,
      clean: false
    };

    function syncFrame(pulse) {
      var sc = SCENES[fstate.scene] || SCENES.land;
      var cmpBox = canvas.closest('.wipe-compare');
      if (cmpBox) cmpBox.classList.toggle('is-portrait', fstate.scene === 'port');
      canvas.setAttribute('data-style', fstate.style);
      canvas.setAttribute('data-ratio', fstate.ratio);
      canvas.setAttribute('data-dark', fstate.dark ? 'true' : 'false');
      if (photoEl) photoEl.setAttribute('data-scene', fstate.scene);
      if (rawPhoto) rawPhoto.setAttribute('data-scene', fstate.scene);
      if (camEl) camEl.textContent = sc.camera;
      var parts = [];
      if (fstate.exposure) parts.push(sc.exposure);
      if (fstate.recipe) parts.push(sc.recipe);
      if (fstate.date) parts.push(sc.date);
      if (fstate.place) parts.push(sc.place);
      if (subEl) subEl.textContent = parts.join(' · ') || ' ';
      if (recipeGrid) {
        recipeGrid.innerHTML = sc.rows.map(function (r) { return '<span>' + r + '</span>'; }).join('');
      }
      if (markEl) markEl.hidden = fstate.clean || fstate.style === 'minimal';
      if (pulse) {
        animateEl(canvas, [
          { transform: 'scale(0.985)' },
          { transform: 'scale(1)' }
        ], { duration: 200 });
      }
    }

    document.querySelectorAll('[data-f-style]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        fstate.style = btn.getAttribute('data-f-style');
        document.querySelectorAll('[data-f-style]').forEach(function (b) {
          b.setAttribute('aria-pressed', b === btn ? 'true' : 'false');
        });
        syncFrame(true);
      });
    });

    document.querySelectorAll('[data-f-ratio]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        fstate.ratio = btn.getAttribute('data-f-ratio');
        document.querySelectorAll('[data-f-ratio]').forEach(function (b) {
          b.setAttribute('aria-pressed', b === btn ? 'true' : 'false');
        });
        syncFrame(true);
      });
    });

    document.querySelectorAll('[data-f-scene]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        fstate.scene = btn.getAttribute('data-f-scene');
        document.querySelectorAll('[data-f-scene]').forEach(function (b) {
          b.setAttribute('aria-pressed', b === btn ? 'true' : 'false');
        });
        syncFrame(true);
      });
    });

    document.querySelectorAll('[data-f-toggle]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var key = btn.getAttribute('data-f-toggle');
        fstate[key] = !fstate[key];
        btn.setAttribute('aria-pressed', fstate[key] ? 'true' : 'false');
        syncFrame(true);
      });
    });

    syncFrame(false);
  }

  /* ---------- 35mm Contact Sheet Optical Loupe + Filter + Lightbox ---------- */
  var contactSheet = document.getElementById('man-hinh') || document.getElementById('screens');
  if (contactSheet && !isReducedMotion()) {
    contactSheet.addEventListener('pointermove', function (e) {
      if (e.pointerType !== 'mouse') return;
      var rect = contactSheet.getBoundingClientRect();
      contactSheet.style.setProperty('--mx', (e.clientX - rect.left).toFixed(0) + 'px');
      contactSheet.style.setProperty('--my', (e.clientY - rect.top).toFixed(0) + 'px');
    });
  }

  var galleryItems = Array.prototype.slice.call(document.querySelectorAll('.gallery-item'));
  var galleryFilters = Array.prototype.slice.call(document.querySelectorAll('[data-gallery-filter]'));
  var galleryCount = document.getElementById('gallery-count');

  function visibleGalleryItems() {
    return galleryItems.filter(function (el) { return !el.hidden; });
  }

  if (galleryItems.length && galleryFilters.length) {
    galleryFilters.forEach(function (pill) {
      pill.addEventListener('click', function () {
        var cat = pill.getAttribute('data-gallery-filter');
        galleryFilters.forEach(function (b) {
          b.setAttribute('aria-pressed', b === pill ? 'true' : 'false');
        });
        var shownIndex = 0;
        galleryItems.forEach(function (item) {
          var cats = (item.getAttribute('data-cat') || '').split(/\s+/);
          var match = cat === 'all' || cats.indexOf(cat) !== -1;
          item.hidden = !match;
          if (match) {
            animateEl(item, [
              { opacity: 0, transform: 'translateY(12px) scale(0.98)' },
              { opacity: 1, transform: 'translateY(0) scale(1)' }
            ], { duration: 240, delay: Math.min(shownIndex, 8) * 28 });
            shownIndex++;
          }
        });
        if (galleryCount) {
          galleryCount.textContent = shownIndex + ' / ' + galleryItems.length;
        }
      });
    });

    galleryItems.forEach(function (item) {
      item.addEventListener('click', function () {
        var vis = visibleGalleryItems();
        var items = vis.map(function (el) {
          return {
            src: el.getAttribute('data-lightbox-src'),
            title: el.getAttribute('data-title') || '',
            caption: el.getAttribute('data-caption') || ''
          };
        });
        openLightbox(items, Math.max(0, vis.indexOf(item)));
      });
    });
  }

  /* ---------- Interruptible WAAPI FAQ accordions ---------- */
  document.querySelectorAll('.faq details').forEach(function (det) {
    var sum = det.querySelector('summary');
    var ans = det.querySelector('p');
    var anim = null;
    if (!sum || !ans) return;
    sum.addEventListener('click', function (e) {
      if (isReducedMotion() || typeof det.animate !== 'function') return;
      e.preventDefault();
      if (anim) anim.cancel();
      if (!det.open) {
        var startH = det.offsetHeight;
        det.open = true;
        var endH = det.offsetHeight;
        det.style.overflow = 'hidden';
        anim = det.animate([
          { height: startH + 'px' },
          { height: endH + 'px' }
        ], { duration: 250, easing: 'cubic-bezier(0.22, 1, 0.36, 1)' });
        animateEl(ans, [
          { opacity: 0, transform: 'translateY(-4px)' },
          { opacity: 1, transform: 'translateY(0)' }
        ], { duration: 220 });
        anim.onfinish = function () { det.style.overflow = ''; anim = null; };
        anim.oncancel = function () { det.style.overflow = ''; };
      } else {
        var openH = det.offsetHeight;
        var closeH = sum.offsetHeight + 36;
        det.style.overflow = 'hidden';
        anim = det.animate([
          { height: openH + 'px' },
          { height: Math.min(openH, closeH) + 'px' }
        ], { duration: 200, easing: 'cubic-bezier(0.22, 1, 0.36, 1)' });
        anim.onfinish = function () { det.open = false; det.style.overflow = ''; anim = null; };
        anim.oncancel = function () { det.style.overflow = ''; };
      }
    });
  });

  /* ---------- Copy buttons ---------- */
  document.querySelectorAll('[data-copy-text]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var val = btn.getAttribute('data-copy-text');
      var orig = btn.getAttribute('data-copy-label') || btn.textContent;
      var done = btn.getAttribute('data-copied-label') || 'Copied';
      if (!btn.getAttribute('data-copy-label')) btn.setAttribute('data-copy-label', orig);
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(val).then(function () {
          btn.textContent = done;
          setTimeout(function () { btn.textContent = orig; }, 1800);
        }).catch(function () {});
      }
    });
  });
})();
