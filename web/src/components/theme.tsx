import * as React from "react";

/**
 * The viewer's look: one 28 px, 1 px-bordered, 4 px-radius control is the only building block;
 * toolbar, pickers and the PTZ pad are rows and grids of it. Every colour outside the picture
 * is a custom property with a fallback, and the fallbacks are Perspective's own theme
 * variables (--container, --label, --border, --callToAction, the neutral ramp), so the viewer
 * follows a Perspective theme, light or dark, without any theme-specific CSS of its own. The
 * gateway web UI has none of those variables and gets the light values. Things drawn over the
 * picture (badge, PTZ pad) sit on a fixed dark scrim, because the picture is always dark.
 *
 * Inline styles cannot express hover, focus or ::before, so the rules live in one stylesheet
 * injected once per document; every class is prefixed {@code kcam-}.
 */
export const ROOT_CLASS = "kcam";

const STYLE_ID = "kcam-styles";

const CSS = `
.kcam{
  --cam-bg:var(--container,#ffffff);
  --cam-fg:var(--label,#0a0a0a);
  --cam-fg-muted:var(--neutral-60,#6b6f76);
  --cam-border:var(--border,#e0e0e0);
  --cam-border-strong:var(--neutral-40,#c9ccd1);
  --cam-accent:var(--callToAction,#2f6ee5);
  --cam-accent-tint:var(--callToAction--activeAlt,rgba(47,110,229,.12));
  --cam-toolbar-bg:var(--neutral-10,#fafafa);
  --cam-btn-bg:var(--neutral-20,#ffffff);
  --cam-btn-hover:var(--neutral-30,#f0f1f3);
  --cam-btn-active:var(--neutral-40,#e4e6ea);
  --cam-timeline-bg:var(--neutral-20,#f4f5f7);
  --cam-tick:var(--neutral-30,#d4d7dc);
  --cam-span:var(--callToAction,#2f6ee5);
  --cam-event:#ef6c00;
  --cam-bookmark:#f5b400;
  --cam-playhead:var(--label,#0a0a0a);
  --cam-popover-bg:var(--neutral-10,#ffffff);
  --cam-shadow:0 2px 8px rgba(0,0,0,.22);
  --cam-live:var(--error,#d32f2f);
  --cam-ok:var(--success,#2e7d32);
  --cam-warn:var(--warning,#ef6c00);
  --cam-err:var(--error,#c62828);
  --cam-idle:var(--neutral-60,#9e9e9e);
  --cam-overlay-bg:rgba(10,10,10,.64);
  --cam-overlay-fg:#ffffff;
  --cam-overlay-border:rgba(255,255,255,.14);
  color:var(--cam-fg);
  font-family:"Noto Sans","Segoe UI",Roboto,Helvetica,Arial,sans-serif;
  font-size:12px;line-height:16px;
}
.kcam *,.kcam *::before,.kcam *::after{box-sizing:border-box}

/* the control primitive */
.kcam-btn{position:relative;height:28px;min-width:28px;padding:0 8px;display:inline-flex;align-items:center;justify-content:center;gap:5px;
  border:1px solid var(--cam-border);border-radius:4px;background:var(--cam-btn-bg);color:var(--cam-fg);
  font-family:inherit;font-size:12px;font-weight:500;line-height:1;cursor:pointer;white-space:nowrap;user-select:none;margin:0}
.kcam-btn.kcam-icon{padding:0;width:28px}
.kcam-btn:hover:not(:disabled){background:var(--cam-btn-hover);border-color:var(--cam-border-strong)}
.kcam-btn:active:not(:disabled){background:var(--cam-btn-active);border-color:var(--cam-border-strong)}
.kcam-btn.kcam-selected{background:var(--cam-accent-tint);border-color:var(--cam-accent);color:var(--cam-accent)}
.kcam-btn:disabled{opacity:.45;cursor:default}
.kcam-btn:focus-visible{outline:2px solid var(--cam-accent);outline-offset:1px}
.kcam-ic{width:16px;height:16px;fill:currentColor;flex:none;display:block}
.kcam-ic.kcam-s{width:12px;height:12px}
.kcam-livedot{width:8px;height:8px;border-radius:50%;background:var(--cam-fg-muted);flex:none}
.kcam-selected .kcam-livedot{background:var(--cam-live)}

/* a native select dressed as the primitive: the wrapper draws the chevron */
.kcam-selwrap{position:relative;display:inline-flex}
.kcam-selwrap select{appearance:none;-webkit-appearance:none;padding-right:22px;text-align:left}
.kcam-selwrap > .kcam-ic{position:absolute;right:6px;top:50%;transform:translateY(-50%);pointer-events:none;opacity:.7;color:var(--cam-fg)}

.kcam-toolbar{flex:none;height:37px;display:flex;align-items:center;gap:4px;padding:0 6px;background:var(--cam-toolbar-bg);border-top:1px solid var(--cam-border);overflow:hidden}
.kcam-vr{width:1px;height:18px;background:var(--cam-border);margin:0 3px;flex:none}
.kcam-spacer{flex:1}

/* status badge, over the picture */
.kcam-badge{position:absolute;left:8px;bottom:8px;max-width:calc(100% - 16px);display:flex;align-items:center;gap:8px;height:24px;padding:0 8px 0 7px;
  border-radius:4px;background:var(--cam-overlay-bg);color:var(--cam-overlay-fg);border:1px solid var(--cam-overlay-border);
  font-size:12px;line-height:22px;white-space:nowrap;overflow:hidden;pointer-events:none}
.kcam-badge.kcam-small{left:6px;bottom:6px;max-width:calc(100% - 12px);height:20px;line-height:18px;font-size:11px;gap:6px;padding:0 6px}
.kcam-badge .kcam-dot{width:8px;height:8px;border-radius:50%;flex:none;box-shadow:0 0 0 1px rgba(0,0,0,.35)}
.kcam-badge.kcam-small .kcam-dot{width:7px;height:7px}
.kcam-badge .kcam-name{font-weight:600}
.kcam-badge .kcam-sep{width:1px;height:12px;background:rgba(255,255,255,.28);flex:none}
.kcam-badge .kcam-time{font-variant-numeric:tabular-nums}
.kcam-badge .kcam-muted{opacity:.78}
.kcam-badge .kcam-chip{background:var(--cam-live);color:#fff;font-size:10px;font-weight:700;letter-spacing:.1em;line-height:14px;padding:0 5px;border-radius:3px}
.kcam-badge.kcam-small .kcam-chip{font-size:9px;line-height:12px}
.kcam-badge .kcam-detail{opacity:.78;overflow:hidden;text-overflow:ellipsis}

/* timeline */
.kcam-timeline{position:relative;flex:none;height:41px;border-top:1px solid var(--cam-border);background:var(--cam-timeline-bg)}
.kcam-track{position:absolute;inset:0;overflow:hidden;cursor:pointer;user-select:none;touch-action:none}
.kcam-span{position:absolute;top:7px;height:10px;background:var(--cam-span);border-radius:2px;opacity:.88}
.kcam-mk{position:absolute;top:5px;height:14px;width:3px;border-radius:2px;cursor:pointer}
.kcam-mk::before{content:"";position:absolute;inset:-4px -5px}
.kcam-mk.kcam-event{background:var(--cam-event)}
.kcam-mk.kcam-bookmark{background:var(--cam-bookmark);border-radius:2px 2px 0 0}
.kcam-mk:hover,.kcam-mk.kcam-hover{box-shadow:0 0 0 1px var(--cam-fg)}
.kcam-tick{position:absolute;top:0;bottom:0;padding:21px 0 0 4px;border-left:1px solid var(--cam-tick);color:var(--cam-fg-muted);font-size:10px;line-height:16px;white-space:nowrap;font-variant-numeric:tabular-nums;pointer-events:none}
.kcam-playhead{position:absolute;top:0;bottom:0;width:2px;margin-left:-1px;background:var(--cam-playhead);z-index:2;pointer-events:none}
.kcam-playhead::before{content:"";position:absolute;top:0;left:-4px;border:5px solid transparent;border-top:6px solid var(--cam-playhead);border-bottom:0}
.kcam-flag{position:absolute;top:0;transform:translateX(-50%);z-index:3;pointer-events:none;background:var(--cam-playhead);color:var(--cam-bg);
  font-size:10px;line-height:14px;font-variant-numeric:tabular-nums;padding:0 5px;border-radius:0 0 3px 3px;white-space:nowrap}
.kcam-edge{position:absolute;top:0;bottom:0;right:0;width:2px;background:var(--cam-live);opacity:.8;pointer-events:none}
.kcam-pop{position:absolute;bottom:calc(100% + 7px);transform:translateX(-50%);z-index:4;width:176px;padding:4px;
  background:var(--cam-popover-bg);border:1px solid var(--cam-border);border-radius:4px;box-shadow:var(--cam-shadow);color:var(--cam-fg);text-align:center;font-size:11px;line-height:14px;pointer-events:none}
.kcam-pop::after{content:"";position:absolute;top:100%;left:50%;margin-left:-5px;border:5px solid transparent;border-top-color:var(--cam-popover-bg)}
.kcam-pop img{display:block;width:100%;height:94px;object-fit:contain;border-radius:2px;margin-bottom:4px;background:#000}
.kcam-pop .kcam-lbl{display:flex;align-items:center;justify-content:center;gap:5px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.kcam-pop .kcam-lbl i{width:7px;height:7px;border-radius:2px;background:var(--cam-event);flex:none}
.kcam-pop .kcam-lbl i.kcam-bookmark{background:var(--cam-bookmark)}
.kcam-pop .kcam-when{color:var(--cam-fg-muted);font-variant-numeric:tabular-nums}

/* the tab that brings hidden controls back, bottom right; a PTZ pad showing at the same time lifts above it */
.kcam-restore{position:absolute;right:8px;bottom:8px;z-index:2;background:var(--cam-overlay-bg);color:var(--cam-overlay-fg);border-color:var(--cam-overlay-border)}
.kcam-ptz.kcam-raised{bottom:42px}
.kcam-restore:hover:not(:disabled){background:rgba(255,255,255,.14);border-color:rgba(255,255,255,.3)}

/* PTZ pad, over the picture */
.kcam-ptz{position:absolute;right:8px;bottom:8px;display:flex;gap:6px;align-items:flex-end;padding:6px;border-radius:4px;
  background:var(--cam-overlay-bg);border:1px solid var(--cam-overlay-border);user-select:none;touch-action:none}
.kcam-ptz .kcam-grid{display:grid;grid-template-columns:repeat(3,28px);gap:3px}
.kcam-ptz .kcam-col{display:grid;gap:3px}
.kcam-ptz .kcam-btn{background:var(--cam-overlay-bg);color:var(--cam-overlay-fg);border-color:var(--cam-overlay-border)}
.kcam-ptz .kcam-btn:hover:not(:disabled){background:rgba(255,255,255,.14);border-color:rgba(255,255,255,.3)}
.kcam-ptz .kcam-btn:active:not(:disabled),.kcam-ptz .kcam-btn.kcam-held{background:rgba(255,255,255,.24);border-color:rgba(255,255,255,.4)}
.kcam-ptz .kcam-selwrap select{width:118px}
.kcam-ptz .kcam-selwrap > .kcam-ic{color:var(--cam-overlay-fg)}
.kcam-ptz select option{color:#1a1a1a;background:#fff}
`;

/** Injects the viewer stylesheet into the document once; safe to call from every component. */
export function useCamStyles(): void {
    React.useLayoutEffect(() => {
        if (typeof document === "undefined" || document.getElementById(STYLE_ID)) {
            return;
        }
        const el = document.createElement("style");
        el.id = STYLE_ID;
        el.textContent = CSS;
        document.head.appendChild(el);
    }, []);
}
