"use client";

import { useMemo, useSyncExternalStore } from "react";
import CodeMirror, { EditorView } from "@uiw/react-codemirror";
import { json, jsonParseLinter } from "@codemirror/lang-json";
import { javascript } from "@codemirror/lang-javascript";
import { linter, lintGutter } from "@codemirror/lint";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { tags } from "@lezer/highlight";
import { fieldClass, labelClass } from "@/components/Input";

export const codeSyntaxColorsLight = HighlightStyle.define([
  { tag: [tags.propertyName, tags.attributeName], color: "#0e7490" },
  { tag: tags.string, color: "#047857" },
  { tag: tags.number, color: "#b45309" },
  { tag: [tags.bool, tags.null, tags.keyword, tags.controlKeyword], color: "#7c3aed" },
  { tag: [tags.function(tags.variableName), tags.function(tags.definition(tags.variableName))], color: "#0e7490" },
  { tag: tags.comment, color: "var(--muted-foreground)", fontStyle: "italic" },
  { tag: [tags.separator, tags.squareBracket, tags.brace, tags.paren, tags.punctuation, tags.operator], color: "#64748b" },
]);

// The landing page's code palette (see --fh-code-* in app/tokens.css).
export const codeSyntaxColorsDark = HighlightStyle.define([
  { tag: [tags.propertyName, tags.attributeName], color: "var(--fh-code-fn)" },
  { tag: tags.string, color: "var(--fh-code-str)" },
  { tag: tags.number, color: "var(--fh-code-num)" },
  { tag: [tags.bool, tags.null, tags.keyword, tags.controlKeyword], color: "var(--fh-code-kw)" },
  { tag: [tags.function(tags.variableName), tags.function(tags.definition(tags.variableName))], color: "var(--fh-code-fn)" },
  { tag: tags.comment, color: "var(--fh-faint)", fontStyle: "italic" },
  { tag: [tags.separator, tags.squareBracket, tags.brace, tags.paren, tags.punctuation, tags.operator], color: "var(--fh-subtle)" },
]);

export const editorChrome = EditorView.theme({
  "&": { backgroundColor: "transparent", fontSize: "0.75rem" },
  ".cm-content": { fontFamily: "var(--font-mono)", caretColor: "var(--foreground)" },
  ".cm-gutters": { backgroundColor: "transparent", border: "none", color: "var(--fh-faint)" },
  ".cm-activeLine": { backgroundColor: "rgb(255 255 255 / 0.03)" },
  ".cm-activeLineGutter": { backgroundColor: "rgb(255 255 255 / 0.03)" },
  "&.cm-focused": { outline: "none" },
  ".cm-lintRange-error": { backgroundImage: "none", textDecoration: "underline wavy var(--fh-bad)" },
});

// The console follows its own theme (the `.dark` class on <html>, which the
// root layout always sets) rather than the OS setting, so the editor's syntax
// colours always match the surface they're drawn on.
function subscribeToColorScheme(callback: () => void) {
  const observer = new MutationObserver(callback);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] });
  return () => observer.disconnect();
}

function getIsDarkModeSnapshot() {
  return document.documentElement.classList.contains("dark");
}

export function useIsDarkMode() {
  return useSyncExternalStore(subscribeToColorScheme, getIsDarkModeSnapshot, () => false);
}

export function languageForPath(path: string) {
  return path.endsWith(".json") ? json() : javascript();
}

interface JsonEditorProps {
  value: string;
  onChange: (value: string) => void;
  error: string | null;
  minHeight?: string;
  label?: string;
}

export function JsonEditor({ value, onChange, error, minHeight = "8rem", label = "Input payload (JSON)" }: JsonEditorProps) {
  const isDark = useIsDarkMode();
  const extensions = useMemo(
    () => [
      json(),
      linter(jsonParseLinter()),
      lintGutter(),
      syntaxHighlighting(isDark ? codeSyntaxColorsDark : codeSyntaxColorsLight),
      editorChrome,
      EditorView.lineWrapping,
    ],
    [isDark]
  );

  return (
    <div className={fieldClass}>
      <span className={labelClass}>{label}</span>
      <div
        className={`overflow-hidden rounded-lg border bg-background transition-colors ${
          error ? "border-danger" : "border-input focus-within:border-ring"
        }`}
      >
        <CodeMirror
          value={value}
          onChange={onChange}
          extensions={extensions}
          theme="none"
          basicSetup={{ foldGutter: false, highlightActiveLine: true }}
          minHeight={minHeight}
        />
      </div>
      {error && <p className="text-[11px] text-danger">{error}</p>}
    </div>
  );
}
