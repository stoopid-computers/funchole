"use client";

import { useMemo } from "react";
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

// The ink-island code palette (see --fh-island-* in app/tokens.css): editors
// are dark in both themes, like the landing page's code blocks.
export const codeSyntaxColorsDark = HighlightStyle.define([
  { tag: [tags.propertyName, tags.attributeName], color: "var(--fh-island-fn)" },
  { tag: tags.string, color: "var(--fh-island-str)" },
  { tag: tags.number, color: "var(--fh-island-num)" },
  { tag: [tags.bool, tags.null, tags.keyword, tags.controlKeyword], color: "var(--fh-island-kw)" },
  { tag: [tags.function(tags.variableName), tags.function(tags.definition(tags.variableName))], color: "var(--fh-island-fn)" },
  { tag: tags.comment, color: "var(--fh-island-mute)", fontStyle: "italic" },
  { tag: [tags.separator, tags.squareBracket, tags.brace, tags.paren, tags.punctuation, tags.operator], color: "var(--fh-island-mute)" },
]);

export const editorChrome = EditorView.theme({
  "&": { backgroundColor: "transparent", color: "var(--fh-island-fg)", fontSize: "0.75rem" },
  ".cm-content": { fontFamily: "var(--font-mono)", caretColor: "var(--fh-island-fg)" },
  ".cm-gutters": { backgroundColor: "transparent", border: "none", color: "var(--fh-island-mute)" },
  ".cm-activeLine": { backgroundColor: "rgb(255 255 255 / 0.05)" },
  ".cm-activeLineGutter": { backgroundColor: "rgb(255 255 255 / 0.05)" },
  "&.cm-focused": { outline: "none" },
  ".cm-lintRange-error": { backgroundImage: "none", textDecoration: "underline wavy var(--fh-island-bad)" },
});

// Editors are ink islands: the dark syntax palette is used in both themes, so
// it always matches the dark surface it is drawn on. Kept as a hook so call
// sites don't change.
export function useIsDarkMode() {
  return true;
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
        className={`overflow-hidden rounded-lg border bg-island text-island-fg transition-colors ${
          error ? "border-danger" : "border-island-line focus-within:border-island-code"
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
