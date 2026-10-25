import * as React from "react";

// Small, dependency-free form controls styled to sit inside the gateway web UI.

export const color = {
    text: "#1f2328", muted: "#636c76", border: "#d0d7de", bg: "#ffffff", panel: "#f6f8fa",
    accent: "#0969da", danger: "#cf222e", ok: "#1a7f37", warn: "#9a6700",
};

export const s = {
    page: { padding: "16px 24px", font: "14px/1.45 -apple-system, 'Segoe UI', Roboto, sans-serif", color: color.text, maxWidth: 1200 } as React.CSSProperties,
    h1: { fontSize: 22, fontWeight: 600, margin: "4px 0 12px" } as React.CSSProperties,
    h2: { fontSize: 15, fontWeight: 600, margin: "18px 0 8px", paddingBottom: 4, borderBottom: `1px solid ${color.border}` } as React.CSSProperties,
    table: { width: "100%", borderCollapse: "collapse" } as React.CSSProperties,
    th: { textAlign: "left", fontWeight: 600, fontSize: 12, color: color.muted, padding: "6px 8px", borderBottom: `1px solid ${color.border}` } as React.CSSProperties,
    td: { padding: "8px", borderBottom: `1px solid ${color.border}`, verticalAlign: "top" } as React.CSSProperties,
    error: { background: "#ffebe9", border: "1px solid #ff8182", color: color.danger, borderRadius: 6, padding: "8px 12px", margin: "8px 0" } as React.CSSProperties,
    note: { color: color.muted, fontSize: 12, marginTop: 2 } as React.CSSProperties,
};

export function Button(props: React.ButtonHTMLAttributes<HTMLButtonElement> & { kind?: "primary" | "danger" | "plain" }) {
    const { kind = "plain", style, ...rest } = props;
    const bg = kind === "primary" ? color.accent : kind === "danger" ? color.danger : color.panel;
    return (
        <button {...rest} style={{
            background: bg, color: kind === "plain" ? color.text : "#fff", border: `1px solid ${kind === "plain" ? color.border : bg}`,
            borderRadius: 6, padding: "5px 12px", font: "inherit", fontSize: 13, cursor: props.disabled ? "default" : "pointer",
            opacity: props.disabled ? 0.6 : 1, ...style,
        }} />
    );
}

export function Field(props: { label: string; note?: string; children: React.ReactNode; wide?: boolean }) {
    return (
        <label style={{ display: "flex", flexDirection: "column", gap: 3, gridColumn: props.wide ? "1 / -1" : undefined }}>
            <span style={{ fontWeight: 600, fontSize: 13 }}>{props.label}</span>
            {props.children}
            {props.note && <span style={s.note}>{props.note}</span>}
        </label>
    );
}

const input: React.CSSProperties = {
    border: `1px solid ${color.border}`, borderRadius: 6, padding: "5px 8px", font: "inherit", fontSize: 13, background: color.bg, minWidth: 0,
};

export function Text(props: { value: string | undefined; onChange: (v: string) => void; placeholder?: string; type?: string; mono?: boolean }) {
    return <input style={{ ...input, fontFamily: props.mono ? "ui-monospace, monospace" : undefined }} type={props.type ?? "text"}
        value={props.value ?? ""} placeholder={props.placeholder} onChange={e => props.onChange(e.target.value)} />;
}

export function Num(props: { value: number | undefined; onChange: (v: number) => void; min?: number }) {
    return <input style={{ ...input, width: 110 }} type="number" min={props.min} value={props.value ?? ""}
        onChange={e => props.onChange(e.target.value === "" ? 0 : Number(e.target.value))} />;
}

export function Select<T extends string>(props: { value: T; options: Array<[T, string]>; onChange: (v: T) => void }) {
    return (
        <select style={input} value={props.value} onChange={e => props.onChange(e.target.value as T)}>
            {props.options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </select>
    );
}

export function Check(props: { value: boolean; onChange: (v: boolean) => void; label: string }) {
    return (
        <label style={{ display: "flex", gap: 6, alignItems: "center" }}>
            <input type="checkbox" checked={props.value} onChange={e => props.onChange(e.target.checked)} />
            {props.label}
        </label>
    );
}

/** Comma-separated list editor for string arrays. */
export function List(props: { value: string[] | undefined; onChange: (v: string[]) => void; placeholder?: string }) {
    const [text, setText] = React.useState((props.value ?? []).join(", "));
    return <input style={input} value={text} placeholder={props.placeholder}
        onChange={e => setText(e.target.value)}
        onBlur={() => props.onChange(text.split(",").map(x => x.trim()).filter(Boolean))} />;
}

export function Grid(props: { children: React.ReactNode; cols?: number }) {
    return <div style={{ display: "grid", gridTemplateColumns: `repeat(${props.cols ?? 2}, minmax(0, 1fr))`, gap: "10px 16px" }}>{props.children}</div>;
}

export function Dot(props: { on: boolean; title?: string; warn?: boolean }) {
    return <span title={props.title} style={{ display: "inline-block", width: 9, height: 9, borderRadius: 5, marginRight: 6, background: props.on ? color.ok : props.warn ? color.warn : "#afb8c1" }} />;
}
