"use client";

import { SOLUTIONS, promptFor, type Solution } from "@/components/app/solutions";
import { useToast } from "@/components/Toast";

// "What do you need?" from funchole.dev: pick an example and we copy a request
// to paste into the coding agent. Examples, not templates.
export function PromptPicker() {
  const toast = useToast();

  async function pick(solution: Solution) {
    try {
      await navigator.clipboard.writeText(promptFor(solution));
      toast("Request copied. Paste it into your coding agent.");
    } catch {
      toast("Copy failed. Try again.");
    }
  }

  return (
    <ul className="grid grid-cols-2 gap-4 sm:grid-cols-3 xl:grid-cols-5">
      {SOLUTIONS.map((solution) => (
        <li key={solution.id}>
          <button
            type="button"
            onClick={() => pick(solution)}
            className="flex h-full w-full cursor-pointer flex-col items-start gap-1 rounded-xl border-2 border-edge bg-card p-2.5 pb-3.5 text-left shadow-hard transition-[transform,box-shadow,background-color] duration-100 hover:-translate-x-0.5 hover:-translate-y-0.5 hover:bg-sun-soft hover:shadow-hard-lg active:translate-x-[3px] active:translate-y-[3px] active:shadow-press-down"
          >
            <span className="mb-1.5 block w-full">{solution.thumb}</span>
            <strong className="font-heading text-lg leading-tight font-extrabold tracking-tight text-foreground">{solution.name}</strong>
            <span className="text-sm text-muted-foreground">{solution.said}</span>
            <span className="mt-1 text-[13px] font-semibold text-brand underline underline-offset-4">Copy request</span>
          </button>
        </li>
      ))}
    </ul>
  );
}
