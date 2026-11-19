import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { todayStr } from "../lib/dates";
import { actionFields } from "@hatch/space-sdk/fields" with { type: "macro" };
import type { Actions } from "../../../server/src/actions";

const FIELDS = actionFields<typeof Actions>();

export function AssistView() {
  const queryClient = useQueryClient();
  const today = todayStr();
  const [text, setText] = useState("");
  const [checked, setChecked] = useState<Set<number>>(new Set());
  const [notice, setNotice] = useState<string | null>(null);

  const extract = useMutation({
    mutationFn: async () => await api.assistExtract({ text, today }),
    onSuccess: (res) => setChecked(new Set(res.items.map((_, i) => i))),
  });
  const bulk = useMutation({
    mutationFn: async (lines: string[]) => await api.bulkAddTasks({ lines, today }),
    onSuccess: (res) => { setNotice(`Added ${res.added} tasks from Assist 🎉`); void queryClient.invalidateQueries(); },
  });

  const items = extract.data?.items ?? [];
  return (
    <div className="space-y-4">
      <p className="text-sm text-[var(--dim)]">Paste an email, message or messy notes — Assist pulls out the actionable lines and turns them into tasks (dates &amp; priorities parsed). Uncheck anything you don't want, then add.</p>
      <div className="rounded-2xl bg-[var(--surface)] p-3">
        <label htmlFor="assist-text" className="text-sm font-semibold text-[var(--text)]">Paste text / email</label>
        <textarea id="assist-text" {...FIELDS.assistExtract.text} value={text} onChange={(e) => setText(e.target.value)} rows={6} placeholder={"Paste here…\n- Send the invoice by friday\n- Review my script tomorrow\nCall the editor at 4pm"} className="mt-2 w-full rounded-lg bg-[var(--surface-alt)] px-3 py-2.5 text-base text-[var(--text)] outline-none placeholder:text-[var(--dim)] focus:ring-2 focus:ring-[var(--accent)]" />
        <button type="button" disabled={!text.trim() || extract.isPending} onClick={() => extract.mutate()} className="mt-2 w-full rounded-lg bg-[var(--accent)] px-4 py-3 font-semibold text-white disabled:opacity-50">{extract.isPending ? "Extracting…" : "Extract tasks"}</button>
        {extract.isError && <p className="mt-2 text-sm text-[#E44332]">Couldn't extract — try shorter text.</p>}
      </div>

      {items.length > 0 && (
        <section aria-label="Extracted tasks">
          <h2 className="mb-2 text-base font-semibold text-[var(--text)]">Ready to add — {checked.size}/{items.length} selected</h2>
          <ul className="space-y-2">
            {items.map((item, i) => (
              <li key={`${item.raw}-${i}`} className="flex items-start gap-3 rounded-xl bg-[var(--surface)] px-3 py-2.5">
                <input type="checkbox" aria-label={`Include "${item.title}"`} checked={checked.has(i)} onChange={(e) => setChecked((prev) => { const n = new Set(prev); if (e.target.checked) n.add(i); else n.delete(i); return n; })} className="mt-1 h-5 w-5 accent-[#E44332]" />
                <span className="min-w-0 flex-1">
                  <span className="block text-sm text-[var(--text)]">{item.title}</span>
                  {item.dueDate && <span className="text-[13px] text-[#4CAF7D]">{item.dueDate}</span>}
                </span>
              </li>
            ))}
          </ul>
          <button type="button" disabled={checked.size === 0 || bulk.isPending} onClick={() => bulk.mutate(items.filter((_, i) => checked.has(i)).map((x) => x.raw))} className="mt-3 w-full rounded-lg bg-[var(--accent)] px-4 py-3 font-semibold text-white disabled:opacity-50">{bulk.isPending ? "Adding…" : `Add ${checked.size} tasks`}</button>
        </section>
      )}
      {extract.data && items.length === 0 && <p className="rounded-xl bg-[var(--surface)] p-4 text-sm text-[var(--dim)]">No actionable lines found in that text.</p>}
      {notice && <p className="rounded-xl bg-[#1d2b1d] p-3 text-sm text-[#4CAF7D]">{notice}</p>}
      <p className="text-[13px] text-[var(--dim)]">Tip: open any task and use <strong className="text-[var(--text)]">“Break it down”</strong> in the editor to auto-create subtasks for it.</p>
    </div>
  );
}
