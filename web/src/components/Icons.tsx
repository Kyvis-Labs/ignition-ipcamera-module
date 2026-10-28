import * as React from "react";

/** 16 px line icons at a 1.5 px stroke, the weight of the Material icons Perspective ships. */
type P = { small?: boolean; style?: React.CSSProperties };

const cls = (p: P) => `kcam-ic${p.small ? " kcam-s" : ""}`;
const stroke = { fill: "none", stroke: "currentColor", strokeWidth: 1.5, strokeLinecap: "round" as const, strokeLinejoin: "round" as const };

export const Play = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true"><path d="M5 3.2v9.6L12.4 8z" /></svg>
);
export const Pause = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true"><path d="M4.5 3h2.6v10H4.5zM8.9 3h2.6v10H8.9z" /></svg>
);
export const Rewind = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true">
        <path d="M4.82 4.82A4.5 4.5 0 1 1 3.5 8" {...stroke} />
        <path d="M2.5 2.9v3h3" {...stroke} />
    </svg>
);
export const Star = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true"><path d="M8 1.8l1.9 4 4.3.6-3.1 3 .8 4.3L8 11.6l-3.9 2.1.8-4.3-3.1-3 4.3-.6z" /></svg>
);
export const Camera = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true">
        <path d="M2.5 5.5h2.4l1.1-1.7h4l1.1 1.7h2.4v7.2h-11z" {...stroke} strokeWidth={1.4} />
        <circle cx="8" cy="9" r="2.2" {...stroke} strokeWidth={1.4} />
    </svg>
);
export const Chevron = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 6l4.5 4.5L12.5 6" {...stroke} strokeWidth={1.6} /></svg>
);
/** An up arrow; rotate it for the other seven directions. */
export const Arrow = (p: P & { deg?: number }) => (
    <svg className={cls(p)} style={{ ...p.style, transform: p.deg ? `rotate(${p.deg}deg)` : undefined }} viewBox="0 0 16 16" aria-hidden="true">
        <path d="M8 13V3.5M4.2 7.3L8 3.5l3.8 3.8" {...stroke} strokeWidth={1.6} />
    </svg>
);
export const Home = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 8.2L8 3.2l5.5 5M4.2 7.2V13h7.6V7.2" {...stroke} strokeWidth={1.4} /></svg>
);
export const Plus = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true"><path d="M8 3.5v9M3.5 8h9" {...stroke} strokeWidth={1.6} /></svg>
);
export const Minus = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 8h9" {...stroke} strokeWidth={1.6} /></svg>
);
export const Move = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true">
        <path d="M8 2v12M2 8h12M8 2L6 4M8 2l2 2M8 14l-2-2M8 14l2-2M2 8l2-2M2 8l2 2M14 8l-2-2M14 8l-2 2" {...stroke} strokeWidth={1.4} />
    </svg>
);
export const ChevronUp = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true"><path d="M3.5 10l4.5-4.5L12.5 10" {...stroke} strokeWidth={1.6} /></svg>
);
export const Save = (p: P) => (
    <svg className={cls(p)} style={p.style} viewBox="0 0 16 16" aria-hidden="true"><path d="M3 3h8l2 2v8H3z M5 3v3.5h5V3 M5 13V9h6v4" {...stroke} strokeWidth={1.4} /></svg>
);
