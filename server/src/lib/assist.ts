// Task & Email Assist — rule-based helpers (no model call needed, instant & offline).
// 1) extractTasks: pull actionable lines out of pasted text/email.
// 2) breakdownTask: suggest subtasks from keyword templates + generic steps.

export function extractActionLines(text: string): string[] {
  const lines = text
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter(Boolean)
    .map((l) => l.replace(/^[-*•\d.)\s]+/, "").replace(/^\[[ xX]\]\s*/, "").trim())
    .filter((l) => l.length >= 3 && l.length <= 300);
  // Drop obvious email chrome
  const skip = /^(from:|to:|subject:|sent:|date:|hi\b|hello\b|thanks|thank you|best|regards|--|sent from)/i;
  const out: string[] = [];
  const seen = new Set<string>();
  for (const l of lines) {
    if (skip.test(l)) continue;
    const key = l.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(l);
  }
  return out.slice(0, 50);
}

const TEMPLATES: { keywords: string[]; steps: string[] }[] = [
  {
    keywords: ["video", "reel", "shorts", "youtube", "record", "film"],
    steps: ["Pick the hook & title", "Write the script / outline", "Record or generate the footage", "Edit & add captions", "Design thumbnail / cover", "Upload, title & tags", "Pin a comment & share"],
  },
  {
    keywords: ["script", "write", "article", "blog", "essay", "content"],
    steps: ["Research & gather points", "Outline the structure", "Write the first draft", "Edit for clarity & hook", "Proofread & polish", "Publish / schedule"],
  },
  {
    keywords: ["project", "launch", "build", "app", "website", "product"],
    steps: ["Define the goal & scope", "List requirements", "Plan the steps & timeline", "Build the first version", "Test & fix issues", "Review & refine", "Launch / ship it"],
  },
  {
    keywords: ["study", "exam", "learn", "revision", "revise", "homework", "assignment"],
    steps: ["Gather materials & syllabus", "Break the topic into chunks", "Study chunk 1 — notes", "Study chunk 2 — notes", "Practice questions", "Review weak spots", "Final revision"],
  },
  {
    keywords: ["clean", "tidy", "organise", "organize", "declutter"],
    steps: ["Get supplies ready", "Clear surfaces first", "Work through one area at a time", "Put everything in its place", "Final wipe-down & check"],
  },
  {
    keywords: ["buy", "shop", "purchase", "order", "grocer"],
    steps: ["Make the list", "Compare prices / options", "Check budget", "Place the order / go shopping", "Confirm delivery & put away"],
  },
  {
    keywords: ["email", "reply", "message", "call", "meeting", "interview"],
    steps: ["Prepare your points", "Draft the message / agenda", "Send / schedule it", "Follow up if no reply"],
  },
  {
    keywords: ["workout", "exercise", "gym", "run", "fitness"],
    steps: ["Warm up (2 min)", "Main set", "Cool down & stretch", "Log how it went"],
  },
];

const GENERIC = ["Research & gather what you need", "Plan the steps", "Do the first small step", "Do the main work", "Review & fix", "Finish & check it off"];

export function breakdownTask(title: string): string[] {
  const low = title.toLowerCase();
  for (const t of TEMPLATES) {
    if (t.keywords.some((k) => low.includes(k))) return [...t.steps];
  }
  return [...GENERIC];
}
