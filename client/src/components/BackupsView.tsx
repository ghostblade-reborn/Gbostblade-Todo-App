import { useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api } from "../api";

function download(filename: string, text: string) {
  const blob = new Blob([text], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url; a.download = filename; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

export function BackupsView() {
  const queryClient = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const [confirmRestoreId, setConfirmRestoreId] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const backups = useQuery({ queryKey: ["backups"], queryFn: () => api.getBackups({}) });
  const invalidate = () => void queryClient.invalidateQueries();

  const create = useMutation({ mutationFn: async () => await api.createBackup({}), onSuccess: () => { setNotice("Backup created ✓"); invalidate(); } });
  const exportAll = useMutation({
    mutationFn: async (backupId?: number) => await api.exportBackup(backupId != null ? { backupId } : {}),
    onSuccess: (res) => download(res.filename, res.json),
  });
  const restore = useMutation({
    mutationFn: async (args: { backupId?: number; json?: string }) => await api.restoreBackup(args),
    onSuccess: (res) => { setNotice(`Restored ${res.restored.tasks} tasks, ${res.restored.projects} projects, ${res.restored.filters} filters ✓`); setConfirmRestoreId(null); invalidate(); },
    onError: (e) => setError(e instanceof Error ? e.message : "Restore failed — check the file is a Task Manager export."),
  });

  const onFile = async (file: File | undefined) => {
    if (!file) return;
    setError(null);
    const text = await file.text();
    restore.mutate({ json: text });
  };

  return (
    <div className="space-y-4">
      <div className="rounded-2xl bg-[var(--surface)] p-4">
        <h2 className="text-base font-bold text-[var(--text)]">Auto backups — ON ✓</h2>
        <p className="mt-1 text-sm text-[var(--dim)]">A snapshot of all your tasks, projects, labels, filters &amp; history is saved automatically once a day (and kept rolling, last 10). Last backup: {backups.data?.lastBackupAt ? new Date(backups.data.lastBackupAt).toLocaleString() : "not yet — one is created as you use the app"}.</p>
        <div className="mt-3 flex flex-wrap gap-2">
          <button type="button" disabled={create.isPending} onClick={() => create.mutate()} className="rounded-lg bg-[var(--accent)] px-4 py-2.5 font-semibold text-white disabled:opacity-50">{create.isPending ? "Backing up…" : "Back up now"}</button>
          <button type="button" disabled={exportAll.isPending} onClick={() => exportAll.mutate(undefined)} className="rounded-lg bg-[var(--surface-alt)] px-4 py-2.5 font-semibold text-[var(--text)] disabled:opacity-50">Export JSON ↓</button>
          <button type="button" onClick={() => fileRef.current?.click()} className="rounded-lg bg-[var(--surface-alt)] px-4 py-2.5 font-semibold text-[var(--text)]">Import / Restore ↑</button>
          <input ref={fileRef} type="file" accept="application/json,.json" aria-label="Import backup JSON file" className="hidden" onChange={(e) => void onFile(e.target.files?.[0])} />
        </div>
        {notice && <p className="mt-2 text-sm text-[#4CAF7D]">{notice}</p>}
        {error && <p className="mt-2 text-sm text-[#E44332]">{error}</p>}
        {restore.isPending && <p className="mt-2 text-sm text-[var(--dim)]">Restoring…</p>}
      </div>

      <section aria-label="Saved backups">
        <h2 className="mb-2 text-base font-semibold text-[var(--text)]">Saved backups</h2>
        {backups.isPending ? <p className="text-sm text-[var(--dim)]">Loading…</p> : (backups.data?.backups.length ?? 0) === 0 ? (
          <p className="rounded-2xl bg-[var(--surface)] p-4 text-sm text-[var(--dim)]">No backups yet. Tap “Back up now” to create your first one.</p>
        ) : (
          <ul className="space-y-2">
            {backups.data!.backups.map((b) => (
              <li key={b.id} className="rounded-xl bg-[var(--surface)] px-3 py-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-sm text-[var(--text)]">{b.kind === "auto" ? "🤖 Auto" : "✋ Manual"} · {new Date(b.createdAt).toLocaleString()}</span>
                  <span className="text-[13px] text-[var(--dim)]">{b.sizeKb} KB</span>
                </div>
                {confirmRestoreId === b.id ? (
                  <div className="mt-2 flex gap-2">
                    <button type="button" onClick={() => setConfirmRestoreId(null)} className="flex-1 rounded-lg bg-[var(--surface-alt)] px-3 py-2 font-medium text-[var(--text)]">Cancel</button>
                    <button type="button" disabled={restore.isPending} onClick={() => restore.mutate({ backupId: b.id })} className="flex-1 rounded-lg bg-[#7a1f16] px-3 py-2 font-semibold text-white disabled:opacity-50">Yes, replace my data</button>
                  </div>
                ) : (
                  <div className="mt-2 flex gap-2">
                    <button type="button" onClick={() => exportAll.mutate(b.id)} className="rounded-lg bg-[var(--surface-alt)] px-3 py-2 text-sm font-medium text-[var(--text)]">Download</button>
                    <button type="button" onClick={() => setConfirmRestoreId(b.id)} className="rounded-lg px-3 py-2 text-sm font-medium text-[#E44332]">Restore this backup</button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
      </section>
      <p className="text-[13px] text-[var(--dim)]">Restore replaces your current data with the backup's data. Export first if you want to keep what's here now.</p>
    </div>
  );
}
