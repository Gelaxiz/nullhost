(() => {
  const reduced = matchMedia('(prefers-reduced-motion: reduce)');
  if (reduced.matches) {
    window.nullhostMotion = {
      setReveal(panel, show) {
        if (!panel) return;
        panel.classList.toggle('is-open', show);
        panel.setAttribute('aria-hidden', String(!show));
      }
    };
    return;
  }

  const easeOut = 'cubic-bezier(.16,1,.3,1)';
  const interactive = 'button,.btn,.button,.primary-link,.download-primary-action';

  const setReveal = (panel, show) => {
    if (!panel) return;
    panel.getAnimations().forEach((animation) => animation.cancel());
    if (show) {
      panel.classList.add('is-open');
      panel.setAttribute('aria-hidden', 'false');
      panel.animate([
        { opacity: 0, transform: 'translateY(-7px)', clipPath: 'inset(0 0 14% 0)' },
        { opacity: 1, transform: 'translateY(0)', clipPath: 'inset(0 0 0 0)' }
      ], { duration: 190, easing: easeOut });
      return;
    }
    const closing = panel.animate([
      { opacity: 1, transform: 'translateY(0)', clipPath: 'inset(0 0 0 0)' },
      { opacity: 0, transform: 'translateY(-4px)', clipPath: 'inset(0 0 10% 0)' }
    ], { duration: 120, easing: 'ease-in' });
    closing.addEventListener('finish', () => {
      panel.classList.remove('is-open');
      panel.setAttribute('aria-hidden', 'true');
    }, { once: true });
  };
  window.nullhostMotion = { setReveal };

  [document.querySelector('.nav-container'), document.querySelector('.content'), document.querySelector('.footer-links')]
    .filter(Boolean)
    .forEach((element, index) => {
      element.animate([
        { opacity: .35, transform: 'translateY(-5px)', clipPath: 'inset(0 0 8% 0)' },
        { opacity: 1, transform: 'translateY(0)', clipPath: 'inset(0 0 0 0)' }
      ], { duration: 220 + index * 25, delay: index * 18, easing: easeOut, fill: 'both' });
    });

  document.addEventListener('pointerdown', (event) => {
    const element = event.target.closest(interactive);
    if (!element || element.matches(':disabled,[aria-disabled="true"]')) return;
    element.animate([{ transform: 'scale(1)' }, { transform: 'scale(.965)' }],
      { duration: 90, easing: 'ease-out', fill: 'forwards' });
  });

  const release = (event) => {
    const element = event.target.closest(interactive);
    if (!element) return;
    element.getAnimations().forEach((animation) => animation.cancel());
    element.animate([{ transform: 'scale(.965)' }, { transform: 'scale(1)' }],
      { duration: 150, easing: easeOut });
  };
  document.addEventListener('pointerup', release);
  document.addEventListener('pointercancel', release);

  document.querySelectorAll('details > summary').forEach((summary) => {
    summary.addEventListener('click', (event) => {
      const details = summary.parentElement;
      const content = [...details.children].filter((child) => child !== summary);
      if (!content.length) return;
      event.preventDefault();
      if (details.dataset.motionBusy === 'true') return;
      details.dataset.motionBusy = 'true';
      if (!details.open) {
        details.open = true;
        content.forEach((child, index) => child.animate([
          { opacity: .1, transform: 'translateY(-7px)', clipPath: 'inset(0 0 12% 0)' },
          { opacity: 1, transform: 'translateY(0)', clipPath: 'inset(0 0 0 0)' }
        ], { duration: 190, delay: Math.min(index * 12, 36), easing: easeOut }));
        window.setTimeout(() => { delete details.dataset.motionBusy; }, 205);
      } else {
        const animations = content.map((child) => child.animate([
          { opacity: 1, transform: 'translateY(0)' },
          { opacity: 0, transform: 'translateY(-4px)' }
        ], { duration: 120, easing: 'ease-in' }));
        Promise.allSettled(animations.map((animation) => animation.finished)).then(() => {
          details.open = false;
          delete details.dataset.motionBusy;
        });
      }
    });
  });

  document.addEventListener('change', (event) => {
    if (!event.target.matches('input[type="checkbox"],input[type="radio"],select')) return;
    const surface = event.target.closest('label,.account-item,.tree-file,.form-row') || event.target;
    surface.animate([
      { backgroundColor: 'color-mix(in srgb, var(--blue) 12%, transparent)' },
      { backgroundColor: 'transparent' }
    ], { duration: 260, easing: easeOut });
  });

  const pulseStatus = (element) => {
    if (!element || !element.textContent.trim() || element.hidden) return;
    element.animate([{ opacity: .2, transform: 'translateY(3px)' }, { opacity: 1, transform: 'translateY(0)' }],
      { duration: 180, easing: easeOut });
  };

  const observer = new MutationObserver((records) => {
    records.forEach((record) => {
      const element = record.target.nodeType === Node.TEXT_NODE ? record.target.parentElement : record.target;
      if (!(element instanceof HTMLElement)) return;
      if (element.matches('.copy-status,[role="status"]')) pulseStatus(element);
      if (record.attributeName === 'hidden' && !element.hidden) {
        element.animate([
          { opacity: 0, transform: 'translateY(8px) scale(.98)', filter: 'blur(3px)' },
          { opacity: 1, transform: 'translateY(0) scale(1)', filter: 'blur(0)' }
        ], { duration: 210, easing: easeOut });
      }
    });
  });
  document.querySelectorAll('.copy-status,[role="status"],[hidden]').forEach((element) => {
    observer.observe(element, { attributes: true, attributeFilter: ['hidden'], childList: true, subtree: true, characterData: true });
  });
})();
