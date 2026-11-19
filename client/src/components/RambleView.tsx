import { useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api } from "../api";
import { todayStr } from "../lib/dates";
import { parseQuickAdd } from "../lib/nlp";

// Web Speech API types (not in TS DOM lib everywhere)
type SpeechRec = {
  continuous: boolean; interimResults: boolean; lang: string;
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }> }) => void) | null;
  onend: (() => void) | null; onerror: (() => void) | null;
  start: () => void; stop: () => void;
};

export function RambleView() {
  const queryClient = useQueryClient();
  const today = todayStr();
  const [listening, setListening] = useState(false);
  const [lines, setLines] = useState<string[]>([]);
  const [typed, setTyped] = useState("");
  const [notice, setNotice] = useState<string | null>(null);
  const recRef = useRef<SpeechRec | null>(null);
  const supported = typeof window !== "undefined" && ("SpeechRecognition" in window || "webkitSpeechRecognition" in window);

  const bulk = useMutation({
    mutationFn: async (toAdd: string[]) => await api.bulkAddTasks({ lines: toAdd, today }),
    onSuccess: (res) => { setNotice(`Added ${res.added} tasks from your ramble 🎉`); setLines([]); void queryClient.invalidateQueries(); },
  });

  const startListening = () => {
    if (!supported) return;
    const Ctor = (window as unknown as { SpeechRecognition?: new () => SpeechRec; webkitSpeechRecognition?: new () => SpeechRec }).SpeechRecognition
      ?? (window as unknown as { webkitSpeechRecognition?: new () => SpeechRec }).webkitSpeechRecognition;
    if (!Ctor) return;
    const rec = new Ctor();
    rec.continuous = true; rec.interimResults = false; rec.lang = "en-US";
    rec.onresult = (e) => {
      for (let i = 0; i < e.results.length; i++) {
        const r = e.results[i];
        if (r?.isFinal) {
          const text = r[0]?.transcript?.trim();
          if (text) setLines((prev) => [...prev, text.replace(/\.$/, "")]);
        }
      }
    };
    rec.onend = () => setListening(false);
    rec.onerror = () => setListening(false);
    recRef.current = rec;
    rec.start();
    setListening(true);
  };
  const stopListening = () => { recRef.current?.stop(); setListening(false); };

  const addTyped = () => {
    const parts = typed.split(/\n|(?<=[.!?])\s+/).map((s) => s.trim()).filter(Boolean);
    if (parts.length) { setLines((prev) => [...prev, ...parts]); setTyped(""); }
  };

  return (
    <div className="space-y-4">
      <p className="text-sm text-[var(--dim)]">Speak or type your brain-dump — each sentence becomes a task, with dates, #projects, @labels, p1 and durations understood. <strong className="text-[var(--text)]">Unlimited sessions.</strong></p>

      <div className="flex flex-col items-center rounded-2xl bg-[var(--surface)] p-5">
        <button type="button" aria-label={listening ? "Stop listening" : "Start Ramble — tap to speak"} onClick={listening ? stopListening : startListening} disabled={!supported}
          className="flex h-24 w-24 items-center justify-center rounded-full text-4xl disabled:opacity-40"
          style={{ backgroundColor: listening ? "#E44332" : "var(--surface-alt)", boxShadow: listening ? "0 0 0 8px rgba(228,67,50,.25)" : "none" }}>
          🎙️
        </button>
        <p className="mt-3 text-sm font-semibold text-[var(--text)]">{listening ? "Listening… speak your tasks, tap to stop" : supported ? "Tap the mic and ramble" : "Voice not available in this browser"}</p>
        {!supported && <p className="mt-1 text-center text-[13px] text-[var(--dim)]">Speech recognition isn't supported here — type your ramble below instead. Same parsing, one task per line or sentence.</p>}
      </div>

      <div className="rounded-2xl bg-[var(--surface)] p-3">
        <label htmlFor="ramble-type" className="text-sm font-semibold text-[var(--text)]">Type your ramble</label>
        <textarea id="ramble-type" value={typed} onChange={(e) => setTyped(e.target.value)} rows={3} placeholder={"e.g. Call mum tomorrow at 6pm\nWrite script for 30m p1\nBuy milk friday"} className="mt-2 w-full rounded-lg bg-[var(--surface-alt)] px-3 py-2.5 text-base text-[var(--text)] outline-none placeholder:text-[var(--dim)] focus:ring-2 focus:ring-[var(--accent)]" />
        <button type="button" onClick={addTyped} className="mt-2 w-full rounded-lg bg-[var(--surface-alt)] px-4 py-2.5 font-semibold text-[var(--text)]">Split into tasks ↓</button>
      </div>

      {lines.length > 0 && (
        <section aria-label="Captured tasks">
          <h2 className="mb-2 text-base font-semibold text-[var(--text)]">Captured — {lines.length} tasks</h2>
          <ul className="space-y-2">
            {lines.map((line, i) => {
              const p = parseQuickAdd(line, today);
              return (
                <li key={`${line}-${i}`} className="flex items-center gap-2 rounded-xl bg-[var(--surface)] px-3 py-2.5">
                  <span className="min-w-0 flex-1 text-sm text-[var(--text)]">{p.title}{p.dueDate ? <span className="text-[#4CAF7D]"> · {p.dueDate}</span> : null}{p.durationMinutes ? <span className="text-[var(--dim)]"> · {p.durationMinutes}m</span> : null}</span>
                  <button type="button" aria-label={`Remove "${line}"`} onClick={() => setLines((prev) => prev.filter((_, j) => j !== i))} className="px-2 text-lg text-[var(--dim)]">×</button>
                </li>
              );
            })}
          </ul>
          <button type="button" disabled={bulk.isPending} onClick={() => bulk.mutate(lines)} className="mt-3 w-full rounded-lg bg-[var(--accent)] px-4 py-3 font-semibold text-white disabled:opacity-50">{bulk.isPending ? "Adding…" : `Add all ${lines.length} tasks`}</button>
        </section>
      )}
      {notice && <p className="rounded-xl bg-[#1d2b1d] p-3 text-sm text-[#4CAF7D]">{notice}</p>}
    </div>
  );
}
