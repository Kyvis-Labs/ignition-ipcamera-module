import * as React from "react";
import { TagTrigger } from "./api";
import { Button, color, Text } from "./ui";

/** Editable rows of tag triggers; bookmarks also carry pre/post seconds. */
export function TriggerTable(props: { value: TagTrigger[]; onChange: (v: TagTrigger[]) => void; bookmarks?: boolean }) {
    const rows = props.value;
    const set = (i: number, patch: Partial<TagTrigger>) => props.onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
    const cell: React.CSSProperties = { padding: "3px 4px" };
    return (
        <div>
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
                <thead>
                    <tr style={{ fontSize: 12, color: color.muted, textAlign: "left" }}>
                        <th style={cell}>Tag path</th><th style={cell}>Condition</th><th style={cell}>Label</th>
                        {props.bookmarks && <><th style={cell}>Before (s)</th><th style={cell}>After (s)</th></>}
                        <th />
                    </tr>
                </thead>
                <tbody>
                    {rows.map((r, i) => (
                        <tr key={i}>
                            <td style={{ ...cell, width: "40%" }}><Text mono value={r.tagPath} onChange={v => set(i, { tagPath: v })} placeholder="[default]Line1/Door/Open" /></td>
                            <td style={cell}><Text value={r.condition} onChange={v => set(i, { condition: v })} placeholder="true, > 5, = Open" /></td>
                            <td style={cell}><Text value={r.label} onChange={v => set(i, { label: v })} /></td>
                            {props.bookmarks && <>
                                <td style={cell}><Text value={String(r.preSec ?? 10)} onChange={v => set(i, { preSec: Number(v) || 0 })} /></td>
                                <td style={cell}><Text value={String(r.postSec ?? 10)} onChange={v => set(i, { postSec: Number(v) || 0 })} /></td>
                            </>}
                            <td style={cell}><Button onClick={() => props.onChange(rows.filter((_, j) => j !== i))} title="Remove">✕</Button></td>
                        </tr>
                    ))}
                </tbody>
            </table>
            <Button style={{ marginTop: 4 }} onClick={() => props.onChange([...rows, { tagPath: "", condition: "true", label: "" }])}>+ Add trigger</Button>
        </div>
    );
}
